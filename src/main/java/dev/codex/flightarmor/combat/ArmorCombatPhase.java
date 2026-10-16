package dev.codex.flightarmor.combat;

/** 服务端权威攻击阶段；腕部脉冲当前只使用 IDLE、FIRING 和 RECOVERY。 */
public enum ArmorCombatPhase {
	IDLE,
	WINDUP,
	CHARGING,
	FIRING,
	RECOVERY;

	public static ArmorCombatPhase fromNetworkId(int id) {
		ArmorCombatPhase[] values = values();
		return id >= 0 && id < values.length ? values[id] : IDLE;
	}
}
