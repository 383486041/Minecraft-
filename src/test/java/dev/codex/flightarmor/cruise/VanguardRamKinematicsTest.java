package dev.codex.flightarmor.cruise;

/** Offline regression gate for the Vanguard ram acceleration and swept target collision. */
public final class VanguardRamKinematicsTest {
	private VanguardRamKinematicsTest() {
	}

	public static void main(String[] args) {
		crossingSegmentHitsEvenWhenBothEndpointsAreOutside();
		offsetSegmentMisses();
		carrierVolumeAndPaddingAreSwept();
		startingInsideIsASingleImmediateContact();
		diveAccelerationIsGradualAndMonotonic();
		ramStagingRequiresRealHeightAndArrival();
		ramDiveDirectionMustBeClearlyDownward();
		recoveryMustSettleBeforeFollowUpAnalysis();
		groundSlamHasItsOwnVerticalDescentCurve();
		interceptPlaneDetectsAnOvershoot();
	}

	private static void crossingSegmentHitsEvenWhenBothEndpointsAreOutside() {
		VanguardRamKinematics.SweepResult result = VanguardRamKinematics.sweep(
				-5.0D, 1.0D, 0.0D, 0.5D, 1.0D, 0.5D,
				10.0D, 0.0D, 0.0D,
				-0.5D, 0.0D, -0.5D, 0.5D, 2.0D, 0.5D, 0.10D);
		require(result.hit(), "a single tick crossing the target must hit");
		require(result.fraction() > 0.0D && result.fraction() < 1.0D,
				"crossing hit must be between the two outside endpoints");
		require(close(result.fraction(), 0.39D, 1.0E-9D),
				"unexpected first swept contact fraction: " + result.fraction());
	}

	private static void offsetSegmentMisses() {
		VanguardRamKinematics.SweepResult result = VanguardRamKinematics.sweep(
				-5.0D, 1.0D, 3.0D, 0.5D, 1.0D, 0.5D,
				10.0D, 0.0D, 0.0D,
				-0.5D, 0.0D, -0.5D, 0.5D, 2.0D, 0.5D, 0.10D);
		require(!result.hit(), "a path outside the expanded target must miss");
		require(Double.isInfinite(result.fraction()), "miss must use the no-hit sentinel");
	}

	private static void carrierVolumeAndPaddingAreSwept() {
		VanguardRamKinematics.SweepResult result = VanguardRamKinematics.sweep(
				-3.0D, 1.0D, 1.0D, 0.45D, 0.95D, 0.45D,
				6.0D, 0.0D, 0.0D,
				-0.4D, 0.0D, -0.4D, 0.4D, 2.0D, 0.4D, 0.18D);
		require(result.hit(), "the swept armor volume must catch a shoulder-edge contact");
	}

	private static void startingInsideIsASingleImmediateContact() {
		VanguardRamKinematics.SweepResult result = VanguardRamKinematics.sweep(
				0.0D, 1.0D, 0.0D, 0.4D, 0.9D, 0.4D,
				4.0D, 0.0D, 0.0D,
				-0.5D, 0.0D, -0.5D, 0.5D, 2.0D, 0.5D, 0.0D);
		require(result.hit() && result.fraction() == 0.0D,
				"an overlap at the start must resolve at t=0");
	}

	private static void diveAccelerationIsGradualAndMonotonic() {
		double previous = VanguardRamKinematics.diveSpeed(0L);
		require(previous < VanguardRamKinematics.diveSpeed(15L),
				"the first DIVE tick must not start at maximum speed");
		for (long tick = 1L; tick <= 10L; tick++) {
			double current = VanguardRamKinematics.diveSpeed(tick);
			require(current > previous,
					"DIVE speed must rise on tick " + tick + ": " + previous + " -> " + current);
			previous = current;
		}
		require(VanguardRamKinematics.diveSpeed(30L)
				== VanguardRamKinematics.diveSpeed(40L), "DIVE speed must have a hard maximum");
	}

	private static void ramStagingRequiresRealHeightAndArrival() {
		require(!VanguardRamKinematics.ramStagingReady(60L, 7.49D, 0.10D),
				"elapsed time alone must not replace the required climb height");
		require(!VanguardRamKinematics.ramStagingReady(60L, 8.0D, 0.86D),
				"the carrier must actually arrive at its elevated staging lane");
		require(!VanguardRamKinematics.ramStagingReady(9L, 8.0D, 0.10D),
				"the climb animation must retain a minimum readable duration");
		require(!VanguardRamKinematics.ramStagingReady(60L, Double.NaN, 0.10D),
				"invalid altitude samples must fail closed");
		require(VanguardRamKinematics.ramStagingReady(10L, 8.0D, 0.55D),
				"a completed elevated staging approach must permit target lock");
	}

	private static void ramDiveDirectionMustBeClearlyDownward() {
		require(!VanguardRamKinematics.ramDiveDirectionReady(1.0D, -0.10D, 0.0D),
				"a nearly horizontal charge must not be accepted as a dive");
		require(!VanguardRamKinematics.ramDiveDirectionReady(0.0D, 0.20D, 1.0D),
				"an ascending intercept vector must not start a ram dive");
		require(!VanguardRamKinematics.ramDiveDirectionReady(Double.NaN, -1.0D, 0.0D),
				"invalid ram vectors must fail closed");
		require(VanguardRamKinematics.ramDiveDirectionReady(1.0D, -0.50D, 0.0D),
				"a visibly descending diagonal intercept must remain valid");
	}

	private static void recoveryMustSettleBeforeFollowUpAnalysis() {
		require(!VanguardRamKinematics.ramRecoveryReady(7L, 6.0D, 0.40D, 0.10D),
				"follow-up analysis must not begin before the recovery dwell");
		require(!VanguardRamKinematics.ramRecoveryReady(12L, 5.49D, 0.40D, 0.10D),
				"the carrier must regain altitude after impact");
		require(!VanguardRamKinematics.ramRecoveryReady(12L, 6.0D, 1.11D, 0.10D),
				"the carrier must reach its recovery point before selecting another weapon");
		require(!VanguardRamKinematics.ramRecoveryReady(12L, 6.0D, 0.40D, 0.26D),
				"high residual speed must keep the carrier in recovery");
		require(VanguardRamKinematics.ramRecoveryReady(12L, 6.0D, 0.40D, 0.10D),
				"a climbed, arrived and settled carrier may analyze its next attack");
	}

	private static void groundSlamHasItsOwnVerticalDescentCurve() {
		for (long tick = 0L; tick < VanguardRamKinematics.GROUND_SLAM_BRACE_TICKS; tick++) {
			require(VanguardRamKinematics.groundSlamDescentSpeed(tick) == 0.0D,
					"ground slam must visibly brace before falling on tick " + tick);
		}
		double previous = VanguardRamKinematics.groundSlamDescentSpeed(
				VanguardRamKinematics.GROUND_SLAM_BRACE_TICKS);
		for (long tick = VanguardRamKinematics.GROUND_SLAM_BRACE_TICKS + 1L;
				tick <= 12L; tick++) {
			double current = VanguardRamKinematics.groundSlamDescentSpeed(tick);
			require(current > previous,
					"ground slam descent must accelerate independently on tick " + tick);
			previous = current;
		}
		require(VanguardRamKinematics.groundSlamDescentSpeed(30L)
				> VanguardRamKinematics.diveSpeed(30L),
				"ground slam must have a distinct terminal descent from an aerial ram");
		require(VanguardRamKinematics.groundSlamDescentSpeed(0L)
				!= VanguardRamKinematics.diveSpeed(0L),
				"ground slam must never alias the ram startup curve");
		require(!VanguardRamKinematics.groundSlamDirectionReady(0.70D, -0.70D, 0.0D),
				"a diagonal ram lane must not qualify as a feet-first ground slam");
		require(VanguardRamKinematics.groundSlamDirectionReady(0.05D, -1.0D, 0.02D),
				"a near-vertical feet-first descent must qualify as a ground slam");
	}

	private static void interceptPlaneDetectsAnOvershoot() {
		require(!VanguardRamKinematics.passedInterceptPlane(
				-0.1D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D),
				"approaching the intercept plane is not a miss");
		require(VanguardRamKinematics.passedInterceptPlane(
				0.8D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D),
				"crossing beyond the intercept plane must end DIVE");
	}

	private static boolean close(double left, double right, double tolerance) {
		return Math.abs(left - right) <= tolerance;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
