package dev.codex.flightarmor.combat;

/** 权威特效终点的命中种类。 */
public enum ArmorAttackHitKind {
	MISS,
	BLOCK,
	ENTITY;

	public static ArmorAttackHitKind fromNetworkId(int id) {
		ArmorAttackHitKind[] values = values();
		return id >= 0 && id < values.length ? values[id] : MISS;
	}
}
