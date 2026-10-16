package dev.codex.flightarmor.core;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** 每套核心绑定副甲的持久化护航配置。 */
public record ArmorEscortProfile(CombatPolicy attackMode, int rangeBlocks, EscortAnchor stance,
		ResourceKey<Level> holdDimension, BlockPos holdPosition) {
	private static final int[] RANGE_STEPS = {16, 32, 48, 64};

	public ArmorEscortProfile {
		attackMode = attackMode == null ? CombatPolicy.HOSTILE : attackMode;
		stance = stance == null ? EscortAnchor.FOLLOW : stance;
		rangeBlocks = normalizeRange(rangeBlocks);
		holdPosition = holdPosition == null ? null : holdPosition.immutable();
		// 只有原地待命需要锚点；跟随自主与贴身跟随都随主人移动，锚点字段必须清空。
		if (stance != EscortAnchor.HOLD) {
			holdDimension = null;
			holdPosition = null;
		}
	}

	public static ArmorEscortProfile defaults() {
		return new ArmorEscortProfile(CombatPolicy.HOSTILE, 32, EscortAnchor.FOLLOW, null, null);
	}

	public ArmorEscortProfile cycleAttackMode() {
		return new ArmorEscortProfile(attackMode.next(), rangeBlocks, stance, holdDimension, holdPosition);
	}

	public ArmorEscortProfile cycleRange() {
		int next = RANGE_STEPS[0];
		for (int index = 0; index < RANGE_STEPS.length; index++) {
			if (rangeBlocks == RANGE_STEPS[index]) {
				next = RANGE_STEPS[(index + 1) % RANGE_STEPS.length];
				break;
			}
		}
		return new ArmorEscortProfile(attackMode, next, stance, holdDimension, holdPosition);
	}

	public ArmorEscortProfile withStance(EscortAnchor next, ResourceKey<Level> dimension, BlockPos position) {
		return new ArmorEscortProfile(attackMode, rangeBlocks, next, dimension, position);
	}

	private static int normalizeRange(int requested) {
		for (int step : RANGE_STEPS) if (requested <= step) return step;
		return RANGE_STEPS[RANGE_STEPS.length - 1];
	}

	public enum CombatPolicy {
		OFF,
		HOSTILE,
		ALL;

		public CombatPolicy next() {
			CombatPolicy[] values = values();
			return values[(ordinal() + 1) % values.length];
		}

		public static CombatPolicy fromName(String value) {
			try { return value == null || value.isBlank() ? HOSTILE : valueOf(value); }
			catch (IllegalArgumentException ignored) { return HOSTILE; }
		}
	}

	/** 副甲任务位置。顺序即循环顺序：跟随自主 → 贴身跟随 → 原地待命 → 跟随自主。 */
	public enum EscortAnchor {
		/** 跟随自主：保持编队随主人移动，并按交战规则自主索敌。 */
		FOLLOW,
		/** 贴身跟随：编队压到约 1 格紧贴主人，并且不主动索敌。 */
		CLOSE,
		/** 原地待命：记住当前位置作为锚点，不再跟随主人。 */
		HOLD;

		public EscortAnchor next() {
			return this == FOLLOW ? CLOSE : this == CLOSE ? HOLD : FOLLOW;
		}

		public static EscortAnchor fromName(String value) {
			try { return value == null || value.isBlank() ? FOLLOW : valueOf(value); }
			catch (IllegalArgumentException ignored) { return FOLLOW; }
		}
	}
}

