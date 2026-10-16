package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;

/** Offline regression gate for impact-blast radius/power/budget scaling. */
public final class ImpactBlastMathTest {
	private ImpactBlastMathTest() {
	}

	public static void main(String[] args) {
		subThresholdSpeedIsDisabled();
		speedScalesBlastSize();
		variantHardnessDiffersDeterministically();
		cruiseAndDiveBlastsOpenRealCraters();
		blockCapCoversTheWholeSphere();
		capsAreFiniteAndOrdered();
	}

	private static void subThresholdSpeedIsDisabled() {
		require(!ImpactBlastMath.profile(ArmorVariant.VANGUARD,
						ImpactBlastMath.MIN_IMPACT_SPEED - 0.01D).enabled(),
				"walking into a wall must not detonate");
		require(!ImpactBlastMath.profile(ArmorVariant.VANGUARD, Double.NaN).enabled(),
				"a non-finite speed must fail closed");
		require(ImpactBlastMath.profile(ArmorVariant.VANGUARD,
						ImpactBlastMath.MIN_IMPACT_SPEED).enabled(),
				"the threshold itself must still detonate");
	}

	private static void speedScalesBlastSize() {
		ImpactBlastMath.Profile slow = ImpactBlastMath.profile(ArmorVariant.VANGUARD, 0.6D);
		ImpactBlastMath.Profile fast = ImpactBlastMath.profile(ArmorVariant.VANGUARD, 6.0D);
		require(fast.radius() > slow.radius(), "faster impacts must open a wider crater");
		require(fast.power() > slow.power(), "faster impacts must report a stronger blast");
		require(fast.budget() > slow.budget(), "faster impacts must carry more budget");
		require(fast.maximumDestroyedBlocks() > slow.maximumDestroyedBlocks(),
				"faster impacts must clear more blocks");
	}

	private static void variantHardnessDiffersDeterministically() {
		double swift = ImpactBlastMath.profile(ArmorVariant.SWIFT, 3.0D).hardnessLimit();
		double vanguard = ImpactBlastMath.profile(ArmorVariant.VANGUARD, 3.0D).hardnessLimit();
		double sentinel = ImpactBlastMath.profile(ArmorVariant.SENTINEL, 3.0D).hardnessLimit();
		require(swift > vanguard && vanguard > sentinel,
				"swift must bite the hardest rock and sentinel the softest");
	}

	/**
	 * 这条断言是「撞击爆炸」的回归闸门：坑必须是球的量级，而不是一条一格宽的细隧道。
	 * 巡航撞一下至少是个半径 3 格的坑，俯冲撞一下要掀掉上千块。
	 */
	private static void cruiseAndDiveBlastsOpenRealCraters() {
		ImpactBlastMath.Profile cruise = ImpactBlastMath.profile(ArmorVariant.VANGUARD, 0.6D);
		require(cruise.radius() >= 3.0D, "a cruise-speed hit must open a visible crater");
		require(cruise.maximumDestroyedBlocks() >= 96,
				"a cruise-speed hit must clear a full small sphere, not a handful of blocks");
		ImpactBlastMath.Profile dive = ImpactBlastMath.profile(ArmorVariant.SWIFT, 20.0D);
		require(dive.radius() >= 9.0D, "a dive impact must open a wide crater");
		require(dive.maximumDestroyedBlocks() >= 3000,
				"a dive impact must clear the whole sphere instead of being cut off halfway");
	}

	/** 块数上限必须覆盖整球，否则同一片石头会因为遍历顺序只被啃掉一半。 */
	private static void blockCapCoversTheWholeSphere() {
		ImpactBlastMath.Profile dive = ImpactBlastMath.profile(ArmorVariant.SWIFT, 20.0D);
		double volume = ImpactBlastMath.sphereVolume(dive.radius());
		require(dive.maximumDestroyedBlocks() >= volume,
				"the block cap must cover the whole crater sphere");
	}

	private static void capsAreFiniteAndOrdered() {
		ImpactBlastMath.Profile extreme = ImpactBlastMath.profile(ArmorVariant.SWIFT, 10_000.0D);
		require(Double.isFinite(extreme.radius())
						&& extreme.radius() <= ImpactBlastMath.MAX_RADIUS,
				"radius must stay finite and capped");
		require(Double.isFinite(extreme.budget()) && extreme.budget() <= 9000.0D,
				"budget must stay finite and capped");
		require(Float.isFinite(extreme.power()) && extreme.power() <= 14.0F,
				"blast power must stay finite and capped");
		require(extreme.maximumDestroyedBlocks() >= 1 && extreme.maximumDestroyedBlocks() <= 3600,
				"block cap must stay positive and bounded");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
