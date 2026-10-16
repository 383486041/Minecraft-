package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;

/**
 * 增强能量光束的纯数学：把「战甲型号 + 已蓄力时长」换算成两档开火的全部硬参数。
 *
 * <p>只做算术、不接触世界，因此可以在离线测试里直接验证边界。两档共用同一门手炮：</p>
 * <ul>
 *   <li><b>点射（PULSE）</b>——短按。冷却刻意压到 2 tick，射击节奏由玩家的点击速度决定，
 *       而不是由冷却决定。单发代价与伤害都低，靠速率堆输出。</li>
 *   <li><b>灭世光束（LANCE）</b>——长按蓄力。蓄力越满，命中点的球形爆坑越大，
 *       满蓄力的数字是刻意「离谱」的：射程 150 格、直径 40 格的坑。</li>
 * </ul>
 *
 * <p>射程上限是 {@link #MAX_LANCE_FX_RANGE}，它**同时**是客户端
 * {@code ArmorCombatFxController} 的特效距离预算来源：超出那个距离客户端会静默丢弃整条光束，
 * 只剩地形被挖掉——那是「看不见的破坏」，属于最难排查的一类 bug。所以客户端不再自己写一份
 * 96，而是直接引用这个常量；型号系数乘完也必须留在它之内。</p>
 */
public final class EnhancedBeamMath {
	/**
	 * 按住多少 tick 之后才算「长按」。
	 *
	 * <p>客户端用鼠标左键当扳机：按下发 {@code HAND_BEAM_LANCE} 的 PRESS 进入蓄力，
	 * 松手才发 RELEASE。**档位由服务端按「按住的长度」在现场分流**——不到这个长度就是点射，
	 * 到了就是灭世光束。这样一次扳机恰好两个意图包，且短按的点射没有额外分类延迟。</p>
	 */
	public static final int LANCE_CLASSIFY_TICKS = 6;
	/** 蓄满所需 tick 数（1.5 秒）。 */
	public static final int LANCE_MAX_CHARGE_TICKS = 30;
	/**
	 * 蓄满后最长还能举着多久。超过就由服务端自动释放，避免蓄力循环音永远停不下来。
	 * 这是「按住不放」的唯一出路，也是防空放的设计：满蓄力一定会打出去。
	 */
	public static final int LANCE_MAX_HOLD_TICKS = 100;

	// ---- 点射档 ----
	public static final double PULSE_RANGE = 42.0D;
	public static final float PULSE_DAMAGE = 9.0F;
	public static final double PULSE_KNOCKBACK = 0.85D;
	public static final float PULSE_ENERGY_COST = 1.6F;
	public static final float PULSE_HEAT = 2.6F;
	/** 点射的客户端光束强度提示（0..1）。纯观感，不参与任何服务端判定。 */
	public static final float PULSE_FX_INTENSITY = 0.22F;
	/** 点射冷却（tick）。这个数字就是「点多快射多快」的上限，别随手调大。 */
	public static final int PULSE_COOLDOWN_TICKS = 2;
	public static final int PULSE_FIRING_TICKS = 2;
	public static final int PULSE_RECOVERY_TICKS = 1;

	// ---- 灭世光束档 ----
	/** 刚够蓄力档的门槛射程（格）。轻蓄力也应该能跨过一整个山谷。 */
	public static final double LANCE_MIN_RANGE = 100.0D;
	/** 满蓄力射程（格）。用户要求「远到 100~150 格」。 */
	public static final double LANCE_MAX_RANGE = 150.0D;
	/**
	 * 客户端特效距离预算（格）。**这是客户端 {@code ArmorCombatFxController} 的唯一真相源**：
	 * 那边不再自己写一份距离，而是直接引用这个常量。任何光束的视觉端点超过它就会被静默丢弃，
	 * 于是型号系数（疾影 1.18）乘完也必须留在之内 —— 150 × 1.18 = 177，所以给到 180。
	 */
	public static final double MAX_LANCE_FX_RANGE = 180.0D;
	public static final float LANCE_MIN_DAMAGE = 150.0F;
	public static final float LANCE_MAX_DAMAGE = 900.0F;
	public static final double LANCE_MIN_KNOCKBACK = 3.0D;
	public static final double LANCE_MAX_KNOCKBACK = 12.0D;
	/** 爆坑半径（格）的先锋型号端点：下限就啃掉一层岩皮，上限是直径 36 格的坑。 */
	public static final double MIN_CRATER_RADIUS = 8.0D;
	public static final double BASE_MAX_CRATER_RADIUS = 18.0D;
	/**
	 * 爆坑半径的硬上限（守望满蓄力），同时是单次扫描规模的闸门。
	 * 20 格的球在格中心口径下有 33552 格，正好是 {@code ImpactBlastController.MAX_EVALUATED_VOXELS}
	 * 与 {@link #MAX_CRATER_BLOCKS} 的取值依据——动这个数必须同时动那两个。
	 */
	public static final double MAX_CRATER_RADIUS = 20.0D;
	/** 原生爆炸威力：只决定粒子、音效与实体伤害的范围，与地形破坏无关。 */
	public static final float MIN_EXPLOSION_POWER = 14.0F;
	public static final float MAX_EXPLOSION_POWER = 70.0F;
	public static final float MIN_ENERGY_COST = 260.0F;
	public static final float MAX_ENERGY_COST = 1400.0F;
	public static final float MIN_HEAT = 30.0F;
	public static final float MAX_HEAT = 72.0F;
	public static final int LANCE_COOLDOWN_TICKS = 84;
	public static final int LANCE_FIRING_TICKS = 12;
	public static final int LANCE_RECOVERY_TICKS = 14;
	/**
	 * 灭世级爆坑的硬度上限。必须高过黑曜石（50），否则硬岩会像一堵墙一样留在坑壁上，
	 * 「磨平山头」就变成「在山里开一个洞」。基岩仍由护栏的黑名单挡下。
	 */
	public static final double LANCE_HARDNESS_LIMIT = 62.0D;
	/** 预算按球体积给：比硬化石略富余，坑才是完整的球而不是提前收口的漏斗。 */
	private static final double LANCE_BUDGET_PER_VOLUME = 3.0D;
	/** 块数上限相对球体积的余量，留一点富余避免差一块而少挖一层。 */
	private static final double LANCE_BLOCK_CAP_VOLUME_FACTOR = 1.15D;
	/**
	 * 单次爆坑最多掀掉多少块。这是单 tick 开销的硬闸门，不要为了「更大」而放开。
	 * 39000 是按最大球（半径 20 → 体积 33510 → 1.15 倍 = 38537）留出的余量。
	 */
	public static final int MAX_CRATER_BLOCKS = 39_000;
	/**
	 * 爆坑球心相对命中点沿光束方向前移的比例。贴着地表炸会把一半球体浪费在空气里，
	 * 前移之后坑体是「咬」进山体而不是「刮」在山皮上。
	 */
	public static final double CRATER_SINK_RATIO = 0.30D;

	private EnhancedBeamMath() {
	}

	/** 点射档的硬参数。型号只影响射程、伤害与代价，不影响冷却与节奏。 */
	public static PulseProfile pulseProfile(ArmorVariant variant) {
		Scale scale = Scale.of(variant);
		return new PulseProfile(
				Math.min(MAX_LANCE_FX_RANGE, PULSE_RANGE * scale.range()),
				(float) (PULSE_DAMAGE * scale.damage()),
				PULSE_KNOCKBACK * scale.damage(),
				(float) (PULSE_ENERGY_COST * scale.cost()),
				(float) (PULSE_HEAT * scale.cost()),
				PULSE_COOLDOWN_TICKS, PULSE_FIRING_TICKS, PULSE_RECOVERY_TICKS);
	}

	/**
	 * 灭世光束档的硬参数。
	 *
	 * @param normalizedCharge 0..1 的蓄力进度。非有限值一律按 0 处理，不做任何「跳级」放行。
	 */
	public static LanceProfile lanceProfile(ArmorVariant variant, float normalizedCharge) {
		Scale scale = Scale.of(variant);
		double charge = clamp(unit(normalizedCharge), 0.0D, 1.0D);
		double radius = Math.min(MAX_CRATER_RADIUS,
				lerp(charge, MIN_CRATER_RADIUS, BASE_MAX_CRATER_RADIUS) * scale.crater());
		double volume = sphereVolume(radius);
		return new LanceProfile(true, charge,
				Math.min(MAX_LANCE_FX_RANGE,
						lerp(charge, LANCE_MIN_RANGE, LANCE_MAX_RANGE) * scale.range()),
				(float) (lerp(charge, LANCE_MIN_DAMAGE, LANCE_MAX_DAMAGE) * scale.damage()),
				lerp(charge, LANCE_MIN_KNOCKBACK, LANCE_MAX_KNOCKBACK) * scale.damage(),
				radius,
				(float) lerp(charge, MIN_EXPLOSION_POWER, MAX_EXPLOSION_POWER),
				volume * LANCE_BUDGET_PER_VOLUME,
				Math.min(MAX_CRATER_BLOCKS, (int) Math.ceil(volume * LANCE_BLOCK_CAP_VOLUME_FACTOR)),
				LANCE_HARDNESS_LIMIT,
				(float) (lerp(charge, MIN_ENERGY_COST, MAX_ENERGY_COST) * scale.cost()),
				(float) (lerp(charge, MIN_HEAT, MAX_HEAT) * scale.cost()),
				LANCE_COOLDOWN_TICKS, LANCE_FIRING_TICKS, LANCE_RECOVERY_TICKS);
	}

	/** 已经按住的 tick 数换算成 0..1 的蓄力进度；不足或非法一律 0。 */
	public static float chargeFor(long heldTicks) {
		if (heldTicks <= 0L) return 0.0F;
		return (float) clamp((double) heldTicks / LANCE_MAX_CHARGE_TICKS, 0.0D, 1.0D);
	}

	/** 半径为 {@code radius} 的球的格数（4/3·π·r³），用来把块数上限绑到坑的完整度上。 */
	public static double sphereVolume(double radius) {
		if (!Double.isFinite(radius) || radius <= 0.0D) return 0.0D;
		return Math.PI * radius * radius * radius * 4.0D / 3.0D;
	}

	private static double unit(float value) {
		return Float.isFinite(value) ? value : 0.0D;
	}

	private static double lerp(double progress, double start, double end) {
		return start + (end - start) * progress;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	/**
	 * 型号系数：先锋居中，疾影射得更远但单体破坏更浅，守望射程最短、伤害与爆坑最大。
	 * {@code cost} 同时缩放能量与热量，保证「更强 = 更贵」不会被绕开。
	 */
	private record Scale(double range, double damage, double crater, double cost) {
		private static Scale of(ArmorVariant variant) {
			return switch (variant == null ? ArmorVariant.DEFAULT : variant) {
				case VANGUARD -> new Scale(1.00D, 1.00D, 1.00D, 1.00D);
				case SWIFT -> new Scale(1.18D, 0.78D, 0.86D, 0.88D);
				case SENTINEL -> new Scale(0.90D, 1.30D, 1.14D, 1.15D);
			};
		}
	}

	/** 点射档参数。 */
	public record PulseProfile(double range, float damage, double knockback,
			float energyCost, float heat, int cooldownTicks, int firingTicks, int recoveryTicks) {
	}

	/**
	 * 灭世光束档参数。{@code craterRadius} 与 {@code carveMaximumBlocks} 只喂给
	 * {@link ImpactBlastController} 的球形破坏；{@code explosionPower} 只喂给原版爆炸表现。
	 */
	public record LanceProfile(boolean enabled, double charge, double range, float damage,
			double knockback, double craterRadius, float explosionPower,
			double carveBudget, int carveMaximumBlocks, double hardnessLimit,
			float energyCost, float heat, int cooldownTicks, int firingTicks, int recoveryTicks) {
	}
}
