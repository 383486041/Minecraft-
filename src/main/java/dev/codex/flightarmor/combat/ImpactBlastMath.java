package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;

/**
 * 撞击爆破的纯数学：把「撞击速度 + 战甲型号」换算成一次撞击的爆炸半径、原生爆炸威力、
 * 预算、能啃多硬、最多掀掉多少块。
 *
 * <p>只做算术、不接触世界，因此可以在离线测试里直接验证边界。破坏形状是**球**，
 * 不是沿运动方向展开的细隧道——撞击要的是「撞上去炸开」，不是「钻一根管子」。</p>
 *
 * <p>刻度：速度单位 block/tick，三型号巡航 0.27～0.66、加速 0.55～1.82、俯冲 12～26。
 * 半径按速度线性放大并在 {@link #MAX_RADIUS} 封顶；块数上限按球体积给，坑才是完整的球，
 * 而不会因为上限偏低被提前掐掉一半。</p>
 */
public final class ImpactBlastMath {
	/**
	 * 低于此速度不算撞击。飞行状态机本身只在飞行中运行，走路撞墙根本到不了本入口，
	 * 所以门槛压得很低：只要还在往前飞，贴墙也能炸一下。
	 */
	public static final double MIN_IMPACT_SPEED = 0.05D;
	/** 爆坑半径（格）的下限。 */
	public static final double MIN_RADIUS = 2.6D;
	/**
	 * 爆坑半径（格）的上限。这同时是单 tick 开销的硬闸门：扫描格数随半径立方式增长，
	 * 半径 9 时球内约 3 千格、包围盒约 7 千格，是能承受的上限。
	 */
	public static final double MAX_RADIUS = 9.0D;
	private static final double RADIUS_BASE = 2.2D;
	private static final double RADIUS_PER_SPEED = 1.45D;
	/** 原生爆炸的威力：只决定粒子、音效与实体伤害的范围，地形破坏与它无关。 */
	private static final float MIN_POWER = 2.5F;
	private static final float MAX_POWER = 14.0F;
	private static final double POWER_PER_SPEED = 0.55D;
	/** 预算单位与方块硬度同量纲，用来限制「啃硬岩」的总代价。 */
	private static final double MIN_BUDGET = 160.0D;
	private static final double MAX_BUDGET = 9000.0D;
	private static final double BUDGET_PER_VOLUME = 2.2D;
	/** 块数上限相对球体积的余量，留一点富余避免差一块而少掀一层。 */
	private static final double BLOCK_CAP_VOLUME_FACTOR = 1.15D;
	private static final int MAX_BLOCKS = 3600;

	private ImpactBlastMath() {
	}

	/**
	 * 疾影型啃得穿硬（能啃下黑曜石），守望型只能啃软岩，先锋型居中——速度越快爆得越大。
	 */
	public static Profile profile(ArmorVariant variant, double impactSpeed) {
		if (!Double.isFinite(impactSpeed) || impactSpeed < MIN_IMPACT_SPEED) {
			return Profile.disabled();
		}
		ArmorVariant resolved = variant == null ? ArmorVariant.DEFAULT : variant;
		double hardnessLimit = switch (resolved) {
			case VANGUARD -> 32.0D;
			case SWIFT -> 55.0D;
			case SENTINEL -> 24.0D;
		};
		double radius = clamp(impactSpeed * RADIUS_PER_SPEED + RADIUS_BASE,
				MIN_RADIUS, MAX_RADIUS);
		float power = (float) clamp(impactSpeed * POWER_PER_SPEED + MIN_POWER,
				MIN_POWER, MAX_POWER);
		double volume = sphereVolume(radius);
		double budget = clamp(volume * BUDGET_PER_VOLUME, MIN_BUDGET, MAX_BUDGET);
		int maximumBlocks = (int) clamp(Math.ceil(volume * BLOCK_CAP_VOLUME_FACTOR),
				1.0D, MAX_BLOCKS);
		return new Profile(true, radius, power, budget, hardnessLimit, maximumBlocks);
	}

	/** 半径为 {@code radius} 的球的格数（4/3·π·r³），用来把块数上限绑到坑的完整度上。 */
	public static double sphereVolume(double radius) {
		if (!Double.isFinite(radius) || radius <= 0.0D) return 0.0D;
		return Math.PI * radius * radius * radius * 4.0D / 3.0D;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	/** {@code enabled} 为假时其余字段无意义，调用方必须先查 {@code enabled}。 */
	public record Profile(boolean enabled, double radius, float power, double budget,
			double hardnessLimit, int maximumDestroyedBlocks) {
		static Profile disabled() {
			return new Profile(false, 0.0D, 0.0F, 0.0D, 0.0D, 0);
		}
	}
}
