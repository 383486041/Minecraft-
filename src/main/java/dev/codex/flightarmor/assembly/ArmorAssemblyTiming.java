package dev.codex.flightarmor.assembly;

/** 服务端判定与客户端动画共同使用的 36 tick 展示型分件着装时间点。 */
public final class ArmorAssemblyTiming {
	public static final int TOTAL_DURATION_TICKS = 36;
	/** 左右战靴都完成锁定的第一 tick；从这一刻起可授权下落悬停。 */
	public static final int BOOTS_LOCK_TICKS = 9;
	public static final int BOOTS_HOVER_SETTLE_TICKS = 4;

	private ArmorAssemblyTiming() {
	}
}
