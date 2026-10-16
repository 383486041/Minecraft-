package dev.codex.flightarmor.cruise;

public enum CruiseCombatBehavior {
	PASSIVE,
	SEARCHING,
	AIMING,
	FIRING,
	COOLDOWN,
	INVALID;

	public static CruiseCombatBehavior fromNetworkId(int id) {
		return id >= 0 && id < values().length ? values()[id] : INVALID;
	}
}
