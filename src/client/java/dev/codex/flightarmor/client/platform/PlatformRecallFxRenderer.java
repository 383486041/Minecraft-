package dev.codex.flightarmor.client.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Texture-free platform recall field. Long additive ribbons make the vertical launch and
 * 1.8-tick hypersonic dive readable while compression rings communicate braking and lock.
 */
public final class PlatformRecallFxRenderer {
	private static final EquipmentSlot[] PART_SLOTS = {
			EquipmentSlot.FEET, EquipmentSlot.LEGS,
			EquipmentSlot.CHEST, EquipmentSlot.HEAD
	};
	private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
	private static final Vec3 WORLD_RIGHT = new Vec3(1.0D, 0.0D, 0.0D);
	private static final Rgba CYAN = new Rgba(38, 244, 255, 255);
	private static final Rgba VIOLET = new Rgba(185, 58, 255, 255);
	private static final Rgba HOT_WHITE = new Rgba(255, 240, 255, 255);
	private static final Rgba BRAKE_GOLD = new Rgba(255, 190, 54, 255);
	private static boolean initialized;

	private PlatformRecallFxRenderer() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		LevelRenderEvents.COLLECT_SUBMITS.register(PlatformRecallFxRenderer::render);
	}

	private static void render(LevelRenderContext context) {
		if (!FlightArmorClientSettingsStore.get().environmentFeedbackEnabled()) return;
		if (context.levelRenderer() == null) return;
		LevelRenderState levelState = context.levelState();
		if (levelState == null || levelState.cameraRenderState == null) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		Vec3 camera = levelState.cameraRenderState.pos;
		if (camera == null) return;
		float partialTick = levelState.worldPartialTicks;
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();

		collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vertices) -> {
			for (Player player : client.level.players()) {
				if (camera.distanceToSqr(player.getPosition(partialTick)) > 160.0D * 160.0D) continue;
				PlatformRecallClient.Sample sample = PlatformRecallClient.sample(player, partialTick);
				if (!sample.active()) continue;
				boolean suppressNearCamera = player == client.player
						&& levelState.cameraRenderState.isFirstPerson;
				if (sample.assembled()) {
					renderAssembled(vertices, pose, camera, player, sample, partialTick, suppressNearCamera);
				} else {
					renderParts(vertices, pose, camera, player, sample, partialTick, suppressNearCamera);
				}
			}
		});
	}

	private static void renderAssembled(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera, Player player,
			PlatformRecallClient.Sample sample, float partialTick, boolean suppressNearCamera) {
		Vec3 base = player.getPosition(partialTick).add(0.0D, 0.95D, 0.0D);
		float elapsed = sample.elapsedTicks();
		Vec3 current = base.add(sample.assembledOffsetAt(elapsed));
		Vec3 previous = base.add(sample.assembledOffsetAt(Math.max(0.0F, elapsed - 0.16F)));
		Vec3 fallback = sample.approachForward().add(0.0D, -0.12D, 0.0D).normalize();
		Vec3 direction = motionDirection(previous, current, fallback);
		float rush = sample.hypersonicStrength();
		float brake = sample.brakingStrength();
		if (rush > 0.01F) {
			Vec3 tail = base.add(sample.assembledOffsetAt(Math.max(0.0F, elapsed - 0.62F)));
			renderSpeedEnvelope(vertices, pose, camera, tail, current, direction,
					0.46D, rush, elapsed * 0.72D);
		}
		float capture = pulse(2.55F, 3.25F, 4.05F, elapsed);
		renderCompression(vertices, pose, camera, current, direction,
				0.72D, brake, capture, suppressNearCamera);
	}

	private static void renderParts(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera, Player player,
			PlatformRecallClient.Sample sample, float partialTick, boolean suppressNearCamera) {
		Vec3 playerBase = player.getPosition(partialTick);
		for (int index = 0; index < PART_SLOTS.length; index++) {
			EquipmentSlot slot = PART_SLOTS[index];
			float elapsed = sample.elapsedTicks();
			Vec3 anchor = playerBase.add(0.0D, partHeight(slot), 0.0D);
			PlatformRecallClient.PartTrajectory trajectory =
					sample.partTrajectoryAt(slot, null, elapsed);
			PlatformRecallClient.PartTrajectory previousTrajectory =
					sample.partTrajectoryAt(slot, null, Math.max(0.0F, elapsed - 0.14F));
			Vec3 current = anchor.add(trajectory.offset());
			Vec3 previous = anchor.add(previousTrajectory.offset());
			Vec3 fallback = sample.approachForward().add(0.0D, -0.08D, 0.0D).normalize();
			Vec3 direction = motionDirection(previous, current, fallback);
			if (trajectory.hypersonicStrength() > 0.01F) {
				PlatformRecallClient.PartTrajectory tailTrajectory =
						sample.partTrajectoryAt(slot, null, Math.max(0.0F, elapsed - 0.52F));
				Vec3 tail = anchor.add(tailTrajectory.offset());
				renderSpeedEnvelope(vertices, pose, camera, tail, current, direction,
						partRadius(slot), trajectory.hypersonicStrength(),
						elapsed * 0.82D + index * 0.42D);
			}
			renderCompression(vertices, pose, camera, current, direction,
					partRadius(slot) * 1.48D, trajectory.brakingStrength(),
					trajectory.lockStrength(), suppressNearCamera);
		}
	}

	private static void renderSpeedEnvelope(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 rawTail, Vec3 current, Vec3 direction, double radius,
			float strength, double phase) {
		Vec3 tailDelta = rawTail.subtract(current);
		Vec3 tail = tailDelta.lengthSqr() > 28.0D * 28.0D
				? current.add(tailDelta.normalize().scale(28.0D)) : rawTail;
		if (tail.distanceToSqr(current) < 0.01D) return;
		Rgba speed = speedColor(strength, 0.0F);
		Rgba core = mix(HOT_WHITE, speed, 0.24F);
		renderPrism(vertices, pose, camera, tail, current,
				radius * 0.03D, radius, 6, phase,
				speed.alpha(0), speed.alpha(118.0F + 64.0F * strength));
		renderPrism(vertices, pose, camera, tail.lerp(current, 0.20D), current,
				radius * 0.018D, radius * 0.31D, 4, -phase * 1.35D,
				core.alpha(10.0F), core.alpha(235.0F));

		Vec3 axis = current.subtract(tail);
		for (int index = 0; index < 3; index++) {
			double travel = 0.24D + index * 0.21D;
			Vec3 center = tail.add(axis.scale(travel));
			float fade = (float) (0.35D + travel * 0.65D) * strength;
			renderRing(vertices, pose, camera, center, direction,
					radius * (0.72D + index * 0.16D),
					Math.max(0.012D, radius * 0.075D), 6,
					phase + index * 0.47D, speed.alpha(92.0F * fade));
		}
	}

	private static void renderCompression(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 center, Vec3 direction, double baseRadius,
			float braking, float locking, boolean suppressNearCamera) {
		if (suppressNearCamera && center.distanceToSqr(camera) < 2.25D) return;
		if (braking > 0.01F) {
			Rgba color = speedColor(0.0F, braking);
			double radius = baseRadius * (0.72D + braking * 1.72D);
			renderRing(vertices, pose, camera, center.subtract(direction.scale(0.10D)), direction,
					radius, Math.max(0.025D, radius * 0.075D), 12,
					braking * 0.42D, color.alpha(186.0F * braking));
			renderRing(vertices, pose, camera, center.subtract(direction.scale(0.42D)), direction,
					radius * 0.62D, Math.max(0.018D, radius * 0.045D), 8,
					-braking * 0.68D, HOT_WHITE.alpha(146.0F * braking));
		}
		if (locking > 0.01F) {
			double radius = baseRadius * (0.48D + locking * 0.64D);
			renderRing(vertices, pose, camera, center, direction, radius,
					Math.max(0.018D, radius * 0.085D), 8,
					locking * Math.PI * 0.25D, CYAN.alpha(224.0F * locking));
		}
	}

	private static void renderPrism(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 start, Vec3 end, double startRadius, double endRadius,
			int sides, double rotation, Rgba startColor, Rgba endColor) {
		Vec3 axis = end.subtract(start);
		if (axis.lengthSqr() < 1.0E-8D) return;
		Basis basis = basis(axis);
		for (int side = 0; side < sides; side++) {
			double angle0 = rotation + Math.PI * 2.0D * side / sides;
			double angle1 = rotation + Math.PI * 2.0D * (side + 1) / sides;
			Vec3 radial0 = basis.radial(angle0);
			Vec3 radial1 = basis.radial(angle1);
			emitQuad(vertices, pose, camera,
					start.add(radial0.scale(startRadius)),
					start.add(radial1.scale(startRadius)),
					end.add(radial1.scale(endRadius)),
					end.add(radial0.scale(endRadius)),
					startColor, startColor, endColor, endColor);
		}
	}

	private static void renderRing(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 center, Vec3 normal, double radius, double thickness,
			int sides, double rotation, Rgba color) {
		if (radius <= 0.001D || color.a <= 0) return;
		Basis basis = basis(normal);
		double inner = Math.max(0.001D, radius - thickness * 0.5D);
		double outer = radius + thickness * 0.5D;
		for (int side = 0; side < sides; side++) {
			double angle0 = rotation + Math.PI * 2.0D * side / sides;
			double angle1 = rotation + Math.PI * 2.0D * (side + 1) / sides;
			Vec3 radial0 = basis.radial(angle0);
			Vec3 radial1 = basis.radial(angle1);
			emitQuad(vertices, pose, camera,
					center.add(radial0.scale(inner)),
					center.add(radial0.scale(outer)),
					center.add(radial1.scale(outer)),
					center.add(radial1.scale(inner)),
					color, color, color, color);
		}
	}

	private static void emitQuad(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3,
			Rgba c0, Rgba c1, Rgba c2, Rgba c3) {
		emitVertex(vertices, pose, camera, v0, c0);
		emitVertex(vertices, pose, camera, v1, c1);
		emitVertex(vertices, pose, camera, v2, c2);
		emitVertex(vertices, pose, camera, v3, c3);
	}

	private static void emitVertex(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 position, Rgba color) {
		vertices.addVertex(pose, (float) (position.x - camera.x),
				(float) (position.y - camera.y), (float) (position.z - camera.z))
				.setColor(color.r, color.g, color.b, color.a);
	}

	private static Vec3 motionDirection(Vec3 previous, Vec3 current, Vec3 fallback) {
		Vec3 delta = current.subtract(previous);
		return delta.lengthSqr() > 1.0E-7D ? delta.normalize() : fallback;
	}

	private static Basis basis(Vec3 axis) {
		Vec3 direction = axis.lengthSqr() > 1.0E-8D ? axis.normalize() : WORLD_UP;
		Vec3 reference = Math.abs(direction.y) < 0.86D ? WORLD_UP : WORLD_RIGHT;
		Vec3 first = direction.cross(reference).normalize();
		return new Basis(first, direction.cross(first).normalize());
	}

	private static double partHeight(EquipmentSlot slot) {
		return switch (slot) {
			case FEET -> 0.22D;
			case LEGS -> 0.66D;
			case CHEST -> 1.13D;
			case HEAD -> 1.65D;
			default -> 0.92D;
		};
	}

	private static double partRadius(EquipmentSlot slot) {
		return switch (slot) {
			case FEET -> 0.17D;
			case LEGS -> 0.23D;
			case CHEST -> 0.29D;
			case HEAD -> 0.21D;
			default -> 0.20D;
		};
	}

	private static Rgba speedColor(float rush, float brake) {
		Rgba speed = mix(CYAN, VIOLET, smoothstep(0.05F, 0.68F, rush));
		speed = mix(speed, HOT_WHITE, smoothstep(0.70F, 1.0F, rush));
		return mix(speed, BRAKE_GOLD, smoothstep(0.06F, 0.84F, brake));
	}

	private static float pulse(float start, float peak, float end, float value) {
		return smoothstep(start, peak, value) * (1.0F - smoothstep(peak, end, value));
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		float t = Mth.clamp((value - edge0) / Math.max(1.0E-5F, edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static Rgba mix(Rgba from, Rgba to, float progress) {
		float t = Mth.clamp(progress, 0.0F, 1.0F);
		return new Rgba(Math.round(Mth.lerp(t, from.r, to.r)),
				Math.round(Mth.lerp(t, from.g, to.g)),
				Math.round(Mth.lerp(t, from.b, to.b)),
				Math.round(Mth.lerp(t, from.a, to.a)));
	}

	private record Basis(Vec3 first, Vec3 second) {
		private Vec3 radial(double angle) {
			return first.scale(Math.cos(angle)).add(second.scale(Math.sin(angle)));
		}
	}

	private record Rgba(int r, int g, int b, int a) {
		private Rgba alpha(float nextAlpha) {
			return new Rgba(r, g, b, Math.round(Mth.clamp(nextAlpha, 0.0F, 255.0F)));
		}
	}
}
