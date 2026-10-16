package dev.codex.flightarmor.assembly;

import net.minecraft.world.entity.EquipmentSlot;

/**
 * 分件动画使用的装备槽位位图和 36 tick 展示时间窗。
 *
 * <p>服务端只同步位图，不同步客户端 OBJ 分组名称；客户端再把每个模型分组归入对应装备槽。</p>
 */
public final class ArmorAssemblySlotMask {
	public static final int FEET = 1;
	public static final int LEGS = 1 << 1;
	public static final int CHEST = 1 << 2;
	public static final int HEAD = 1 << 3;
	public static final int FULL_SET = FEET | LEGS | CHEST | HEAD;

	private ArmorAssemblySlotMask() {
	}

	public static int bit(EquipmentSlot slot) {
		return switch (slot) {
			case FEET -> FEET;
			case LEGS -> LEGS;
			case CHEST -> CHEST;
			case HEAD -> HEAD;
			default -> 0;
		};
	}

	public static boolean contains(int mask, EquipmentSlot slot) {
		int bit = bit(slot);
		return bit != 0 && (mask & bit) != 0;
	}

	/** 完整着甲使用 0..36 展示节奏；补单件时从该件真正开始飞入的时间点起播。 */
	public static int timelineStart(int mask) {
		if ((mask & FULL_SET) == FULL_SET) return 0;
		int start = ArmorAssemblyTiming.TOTAL_DURATION_TICKS;
		if ((mask & FEET) != 0) start = Math.min(start, 0);
		if ((mask & LEGS) != 0) start = Math.min(start, 5);
		if ((mask & CHEST) != 0) start = Math.min(start, 7);
		if ((mask & HEAD) != 0) start = Math.min(start, 14);
		return start == ArmorAssemblyTiming.TOTAL_DURATION_TICKS ? 0 : start;
	}

	/**
	 * 只穿战靴时在视觉锁定后保留 2 tick 接管窗口，地面轻抬能够在状态完成前进入自动悬停。
	 */
	public static int timelineEnd(int mask) {
		return timelineEnd(mask, false);
	}

	public static int timelineEnd(int mask, boolean includeBootHoverSettle) {
		if ((mask & FULL_SET) == FULL_SET) return ArmorAssemblyTiming.TOTAL_DURATION_TICKS;
		int end = 0;
		if ((mask & FEET) != 0) end = Math.max(end, 9);
		if ((mask & LEGS) != 0) end = Math.max(end, 16);
		if ((mask & CHEST) != 0) end = Math.max(end, 36);
		if ((mask & HEAD) != 0) end = Math.max(end, 32);
		if (includeBootHoverSettle && (mask & FEET) != 0 && end == ArmorAssemblyTiming.BOOTS_LOCK_TICKS) {
			end += ArmorAssemblyTiming.BOOTS_HOVER_SETTLE_TICKS;
		}
		return Math.max(timelineStart(mask) + 1, end);
	}

	public static int duration(int mask) {
		return Math.max(1, timelineEnd(mask) - timelineStart(mask));
	}
}
