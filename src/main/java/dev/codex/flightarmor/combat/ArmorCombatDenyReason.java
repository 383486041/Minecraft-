package dev.codex.flightarmor.combat;

/** 拒绝原因会回发给所属客户端，用于 HUD 提示和纠正本地预测。 */
public enum ArmorCombatDenyReason {
	NONE,
	DUPLICATE_SEQUENCE,
	PLAYER_DEAD,
	SPECTATOR,
	SLEEPING,
	RIDING,
	ASSEMBLY_ACTIVE,
	CRUISE_ACTIVE,
	MISSING_FULL_SET,
	DIVING,
	UNSUPPORTED_ATTACK,
	INVALID_ACTION,
	ATTACK_BUSY,
	BOOSTING,
	CHARGE_TOO_SHORT,
	COOLDOWN,
	NOT_ENOUGH_ENERGY,
	OVERHEATED,
	DIMENSION_CHANGED,
	NOT_ARMOR_OWNER;

	public static ArmorCombatDenyReason fromNetworkId(int id) {
		ArmorCombatDenyReason[] values = values();
		return id >= 0 && id < values.length ? values[id] : NONE;
	}
}
