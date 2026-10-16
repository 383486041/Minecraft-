package dev.codex.flightarmor.flight;

import static dev.codex.flightarmor.flight.AutomaticEquipHoverTransition.Action.BASELINE;
import static dev.codex.flightarmor.flight.AutomaticEquipHoverTransition.Action.CANCEL_PENDING;
import static dev.codex.flightarmor.flight.AutomaticEquipHoverTransition.Action.IDLE;
import static dev.codex.flightarmor.flight.AutomaticEquipHoverTransition.Action.RETRY_EDGE;
import static dev.codex.flightarmor.flight.AutomaticEquipHoverTransition.Action.START_EDGE;
import static dev.codex.flightarmor.flight.AutomaticEquipHoverTransition.Action.SUPPRESS;

/** Offline edge-policy regression test; it does not launch Minecraft. */
public final class AutomaticEquipHoverTransitionTest {
	private AutomaticEquipHoverTransitionTest() {
	}

	public static void main(String[] args) {
		require(decide(false, false, true, false, false, false) == BASELINE,
				"first observation must establish a baseline, not launch a login player");
		require(decide(true, false, false, false, false, false) == IDLE,
				"no gear must remain transparent to vanilla creative flight");
		require(decide(true, false, true, false, false, false) == START_EDGE,
				"ordinary false-to-true equipment edge was lost");
		require(decide(true, true, true, false, false, false) == IDLE,
				"steady equipped state would repeatedly relaunch flight");
		require(decide(true, true, true, false, false, true) == RETRY_EDGE,
				"the same ground-lift edge must remain retryable");
		require(decide(true, true, false, false, false, true) == CANCEL_PENDING,
				"removing boots must cancel the pending edge");
		require(decide(true, false, true, true, false, false) == SUPPRESS,
				"assembly/docking transaction must own its equip transition");
		require(decide(true, false, true, false, true, false) == SUPPRESS,
				"an existing server flight session must consume the edge");
	}

	private static AutomaticEquipHoverTransition.Action decide(boolean initialized,
			boolean previous, boolean current, boolean busy, boolean active, boolean pending) {
		return AutomaticEquipHoverTransition.decide(
				initialized, previous, current, busy, active, pending);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
