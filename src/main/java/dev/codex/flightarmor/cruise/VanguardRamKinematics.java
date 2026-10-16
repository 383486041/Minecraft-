package dev.codex.flightarmor.cruise;

/**
 * Pure geometry and timing rules for the Vanguard escort ram.
 *
 * <p>This class deliberately has no Minecraft dependencies, so the continuous-collision and
 * acceleration invariants can be exercised by an offline verification task.</p>
 */
final class VanguardRamKinematics {
	static final double NO_HIT = Double.POSITIVE_INFINITY;
	static final long MIN_RAM_CLIMB_TICKS = 10L;
	static final double MIN_RAM_ALTITUDE_ABOVE_TARGET = 7.50D;
	static final double MAX_RAM_STAGING_DISTANCE = 0.85D;
	static final double MIN_RAM_DESCENT_COMPONENT = 0.30D;
	static final long MIN_RAM_RECOVERY_TICKS = 8L;
	static final double MIN_RAM_RECOVERY_CLIMB = 5.50D;
	static final double MAX_RAM_RECOVERY_DISTANCE = 1.10D;
	static final double MAX_RAM_RECOVERY_SPEED = 0.25D;
	static final long GROUND_SLAM_BRACE_TICKS = 4L;
	static final double MIN_GROUND_SLAM_DESCENT_COMPONENT = 0.88D;
	private static final double AXIS_EPSILON = 1.0E-9D;

	private VanguardRamKinematics() {
	}

	/**
	 * Finds the first contact between a moving axis-aligned carrier box and a stationary target
	 * box. The calculation is the Minkowski equivalent of sweeping the full carrier AABB, not just
	 * testing either endpoint.
	 */
	static SweepResult sweep(
			double movingCenterX, double movingCenterY, double movingCenterZ,
			double movingHalfX, double movingHalfY, double movingHalfZ,
			double deltaX, double deltaY, double deltaZ,
			double targetMinX, double targetMinY, double targetMinZ,
			double targetMaxX, double targetMaxY, double targetMaxZ,
			double padding) {
		double safePadding = Double.isFinite(padding) ? Math.max(0.0D, padding) : 0.0D;
		double minX = targetMinX - Math.max(0.0D, movingHalfX) - safePadding;
		double minY = targetMinY - Math.max(0.0D, movingHalfY) - safePadding;
		double minZ = targetMinZ - Math.max(0.0D, movingHalfZ) - safePadding;
		double maxX = targetMaxX + Math.max(0.0D, movingHalfX) + safePadding;
		double maxY = targetMaxY + Math.max(0.0D, movingHalfY) + safePadding;
		double maxZ = targetMaxZ + Math.max(0.0D, movingHalfZ) + safePadding;

		double entry = 0.0D;
		double exit = 1.0D;
		double[] xRange = clipAxis(movingCenterX, deltaX, minX, maxX, entry, exit);
		if (xRange == null) return SweepResult.miss();
		entry = xRange[0];
		exit = xRange[1];
		double[] yRange = clipAxis(movingCenterY, deltaY, minY, maxY, entry, exit);
		if (yRange == null) return SweepResult.miss();
		entry = yRange[0];
		exit = yRange[1];
		double[] zRange = clipAxis(movingCenterZ, deltaZ, minZ, maxZ, entry, exit);
		if (zRange == null) return SweepResult.miss();
		entry = zRange[0];

		return new SweepResult(true, entry,
				movingCenterX + deltaX * entry,
				movingCenterY + deltaY * entry,
				movingCenterZ + deltaZ * entry);
	}

	private static double[] clipAxis(double origin, double delta, double min, double max,
			double currentEntry, double currentExit) {
		if (Math.abs(delta) < AXIS_EPSILON) {
			return origin < min || origin > max ? null : new double[] {currentEntry, currentExit};
		}
		double first = (min - origin) / delta;
		double second = (max - origin) / delta;
		if (first > second) {
			double swap = first;
			first = second;
			second = swap;
		}
		double entry = Math.max(currentEntry, first);
		double exit = Math.min(currentExit, second);
		return entry <= exit && exit >= 0.0D && entry <= 1.0D
				? new double[] {Math.max(0.0D, entry), Math.min(1.0D, exit)} : null;
	}

	/** DIVE starts below maximum speed and accelerates monotonically for well over eight ticks. */
	static double diveSpeed(long elapsedTicks) {
		long safeTicks = Math.max(0L, elapsedTicks);
		return Math.min(2.60D, 0.55D + (safeTicks + 1L) * 0.13D);
	}

	/**
	 * A ram may lock its target only after the carrier has actually reached the elevated staging
	 * lane. Merely waiting out an animation timer is deliberately insufficient.
	 */
	static boolean ramStagingReady(long elapsedTicks, double altitudeAboveTarget,
			double distanceToStagingPoint) {
		return elapsedTicks >= MIN_RAM_CLIMB_TICKS
				&& finiteNonNegative(altitudeAboveTarget)
				&& altitudeAboveTarget >= MIN_RAM_ALTITUDE_ABOVE_TARGET
				&& finiteNonNegative(distanceToStagingPoint)
				&& distanceToStagingPoint <= MAX_RAM_STAGING_DISTANCE;
	}

	/**
	 * Rejects horizontal charges masquerading as dives. The direction does not need to be vertical,
	 * but its normalized downward component must be visually and tactically unambiguous.
	 */
	static boolean ramDiveDirectionReady(double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
		double length = Math.sqrt(x * x + y * y + z * z);
		return length > AXIS_EPSILON && y / length <= -MIN_RAM_DESCENT_COMPONENT;
	}

	/**
	 * Follow-up weapon analysis is legal only after the post-impact pull-up has gained altitude,
	 * reached its recovery point and settled to hover speed.
	 */
	static boolean ramRecoveryReady(long elapsedTicks, double climbedBlocks,
			double distanceToRecoveryPoint, double speed) {
		return elapsedTicks >= MIN_RAM_RECOVERY_TICKS
				&& finiteNonNegative(climbedBlocks)
				&& climbedBlocks >= MIN_RAM_RECOVERY_CLIMB
				&& finiteNonNegative(distanceToRecoveryPoint)
				&& distanceToRecoveryPoint <= MAX_RAM_RECOVERY_DISTANCE
				&& finiteNonNegative(speed)
				&& speed <= MAX_RAM_RECOVERY_SPEED;
	}

	/**
	 * Ground slam owns a separate feet-first trajectory. Four brace ticks hold the carrier in
	 * place, then a quadratic gravity-field ramp accelerates past the ram's terminal speed.
	 */
	static double groundSlamDescentSpeed(long elapsedTicks) {
		long safeTicks = Math.max(0L, elapsedTicks);
		if (safeTicks < GROUND_SLAM_BRACE_TICKS) return 0.0D;
		double fallingTicks = safeTicks - GROUND_SLAM_BRACE_TICKS + 1.0D;
		return Math.min(3.40D,
				0.24D + fallingTicks * 0.18D + fallingTicks * fallingTicks * 0.012D);
	}

	static boolean groundSlamDirectionReady(double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
		double length = Math.sqrt(x * x + y * y + z * z);
		return length > AXIS_EPSILON && y / length <= -MIN_GROUND_SLAM_DESCENT_COMPONENT;
	}

	static boolean passedInterceptPlane(double endX, double endY, double endZ,
			double interceptX, double interceptY, double interceptZ,
			double directionX, double directionY, double directionZ) {
		return (endX - interceptX) * directionX
				+ (endY - interceptY) * directionY
				+ (endZ - interceptZ) * directionZ > 0.65D;
	}

	private static boolean finiteNonNegative(double value) {
		return Double.isFinite(value) && value >= 0.0D;
	}

	record SweepResult(boolean hit, double fraction, double contactX, double contactY,
			double contactZ) {
		static SweepResult miss() {
			return new SweepResult(false, NO_HIT, 0.0D, 0.0D, 0.0D);
		}
	}
}
