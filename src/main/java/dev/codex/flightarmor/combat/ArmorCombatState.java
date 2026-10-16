package dev.codex.flightarmor.combat;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** 单个玩家的纯服务端运行态。第一版由玩家 UUID 持有。 */
final class ArmorCombatState {
	float energy = ArmorCombatTuning.MAX_ENERGY;
	float heat;
	boolean overheated;
	long wristCooldownUntilTick;
	long coreCooldownUntilTick;
	long diveImpactCooldownUntilTick;
	/** Independent cooldown for the autonomous vertical ground-slam maneuver. */
	long cruiseSlamCooldownUntilTick;
	/** Exact-carrier cooldown for the Sentinel's alternating detached back-pod pulse. */
	long sentinelDroneCooldownUntilTick;
	/**
	 * 增强能量光束的共用冷却。点射档只有 2 tick，灭世档 84 tick，两档共用同一个时间戳：
	 * 满蓄力一炮之后不能立刻改点射刷输出，反过来也一样。
	 *
	 * <p>刻意不进 {@link ArmorCombatSavedData}：它是纯武器节奏，跨会话保留没有意义，
	 * 而给存档加字段要动序列化格式。重连最坏情况是少等几秒冷却。</p>
	 */
	long handBeamCooldownUntilTick;
	long lastFireTick = Long.MIN_VALUE;
	long lastClientSequence = -1L;
	long serverAttackId;
	int visualRevision;
	boolean nextLeftHand;
	boolean nextLeftSentinelDrone = true;
	boolean bladeExtended;
	long bladeRevision;
	long bladeReadyAtTick;
	int bladeComboStep = -1;
	long lastBladeComboTick = Long.MIN_VALUE;
	ArmorCombatPhase phase = ArmorCombatPhase.IDLE;
	ArmorAttackType attackType = ArmorAttackType.WRIST_GRAVITY_PULSE;
	boolean activeLeftHand;
	long phaseStartGameTime;
	long phaseUntilGameTime;
	long lastOwnerSyncGameTime = Long.MIN_VALUE;
	/** C2S 攻击意图令牌桶：允许短突发，持续速率由服务端补充。 */
	double intentTokens = 4.0D;
	long lastIntentRefillGameTime = Long.MIN_VALUE;
	long lastDeniedSyncGameTime = Long.MIN_VALUE;
	/** 持久快照自上次 setDirty 后又发生了变化。仅服务于 SavedData 节流。 */
	boolean persistenceDirty;
	/** 使用主世界 gameTime，避免玩家切换维度后节流时钟跳变。 */
	long lastPersistenceDirtyGameTime = Long.MIN_VALUE;
	ResourceKey<Level> dimension;

	ArmorCombatState(ResourceKey<Level> dimension) {
		this.dimension = dimension;
	}
}
