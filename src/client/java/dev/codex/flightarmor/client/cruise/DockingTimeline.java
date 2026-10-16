package dev.codex.flightarmor.client.cruise;

import dev.codex.flightarmor.cruise.ArmorCruisePhase;

/**
 * 32 tick 服务端对接阶段中的展示型客户端包覆时间轴。
 *
 * <p>所有渲染、姿势、灯光和声音只能引用这里的归一化边界，禁止各自手写近似小数：</p>
 * <ul>
 *     <li>0-4：保留最后一段动势，边贴近边完全展开外骨骼</li>
 *     <li>4-22：沿侧上方弧线包围玩家，持续移动而非原地等待</li>
 *     <li>22-30：战靴、腿、胸臂、头依次机械闭合</li>
 *     <li>28-32：核心与灯纹同步封锁并提交成甲</li>
 * </ul>
 */
public final class DockingTimeline {
	private static final float DURATION = ArmorCruisePhase.DOCKING_DURATION_TICKS;

	public static final float FRAME_START = ArmorCruisePhase.DOCKING_FRAME_START_TICKS / DURATION;
	public static final float WRAP_START = ArmorCruisePhase.DOCKING_WRAP_START_TICKS / DURATION;
	public static final float ZONE_LOCK_START = ArmorCruisePhase.DOCKING_ZONE_LOCK_START_TICKS / DURATION;
	public static final float CORE_START = ArmorCruisePhase.DOCKING_CORE_START_TICKS / DURATION;
	public static final float END = 1.0F;

	// 区域所有权只在对应闭合完成后切到 PlayerModel；每一时刻每个区域只有一个渲染所有者。
	public static final float BOOTS_TRANSFER = ArmorCruisePhase.DOCKING_BOOTS_LOCK_TICKS / DURATION;
	public static final float SHINS_TRANSFER = 24.50F / DURATION;
	public static final float LEGS_LOCK_START = SHINS_TRANSFER;
	public static final float LEGS_TRANSFER = 25.00F / DURATION;
	public static final float CHEST_LOCK_START = LEGS_TRANSFER;
	public static final float ARMS_TRANSFER = 26.00F / DURATION;
	public static final float CHEST_TRANSFER = 26.50F / DURATION;
	public static final float HELMET_LOCK_START = CHEST_TRANSFER;
	public static final float HELMET_TRANSFER = 28.00F / DURATION;
	public static final float CORE_TRANSFER = 30.00F / DURATION;

	private DockingTimeline() {
	}
}
