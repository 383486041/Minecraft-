package dev.codex.flightarmor.flight;

/**
 * Resolves flight controls before either client prediction or server input sees them.
 *
 * <p>Armor commands own their chord ticks. Vertical flight also owns a key shared with the
 * rebindable sprint mapping, so binding sprint to sneak never means "boost and descend".</p>
 */
public final class FlightInputArbitration {
	private FlightInputArbitration() {
	}

	public static Resolved resolve(Inputs input) {
		boolean controlsEnabled = input.controlsUnlocked()
				&& !input.operationCaptured()
				&& !input.wardrobeChordCaptured();
		if (!controlsEnabled) return Resolved.LOCKED;

		boolean ascending = input.jumpDown();
		boolean descending = input.descendDown();
		if (ascending && descending) {
			ascending = false;
			descending = false;
		}

		boolean boostConflictsWithVertical =
				input.boostSharesJump() && input.jumpDown()
						|| input.boostSharesDescend() && input.descendDown();
		boolean boostConflictsWithBrake =
				input.boostSharesBackward() && input.backwardDown();
		boolean boosting = input.boostDown()
				&& !boostConflictsWithVertical
				&& !boostConflictsWithBrake;
		return new Resolved(boosting, input.forwardDown(), input.backwardDown(),
				input.leftDown(), input.rightDown(), ascending, descending);
	}

	public record Inputs(boolean controlsUnlocked, boolean operationCaptured,
			boolean wardrobeChordCaptured, boolean boostDown,
			boolean boostSharesJump, boolean boostSharesDescend, boolean boostSharesBackward,
			boolean forwardDown, boolean backwardDown, boolean leftDown, boolean rightDown,
			boolean jumpDown, boolean descendDown) {
	}

	public record Resolved(boolean boosting, boolean movingForward, boolean movingBackward,
			boolean movingLeft, boolean movingRight, boolean ascending, boolean descending) {
		private static final Resolved LOCKED =
				new Resolved(false, false, false, false, false, false, false);
	}
}
