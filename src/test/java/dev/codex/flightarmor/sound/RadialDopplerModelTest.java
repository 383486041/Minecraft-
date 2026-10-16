package dev.codex.flightarmor.sound;

import dev.codex.flightarmor.cruise.ArmorCruisePhase;

/** Offline radial Doppler direction, smoothing and hard-bound checks. */
public final class RadialDopplerModelTest {
	private RadialDopplerModelTest() {
	}

	public static void main(String[] args) {
		checkClosingAndRecedingDirection();
		checkProjectedMotionWarmup();
		checkLocalAndNearFieldNeutrality();
		checkSmoothingAndReturn();
		checkStrictBounds();
		checkInvalidAndTeleportSamples();
		checkCruiseObservationRetention();
		System.out.println("RadialDopplerModelTest passed");
	}

	private static void checkClosingAndRecedingDirection() {
		double closing = RadialDopplerModel.targetFactor(true, 24.0D, 0.18D);
		double receding = RadialDopplerModel.targetFactor(true, 24.0D, -0.18D);
		require(closing > 1.0D, "approaching source must pitch upward");
		require(receding < 1.0D, "receding source must pitch downward");
		require(Math.abs((closing - 1.0D) - (1.0D - receding)) < 1.0E-9D,
				"equal radial speeds should have a symmetric bounded signature");
	}

	private static void checkProjectedMotionWarmup() {
		double approaching = RadialDopplerModel.projectedClosingSpeed(
				10.0D, 0.0D, 0.0D,
				-0.20D, 0.0D, 0.0D,
				0.0D, 0.0D, 0.0D);
		double listenerApproaching = RadialDopplerModel.projectedClosingSpeed(
				10.0D, 0.0D, 0.0D,
				0.0D, 0.0D, 0.0D,
				0.10D, 0.0D, 0.0D);
		double receding = RadialDopplerModel.projectedClosingSpeed(
				10.0D, 0.0D, 0.0D,
				0.20D, 0.0D, 0.0D,
				0.0D, 0.0D, 0.0D);
		double tangential = RadialDopplerModel.projectedClosingSpeed(
				10.0D, 0.0D, 0.0D,
				0.0D, 0.20D, 0.0D,
				0.0D, 0.0D, 0.0D);
		require(approaching > 0.0D && listenerApproaching > 0.0D,
				"source and listener approach must both warm upward pitch");
		require(receding < 0.0D, "source recession must warm downward pitch");
		require(Math.abs(tangential) < 1.0E-12D,
				"tangential motion must not fake radial Doppler");
		double warmed = RadialDopplerModel.smoothFactor(1.0D,
				RadialDopplerModel.targetFactor(true, 10.0D, approaching));
		require(warmed > 1.0D && warmed < RadialDopplerModel.MAX_FACTOR,
				"first real motion sample must be audible but remain smoothed");
		require(RadialDopplerModel.projectedClosingSpeed(
				10.0D, 0.0D, 0.0D,
				RadialDopplerModel.MAX_VALID_RADIAL_DELTA + 1.0D, 0.0D, 0.0D,
				0.0D, 0.0D, 0.0D) == 0.0D,
				"abnormal warmup motion must stay neutral");
	}

	private static void checkLocalAndNearFieldNeutrality() {
		require(RadialDopplerModel.targetFactor(false, 40.0D, 1.0D) == 1.0D,
				"listener-relative/local first-person sound must remain neutral");
		require(RadialDopplerModel.targetFactor(true, 1.5D, 1.0D) == 1.0D,
				"near-field movement must not wobble pitch around the camera");
		double transition = RadialDopplerModel.targetFactor(true, 5.0D, 0.2D);
		double distant = RadialDopplerModel.targetFactor(true, 20.0D, 0.2D);
		require(transition > 1.0D && transition < distant,
				"spatial Doppler must fade in continuously with distance");
	}

	private static void checkSmoothingAndReturn() {
		double target = RadialDopplerModel.targetFactor(true, 24.0D, 1.0D);
		double first = RadialDopplerModel.smoothFactor(1.0D, target);
		double second = RadialDopplerModel.smoothFactor(first, target);
		require(first > 1.0D && first < target,
				"first sample must move toward rather than jump to target");
		require(second > first && second < target,
				"repeated real motion must converge smoothly");
		double returning = RadialDopplerModel.smoothFactor(second, 1.0D);
		require(returning < second && returning > 1.0D,
				"stopped relative motion must return smoothly to neutral");
	}

	private static void checkStrictBounds() {
		double extremeClosing = RadialDopplerModel.targetFactor(true, 100.0D, 100.0D);
		double extremeReceding = RadialDopplerModel.targetFactor(true, 100.0D, -100.0D);
		require(extremeClosing <= RadialDopplerModel.MAX_FACTOR
				&& extremeReceding >= RadialDopplerModel.MIN_FACTOR,
				"factor must remain inside its hard bounds");
		require(RadialDopplerModel.applyToPitch(20.0D, extremeClosing)
				== RadialDopplerModel.MAX_OUTPUT_PITCH,
				"final high pitch must be clamped after multiplication");
		require(RadialDopplerModel.applyToPitch(0.01D, extremeReceding)
				== RadialDopplerModel.MIN_OUTPUT_PITCH,
				"final low pitch must be clamped after multiplication");
	}

	private static void checkInvalidAndTeleportSamples() {
		require(RadialDopplerModel.targetFactor(true, Double.NaN, 0.2D) == 1.0D,
				"invalid distance must reset to neutral");
		require(RadialDopplerModel.targetFactor(true, 20.0D, Double.POSITIVE_INFINITY)
				== 1.0D, "invalid velocity must reset to neutral");
		require(RadialDopplerModel.targetFactor(true, 20.0D,
				RadialDopplerModel.MAX_VALID_RADIAL_DELTA + 1.0D) == 1.0D,
				"same-world teleport-sized radial delta must not create a pitch spike");
		require(RadialDopplerModel.smoothFactor(Double.NaN, Double.NaN) == 1.0D,
				"invalid smoothing state must recover to neutral");
	}

	private static void checkCruiseObservationRetention() {
		require(!CruiseSoundObservationRetention.shouldRetain(
				false, true, ArmorCruisePhase.CRUISING, 0L),
				"a vanished state must be removed in the same client world");
		require(CruiseSoundObservationRetention.shouldRetain(
				true, true, ArmorCruisePhase.CRUISING, 1000L),
				"a live carrier observation must remain");
		require(!CruiseSoundObservationRetention.shouldRetain(
				true, false, ArmorCruisePhase.CRUISING, 1L),
				"a disappeared stable carrier must be removed immediately");
		require(CruiseSoundObservationRetention.shouldRetain(
				true, false, ArmorCruisePhase.PHASING,
				CruiseSoundObservationRetention.MISSING_PHASING_GRACE_TICKS),
				"short phasing migration hole needs a bounded grace period");
		require(!CruiseSoundObservationRetention.shouldRetain(
				true, false, ArmorCruisePhase.PHASING,
				CruiseSoundObservationRetention.MISSING_PHASING_GRACE_TICKS + 1L),
				"stale phasing observations must expire");
		require(!CruiseSoundObservationRetention.shouldRetain(
				true, false, ArmorCruisePhase.OWNER_DOWN,
				CruiseSoundObservationRetention.MISSING_OWNER_DOWN_GRACE_TICKS + 1L),
				"owner-down observations must not grow for the whole session");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
