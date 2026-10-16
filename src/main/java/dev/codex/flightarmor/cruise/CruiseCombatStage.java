package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.combat.ArmorAttackType;

/**
 * Stable, renderer-facing stage of a cruise carrier's current combat maneuver.
 *
 * <p>This deliberately stays separate from the server's internal tactic graph. The wire stage
 * describes only poses that a client may render; damage and movement remain server-authoritative.
 * New values must be appended so recorded ordinals remain stable.
 */
public enum CruiseCombatStage {
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
	COOLDOWN,
	INVALID;

	public static CruiseCombatStage fromNetworkId(int id) {
		CruiseCombatStage[] values = values();
		return id >= 0 && id < values.length ? values[id] : INVALID;
	}

	/**
	 * Compatibility mapping for an older server call site that has not supplied a detailed stage.
	 * It never guesses a ground slam from a ram packet.
	 */
	public static CruiseCombatStage fromLegacy(CruiseCombatMode mode,
			CruiseCombatBehavior behavior, ArmorAttackType attackType) {
		if (mode != CruiseCombatMode.GUARD || behavior == null || attackType == null) {
			return IDLE;
		}
		if (attackType == ArmorAttackType.CRUISE_GROUND_SLAM) {
			return switch (behavior) {
				case AIMING -> VANGUARD_SLAM_CLIMB;
				case FIRING -> VANGUARD_SLAM_DROP;
				case COOLDOWN -> VANGUARD_SLAM_RECOVER;
				case PASSIVE, SEARCHING, INVALID -> IDLE;
			};
		}
		if (attackType == ArmorAttackType.CRUISE_RAM) {
			return switch (behavior) {
				case AIMING -> VANGUARD_LOCK;
				case FIRING -> VANGUARD_DIVE;
				case COOLDOWN -> VANGUARD_ANALYZE;
				case PASSIVE, SEARCHING, INVALID -> IDLE;
			};
		}
		return switch (behavior) {
			case FIRING -> VANGUARD_PULL_UP;
			case COOLDOWN -> COOLDOWN;
			case PASSIVE, SEARCHING, AIMING, INVALID -> IDLE;
		};
	}

	public boolean isRam() {
		return switch (this) {
			case VANGUARD_CLIMB, VANGUARD_LOCK, VANGUARD_DIVE,
					VANGUARD_PULL_UP, VANGUARD_ANALYZE -> true;
			default -> false;
		};
	}

	public boolean isGroundSlam() {
		return switch (this) {
			case VANGUARD_SLAM_CLIMB, VANGUARD_SLAM_HOVER, VANGUARD_SLAM_DROP,
					VANGUARD_SLAM_IMPACT, VANGUARD_SLAM_RECOVER -> true;
			default -> false;
		};
	}
}
