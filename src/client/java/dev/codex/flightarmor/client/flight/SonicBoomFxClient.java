package dev.codex.flightarmor.client.flight;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.codex.flightarmor.flight.SonicBoomVisualProfile;
import dev.codex.flightarmor.network.SonicBoomFxPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Event-driven custom geometry for a clean Mach-1 crossing.
 *
 * <p>The pressure cone, two expanding wave rings and broken condensation bands
 * are intentionally untextured. They remain visible to observers without
 * spawning vanilla particles, and local first-person quads are clipped against
 * a conservative camera gap so the effect never becomes a white full-screen
 * flash.</p>
 */
public final class SonicBoomFxClient {
	private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
	private static final Vec3 WORLD_RIGHT = new Vec3(1.0D, 0.0D, 0.0D);
	private static final int MAX_ACTIVE_EVENTS = 48;
	private static final int FULL_SEGMENTS = 28;
	private static final int FAST_SEGMENTS = 16;
	private static final double[] FULL_COS = buildCosTable(FULL_SEGMENTS);
	private static final double[] FULL_SIN = buildSinTable(FULL_SEGMENTS);
	private static final double[] FAST_COS = buildCosTable(FAST_SEGMENTS);
	private static final double[] FAST_SIN = buildSinTable(FAST_SEGMENTS);
	private static final Map<EventKey, ActiveEvent> EVENTS = new LinkedHashMap<>();
	private static boolean initialized;

	private SonicBoomFxClient() {
	}

	private static double[] buildCosTable(int segments) {
		double[] table = new double[segments + 1];
		for (int i = 0; i <= segments; i++) {
			table[i] = Math.cos(Math.PI * 2.0D * i / segments);
		}
		return table;
	}

	private static double[] buildSinTable(int segments) {
		double[] table = new double[segments + 1];
		for (int i = 0; i <= segments; i++) {
			table[i] = Math.sin(Math.PI * 2.0D * i / segments);
		}
		return table;
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientPlayNetworking.registerGlobalReceiver(SonicBoomFxPayload.TYPE, (payload, context) ->
				context.client().execute(() -> receive(context.client(), payload)));
		ClientTickEvents.END_CLIENT_TICK.register(SonicBoomFxClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> EVENTS.clear());
		LevelRenderEvents.COLLECT_SUBMITS.register(SonicBoomFxClient::render);
	}

	private static void receive(Minecraft client, SonicBoomFxPayload payload) {
		if (client.level == null
				|| !client.level.dimension().identifier().equals(payload.dimension())) return;
		EventKey key = new EventKey(payload.flyerId(), payload.eventId());
		if (EVENTS.containsKey(key)) return;
		Vec3 axis = payload.direction().scale(-1.0D);
		Basis basis = basis(axis);
		EVENTS.put(key, new ActiveEvent(payload.flyerId(), payload.dimension(),
				payload.origin(), axis, basis.first, basis.second,
				Math.max(1.0F, payload.mach()), client.level.getGameTime()));
		while (EVENTS.size() > MAX_ACTIVE_EVENTS) {
			EventKey oldest = EVENTS.entrySet().stream()
					.min(Comparator.comparingLong(entry -> entry.getValue().createdAt))
					.map(Map.Entry::getKey).orElse(null);
			if (oldest == null) break;
			EVENTS.remove(oldest);
		}
	}

	private static void tick(Minecraft client) {
		if (client.level == null) {
			EVENTS.clear();
			return;
		}
		long now = client.level.getGameTime();
		Identifier dimension = client.level.dimension().identifier();
		EVENTS.entrySet().removeIf(entry ->
				!dimension.equals(entry.getValue().dimension)
						|| now - entry.getValue().createdAt
						> Math.ceil(SonicBoomVisualProfile.LIFETIME_TICKS) + 2L);
	}

	private static void render(LevelRenderContext context) {
		if (context.levelRenderer() == null || EVENTS.isEmpty()) return;
		LevelRenderState levelState = context.levelState();
		if (levelState == null || levelState.cameraRenderState == null) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		Identifier dimension = client.level.dimension().identifier();
		Vec3 camera = levelState.cameraRenderState.pos;
		if (camera == null) return;
		double renderTime = levelState.gameTime + levelState.worldPartialTicks;
		boolean fast = client.options.graphicsPreset().get() == GraphicsPreset.FAST;
		int segments = fast ? FAST_SEGMENTS : FULL_SEGMENTS;
		double maximumDistanceSqr = SonicBoomVisualProfile.MAX_RENDER_DISTANCE_BLOCKS
				* SonicBoomVisualProfile.MAX_RENDER_DISTANCE_BLOCKS;

		boolean anyVisible = false;
		for (ActiveEvent event : EVENTS.values()) {
			if (dimension.equals(event.dimension) && camera.distanceToSqr(event.origin) <= maximumDistanceSqr) {
				double age = renderTime - event.createdAt;
				if (age >= 0.0D && age <= SonicBoomVisualProfile.LIFETIME_TICKS) {
					anyVisible = true;
					break;
				}
			}
		}
		if (!anyVisible) return;

		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vertices) -> {
			for (ActiveEvent event : EVENTS.values()) {
				if (!dimension.equals(event.dimension)
						|| camera.distanceToSqr(event.origin) > maximumDistanceSqr) continue;
				double age = renderTime - event.createdAt;
				if (age < 0.0D || age > SonicBoomVisualProfile.LIFETIME_TICKS) continue;
				double progress = SonicBoomVisualProfile.progress(age);
				boolean localFirstPerson = client.player != null
						&& event.flyerId.equals(client.player.getUUID())
						&& levelState.cameraRenderState.isFirstPerson;
				double alphaScale = localFirstPerson ? 0.42D : 1.0D;
				renderPressureCone(vertices, pose, camera, event, progress, segments,
						localFirstPerson, alphaScale);
				renderDoublePressureRings(vertices, pose, camera, event, progress, segments,
						localFirstPerson, alphaScale);
				renderCondensationCloud(vertices, pose, camera, event, progress, segments,
						localFirstPerson, alphaScale);
			}
		});
	}

	private static void renderPressureCone(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			ActiveEvent event, double progress, int segments,
			boolean localFirstPerson, double alphaScale) {
		double length = SonicBoomVisualProfile.pressureConeLength(progress);
		int sections = segments >= FULL_SEGMENTS ? 6 : 4;
		double lifeFade = 1.0D - smoothstep(0.48D, 1.0D, progress);
		Rgba outerColor = new Rgba(174, 232, 255, alpha(42.0D * lifeFade * alphaScale));
		Rgba edgeColor = new Rgba(174, 232, 255, alpha(82.0D * lifeFade * alphaScale));
		double[] cosTable = segments == FULL_SEGMENTS ? FULL_COS : FAST_COS;
		double[] sinTable = segments == FULL_SEGMENTS ? FULL_SIN : FAST_SIN;
		Vec3[] radials = new Vec3[segments + 1];
		for (int segment = 0; segment <= segments; segment++) {
			radials[segment] = event.right.scale(cosTable[segment]).add(event.up.scale(sinTable[segment]));
		}
		for (int section = 0; section < sections; section++) {
			double t0 = section / (double) sections;
			double t1 = (section + 1.0D) / sections;
			double axial0 = 0.48D + t0 * length;
			double axial1 = 0.48D + t1 * length;
			double radius0 = SonicBoomVisualProfile.pressureConeRadius(axial0, event.mach);
			double radius1 = SonicBoomVisualProfile.pressureConeRadius(axial1, event.mach);
			Vec3 center0 = event.origin.add(event.axis.scale(axial0));
			Vec3 center1 = event.origin.add(event.axis.scale(axial1));
			Rgba quadColor = (section == 0 || section == sections - 1) ? edgeColor : outerColor;
			for (int segment = 0; segment < segments; segment++) {
				Vec3 radial0 = radials[segment];
				Vec3 radial1 = radials[segment + 1];
				Vec3 v0 = center0.add(radial0.scale(radius0));
				Vec3 v1 = center0.add(radial1.scale(radius0));
				Vec3 v2 = center1.add(radial1.scale(radius1));
				Vec3 v3 = center1.add(radial0.scale(radius1));
				emitQuad(vertices, pose, camera, v0, v1, v2, v3, quadColor, localFirstPerson);
			}
		}
	}

	private static void renderDoublePressureRings(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			ActiveEvent event, double progress, int segments,
			boolean localFirstPerson, double alphaScale) {
		double pulseFade = 1.0D - smoothstep(0.58D, 1.0D, progress);
		Vec3 firstCenter = event.origin.add(event.axis.scale(0.28D + progress * 1.75D));
		Vec3 secondCenter = event.origin.add(event.axis.scale(1.00D + progress * 2.65D));
		renderRing(vertices, pose, camera, event, firstCenter,
				SonicBoomVisualProfile.firstRingRadius(progress),
				0.095D + progress * 0.15D,
				new Rgba(228, 250, 255, alpha(212.0D * pulseFade * alphaScale)),
				segments, localFirstPerson, false);
		renderRing(vertices, pose, camera, event, secondCenter,
				SonicBoomVisualProfile.secondRingRadius(progress),
				0.075D + progress * 0.12D,
				new Rgba(116, 214, 255, alpha(178.0D * pulseFade * alphaScale)),
				segments, localFirstPerson, false);
	}

	private static void renderCondensationCloud(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			ActiveEvent event, double progress, int segments,
			boolean localFirstPerson, double alphaScale) {
		double opacity = SonicBoomVisualProfile.condensationOpacity(progress) * alphaScale;
		if (opacity <= 0.002D) return;
		int bands = segments >= FULL_SEGMENTS ? 4 : 3;
		for (int band = 0; band < bands; band++) {
			double axial = 0.92D + band * 1.18D + progress * (1.15D + band * 0.18D);
			Vec3 center = event.origin.add(event.axis.scale(axial));
			double coneRadius = SonicBoomVisualProfile.pressureConeRadius(axial, event.mach);
			double radius = coneRadius * (0.88D + band * 0.055D)
					+ Math.sin(progress * 7.0D + band * 1.9D) * 0.11D;
			Rgba color = new Rgba(238, 249, 255,
					alpha((76.0D - band * 8.0D) * opacity));
			renderRing(vertices, pose, camera, event, center, radius,
					0.18D + band * 0.035D, color, segments,
					localFirstPerson, true);
		}
	}

	private static void renderRing(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			ActiveEvent event, Vec3 center, double radius, double width,
			Rgba color, int segments, boolean localFirstPerson, boolean brokenCloud) {
		double innerRadius = Math.max(0.02D, radius - width * 0.5D);
		double outerRadius = radius + width * 0.5D;
		double[] cosTable = segments == FULL_SEGMENTS ? FULL_COS : FAST_COS;
		double[] sinTable = segments == FULL_SEGMENTS ? FULL_SIN : FAST_SIN;
		for (int segment = 0; segment < segments; segment++) {
			if (brokenCloud && Math.floorMod(segment * 5 + (int) (radius * 13.0D), 11) < 3) continue;
			double wobble0 = brokenCloud ? 1.0D
					+ Math.sin(segment * 2.37D + radius * 3.1D) * 0.08D : 1.0D;
			double wobble1 = brokenCloud ? 1.0D
					+ Math.sin((segment + 1.0D) * 2.37D + radius * 3.1D) * 0.08D : 1.0D;
			Vec3 radial0 = event.right.scale(cosTable[segment]).add(event.up.scale(sinTable[segment]));
			Vec3 radial1 = event.right.scale(cosTable[segment + 1]).add(event.up.scale(sinTable[segment + 1]));
			Vec3 v0 = center.add(radial0.scale(innerRadius * wobble0));
			Vec3 v1 = center.add(radial0.scale(outerRadius * wobble0));
			Vec3 v2 = center.add(radial1.scale(outerRadius * wobble1));
			Vec3 v3 = center.add(radial1.scale(innerRadius * wobble1));
			emitQuad(vertices, pose, camera, v0, v1, v2, v3, color, localFirstPerson);
		}
	}

	private static void emitQuad(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3, Rgba color,
			boolean localFirstPerson) {
		if (localFirstPerson && (!safe(v0, camera, true)
				|| !safe(v1, camera, true)
				|| !safe(v2, camera, true)
				|| !safe(v3, camera, true))) return;
		emit(vertices, pose, camera, v0, color);
		emit(vertices, pose, camera, v1, color);
		emit(vertices, pose, camera, v2, color);
		emit(vertices, pose, camera, v3, color);
	}

	private static boolean safe(Vec3 vertex, Vec3 camera, boolean localFirstPerson) {
		if (!localFirstPerson) return true;
		return SonicBoomVisualProfile.outsideCameraGap(
				Math.sqrt(vertex.distanceToSqr(camera)), true);
	}

	private static void emit(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera, Vec3 value, Rgba color) {
		vertices.addVertex(pose, (float) (value.x - camera.x),
				(float) (value.y - camera.y), (float) (value.z - camera.z))
				.setColor(color.red, color.green, color.blue, color.alpha);
	}

	private static Basis basis(Vec3 axis) {
		Vec3 normalized = axis.lengthSqr() < 1.0E-8D
				? new Vec3(0.0D, 0.0D, -1.0D) : axis.normalize();
		Vec3 reference = Math.abs(normalized.y) < 0.88D ? WORLD_UP : WORLD_RIGHT;
		Vec3 first = normalized.cross(reference).normalize();
		return new Basis(first, normalized.cross(first).normalize());
	}

	private static int alpha(double value) {
		return Mth.clamp((int) Math.round(value), 0, 255);
	}

	private static double smoothstep(double edge0, double edge1, double value) {
		double t = Mth.clamp((value - edge0) / Math.max(1.0E-8D, edge1 - edge0),
				0.0D, 1.0D);
		return t * t * (3.0D - 2.0D * t);
	}

	private record EventKey(UUID flyerId, long eventId) {
	}

	private record Basis(Vec3 first, Vec3 second) {
	}

	private record Rgba(int red, int green, int blue, int alpha) {
	}

	private record ActiveEvent(UUID flyerId, Identifier dimension,
			Vec3 origin, Vec3 axis, Vec3 right, Vec3 up,
			float mach, long createdAt) {
	}
}
