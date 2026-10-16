package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Minecraft world/camera adapter for {@link ArmorAnchorLayout}. */
public final class ArmorAnchorResolver {
	private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);

	private ArmorAnchorResolver() {
	}

	public static ArmorAnchorPose inferPose(Entity entity) {
		Objects.requireNonNull(entity, "entity");
		Pose pose = entity.getPose();
		if (pose == Pose.CROUCHING) return ArmorAnchorPose.CROUCHING;
		if (pose == Pose.SWIMMING) return ArmorAnchorPose.SWIMMING;
		if (pose == Pose.FALL_FLYING) return ArmorAnchorPose.FLIGHT;
		return ArmorAnchorPose.STANDING;
	}

	public static Vec3 resolve(Entity wearer, ArmorVariant variant, ArmorAnchorPose pose,
			ArmorSemanticAnchor anchor, Vec3 preferredForward) {
		return resolve(wearer, variant, pose, anchor, 0, preferredForward);
	}

	public static Vec3 resolve(Entity wearer, ArmorVariant variant, ArmorAnchorPose pose,
			ArmorSemanticAnchor anchor, int lane, Vec3 preferredForward) {
		Objects.requireNonNull(wearer, "wearer");
		Vec3 forward = safeForward(wearer, preferredForward);
		Vec3 right = horizontalRight(wearer, forward);
		return resolveFromBasis(wearer.position(), forward, right, WORLD_UP,
				variant, pose, anchor, lane);
	}

	public static Vec3 resolveFromBasis(Vec3 root, Vec3 forward, Vec3 right, Vec3 up,
			ArmorVariant variant, ArmorAnchorPose pose, ArmorSemanticAnchor anchor, int lane) {
		return apply(root, forward, right, up, ArmorAnchorLayout.resolve(variant, pose, anchor, lane));
	}

	public static Vec3 resolveFirstPerson(Vec3 camera, Vec3 forward, Vec3 right, Vec3 up,
			ArmorVariant variant, ArmorAnchorPose pose, ArmorSemanticAnchor anchor) {
		return apply(camera, forward, right, up,
				ArmorAnchorLayout.resolveFirstPerson(variant, pose, anchor));
	}

	private static Vec3 apply(Vec3 root, Vec3 forward, Vec3 right, Vec3 up,
			ArmorAnchorLayout.LocalOffset offset) {
		Objects.requireNonNull(root, "root");
		Vec3 safeForward = normalized(forward, new Vec3(0.0D, 0.0D, 1.0D));
		Vec3 safeRight = normalized(right, new Vec3(1.0D, 0.0D, 0.0D));
		Vec3 safeUp = normalized(up, WORLD_UP);
		return root.add(safeRight.scale(offset.right()))
				.add(safeUp.scale(offset.up()))
				.add(safeForward.scale(offset.forward()));
	}

	private static Vec3 safeForward(Entity wearer, Vec3 preferred) {
		if (finite(preferred) && preferred.lengthSqr() > 1.0E-8D) return preferred.normalize();
		Vec3 look = wearer.getLookAngle();
		if (finite(look) && look.lengthSqr() > 1.0E-8D) return look.normalize();
		double yaw = Math.toRadians(wearer.getYRot());
		return new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
	}

	private static Vec3 horizontalRight(Entity wearer, Vec3 forward) {
		Vec3 right = new Vec3(forward.z, 0.0D, -forward.x);
		if (right.lengthSqr() > 1.0E-8D) return right.normalize();
		double yaw = Math.toRadians(wearer.getYRot());
		return new Vec3(Math.cos(yaw), 0.0D, Math.sin(yaw));
	}

	private static Vec3 normalized(Vec3 candidate, Vec3 fallback) {
		return finite(candidate) && candidate.lengthSqr() > 1.0E-8D ? candidate.normalize() : fallback;
	}

	private static boolean finite(Vec3 value) {
		return value != null && Double.isFinite(value.x)
				&& Double.isFinite(value.y) && Double.isFinite(value.z);
	}
}
