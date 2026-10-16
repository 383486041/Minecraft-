package dev.codex.flightarmor.variant;

/**
 * 战甲型号对现有服务端武器基础参数的倍率。
 *
 * <p>这里只描述差异，不复制 {@code ArmorCombatTuning} 的基础数值。俯冲的“范围”特指
 * 落地冲击半径。所有倍率都必须为有限正数，避免错误数据制造零距离或无限伤害。</p>
 */
public record VariantCombatProfile(
		float wristDamageMultiplier,
		double wristRangeMultiplier,
		float coreDamageMultiplier,
		double coreRangeMultiplier,
		float diveDamageMultiplier,
		double diveRadiusMultiplier) {

	public VariantCombatProfile {
		requirePositiveFinite(wristDamageMultiplier, "腕炮伤害倍率");
		requirePositiveFinite(wristRangeMultiplier, "腕炮范围倍率");
		requirePositiveFinite(coreDamageMultiplier, "核心束伤害倍率");
		requirePositiveFinite(coreRangeMultiplier, "核心束范围倍率");
		requirePositiveFinite(diveDamageMultiplier, "俯冲伤害倍率");
		requirePositiveFinite(diveRadiusMultiplier, "俯冲半径倍率");
	}

	public float scaleWristDamage(float baseDamage) {
		return baseDamage * wristDamageMultiplier;
	}

	public double scaleWristRange(double baseRange) {
		return baseRange * wristRangeMultiplier;
	}

	public float scaleCoreDamage(float baseDamage) {
		return baseDamage * coreDamageMultiplier;
	}

	public double scaleCoreRange(double baseRange) {
		return baseRange * coreRangeMultiplier;
	}

	public float scaleDiveDamage(float baseDamage) {
		return baseDamage * diveDamageMultiplier;
	}

	public double scaleDiveRadius(double baseRadius) {
		return baseRadius * diveRadiusMultiplier;
	}

	private static void requirePositiveFinite(float value, String label) {
		if (!Float.isFinite(value) || value <= 0.0F) {
			throw new IllegalArgumentException(label + "必须是有限正数：" + value);
		}
	}

	private static void requirePositiveFinite(double value, String label) {
		if (!Double.isFinite(value) || value <= 0.0D) {
			throw new IllegalArgumentException(label + "必须是有限正数：" + value);
		}
	}
}
