package dev.codex.flightarmor.cruise;

/** Offline regression coverage for independent-carrier platform-return admission and trajectory. */
public final class IndependentCruiseReturnHandoffTest {
	private IndependentCruiseReturnHandoffTest() {
	}

	public static void main(String[] args) {
		require(IndependentCruiseReturnHandoff.decide(
				IndependentCruiseReturnHandoff.Evidence.valid())
				== IndependentCruiseReturnHandoff.Admission.START,
				"valid handoff must start");
		require(IndependentCruiseReturnHandoff.decide(new IndependentCruiseReturnHandoff.Evidence(
				true, false, false, false, false, true, false, false, false))
				== IndependentCruiseReturnHandoff.Admission.ALREADY_RETURNING,
				"same-target repeated command must be idempotent");
		require(reject(false, true, true, true, false, true, true, true)
				== IndependentCruiseReturnHandoff.Admission.OWNER_MISMATCH,
				"owner mismatch must reject before mutation");
		require(reject(true, false, true, true, false, true, true, true)
				== IndependentCruiseReturnHandoff.Admission.SUIT_MISMATCH,
				"suit mismatch must reject before mutation");
		require(reject(true, true, false, true, false, true, true, true)
				== IndependentCruiseReturnHandoff.Admission.CARRIER_UNAVAILABLE,
				"unavailable carrier must reject");
		require(reject(true, true, true, false, false, true, true, true)
				== IndependentCruiseReturnHandoff.Admission.PHASE_BUSY,
				"busy independent phase must reject");
		require(reject(true, true, true, true, true, true, true, true)
				== IndependentCruiseReturnHandoff.Admission.ESCORT_CONFLICT,
				"existing escort ownership must reject");
		require(reject(true, true, true, true, false, true, false, true)
				== IndependentCruiseReturnHandoff.Admission.PLATFORM_REPLACED,
				"replaced platform instance must reject");
		require(reject(true, true, true, true, false, true, true, false)
				== IndependentCruiseReturnHandoff.Admission.PLATFORM_OCCUPIED,
				"occupied platform must reject");

		IndependentCruiseReturnHandoff.Approach far =
				IndependentCruiseReturnHandoff.approach(80.0D);
		IndependentCruiseReturnHandoff.Approach braking =
				IndependentCruiseReturnHandoff.approach(12.0D);
		IndependentCruiseReturnHandoff.Approach finalDescent =
				IndependentCruiseReturnHandoff.approach(2.0D);
		require(far.stage() == IndependentCruiseReturnHandoff.ApproachStage.HIGH_SPEED
				&& far.targetHeightAboveDock() == 4.2D,
				"long-range return must aim above the platform");
		require(braking.stage() == IndependentCruiseReturnHandoff.ApproachStage.OVERHEAD_BRAKING
				&& braking.targetHeightAboveDock() == 2.6D,
				"mid-range return must brake overhead");
		require(finalDescent.stage() == IndependentCruiseReturnHandoff.ApproachStage.FINAL_DESCENT
				&& finalDescent.targetHeightAboveDock() == 0.0D,
				"short-range return must descend onto the dock");
		require(far.maxSpeed() > braking.maxSpeed()
				&& braking.maxSpeed() > finalDescent.maxSpeed(),
				"approach speed must decrease through all three stages");
		require(IndependentCruiseReturnHandoff.approach(Double.NaN).stage()
				== IndependentCruiseReturnHandoff.ApproachStage.HIGH_SPEED,
				"non-finite distance must choose the conservative long-range profile");
		System.out.println("IndependentCruiseReturnHandoffTest passed");
	}

	private static IndependentCruiseReturnHandoff.Admission reject(
			boolean owner, boolean suit, boolean carrier, boolean phase, boolean escortConflict,
			boolean dimension, boolean platform, boolean empty) {
		return IndependentCruiseReturnHandoff.decide(new IndependentCruiseReturnHandoff.Evidence(
				false, owner, suit, carrier, phase, escortConflict,
				dimension, platform, empty));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
