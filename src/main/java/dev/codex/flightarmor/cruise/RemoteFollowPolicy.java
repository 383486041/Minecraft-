package dev.codex.flightarmor.cruise;

/**
 * Pure movement policy for an explicitly selected remote suit.
 *
 * <p>The controller owns the world queries and actual movement. Keeping the layer decision here
 * makes the transition thresholds deterministic and lets the behavior be regression-tested
 * without starting Minecraft.</p>
 */
public final class RemoteFollowPolicy {
	public static final double CATCH_UP_DISTANCE = 11.0D;
	public static final double LOST_DISTANCE = 46.0D;
	private static final double MAX_OWNER_SPEED = 8.0D;

	private RemoteFollowPolicy() {
	}

	public static Plan plan(double ownerToCarrierDistance, double ownerHorizontalSpeed,
			boolean combatDetached, boolean hasLineOfSight, boolean sameDimension) {
		double distance = finiteNonNegative(ownerToCarrierDistance, LOST_DISTANCE);
		double ownerSpeed = finiteNonNegative(ownerHorizontalSpeed, 0.0D);
		if (!sameDimension || distance >= LOST_DISTANCE) {
			return new Plan(Layer.LOST, 0.0D, 0.0D, 0.0D, false);
		}
		if (combatDetached) {
			return new Plan(Layer.COMBAT_DETACHED, 0.0D, 1.85D, 0.0D, true);
		}
		if (!hasLineOfSight || distance >= CATCH_UP_DISTANCE) {
			double boundedSpeed = Math.min(MAX_OWNER_SPEED, ownerSpeed);
			return new Plan(Layer.CATCH_UP,
					clamp(7.0D + boundedSpeed * 1.5D, 7.0D, 18.0D),
					clamp(2.4D + boundedSpeed * 1.45D, 2.4D, 8.0D),
					hasLineOfSight ? 2.6D : 4.2D,
					true);
		}
		return new Plan(Layer.FORMATION,
				clamp(3.0D + ownerSpeed * 0.75D, 3.0D, 8.0D),
				clamp(1.35D + ownerSpeed * 0.70D, 1.35D, 3.8D),
				1.15D,
				true);
	}

	private static double finiteNonNegative(double value, double fallback) {
		return Double.isFinite(value) ? Math.max(0.0D, value) : fallback;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	public enum Layer {
		FORMATION,
		COMBAT_DETACHED,
		CATCH_UP,
		LOST
	}

	public record Plan(Layer layer, double predictionTicks, double maxSpeed,
			double verticalClearance, boolean controlsMovement) {
	}
}
