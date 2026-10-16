package dev.codex.flightarmor.cruise;

/** Pure variant tactic graph and once-per-action completion lock. */
final class IndependentCruiseTactics {
	private IndependentCruiseTactics() {
	}

	static Phase initial(Role role) {
		return switch (role) {
			case VANGUARD -> Phase.VANGUARD_CLIMB;
			case SWIFT -> Phase.SWIFT_TRACK;
			case SENTINEL -> Phase.SENTINEL_HOLD;
		};
	}

	static Phase initial(VanguardAction action) {
		return switch (action) {
			case RAM -> Phase.VANGUARD_CLIMB;
			case GROUND_SLAM -> Phase.VANGUARD_SLAM_CLIMB;
		};
	}

	static Phase next(Phase phase) {
		return switch (phase) {
			case VANGUARD_CLIMB -> Phase.VANGUARD_LOCK;
			case VANGUARD_LOCK -> Phase.VANGUARD_DIVE;
			case VANGUARD_DIVE -> Phase.VANGUARD_PULL_UP;
			case VANGUARD_PULL_UP -> Phase.VANGUARD_ANALYZE;
			case VANGUARD_ANALYZE -> Phase.VANGUARD_WRIST;
			case VANGUARD_WRIST, SWIFT_ESCAPE, SENTINEL_FIRE -> Phase.COOLDOWN;
			case VANGUARD_SLAM_CLIMB -> Phase.VANGUARD_SLAM_HOVER;
			case VANGUARD_SLAM_HOVER -> Phase.VANGUARD_SLAM_DROP;
			case VANGUARD_SLAM_DROP -> Phase.VANGUARD_SLAM_IMPACT;
			case VANGUARD_SLAM_IMPACT -> Phase.VANGUARD_SLAM_RECOVER;
			case VANGUARD_SLAM_RECOVER -> Phase.VANGUARD_ANALYZE;
			case SWIFT_TRACK -> Phase.SWIFT_VOLLEY;
			case SWIFT_VOLLEY -> Phase.SWIFT_ESCAPE;
			case SENTINEL_HOLD -> Phase.SENTINEL_FIRE;
			case IDLE, COOLDOWN -> Phase.IDLE;
		};
	}

	static boolean canSelectFollowUp(Phase phase) {
		return phase == Phase.VANGUARD_ANALYZE;
	}

	/**
	 * Pull-up and recovery own their exit trajectory even if the impact killed or invalidated the
	 * original target. Only approach, lock and contact phases require that target to stay alive.
	 */
	static boolean requiresLockedTarget(Phase phase) {
		return switch (phase) {
			case VANGUARD_CLIMB, VANGUARD_LOCK, VANGUARD_DIVE,
					VANGUARD_SLAM_CLIMB, VANGUARD_SLAM_HOVER,
					VANGUARD_SLAM_DROP -> true;
			default -> false;
		};
	}

	static boolean isRamPhase(Phase phase) {
		return switch (phase) {
			case VANGUARD_CLIMB, VANGUARD_LOCK, VANGUARD_DIVE,
					VANGUARD_PULL_UP, VANGUARD_WRIST -> true;
			default -> false;
		};
	}

	static boolean isGroundSlamPhase(Phase phase) {
		return switch (phase) {
			case VANGUARD_SLAM_CLIMB, VANGUARD_SLAM_HOVER,
					VANGUARD_SLAM_DROP, VANGUARD_SLAM_IMPACT,
					VANGUARD_SLAM_RECOVER -> true;
			default -> false;
		};
	}

	static boolean canCommit(long activeSequence, long completedSequence) {
		return activeSequence > 0L && activeSequence != completedSequence;
	}

	enum Role { VANGUARD, SWIFT, SENTINEL }

	enum VanguardAction { RAM, GROUND_SLAM }

	enum Phase {
		IDLE,
		VANGUARD_CLIMB,
		VANGUARD_LOCK,
		VANGUARD_DIVE,
		VANGUARD_PULL_UP,
		VANGUARD_ANALYZE,
		VANGUARD_WRIST,
		VANGUARD_SLAM_CLIMB,
		VANGUARD_SLAM_HOVER,
		VANGUARD_SLAM_DROP,
		VANGUARD_SLAM_IMPACT,
		VANGUARD_SLAM_RECOVER,
		SWIFT_TRACK,
		SWIFT_VOLLEY,
		SWIFT_ESCAPE,
		SENTINEL_HOLD,
		SENTINEL_FIRE,
		COOLDOWN
	}
}
