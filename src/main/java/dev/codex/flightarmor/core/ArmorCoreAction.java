package dev.codex.flightarmor.core;

public enum ArmorCoreAction {
	REFRESH,
	BIND_LOOKED_AT,
	SET_PRIMARY,
	RECALL_SELECTED,
	RECALL_AS_ESCORT,
	RECALL_ALL,
	RETURN_ESCORT,
	CYCLE_ATTACK_MODE,
	CYCLE_ATTACK_RANGE,
	TOGGLE_ESCORT_STANCE,
	FORGET_MISSING,
	/** Appended for network ordinal compatibility: releases the exact selected worn suit. */
	DEPLOY_SELECTED_WORN,
	/** Appended for compatibility: selects or clears the exact autonomous command suit. */
	TOGGLE_REMOTE_COMMAND,
	/**
	 * Appended for compatibility: cycles the task position of every loaded escort suit.
	 * Unlike {@link #TOGGLE_ESCORT_STANCE} this carries no suit id, so the server picks the
	 * targets itself and the client never names a suit.
	 */
	CYCLE_ESCORT_STANCE;

	public static ArmorCoreAction fromNetworkId(int id) {
		ArmorCoreAction[] values = values();
		return id >= 0 && id < values.length ? values[id] : REFRESH;
	}
}
