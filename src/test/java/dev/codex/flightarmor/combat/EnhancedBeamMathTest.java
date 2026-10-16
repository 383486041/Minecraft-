package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;

/**
 * Offline regression gate for the enhanced energy beam's two firing modes.
 *
 * <p>这一层是「点了多快射多快」与「一炮削平山头」两条手感的量化闸门：
 * 点射档的冷却必须短到接近点击速度，灭世档满蓄力必须真的给出山头级爆坑。</p>
 */
public final class EnhancedBeamMathTest {
	private static final double EPSILON = 1.0E-4D;
	/** 石头（minecraft:stone）的硬度。最普通、也最该被完整清掉的山体岩层。 */
	private static final double REFERENCE_ROCK_HARDNESS = 1.5D;
	/**
	 * {@link ImpactBlastController} 的球内评估格数上限。这里刻意重抄一份而不是读它的私有常量，
	 * 由 HandBeamContractTest 断言两处字面量一致——这样任何一边被改动都会立刻翻红。
	 */
	private static final int EVALUATED_VOXEL_CAP = 36_864;

	private EnhancedBeamMathTest() {
	}

	public static void main(String[] args) {
		tapFireRateFollowsTheClickNotACooldown();
		pulseStaysCheapAndWeak();
		chargeScalesTheAnnihilationBeam();
		fullChargeFlattensAMountainTop();
		craterBudgetAndBlockCapAreWhole();
		theLargestCraterFitsInsideTheEvaluatedVoxelBudget();
		lanceReachCoversTheDemandedDistanceWithinTheFxBudget();
		variantScalingDiffersDeterministically();
		nonFiniteChargeFailsClosed();
	}

	/**
	 * 点射档的核心手感：冷却只有 2 tick，一次完整的「开火 + 恢复」不超过一个点击间隔，
	 * 所以射击节奏由玩家点得多快决定，而不是由冷却排队决定。
	 */
	private static void tapFireRateFollowsTheClickNotACooldown() {
		EnhancedBeamMath.PulseProfile pulse = EnhancedBeamMath.pulseProfile(ArmorVariant.VANGUARD);
		require(pulse.cooldownTicks() <= 2,
				"the tap cooldown must not be the thing limiting the fire rate: " + pulse.cooldownTicks());
		int cycle = pulse.cooldownTicks() + pulse.firingTicks() + pulse.recoveryTicks();
		require(cycle <= 6,
				"one tap cycle must stay under a third of a second so fast clicking stays fast: " + cycle);
	}

	private static void pulseStaysCheapAndWeak() {
		EnhancedBeamMath.PulseProfile pulse = EnhancedBeamMath.pulseProfile(ArmorVariant.VANGUARD);
		require(pulse.damage() > 0.0F && pulse.damage() < EnhancedBeamMath.LANCE_MIN_DAMAGE,
				"the tap must stay a light shot; the charge is where the damage lives");
		require(pulse.energyCost() > 0.0F && pulse.heat() > 0.0F,
				"a free tap would make the charge mode pointless");
		require(pulse.range() >= 16.0D, "the tap must at least reach across a clearing");
		require(EnhancedBeamMath.PULSE_FX_INTENSITY > 0.0F
						&& EnhancedBeamMath.PULSE_FX_INTENSITY < 1.0F,
				"the tap's client-only beam intensity hint must stay inside the normalized range");
	}

	/** 蓄力越满，射程、伤害、坑径、威力、代价都要单调增长。 */
	private static void chargeScalesTheAnnihilationBeam() {
		EnhancedBeamMath.LanceProfile low =
				EnhancedBeamMath.lanceProfile(ArmorVariant.SENTINEL, 0.0F);
		EnhancedBeamMath.LanceProfile full =
				EnhancedBeamMath.lanceProfile(ArmorVariant.SENTINEL, 1.0F);
		require(full.range() > low.range(), "a fuller charge must reach further");
		require(full.damage() > low.damage(), "a fuller charge must hit harder");
		require(full.craterRadius() > low.craterRadius(), "a fuller charge must open a wider crater");
		require(full.explosionPower() > low.explosionPower(), "a fuller charge must report a bigger blast");
		require(full.energyCost() > low.energyCost() && full.heat() > low.heat(),
				"the strongest shot must be the most expensive one");
		require(full.carveBudget() > low.carveBudget()
						&& full.carveMaximumBlocks() > low.carveMaximumBlocks(),
				"a fuller charge must be allowed to clear more rock");
		require(Math.abs(full.charge() - 1.0D) < EPSILON && low.charge() == 0.0D,
				"the reported charge must be the normalized input");
	}

	/**
	 * 这条断言就是「磨平一个山头」的回归闸门：满蓄力必须给出直径 36 格以上的爆坑，
	 * 并覆盖整球块数，否则山尖会剩下一圈啃不动的硬岩。
	 */
	private static void fullChargeFlattensAMountainTop() {
		EnhancedBeamMath.LanceProfile full =
				EnhancedBeamMath.lanceProfile(ArmorVariant.VANGUARD, 1.0F);
		require(full.craterRadius() * 2.0D >= 36.0D,
				"a full charge must open a mountain-topping crater, got radius " + full.craterRadius());
		require(full.craterRadius() <= EnhancedBeamMath.MAX_CRATER_RADIUS,
				"the crater must stay inside the capped radius");
		double volume = EnhancedBeamMath.sphereVolume(full.craterRadius());
		require(full.carveMaximumBlocks() >= volume,
				"the block cap must cover the whole crater sphere");
		require(full.hardnessLimit() > 50.0D,
				"the crater must bite through obsidian, otherwise hard rock rings the crater");
		require(full.carveMaximumBlocks() <= EnhancedBeamMath.MAX_CRATER_BLOCKS,
				"one shot must respect the single-tick block-update ceiling");
	}

	/**
	 * 预算与块数上限必须是一对自洽的数。
	 *
	 * <p>球形破坏里**真正的闸门是块数上限**，预算只是「啃硬岩」的代价旋钮：岩层越硬、坑越小，
	 * 那是设计意图（和撞击爆破同一套取舍）。但普通山体岩层不该提前收口，所以这里按石头硬度
	 * 要求预算够啃完整球——把预算调小到覆盖不住的量级，这条就会翻红。</p>
	 */
	private static void craterBudgetAndBlockCapAreWhole() {
		for (float charge = 0.0F; charge <= 1.0F; charge += 0.125F) {
			EnhancedBeamMath.LanceProfile profile =
					EnhancedBeamMath.lanceProfile(ArmorVariant.VANGUARD, charge);
			double volume = EnhancedBeamMath.sphereVolume(profile.craterRadius());
			require(profile.carveBudget()
							>= profile.carveMaximumBlocks() * REFERENCE_ROCK_HARDNESS,
					"budget must clear ordinary rock for the whole crater: got "
							+ profile.carveBudget() + " for "
							+ profile.carveMaximumBlocks() + " blocks");
			require(profile.carveMaximumBlocks() >= volume * 0.9D,
					"block cap must stay within ten percent of the full sphere volume");
			require(profile.carveMaximumBlocks() <= EnhancedBeamMath.MAX_CRATER_BLOCKS,
					"one shot must never exceed the single-tick block ceiling");
		}
	}

	/**
	 * 最大的球必须落在评估格数上限之内。超出去不是「挖少一点」：那个循环是按球内格数计数的，
	 * 一旦封顶就 {@code break}，坑会被整整齐齐切掉一层——画面上「看着差不多」，实际很不对。
	 */
	private static void theLargestCraterFitsInsideTheEvaluatedVoxelBudget() {
		double radius = EnhancedBeamMath.MAX_CRATER_RADIUS;
		int span = (int) Math.ceil(radius);
		int inside = 0;
		for (int x = -span; x <= span; x++) {
			for (int y = -span; y <= span; y++) {
				for (int z = -span; z <= span; z++) {
					// 与 ImpactBlastController 保持同一口径：用格中心判球内。
					double dx = x + 0.5D;
					double dy = y + 0.5D;
					double dz = z + 0.5D;
					if (dx * dx + dy * dy + dz * dz <= radius * radius) inside++;
				}
			}
		}
		require(inside <= EVALUATED_VOXEL_CAP,
				"the largest crater needs " + inside
						+ " in-sphere voxels, over the evaluated cap " + EVALUATED_VOXEL_CAP);
	}

	/**
	 * 射程是本轮用户硬性要求：轻蓄力就要够 100 格，满蓄力要够 150 格；同时任何型号的射程
	 * 都不能越过客户端特效预算。超出去的表现是「山被挖了但看不见光束」，
	 * 属于最难排查的一类 bug，所以在这里钉死。
	 */
	private static void lanceReachCoversTheDemandedDistanceWithinTheFxBudget() {
		EnhancedBeamMath.LanceProfile low =
				EnhancedBeamMath.lanceProfile(ArmorVariant.VANGUARD, 0.0F);
		EnhancedBeamMath.LanceProfile full =
				EnhancedBeamMath.lanceProfile(ArmorVariant.VANGUARD, 1.0F);
		require(low.range() >= 100.0D,
				"even a barely-charged lance must reach 100 blocks, got " + low.range());
		require(full.range() >= 150.0D,
				"a full charge must reach 150 blocks, got " + full.range());
		require(full.range() <= EnhancedBeamMath.MAX_LANCE_FX_RANGE,
				"the furthest lance must stay inside the client FX budget: " + full.range());
		// 预算还要覆盖最长的型号系数，否则疾影满蓄力会打出一条看不见的光束。
		require(EnhancedBeamMath.MAX_LANCE_FX_RANGE
						>= EnhancedBeamMath.LANCE_MAX_RANGE * 1.18D,
				"the FX budget must cover the longest variant's full-charge reach");
		for (ArmorVariant variant : ArmorVariant.values()) {
			require(EnhancedBeamMath.lanceProfile(variant, 1.0F).range()
							<= EnhancedBeamMath.MAX_LANCE_FX_RANGE,
					"the furthest lance must stay inside the client FX budget: " + variant);
			require(EnhancedBeamMath.pulseProfile(variant).range()
							<= EnhancedBeamMath.MAX_LANCE_FX_RANGE,
					"the furthest tap must stay inside the client FX budget: " + variant);
		}
	}

	private static void variantScalingDiffersDeterministically() {
		EnhancedBeamMath.LanceProfile vanguard =
				EnhancedBeamMath.lanceProfile(ArmorVariant.VANGUARD, 1.0F);
		EnhancedBeamMath.LanceProfile swift =
				EnhancedBeamMath.lanceProfile(ArmorVariant.SWIFT, 1.0F);
		EnhancedBeamMath.LanceProfile sentinel =
				EnhancedBeamMath.lanceProfile(ArmorVariant.SENTINEL, 1.0F);
		require(swift.range() > vanguard.range() && vanguard.range() > sentinel.range(),
				"swift must be the long-reach platform and sentinel the brawler");
		require(sentinel.craterRadius() > vanguard.craterRadius()
						&& vanguard.craterRadius() > swift.craterRadius(),
				"sentinel must open the widest crater and swift the narrowest");
		require(sentinel.damage() > vanguard.damage() && vanguard.damage() > swift.damage(),
				"sentinel must hit hardest and swift lightest");
	}

	private static void nonFiniteChargeFailsClosed() {
		require(EnhancedBeamMath.lanceProfile(ArmorVariant.VANGUARD, Float.NaN).charge() == 0.0D,
				"a non-finite charge must fall back to the weakest shot, never a free full charge");
		require(EnhancedBeamMath.lanceProfile(ArmorVariant.VANGUARD, 9.0F).charge() <= 1.0D,
				"an out-of-range charge must clamp to full");
		require(EnhancedBeamMath.chargeFor(-5L) == 0.0F && EnhancedBeamMath.chargeFor(0L) == 0.0F,
				"a non-positive hold must not report charge progress");
		require(Math.abs(EnhancedBeamMath.chargeFor(EnhancedBeamMath.LANCE_MAX_CHARGE_TICKS) - 1.0F) < EPSILON,
				"the max hold must exactly reach full charge");
		require(EnhancedBeamMath.chargeFor(10_000L) <= 1.0F,
				"an over-long hold must not exceed full charge");
		require(EnhancedBeamMath.LANCE_MAX_HOLD_TICKS > EnhancedBeamMath.LANCE_MAX_CHARGE_TICKS,
				"the auto-release window must leave room after the charge is full");
		require(EnhancedBeamMath.LANCE_CLASSIFY_TICKS < EnhancedBeamMath.LANCE_MAX_CHARGE_TICKS,
				"the tap/hold split must happen strictly before the charge is full");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
