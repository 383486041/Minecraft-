package dev.codex.flightarmor.cruise;

/**
 * 服务端权威的战甲巡航阶段。
 *
 * <p>IDLE 只用于网络清理快照，不会保存在活动状态表中。</p>
 */
public enum ArmorCruisePhase {
	IDLE,
	CRUISING,
	RECALLING,
	PHASING,
	OWNER_DOWN,
	DOCKING,
	DEPLOYING;

	public static final int DEPLOYMENT_DURATION_TICKS = 28;
	public static final int DEPLOYMENT_HANDOFF_TICKS = 9;

	/** 整甲抵达玩家近身范围后，完成可观赏的整体展开、包覆与封锁所需的服务端 tick 数。 */
	public static final int DOCKING_DURATION_TICKS = 32;
	/** 进入 DOCKING 后保持来向动势，并在 4 tick 内抵达玩家背后上方。 */
	public static final int DOCKING_FRAME_START_TICKS = 4;
	public static final int DOCKING_WRAP_START_TICKS = 8;
	public static final int DOCKING_ZONE_LOCK_START_TICKS = 22;
	/** 双战靴完成闭合并转入玩家脚部视觉所有权的第一 tick。 */
	public static final int DOCKING_BOOTS_LOCK_TICKS = DOCKING_ZONE_LOCK_START_TICKS + 1;
	public static final int DOCKING_CORE_START_TICKS = 28;
	/** 远距离或跨维度召回的完整折跃时长；中点执行唯一一次载体迁移。 */
	public static final int PHASING_DURATION_TICKS = 20;
	public static final int PHASING_MIDPOINT_TICKS = PHASING_DURATION_TICKS / 2;
	/** 保持实体飞行的最大距离，超过该距离会切换为折跃接入。 */
	public static final double DIRECT_RECALL_DISTANCE = 160.0D;
	/** 普通实体召回超时，避免载体因地形或异常坐标永久滞留。 */
	public static final int RECALL_TIMEOUT_TICKS = 20 * 12;

	public static ArmorCruisePhase fromNetworkId(int networkId) {
		ArmorCruisePhase[] values = values();
		return networkId >= 0 && networkId < values.length ? values[networkId] : IDLE;
	}
}
