package dev.codex.flightarmor.client.environment;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.codex.flightarmor.client.FlightArmorClient;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.client.telemetry.FlightSpeedTelemetry;
import dev.codex.flightarmor.flight.FlightLightColorCurve;
import dev.codex.flightarmor.flight.LowAltitudeFeedbackModel;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.mixin.RenderTypeInvoker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Directional high-speed ground coupling rendered as custom untextured geometry.
 *
 * <p>Every second client tick, at most six nearby flying players receive one
 * downward collider/fluid trace. The resulting surface wake has a hard history
 * cap and a frame-wide vertex budget. No vanilla particles are spawned. Local
 * first-person geometry is conservatively rejected inside a four-block camera
 * safety sphere, in addition to normal depth testing.</p>
 */
public final class LowAltitudeFeedbackClient {
	private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
	private static final double MAX_RENDER_DISTANCE_SQUARED =
			LowAltitudeFeedbackModel.MAX_RENDER_DISTANCE_BLOCKS
					* LowAltitudeFeedbackModel.MAX_RENDER_DISTANCE_BLOCKS;
	private static final double REDUCED_DETAIL_DISTANCE_SQUARED = 36.0D * 36.0D;
	private static final Rgba LAND_AIR = new Rgba(238, 226, 198, 255);
	private static final Rgba WATER_AIR = new Rgba(106, 226, 255, 255);
	private static final Rgba PRESSURE_WHITE = new Rgba(238, 252, 255, 255);
	private static final Map<UUID, FeedbackRig> RIGS = new HashMap<>();
	private static ResourceKey<Level> sampledDimension;
	private static boolean initialized;

	private LowAltitudeFeedbackClient() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		FeedbackRenderType.bootstrap();
		ClientTickEvents.END_CLIENT_TICK.register(LowAltitudeFeedbackClient::samplePlayers);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		WorldRenderEvents.AFTER_ENTITIES.register(LowAltitudeFeedbackClient::render);
	}

	/**
	 * Thin read-only bridge used by the existing three-layer flight mixer.
	 * Settings remain owned by FlightArmorClientSettingsStore.
	 */
	public static LowAltitudeFeedbackModel.SoundModifier soundModifier(Player player) {
		if (player == null
				|| !FlightArmorClientSettingsStore.get().environmentFeedbackEnabled()) {
			return LowAltitudeFeedbackModel.SoundModifier.NEUTRAL;
		}
		FeedbackRig rig = RIGS.get(player.getUUID());
		if (rig == null || rig.player != player) {
			return LowAltitudeFeedbackModel.SoundModifier.NEUTRAL;
		}
		long age = player.level().getGameTime() - rig.soundSampleGameTime;
		return age >= 0L && age <= LowAltitudeFeedbackModel.SAMPLE_INTERVAL_TICKS + 1L
				? rig.soundModifier
				: LowAltitudeFeedbackModel.SoundModifier.NEUTRAL;
	}

	private static void samplePlayers(Minecraft client) {
		if (client.level == null || client.player == null
				|| !FlightArmorClientSettingsStore.get().environmentFeedbackEnabled()) {
			clear();
			return;
		}
		ResourceKey<Level> dimension = client.level.dimension();
		if (sampledDimension != null && !sampledDimension.equals(dimension)) RIGS.clear();
		sampledDimension = dimension;
		long now = client.level.getGameTime();
		if (Math.floorMod(now, LowAltitudeFeedbackModel.SAMPLE_INTERVAL_TICKS) != 0) return;

		for (FeedbackRig rig : RIGS.values()) rig.silenceSound();
		Vec3 listener = client.player.position();
		List<Candidate> candidates = new ArrayList<>();
		Set<UUID> loadedPlayers = new HashSet<>();
		for (Player player : client.level.players()) {
			loadedPlayers.add(player.getUUID());
			if (player.isRemoved() || !player.isAlive()
					|| player.isUnderWater() || player.isInWaterOrBubble()
					|| !ModItems.hasFlightGear(player)
					|| !FlightArmorClient.isFlightEffectActive(player)) {
				continue;
			}
			double distanceSquared = listener.distanceToSqr(player.position());
			if (distanceSquared > MAX_RENDER_DISTANCE_SQUARED) continue;
			double speed = FlightSpeedTelemetry.currentBlocksPerTick(player);
			if (speed < LowAltitudeFeedbackModel.MINIMUM_SPEED_BLOCKS_PER_TICK) continue;
			candidates.add(new Candidate(player, distanceSquared, speed));
		}
		candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared));

		int count = Math.min(LowAltitudeFeedbackModel.MAX_ACTIVE_RIGS, candidates.size());
		for (int index = 0; index < count; index++) {
			sampleCandidate(client.level, candidates.get(index), now);
		}

		Iterator<Map.Entry<UUID, FeedbackRig>> iterator = RIGS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, FeedbackRig> entry = iterator.next();
			FeedbackRig rig = entry.getValue();
			rig.expire(now);
			if (!loadedPlayers.contains(entry.getKey()) || rig.player.isRemoved()
					|| (rig.samples.isEmpty() && rig.soundModifier
					== LowAltitudeFeedbackModel.SoundModifier.NEUTRAL)) {
				iterator.remove();
			}
		}
	}

	private static void sampleCandidate(Level level, Candidate candidate, long now) {
		Player player = candidate.player;
		Vec3 from = player.position().add(0.0D, 0.16D, 0.0D);
		Vec3 to = from.add(0.0D,
				-(LowAltitudeFeedbackModel.MAXIMUM_CLEARANCE_BLOCKS + 0.75D), 0.0D);
		BlockHitResult hit = level.clip(new ClipContext(from, to,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
		if (hit.getType() != HitResult.Type.BLOCK) return;

		double clearance = player.getY() - hit.getLocation().y;
		LowAltitudeFeedbackModel.Sample policy = LowAltitudeFeedbackModel.sample(
				true, true, false, candidate.speed, clearance);
		if (!policy.active()) return;

		Vec3 direction = horizontalDirection(player);
		Vec3 normal = new Vec3(hit.getDirection().getStepX(),
				hit.getDirection().getStepY(), hit.getDirection().getStepZ());
		if (normal.y < 0.45D) normal = WORLD_UP;
		boolean water = !level.getFluidState(hit.getBlockPos()).isEmpty();
		Vec3 surface = hit.getLocation().add(normal.scale(water ? 0.045D : 0.025D));

		FeedbackRig rig = RIGS.get(player.getUUID());
		if (rig == null || rig.player != player) {
			rig = new FeedbackRig(player);
			RIGS.put(player.getUUID(), rig);
		}
		rig.add(new WakeSample(surface, normal, direction, water, now, policy));
	}

	private static Vec3 horizontalDirection(Player player) {
		Vec3 motion = player.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		if (horizontal.lengthSqr() < 1.0E-6D) {
			Vec3 look = player.getLookAngle();
			horizontal = new Vec3(look.x, 0.0D, look.z);
		}
		return horizontal.lengthSqr() < 1.0E-8D
				? new Vec3(0.0D, 0.0D, 1.0D) : horizontal.normalize();
	}

	private static void render(WorldRenderContext context) {
		if (context.world() == null || context.consumers() == null || RIGS.isEmpty()
				|| !FlightArmorClientSettingsStore.get().environmentFeedbackEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		Vec3 camera = context.camera().getPosition();
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);
		double renderTime = context.world().getGameTime() + partialTick;
		boolean fastGraphics = client.options.graphicsMode().get() == GraphicsStatus.FAST;
		VertexBudget budget = new VertexBudget(fastGraphics
				? LowAltitudeFeedbackModel.FAST_VERTEX_BUDGET
				: LowAltitudeFeedbackModel.FULL_VERTEX_BUDGET);
		VertexConsumer vertices = context.consumers().getBuffer(FeedbackRenderType.INSTANCE);

		for (FeedbackRig rig : RIGS.values()) {
			if (rig.player.isRemoved() || rig.player.level() != context.world()) continue;
			double distanceSquared = camera.distanceToSqr(rig.player.getPosition(partialTick));
			if (distanceSquared > MAX_RENDER_DISTANCE_SQUARED) continue;
			boolean reduced = fastGraphics || distanceSquared > REDUCED_DETAIL_DISTANCE_SQUARED;
			boolean firstPersonLocal = rig.player == client.player
					&& client.options.getCameraType().isFirstPerson();
			List<WakeSample> samples = rig.snapshot(reduced ? 4
					: LowAltitudeFeedbackModel.MAX_SAMPLES_PER_RIG);
			for (WakeSample sample : samples) {
				double age = renderTime - sample.createdAt;
				if (age < 0.0D || age > LowAltitudeFeedbackModel.SAMPLE_LIFETIME_TICKS) continue;
				renderSample(vertices, budget, camera, sample, age,
						firstPersonLocal, reduced);
				if (!budget.hasQuad()) return;
			}
		}
	}

	private static void renderSample(VertexConsumer vertices, VertexBudget budget,
			Vec3 camera, WakeSample sample, double ageTicks,
			boolean firstPersonLocal, boolean reduced) {
		double life = ageTicks / LowAltitudeFeedbackModel.SAMPLE_LIFETIME_TICKS;
		float fade = (float) (1.0D - smoothstep(0.28D, 1.0D, life));
		float strength = (float) sample.policy.intensity() * fade;
		if (strength <= 0.002F) return;

		Vec3 normal = sample.normal.normalize();
		Vec3 forward = sample.direction.subtract(normal.scale(sample.direction.dot(normal)));
		if (forward.lengthSqr() < 1.0E-8D) forward = new Vec3(0.0D, 0.0D, 1.0D);
		forward = forward.normalize();
		Vec3 side = normal.cross(forward).normalize();
		double lift = 0.025D + life * 0.055D;
		Vec3 center = sample.surface.add(normal.scale(lift));
		double safety = firstPersonLocal
				? LowAltitudeFeedbackModel.FIRST_PERSON_SAFE_RADIUS_BLOCKS : 0.75D;

		FlightLightColorCurve.Rgb speed = FlightLightColorCurve.colorAt(
				sample.policy.speedProgress());
		Rgba energy = new Rgba(speed.red(), speed.green(), speed.blue(), 255);
		Rgba medium = sample.water ? WATER_AIR : LAND_AIR;
		Rgba wakeColor = mix(medium, energy, 0.30F + 0.30F * strength);
		Rgba edgeColor = mix(PRESSURE_WHITE, energy, 0.24F);

		double ageExpansion = 1.0D + life * 0.55D;
		double wakeLength = sample.policy.wakeLength() * (0.68D + life * 0.48D);
		double halfWidth = sample.policy.wakeHalfWidth() * ageExpansion;
		renderShearLane(vertices, budget, camera, center, forward, side,
				wakeLength, halfWidth * 0.48D, halfWidth * 0.07D,
				wakeColor, 62.0F * strength, safety);
		renderShearLane(vertices, budget, camera, center, forward, side,
				wakeLength * 0.88D, -halfWidth * 0.48D, halfWidth * 0.07D,
				wakeColor, 62.0F * strength, safety);
		if (!reduced) {
			renderPressureSpine(vertices, budget, camera, center, forward, side,
					wakeLength * 0.74D, halfWidth * 0.055D,
					edgeColor, 43.0F * strength, safety);
		}

		double radius = sample.policy.disturbanceRadius() * ageExpansion;
		renderBrokenEllipse(vertices, budget, camera, center, forward, side,
				radius * 1.45D, radius * 0.70D,
				reduced ? 8 : 12, ageTicks * 0.17D,
				edgeColor, 52.0F * strength, safety);
	}

	private static void renderShearLane(VertexConsumer vertices, VertexBudget budget,
			Vec3 camera, Vec3 center, Vec3 forward, Vec3 side,
			double length, double lateralOffset, double width,
			Rgba color, float alpha, double safety) {
		Vec3 head = center.add(forward.scale(0.38D)).add(side.scale(lateralOffset));
		Vec3 tail = center.subtract(forward.scale(length)).add(side.scale(lateralOffset * 1.28D));
		Vec3 headSide = side.scale(width);
		Vec3 tailSide = side.scale(width * 2.6D);
		emitSafeQuad(vertices, budget, camera,
				tail.subtract(tailSide), tail.add(tailSide),
				head.add(headSide), head.subtract(headSide),
				color.alpha(0.0F), color.alpha(alpha * 0.28F),
				color.alpha(alpha), color.alpha(alpha * 0.68F), safety);
	}

	private static void renderPressureSpine(VertexConsumer vertices, VertexBudget budget,
			Vec3 camera, Vec3 center, Vec3 forward, Vec3 side,
			double length, double width, Rgba color, float alpha, double safety) {
		Vec3 nose = center.add(forward.scale(0.62D));
		Vec3 tail = center.subtract(forward.scale(length));
		emitSafeQuad(vertices, budget, camera,
				tail.subtract(side.scale(width * 3.0D)),
				tail.add(side.scale(width * 3.0D)),
				nose.add(side.scale(width)), nose.subtract(side.scale(width)),
				color.alpha(0.0F), color.alpha(0.0F),
				color.alpha(alpha), color.alpha(alpha), safety);
	}

	private static void renderBrokenEllipse(VertexConsumer vertices, VertexBudget budget,
			Vec3 camera, Vec3 center, Vec3 forward, Vec3 side,
			double forwardRadius, double sideRadius, int segments,
			double phase, Rgba color, float alpha, double safety) {
		for (int segment = 0; segment < segments && budget.hasQuad(); segment++) {
			double angle0 = Math.PI * 2.0D * segment / segments;
			double angle1 = Math.PI * 2.0D * (segment + 1) / segments;
			double noise = Math.sin(angle0 * 3.0D + phase)
					+ 0.48D * Math.sin(angle0 * 7.0D - phase * 1.6D);
			if (noise < -0.34D) continue;
			float segmentAlpha = alpha * (float) Mth.clamp(0.55D + noise * 0.26D,
					0.15D, 1.0D);
			emitSafeQuad(vertices, budget, camera,
					ellipsePoint(center, forward, side, forwardRadius, sideRadius, angle0, 0.90D),
					ellipsePoint(center, forward, side, forwardRadius, sideRadius, angle0, 1.10D),
					ellipsePoint(center, forward, side, forwardRadius, sideRadius, angle1, 1.10D),
					ellipsePoint(center, forward, side, forwardRadius, sideRadius, angle1, 0.90D),
					color.alpha(segmentAlpha * 0.60F), color.alpha(segmentAlpha),
					color.alpha(segmentAlpha * 0.72F), color.alpha(segmentAlpha * 0.42F),
					safety);
		}
	}

	private static Vec3 ellipsePoint(Vec3 center, Vec3 forward, Vec3 side,
			double forwardRadius, double sideRadius, double angle, double scale) {
		return center.add(forward.scale(Math.cos(angle) * forwardRadius * scale))
				.add(side.scale(Math.sin(angle) * sideRadius * scale));
	}

	private static void emitSafeQuad(VertexConsumer vertices, VertexBudget budget,
			Vec3 camera, Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3,
			Rgba c0, Rgba c1, Rgba c2, Rgba c3, double cameraSafety) {
		Vec3 center = v0.add(v1).add(v2).add(v3).scale(0.25D);
		double radius = Math.max(Math.max(center.distanceTo(v0), center.distanceTo(v1)),
				Math.max(center.distanceTo(v2), center.distanceTo(v3)));
		if (camera.distanceTo(center) <= radius + cameraSafety || !budget.claimQuad()) return;
		emitVertex(vertices, camera, v0, c0);
		emitVertex(vertices, camera, v1, c1);
		emitVertex(vertices, camera, v2, c2);
		emitVertex(vertices, camera, v3, c3);
	}

	private static void emitVertex(VertexConsumer vertices, Vec3 camera,
			Vec3 position, Rgba color) {
		vertices.addVertex((float) (position.x - camera.x),
				(float) (position.y - camera.y), (float) (position.z - camera.z))
				.setColor(color.r, color.g, color.b, color.a);
	}

	private static Rgba mix(Rgba from, Rgba to, float progress) {
		float value = Mth.clamp(progress, 0.0F, 1.0F);
		return new Rgba(Math.round(Mth.lerp(value, from.r, to.r)),
				Math.round(Mth.lerp(value, from.g, to.g)),
				Math.round(Mth.lerp(value, from.b, to.b)),
				Math.round(Mth.lerp(value, from.a, to.a)));
	}

	private static double smoothstep(double edge0, double edge1, double value) {
		double progress = Mth.clamp((value - edge0) / Math.max(1.0E-8D, edge1 - edge0),
				0.0D, 1.0D);
		return progress * progress * (3.0D - 2.0D * progress);
	}

	private static void clear() {
		RIGS.clear();
		sampledDimension = null;
	}

	private record Candidate(Player player, double distanceSquared, double speed) {
	}

	private record WakeSample(Vec3 surface, Vec3 normal, Vec3 direction,
			boolean water, long createdAt, LowAltitudeFeedbackModel.Sample policy) {
	}

	private static final class FeedbackRig {
		private final Player player;
		private final ArrayDeque<WakeSample> samples = new ArrayDeque<>();
		private LowAltitudeFeedbackModel.SoundModifier soundModifier =
				LowAltitudeFeedbackModel.SoundModifier.NEUTRAL;
		private long soundSampleGameTime = Long.MIN_VALUE;

		private FeedbackRig(Player player) {
			this.player = player;
		}

		private void add(WakeSample sample) {
			samples.addLast(sample);
			soundModifier = sample.policy.sound();
			soundSampleGameTime = sample.createdAt;
			while (samples.size() > LowAltitudeFeedbackModel.MAX_SAMPLES_PER_RIG) {
				samples.removeFirst();
			}
		}

		private void silenceSound() {
			soundModifier = LowAltitudeFeedbackModel.SoundModifier.NEUTRAL;
		}

		private void expire(long now) {
			while (!samples.isEmpty()
					&& now - samples.peekFirst().createdAt
					> LowAltitudeFeedbackModel.SAMPLE_LIFETIME_TICKS) {
				samples.removeFirst();
			}
		}

		private List<WakeSample> snapshot(int limit) {
			List<WakeSample> result = new ArrayList<>(samples);
			if (result.size() > limit) {
				return result.subList(result.size() - limit, result.size());
			}
			return result;
		}
	}

	private static final class VertexBudget {
		private int remaining;

		private VertexBudget(int maximumVertices) {
			remaining = Math.max(0, maximumVertices);
		}

		private boolean claimQuad() {
			if (remaining < 4) return false;
			remaining -= 4;
			return true;
		}

		private boolean hasQuad() {
			return remaining >= 4;
		}
	}

	private record Rgba(int r, int g, int b, int a) {
		private Rgba alpha(float nextAlpha) {
			return new Rgba(r, g, b, Math.round(Mth.clamp(nextAlpha, 0.0F, 255.0F)));
		}
	}

	private static final class FeedbackRenderType extends RenderStateShard {
		private static final RenderType INSTANCE = RenderTypeInvoker.flightArmor$create(
				"flight_armor_low_altitude_feedback",
				DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
				65536, false, true,
				RenderType.CompositeState.builder()
						.setShaderState(POSITION_COLOR_SHADER)
						.setTextureState(NO_TEXTURE)
						.setTransparencyState(TRANSLUCENT_TRANSPARENCY)
						.setDepthTestState(LEQUAL_DEPTH_TEST)
						.setCullState(NO_CULL)
						.setLightmapState(NO_LIGHTMAP)
						.setOverlayState(NO_OVERLAY)
						.setWriteMaskState(COLOR_WRITE)
						.setOutputState(MAIN_TARGET)
						.createCompositeState(false));

		private FeedbackRenderType() {
			super("flight_armor_low_altitude_feedback_state", () -> { }, () -> { });
		}

		private static void bootstrap() {
			if (INSTANCE == null) throw new IllegalStateException(
					"Low-altitude feedback render type unavailable");
		}
	}
}
