package dev.codex.flightarmor.flight;

public final class FlightInputArbitrationTest {
	private FlightInputArbitrationTest() {
	}

	public static void main(String[] args) {
		defaultBoostRemainsAvailable();
		sneakWinsAReboundSprintConflict();
		jumpWinsAReboundSprintConflict();
		brakeWinsAReboundSprintConflict();
		armorCommandCaptureLocksFlightInput();
		wardrobeChordWindowLocksFlightInput();
		oppositeVerticalInputsCancel();
		System.out.println("FlightInputArbitrationTest passed");
	}

	private static void defaultBoostRemainsAvailable() {
		var result = resolve(false, false, true, false, false, false, false);
		require(result.boosting(), "ordinary sprint input no longer boosts");
		require(!result.ascending() && !result.descending(), "ordinary boost gained vertical input");
	}

	private static void sneakWinsAReboundSprintConflict() {
		var result = resolve(false, false, true, false, true, false, true);
		require(!result.boosting(), "sprint bound to sneak still boosted while descending");
		require(result.descending(), "shared sneak binding no longer descends");
	}

	private static void jumpWinsAReboundSprintConflict() {
		var result = resolve(false, false, true, true, false, false, false);
		require(!result.boosting(), "sprint bound to jump still boosted while ascending");
		require(result.ascending(), "shared jump binding no longer ascends");
	}

	private static void brakeWinsAReboundSprintConflict() {
		var result = resolve(false, false, true, false, false, true, false);
		require(!result.boosting(), "sprint bound to backward still exposed a false boost state");
		require(result.movingBackward(), "shared backward binding no longer brakes");
	}

	private static void armorCommandCaptureLocksFlightInput() {
		var result = resolve(true, false, true, false, false, false, false);
		require(result.equals(new FlightInputArbitration.Resolved(
				false, false, false, false, false, false, false)),
				"B/N/wardrobe command tick leaked into flight controls");
	}

	private static void wardrobeChordWindowLocksFlightInput() {
		var result = resolve(false, true, true, false, false, false, false);
		require(result.equals(new FlightInputArbitration.Resolved(
				false, false, false, false, false, false, false)),
				"Ctrl wardrobe chord leaked into flight controls");
	}

	private static void oppositeVerticalInputsCancel() {
		var result = FlightInputArbitration.resolve(new FlightInputArbitration.Inputs(
				true, false, false, false,
				false, false, false, false, false, false, false, true, true));
		require(!result.ascending() && !result.descending(),
				"simultaneous ascent/descent was not normalized");
	}

	private static FlightInputArbitration.Resolved resolve(boolean operationCaptured,
			boolean wardrobeCaptured, boolean boostDown, boolean boostSharesJump,
			boolean boostSharesDescend, boolean boostSharesBackward, boolean descendDown) {
		return FlightInputArbitration.resolve(new FlightInputArbitration.Inputs(
				true, operationCaptured, wardrobeCaptured, boostDown,
				boostSharesJump, boostSharesDescend, boostSharesBackward,
				true, boostSharesBackward, false, false, boostSharesJump, descendDown));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
