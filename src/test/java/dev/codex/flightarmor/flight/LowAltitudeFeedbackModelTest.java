package dev.codex.flightarmor.flight;

/** Offline trigger, sound-coupling, and performance-budget regression checks. */
public final class LowAltitudeFeedbackModelTest {
	private LowAltitudeFeedbackModelTest() {
	}

	public static void main(String[] args) {
		checkEligibilityBoundaries();
		checkRealizedSpeedGradient();
		checkClearanceGradient();
		checkSoundStaysInsideExistingLayers();
		checkHardBudgetsAndFirstPersonSafety();
		System.out.println("LowAltitudeFeedbackModelTest passed");
	}

	private static void checkEligibilityBoundaries() {
		require(!sample(false, true, false, 1.2D, 2.0D).active(),
				"the independent client switch must disable the entire effect");
		require(!sample(true, false, false, 1.2D, 2.0D).active(),
				"ordinary fast movement must not create an armor wake");
		require(!sample(true, true, true, 1.2D, 2.0D).active(),
				"submerged flight must not render an air/ground wake around the camera");
		require(!sample(true, true, false,
				LowAltitudeFeedbackModel.MINIMUM_SPEED_BLOCKS_PER_TICK - 0.01D, 2.0D).active(),
				"slow hover must stay quiet");
		require(!sample(true, true, false, 1.2D,
				LowAltitudeFeedbackModel.MAXIMUM_CLEARANCE_BLOCKS + 0.01D).active(),
				"high-altitude flight must not spend the ray/render budget");
		require(sample(true, true, false, 1.2D, 2.0D).active(),
				"fast low flight must activate");
	}

	private static void checkRealizedSpeedGradient() {
		var slow = sample(true, true, false, 0.40D, 2.0D);
		var medium = sample(true, true, false, 0.72D, 2.0D);
		var fast = sample(true, true, false, 1.20D, 2.0D);
		require(slow.active() && medium.active() && fast.active(),
				"all gradient samples must be eligible");
		require(slow.intensity() < medium.intensity()
				&& medium.intensity() < fast.intensity(),
				"feedback must follow realized speed continuously");
		require(slow.wakeLength() < medium.wakeLength()
				&& medium.wakeLength() < fast.wakeLength(),
				"directional wake length must grow with actual speed");
	}

	private static void checkClearanceGradient() {
		var near = sample(true, true, false, 1.2D, 1.0D);
		var middle = sample(true, true, false, 1.2D, 5.0D);
		var far = sample(true, true, false, 1.2D, 9.0D);
		require(near.intensity() > middle.intensity()
				&& middle.intensity() > far.intensity(),
				"ground coupling must fade monotonically with clearance");
	}

	private static void checkSoundStaysInsideExistingLayers() {
		var modifier = sample(true, true, false, 1.2D, 1.0D).sound();
		require(modifier.servoVolumeMultiplier() >= 1.0D
				&& modifier.servoVolumeMultiplier() <= 1.12D,
				"low-altitude weight must only make a bounded servo adjustment");
		require(modifier.airVolumeMultiplier() >= modifier.servoVolumeMultiplier()
				&& modifier.airVolumeMultiplier() <= 1.27D,
				"air pressure should be the strongest but remain bounded");
		require(modifier.airPitchOffset() >= 0.0D
				&& modifier.airPitchOffset() <= 0.036D,
				"surface-air pitch offset must remain subtle");
	}

	private static void checkHardBudgetsAndFirstPersonSafety() {
		require(LowAltitudeFeedbackModel.SAMPLE_INTERVAL_TICKS >= 2,
				"surface traces must not run every client tick");
		require(LowAltitudeFeedbackModel.MAX_ACTIVE_RIGS <= 6,
				"nearby trace concurrency exceeded its budget");
		require(LowAltitudeFeedbackModel.MAX_SAMPLES_PER_RIG <= 8,
				"history grew beyond the fixed per-rig budget");
		require(LowAltitudeFeedbackModel.FULL_VERTEX_BUDGET <= 3072
				&& LowAltitudeFeedbackModel.FAST_VERTEX_BUDGET
				<= LowAltitudeFeedbackModel.FULL_VERTEX_BUDGET / 2,
				"frame-wide geometry budget changed unexpectedly");
		require(LowAltitudeFeedbackModel.FIRST_PERSON_SAFE_RADIUS_BLOCKS >= 4.0D,
				"first-person camera exclusion must remain conservative");
	}

	private static LowAltitudeFeedbackModel.Sample sample(boolean enabled,
			boolean active, boolean submerged, double speed, double clearance) {
		return LowAltitudeFeedbackModel.sample(enabled, active, submerged, speed, clearance);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
