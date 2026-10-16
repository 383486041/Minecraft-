package dev.codex.flightarmor.combat;

/** 战甲武器的稳定网络类型。新增值只能追加，不能调整已有顺序。 */
public enum ArmorAttackType {
	WRIST_GRAVITY_PULSE,
	CORE_BEAM,
	GRAVITY_DIVE_IMPACT,
	CRUISE_RAM,
	CRUISE_CORE_BEAM,
	CRUISE_MISSILE_VOLLEY,
	ENERGY_BLADE_TOGGLE,
	ENERGY_BLADE_SLASH,
	/** 非法网络编号只会落到此值，绝不能回落为可执行武器。 */
	INVALID,
	/** Independent cruise carrier ground slam. Appended to preserve every previous wire ordinal. */
	CRUISE_GROUND_SLAM,
	/** Player blade primary attack: a short-range authored three-hit combo. */
	ENERGY_BLADE_COMBO,
	/** 增强能量光束：短按点射。射击节奏由玩家点击速度决定，冷却刻意压到 2 tick。 */
	HAND_BEAM_PULSE,
	/**
	 * 增强能量光束：长按蓄力的灭世光束。命中点开球形爆坑，满蓄力可削平山头。
	 * 客户端按下时先发一发点射，继续按住超过 {@code EnhancedBeamMath.LANCE_CLASSIFY_TICKS}
	 * 才补发此值的 PRESS 进入 CHARGING，松手发 RELEASE。服务端只校验状态机，不做分类。
	 */
	HAND_BEAM_LANCE;

	public static ArmorAttackType fromNetworkId(int id) {
		ArmorAttackType[] values = values();
		return id >= 0 && id < values.length ? values[id] : INVALID;
	}
}
