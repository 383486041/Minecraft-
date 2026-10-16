package dev.codex.flightarmor.flight;

/**
 * Pure transition policy for ordinary equipment-slot flight eligibility.
 *
 * <p>The policy deliberately reacts only to a false-to-true edge. A pending ground launch is
 * a retry of that same edge, not a new edge, so manually disabling flight while the boots stay
 * equipped can never make the controller start it again.</p>
 */
public final class AutomaticEquipHoverTransition {
	private AutomaticEquipHoverTransition() {
	}

	public static Action decide(boolean initialized, boolean previouslyEligible,
			boolean currentlyEligible, boolean transactionBusy, boolean flightSessionActive,
			boolean edgePending) {
		if (!initialized) return Action.BASELINE;
		if (!currentlyEligible) return edgePending ? Action.CANCEL_PENDING : Action.IDLE;
		if (transactionBusy || flightSessionActive) return Action.SUPPRESS;
		if (edgePending) return Action.RETRY_EDGE;
		return previouslyEligible ? Action.IDLE : Action.START_EDGE;
	}

	public enum Action {
		BASELINE,
		IDLE,
		START_EDGE,
		RETRY_EDGE,
		CANCEL_PENDING,
		SUPPRESS
	}
}
