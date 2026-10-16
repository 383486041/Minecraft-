package dev.codex.flightarmor.cruise;

import java.util.List;

/** Offline gate for threat hysteresis and the three independent tactic graphs. */
public final class IndependentCruiseTacticsTest {
	private IndependentCruiseTacticsTest() {
	}

	public static void main(String[] args) {
		recentOwnerAttackerWinsThreatSelection();
		stickyTargetDoesNotJumpForSmallScoreChanges();
		invalidOrMissingCurrentTargetCanSwitch();
		variantGraphsAreDistinctAndFinite();
		ramMustRecoverBeforeFollowUpAnalysis();
		recoverySurvivesLossOfTheImpactedTarget();
		groundSlamUsesAnIndependentPhaseGraph();
		completionLockRejectsDuplicateDamage();
	}

	private static void recentOwnerAttackerWinsThreatSelection() {
		IndependentCruiseThreatTable table = new IndependentCruiseThreatTable();
		IndependentCruiseThreatTable.Selection selection = table.update(10L, List.of(
				new IndependentCruiseThreatTable.Observation(1, 5.0D, true, true, false, false),
				new IndependentCruiseThreatTable.Observation(2, 18.0D, true, false, true, true)), -1);
		require(selection.targetEntityId() == 2, "the owner's recent attacker must have priority");
	}

	private static void stickyTargetDoesNotJumpForSmallScoreChanges() {
		IndependentCruiseThreatTable table = new IndependentCruiseThreatTable();
		table.update(1L, List.of(
				new IndependentCruiseThreatTable.Observation(7, 8.0D, true, true, false, false)), -1);
		IndependentCruiseThreatTable.Selection selection = table.update(2L, List.of(
				new IndependentCruiseThreatTable.Observation(7, 9.0D, true, true, false, false),
				new IndependentCruiseThreatTable.Observation(8, 7.0D, true, true, false, false)), 7);
		require(selection.targetEntityId() == 7 && !selection.switched(),
				"small score noise must not cause per-tick target switching");
	}

	private static void invalidOrMissingCurrentTargetCanSwitch() {
		IndependentCruiseThreatTable table = new IndependentCruiseThreatTable();
		IndependentCruiseThreatTable.Selection selection = table.update(4L, List.of(
				new IndependentCruiseThreatTable.Observation(12, 6.0D, true, true, true, false)), 99);
		require(selection.targetEntityId() == 12 && selection.switched(),
				"a missing current target must not block a valid replacement");
	}

	private static void variantGraphsAreDistinctAndFinite() {
		require(IndependentCruiseTactics.initial(IndependentCruiseTactics.Role.VANGUARD)
				== IndependentCruiseTactics.Phase.VANGUARD_CLIMB, "Vanguard must start by climbing");
		require(IndependentCruiseTactics.initial(IndependentCruiseTactics.Role.SWIFT)
				== IndependentCruiseTactics.Phase.SWIFT_TRACK, "Swift must start with lateral tracking");
		require(IndependentCruiseTactics.initial(IndependentCruiseTactics.Role.SENTINEL)
				== IndependentCruiseTactics.Phase.SENTINEL_HOLD, "Sentinel must start at standoff hold");
		IndependentCruiseTactics.Phase phase = IndependentCruiseTactics.Phase.VANGUARD_CLIMB;
		for (int step = 0; step < 7; step++) phase = IndependentCruiseTactics.next(phase);
		require(phase == IndependentCruiseTactics.Phase.IDLE,
				"Vanguard graph must reach idle after cooldown");
	}

	private static void ramMustRecoverBeforeFollowUpAnalysis() {
		IndependentCruiseTactics.Phase phase =
				IndependentCruiseTactics.Phase.VANGUARD_PULL_UP;
		require(!IndependentCruiseTactics.canSelectFollowUp(phase),
				"a ram must not chain another weapon while pulling up");
		phase = IndependentCruiseTactics.next(phase);
		require(phase == IndependentCruiseTactics.Phase.VANGUARD_ANALYZE,
				"a completed pull-up must enter an explicit tactical analysis phase");
		require(IndependentCruiseTactics.canSelectFollowUp(phase),
				"only the analysis phase may choose the follow-up weapon");
	}

	private static void recoverySurvivesLossOfTheImpactedTarget() {
		require(!IndependentCruiseTactics.requiresLockedTarget(
						IndependentCruiseTactics.Phase.VANGUARD_PULL_UP),
				"pull-up must continue when the ram kills its target");
		require(!IndependentCruiseTactics.requiresLockedTarget(
						IndependentCruiseTactics.Phase.VANGUARD_ANALYZE),
				"analysis may acquire a replacement after recovery");
		require(IndependentCruiseTactics.requiresLockedTarget(
						IndependentCruiseTactics.Phase.VANGUARD_DIVE),
				"the pre-contact dive must retain its locked target");
	}

	private static void groundSlamUsesAnIndependentPhaseGraph() {
		IndependentCruiseTactics.Phase phase = IndependentCruiseTactics.initial(
				IndependentCruiseTactics.VanguardAction.GROUND_SLAM);
		IndependentCruiseTactics.Phase[] expected = {
				IndependentCruiseTactics.Phase.VANGUARD_SLAM_CLIMB,
				IndependentCruiseTactics.Phase.VANGUARD_SLAM_HOVER,
				IndependentCruiseTactics.Phase.VANGUARD_SLAM_DROP,
				IndependentCruiseTactics.Phase.VANGUARD_SLAM_IMPACT,
				IndependentCruiseTactics.Phase.VANGUARD_SLAM_RECOVER
		};
		for (IndependentCruiseTactics.Phase expectedPhase : expected) {
			require(phase == expectedPhase, "unexpected ground-slam phase: " + phase);
			require(IndependentCruiseTactics.isGroundSlamPhase(phase),
					"ground-slam graph must retain its own animation identity");
			require(!IndependentCruiseTactics.isRamPhase(phase),
					"ground slam must never alias an aerial-ram phase");
			phase = IndependentCruiseTactics.next(phase);
		}
		require(phase == IndependentCruiseTactics.Phase.VANGUARD_ANALYZE,
				"ground slam must finish through its own recovery before shared analysis");
		require(IndependentCruiseTactics.canSelectFollowUp(phase),
				"ground slam may select a new attack only after recovery");
	}

	private static void completionLockRejectsDuplicateDamage() {
		require(IndependentCruiseTactics.canCommit(41L, 40L), "new sequence must be committable");
		require(!IndependentCruiseTactics.canCommit(41L, 41L),
				"the same action sequence must not commit damage twice");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
