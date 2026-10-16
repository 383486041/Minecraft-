package dev.codex.flightarmor.cruise;

public enum CruiseCombatMode {
	PASSIVE,
	GUARD,
	INVALID;

	public static CruiseCombatMode fromNetworkId(int id) {
		return id >= 0 && id < values().length ? values()[id] : INVALID;
	}
}
