package dev.codex.flightarmor.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.codex.flightarmor.client.FlightArmorClient;
import dev.codex.flightarmor.combat.ArmorAnchorPose;
import dev.codex.flightarmor.combat.ArmorAnchorResolver;
import dev.codex.flightarmor.combat.ArmorSemanticAnchor;
import dev.codex.flightarmor.client.telemetry.FlightSpeedTelemetry;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.flight.FlightLightColorCurve;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 疾影型高速飞行的无纹理几何尾迹。
 *
 * <p>尾迹只采样完整疾影套装的真实世界位移，不生成原版粒子。五条光带分别从胸部核心、
 * 双臂和双靴后方延伸，并使用与疾影灯光一致的紫色到粉色速度色。历史有采样数和世界长度
 * 双重上限；传送、换维度、玩家实体替换和断线都会清空，避免残留跨越世界。</p>
 */
public final class SwiftFlightTrailRenderer {
	private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
	private static final Vec3 WORLD_RIGHT = new Vec3(1.0D, 0.0D, 0.0D);
	private static final double HIGH_SPEED_THRESHOLD = 0.78D;
	private static final double TELEPORT_RESET_DISTANCE_SQR = 72.0D * 72.0D;
	private static final double MAX_TRAIL_WORLD_LENGTH = 84.0D;
	private static final double MAX_RENDER_DISTANCE_SQR = 112.0D * 112.0D;
	private static final double REDUCED_DETAIL_DISTANCE_SQR = 56.0D * 56.0D;
	private static final int MAX_HISTORY_SAMPLES = 24;
	private static final int FAST_HISTORY_SAMPLES = 14;
	private static final int FADE_TICKS = 7;

	private static final ArmorSemanticAnchor[] FULL_ANCHORS = {
			ArmorSemanticAnchor.CHEST_CORE,
			ArmorSemanticAnchor.LEFT_PALM,
			ArmorSemanticAnchor.RIGHT_PALM,
			ArmorSemanticAnchor.LEFT_BOOT_SOLE,
			ArmorSemanticAnchor.RIGHT_BOOT_SOLE
	};
	private static final ArmorSemanticAnchor[] REDUCED_ANCHORS = {
			ArmorSemanticAnchor.CHEST_CORE,
			ArmorSemanticAnchor.LEFT_BOOT_SOLE,
			ArmorSemanticAnchor.RIGHT_BOOT_SOLE
	};

	private static final Map<UUID, TrailHistory> TRAILS = new HashMap<>();
	private static ResourceKey<Level> sampledDimension;
	private static boolean initialized;

	private SwiftFlightTrailRenderer() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientTickEvents.END_CLIENT_TICK.register(SwiftFlightTrailRenderer::samplePlayers);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		LevelRenderEvents.COLLECT_SUBMITS.register(SwiftFlightTrailRenderer::render);
	}

	private static void samplePlayers(Minecraft client) {
		if (!FlightArmorClientSettingsStore.get().trailEnabled()) {
			clear();
			return;
		}
		if (client.level == null) {
			clear();
			return;
		}
		ResourceKey<Level> dimension = client.level.dimension();
		if (sampledDimension != null && !sampledDimension.equals(dimension)) TRAILS.clear();
		sampledDimension = dimension;
		int historyLimit = client.options.graphicsPreset().get() == GraphicsPreset.FAST
				? FAST_HISTORY_SAMPLES : MAX_HISTORY_SAMPLES;
		Set<UUID> loadedPlayers = new HashSet<>();

		for (Player player : client.level.players()) {
			UUID playerId = player.getUUID();
			loadedPlayers.add(playerId);
			TrailHistory history = TRAILS.get(playerId);
			boolean swiftFlight = !player.isRemoved()
					&& ModItems.fullSetVariant(player).orElse(null) == ArmorVariant.SWIFT
					&& FlightArmorClient.isFlightEffectActive(player);
			boolean highSpeed = swiftFlight
					&& FlightSpeedTelemetry.currentBlocksPerTick(player) >= HIGH_SPEED_THRESHOLD;
			if (highSpeed) {
				if (history == null || history.player != player) {
					history = new TrailHistory(player);
					TRAILS.put(playerId, history);
				}
				history.sample(historyLimit);
			} else if (history != null) {
				history.inactiveTicks++;
				if (!swiftFlight || history.inactiveTicks > FADE_TICKS) TRAILS.remove(playerId);
			}
		}

		Iterator<Map.Entry<UUID, TrailHistory>> iterator = TRAILS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, TrailHistory> entry = iterator.next();
			if (!loadedPlayers.contains(entry.getKey()) || entry.getValue().player.isRemoved()) iterator.remove();
		}
	}

	private static void render(LevelRenderContext context) {
		if (!FlightArmorClientSettingsStore.get().trailEnabled() || TRAILS.isEmpty()) return;
		LevelRenderState levelState = context.levelState();
		if (levelState == null || levelState.cameraRenderState == null) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		Vec3 camera = levelState.cameraRenderState.pos;
		if (camera == null) return;
		float partialTick = levelState.worldPartialTicks;
		boolean fastGraphics = client.options.graphicsPreset().get() == GraphicsPreset.FAST;
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();

		collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vertices) -> {
			for (TrailHistory history : TRAILS.values()) {
				Player player = history.player;
				if (player.isRemoved() || player.level() != client.level) continue;
				// 第一人称完全不提交本地玩家的尾迹顶点，避免任何近裁剪面遮挡。
				if (player == client.player && levelState.cameraRenderState.isFirstPerson) continue;
				double distanceSqr = camera.distanceToSqr(player.getPosition(partialTick));
				if (distanceSqr > MAX_RENDER_DISTANCE_SQR) continue;

				boolean reduced = fastGraphics || distanceSqr > REDUCED_DETAIL_DISTANCE_SQR;
				List<TrailSample> samples = history.snapshot(
						reduced ? FAST_HISTORY_SAMPLES : MAX_HISTORY_SAMPLES,
						player.getPosition(partialTick));
				if (samples.size() < 2) continue;
				float fade = 1.0F - Mth.clamp(history.inactiveTicks / (float) FADE_TICKS, 0.0F, 1.0F);
				for (ArmorSemanticAnchor anchor : reduced ? REDUCED_ANCHORS : FULL_ANCHORS) {
					renderAnchorRibbon(vertices, pose, camera, samples, anchor, reduced ? 1 : 3, fade);
				}
			}
		});
	}

	private static void renderAnchorRibbon(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			List<TrailSample> samples, ArmorSemanticAnchor anchor, int subdivisions, float globalFade) {
		List<CurvePoint> curve = buildCurve(samples, anchor, subdivisions);
		int size = curve.size();
		if (size < 2) return;
		double width = switch (anchor) {
			case CHEST_CORE -> 0.145D;
			case LEFT_PALM, RIGHT_PALM -> 0.095D;
			default -> 0.110D;
		};
		Vec3[] sides = new Vec3[size];
		for (int index = 0; index < size; index++) {
			Vec3 previous = curve.get(Math.max(0, index - 1)).position;
			Vec3 next = curve.get(Math.min(size - 1, index + 1)).position;
			Vec3 tangent = next.subtract(previous);
			Vec3 side = tangent.cross(camera.subtract(curve.get(index).position));
			sides[index] = side.lengthSqr() < 1.0E-8D ? basis(tangent).first : side.normalize();
		}
		// 外层柔光与高亮内芯共用同一组法向向量，避免重复叉乘归一化。
		emitRibbon(vertices, pose, camera, curve, sides, width, 150.0F * globalFade, false);
		emitRibbon(vertices, pose, camera, curve, sides, width * 0.36D, 245.0F * globalFade, true);
	}

	private static List<CurvePoint> buildCurve(List<TrailSample> samples, ArmorSemanticAnchor anchor, int subdivisions) {
		List<CurvePoint> curve = new ArrayList<>((samples.size() - 1) * subdivisions + 1);
		int last = samples.size() - 1;
		for (int segment = 0; segment < last; segment++) {
			Vec3 p0 = samples.get(Math.max(0, segment - 1)).anchor(anchor);
			Vec3 p1 = samples.get(segment).anchor(anchor);
			Vec3 p2 = samples.get(segment + 1).anchor(anchor);
			Vec3 p3 = samples.get(Math.min(last, segment + 2)).anchor(anchor);
			for (int step = 0; step < subdivisions; step++) {
				float local = step / (float) subdivisions;
				float life = (segment + local) / last;
				float speedProgress = Mth.lerp(local,
						samples.get(segment).speedProgress,
						samples.get(segment + 1).speedProgress);
				curve.add(new CurvePoint(catmullRom(p0, p1, p2, p3, local), life, speedProgress));
			}
		}
		TrailSample newest = samples.get(last);
		curve.add(new CurvePoint(newest.anchor(anchor), 1.0F, newest.speedProgress));
		return curve;
	}

	private static void emitRibbon(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera, List<CurvePoint> curve,
			Vec3[] sides, double baseWidth, float maximumAlpha, boolean core) {
		for (int index = 0, limit = curve.size() - 1; index < limit; index++) {
			CurvePoint first = curve.get(index);
			CurvePoint second = curve.get(index + 1);
			if (first.position.distanceToSqr(second.position) < 1.0E-8D) continue;
			double firstWidth = baseWidth * Mth.lerp(smoothstep(0.0F, 0.30F, first.life), 0.10D, 1.0D);
			double secondWidth = baseWidth * Mth.lerp(smoothstep(0.0F, 0.30F, second.life), 0.10D, 1.0D);
			Rgba firstColor = trailColor(first.speedProgress, core,
					maximumAlpha * tailAlpha(first.life));
			Rgba secondColor = trailColor(second.speedProgress, core,
					maximumAlpha * tailAlpha(second.life));
			Vec3 firstSide = sides[index].scale(firstWidth);
			Vec3 secondSide = sides[index + 1].scale(secondWidth);
			emitQuad(vertices, pose, camera,
					first.position.subtract(firstSide), first.position.add(firstSide),
					second.position.add(secondSide), second.position.subtract(secondSide),
					firstColor, firstColor, secondColor, secondColor);
		}
	}

	private static float tailAlpha(float life) {
		return smoothstep(0.0F, 0.34F, life)
				* (1.0F - 0.18F * smoothstep(0.88F, 1.0F, life));
	}

	private static Rgba trailColor(float speedProgress, boolean core, float alpha) {
		FlightLightColorCurve.Rgb color = FlightLightColorCurve.colorAt(speedProgress);
		if (core) {
			return new Rgba(
					Math.min(255, color.red() + 28),
					Math.min(255, color.green() + 36),
					Math.min(255, color.blue() + 20),
					255).alpha(alpha);
		}
		return new Rgba(color.red(), color.green(), color.blue(), 255).alpha(alpha);
	}

	private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float progress) {
		double t = progress;
		double t2 = t * t;
		double t3 = t2 * t;
		return p1.scale(2.0D)
				.add(p2.subtract(p0).scale(t))
				.add(p0.scale(2.0D).subtract(p1.scale(5.0D)).add(p2.scale(4.0D)).subtract(p3).scale(t2))
				.add(p3.subtract(p0).add(p1.scale(3.0D).subtract(p2.scale(3.0D))).scale(t3))
				.scale(0.5D);
	}

	private static Basis basis(Vec3 axis) {
		Vec3 direction = axis.lengthSqr() > 1.0E-8D ? axis.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
		Vec3 reference = Math.abs(direction.y) < 0.88D ? WORLD_UP : WORLD_RIGHT;
		Vec3 first = direction.cross(reference).normalize();
		return new Basis(first, direction.cross(first).normalize());
	}

	private static void emitQuad(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3,
			Rgba c0, Rgba c1, Rgba c2, Rgba c3) {
		emitVertex(vertices, pose, camera, v0, c0);
		emitVertex(vertices, pose, camera, v1, c1);
		emitVertex(vertices, pose, camera, v2, c2);
		emitVertex(vertices, pose, camera, v3, c3);
	}

	private static void emitVertex(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera, Vec3 position, Rgba color) {
		vertices.addVertex(pose, (float) (position.x - camera.x),
				(float) (position.y - camera.y), (float) (position.z - camera.z))
				.setColor(color.r, color.g, color.b, color.a);
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		float progress = Mth.clamp((value - edge0) / Math.max(1.0E-5F, edge1 - edge0), 0.0F, 1.0F);
		return progress * progress * (3.0F - 2.0F * progress);
	}

	private static Rgba mix(Rgba from, Rgba to, float progress) {
		float value = Mth.clamp(progress, 0.0F, 1.0F);
		return new Rgba(Math.round(Mth.lerp(value, from.r, to.r)),
				Math.round(Mth.lerp(value, from.g, to.g)),
				Math.round(Mth.lerp(value, from.b, to.b)),
				Math.round(Mth.lerp(value, from.a, to.a)));
	}

	private static void clear() {
		TRAILS.clear();
		sampledDimension = null;
	}

	private static final class TrailHistory {
		private final Player player;
		private final ArrayDeque<TrailSample> samples = new ArrayDeque<>();
		private int inactiveTicks;
		private double totalLength;

		private TrailHistory(Player player) {
			this.player = player;
		}

		private void sample(int historyLimit) {
			Vec3 position = player.position();
			TrailSample previous = samples.peekLast();
			if (previous != null && previous.playerPosition.distanceToSqr(position) > TELEPORT_RESET_DISTANCE_SQR) {
				samples.clear();
				totalLength = 0.0D;
				previous = null;
			}
			Vec3 movement = previous == null ? player.getDeltaMovement() : position.subtract(previous.playerPosition);
			Vec3 direction = movement.lengthSqr() > 1.0E-6D ? movement.normalize()
					: player.getLookAngle().normalize();
			Basis bodyBasis = basis(direction);
			float speedProgress = (float) FlightSpeedTelemetry.profileEffectProgress(player, 1.0F);
			TrailSample next = new TrailSample(position, position.add(0.0D, 0.95D, 0.0D),
					direction, bodyBasis.first.scale(-1.0D), bodyBasis.second, speedProgress);
			if (previous != null) totalLength += previous.center.distanceTo(next.center);
			samples.addLast(next);
			inactiveTicks = 0;
			while (samples.size() > historyLimit || totalLength > MAX_TRAIL_WORLD_LENGTH) removeOldest();
		}

		private void removeOldest() {
			TrailSample removed = samples.pollFirst();
			TrailSample next = samples.peekFirst();
			if (removed != null && next != null) {
				totalLength = Math.max(0.0D, totalLength - removed.center.distanceTo(next.center));
			} else if (samples.isEmpty()) {
				totalLength = 0.0D;
			}
		}

		private List<TrailSample> snapshot(int maximumSamples, Vec3 interpolatedPlayerPosition) {
			List<TrailSample> result = new ArrayList<>(samples);
			if (result.size() > maximumSamples) {
				result = new ArrayList<>(result.subList(result.size() - maximumSamples, result.size()));
			}
			if (!result.isEmpty()) {
				int last = result.size() - 1;
				result.set(last, result.get(last).withPlayerPosition(interpolatedPlayerPosition));
			}
			return result;
		}
	}

	private record TrailSample(Vec3 playerPosition, Vec3 center, Vec3 direction,
			Vec3 right, Vec3 up, float speedProgress) {
		private Vec3 anchor(ArmorSemanticAnchor anchor) {
			return ArmorAnchorResolver.resolveFromBasis(playerPosition, direction, right, up,
					ArmorVariant.SWIFT, ArmorAnchorPose.FLIGHT, anchor, 0);
		}

		private TrailSample withPlayerPosition(Vec3 nextPlayerPosition) {
			Vec3 offset = nextPlayerPosition.subtract(playerPosition);
			return new TrailSample(nextPlayerPosition, center.add(offset), direction, right, up, speedProgress);
		}
	}

	private record CurvePoint(Vec3 position, float life, float speedProgress) {
	}

	private record Basis(Vec3 first, Vec3 second) {
	}

	private record Rgba(int r, int g, int b, int a) {
		private Rgba alpha(float nextAlpha) {
			return new Rgba(r, g, b, Math.round(Mth.clamp(nextAlpha, 0.0F, 255.0F)));
		}
	}
}
