package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;

import java.util.Objects;

/**
 * Pure armor-space anchor profile. Coordinates are right/up/forward offsets from the wearer root.
 * All variant and pose tuning lives here so gameplay and rendering cannot silently drift apart.
 */
public final class ArmorAnchorLayout {
	private ArmorAnchorLayout() {
	}

	public static LocalOffset resolve(ArmorVariant variant, ArmorAnchorPose pose,
			ArmorSemanticAnchor anchor) {
		return resolve(variant, pose, anchor, 0);
	}

	public static LocalOffset resolve(ArmorVariant variant, ArmorAnchorPose pose,
			ArmorSemanticAnchor anchor, int lane) {
		Objects.requireNonNull(variant, "variant");
		Objects.requireNonNull(pose, "pose");
		Objects.requireNonNull(anchor, "anchor");
		if (anchor.isDronePod()) {
			return dronePod(pose, anchor);
		}
		if (anchor.isWingCannon()) {
			return wingCannon(pose, anchor);
		}
		LocalOffset base = base(pose, anchor);
		double sideScale = sideScale(variant, anchor);
		double upAdjustment = switch (variant) {
			case VANGUARD -> 0.0D;
			case SWIFT -> -0.02D;
			case SENTINEL -> 0.03D;
		};
		double forwardAdjustment = switch (variant) {
			case VANGUARD -> 0.0D;
			case SWIFT -> -0.01D;
			case SENTINEL -> 0.05D;
		};
		double laneLift = anchor.isMissileRack() ? Math.max(0, lane) * 0.10D : 0.0D;
		return new LocalOffset(base.right() * sideScale,
				base.up() + upAdjustment + laneLift,
				base.forward() + forwardAdjustment);
	}

	/** Camera-space projection of the same semantic point for local first-person weapon FX. */
	public static LocalOffset resolveFirstPerson(ArmorVariant variant, ArmorAnchorPose pose,
			ArmorSemanticAnchor anchor) {
		Objects.requireNonNull(variant, "variant");
		Objects.requireNonNull(pose, "pose");
		Objects.requireNonNull(anchor, "anchor");
		if (anchor.isDronePod()) {
			return new LocalOffset(anchor.sideSign() * 0.34D, -0.10D, 0.02D);
		}
		if (anchor.isWingCannon()) {
			return new LocalOffset(anchor.sideSign() * 0.42D, -0.08D, 0.04D);
		}
		double poseDrop = switch (pose) {
			case CROUCHING -> 0.02D;
			case FLIGHT -> -0.03D;
			case SWIMMING -> -0.06D;
			default -> 0.0D;
		};
		double sideScale = switch (variant) {
			case VANGUARD -> 1.0D;
			case SWIFT -> 0.91D;
			case SENTINEL -> 1.10D;
		};
		if (anchor == ArmorSemanticAnchor.CHEST_CORE) {
			return new LocalOffset(0.0D, -0.15D + poseDrop,
					variant == ArmorVariant.SENTINEL ? 0.49D : 0.46D);
		}
		if (anchor.isPalm()) {
			return new LocalOffset(anchor.sideSign() * 0.22D * sideScale,
					-0.18D + poseDrop, variant == ArmorVariant.SWIFT ? 0.45D : 0.43D);
		}
		if (anchor.isMissileRack()) {
			return new LocalOffset(anchor.sideSign() * 0.25D * sideScale,
					-0.10D + poseDrop, 0.40D);
		}
		return new LocalOffset(anchor.sideSign() * 0.18D * sideScale,
				-0.58D + poseDrop, 0.28D);
	}

	/**
	 * Sentinel's detachable pods sit behind the shoulder line rather than sharing the forward
	 * missile-rack muzzle. Keeping this as a semantic armor-space anchor lets server fire lines
	 * follow the same carrier yaw/aim basis as the visible segmented back assembly.
	 */
	private static LocalOffset dronePod(ArmorAnchorPose pose, ArmorSemanticAnchor anchor) {
		double side = switch (pose) {
			case STANDING, CROUCHING -> 0.58D;
			case HOVER -> 0.66D;
			case FLIGHT, SWIMMING -> 0.62D;
		};
		double up = switch (pose) {
			case STANDING -> 1.36D;
			case CROUCHING -> 1.14D;
			case HOVER -> 1.42D;
			case FLIGHT -> 1.23D;
			case SWIMMING -> 0.55D;
		};
		double forward = switch (pose) {
			case STANDING, CROUCHING -> -0.24D;
			case HOVER -> -0.31D;
			case FLIGHT -> -0.22D;
			case SWIMMING -> -0.17D;
		};
		return new LocalOffset(anchor.sideSign() * side, up, forward);
	}

	/**
	 * Muzzle at the outer rail of the player's mechanical overdrive wing. This
	 * is intentionally independent from detachable drone-pod formation anchors:
	 * G always fires from armor still physically attached to the worn suit.
	 */
	private static LocalOffset wingCannon(
			ArmorAnchorPose pose, ArmorSemanticAnchor anchor) {
		double side = switch (pose) {
			case STANDING, CROUCHING -> 1.24D;
			case HOVER -> 1.48D;
			case FLIGHT -> 1.38D;
			case SWIMMING -> 0.92D;
		};
		double up = switch (pose) {
			case STANDING -> 1.58D;
			case CROUCHING -> 1.35D;
			case HOVER -> 1.68D;
			case FLIGHT -> 1.32D;
			case SWIMMING -> 0.62D;
		};
		double forward = switch (pose) {
			case STANDING, CROUCHING -> -0.54D;
			case HOVER -> -0.62D;
			case FLIGHT -> -0.48D;
			case SWIMMING -> -0.28D;
		};
		return new LocalOffset(anchor.sideSign() * side, up, forward);
	}

	private static LocalOffset base(ArmorAnchorPose pose, ArmorSemanticAnchor anchor) {
		return switch (pose) {
			case STANDING -> switchAnchor(anchor,
					0.94D, 0.24D, 0.35D, 1.14D, 0.28D,
					0.18D, 0.12D, -0.08D, 0.44D, 1.24D, 0.20D);
			case CROUCHING -> switchAnchor(anchor,
					0.72D, 0.30D, 0.34D, 0.92D, 0.32D,
					0.18D, 0.10D, -0.04D, 0.43D, 1.02D, 0.22D);
			case HOVER -> switchAnchor(anchor,
					1.22D, 0.34D, 0.46D, 1.18D, 0.30D,
					0.18D, 0.16D, -0.16D, 0.44D, 1.28D, 0.22D);
			case FLIGHT -> switchAnchor(anchor,
					0.95D, 0.20D, 0.37D, 1.17D, 0.22D,
					0.18D, 0.42D, -0.22D, 0.44D, 1.05D, 0.08D);
			case SWIMMING -> switchAnchor(anchor,
					0.30D, 0.50D, 0.34D, 0.30D, 0.56D,
					0.18D, 0.16D, -0.08D, 0.42D, 0.42D, 0.20D);
		};
	}

	private static LocalOffset switchAnchor(ArmorSemanticAnchor anchor,
			double coreUp, double coreForward,
			double palmSide, double palmUp, double palmForward,
			double bootSide, double bootUp, double bootForward,
			double rackSide, double rackUp, double rackForward) {
		if (anchor == ArmorSemanticAnchor.CHEST_CORE) {
			return new LocalOffset(0.0D, coreUp, coreForward);
		}
		if (anchor.isPalm()) {
			return new LocalOffset(anchor.sideSign() * palmSide, palmUp, palmForward);
		}
		if (anchor.isBootSole()) {
			return new LocalOffset(anchor.sideSign() * bootSide, bootUp, bootForward);
		}
		return new LocalOffset(anchor.sideSign() * rackSide, rackUp, rackForward);
	}

	private static double sideScale(ArmorVariant variant, ArmorSemanticAnchor anchor) {
		return switch (variant) {
			case VANGUARD -> 1.0D;
			case SWIFT -> anchor.isMissileRack() ? 1.08D : 0.90D;
			case SENTINEL -> 1.14D;
		};
	}

	public record LocalOffset(double right, double up, double forward) {
		public LocalOffset {
			if (!Double.isFinite(right) || !Double.isFinite(up) || !Double.isFinite(forward)) {
				throw new IllegalArgumentException("anchor offsets must be finite");
			}
		}
	}
}
