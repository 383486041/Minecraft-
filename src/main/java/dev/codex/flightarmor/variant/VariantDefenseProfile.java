package dev.codex.flightarmor.variant;

/** 完整同型号套装的服务端防御倍率；单穿战靴和混装永远不会读取此配置。 */
public record VariantDefenseProfile(
		float incomingDamageMultiplier,
		float flightCollisionDamageMultiplier,
		double knockbackMultiplier) {

	public VariantDefenseProfile {
		requireFiniteRange(incomingDamageMultiplier, 0.0F, 3.0F, "承伤倍率");
		requireFiniteRange(flightCollisionDamageMultiplier, 0.0F, 3.0F, "飞行碰撞承伤倍率");
		requireFiniteRange(knockbackMultiplier, 0.0D, 3.0D, "击退倍率");
	}

	public float scaleDamage(float amount, boolean flightCollision) {
		return amount * (flightCollision
				? flightCollisionDamageMultiplier
				: incomingDamageMultiplier);
	}

	private static void requireFiniteRange(float value, float minimum, float maximum, String label) {
		if (!Float.isFinite(value) || value < minimum || value > maximum) {
			throw new IllegalArgumentException(label + "超出范围：" + value);
		}
	}

	private static void requireFiniteRange(double value, double minimum, double maximum, String label) {
		if (!Double.isFinite(value) || value < minimum || value > maximum) {
			throw new IllegalArgumentException(label + "超出范围：" + value);
		}
	}
}
