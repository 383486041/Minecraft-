package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;

/**
 * Pure weapon policy shared by the authoritative resolver and offline tests.
 *
 * <p>Explosion eligibility lives here so a client-supplied visual charge can
 * never silently turn an ordinary hit (or an autonomous cruise wrist shot)
 * into a native explosion.</p>
 */
final class ArmorWeaponRules {
	static final float WRIST_EXPLOSION_CHARGE_THRESHOLD =
			ArmorCombatTuning.WRIST_EXPLOSION_CHARGE_THRESHOLD;
	static final float SWIFT_CORE_EXPLOSION_CHARGE_THRESHOLD =
			ArmorCombatTuning.SWIFT_CORE_EXPLOSION_CHARGE_THRESHOLD;
	static final double CRUISE_RAM_EXPLOSION_SPEED_THRESHOLD =
			ArmorCombatTuning.CRUISE_RAM_EXPLOSION_SPEED_THRESHOLD;
	static final int BLADE_SLASH_WINDUP_TICKS =
			ArmorCombatTuning.BLADE_SLASH_WINDUP_TICKS;

	private static final ExplosionSpec NO_EXPLOSION = new ExplosionSpec(false, 0.0F);

	private ArmorWeaponRules() {
	}

	static ExplosionSpec wristExplosion(ArmorVariant variant, float charge,
			boolean autonomous) {
		float normalized = unit(charge);
		if (autonomous || normalized < WRIST_EXPLOSION_CHARGE_THRESHOLD) {
			return NO_EXPLOSION;
		}
		float thresholdProgress = thresholdProgress(
				normalized, WRIST_EXPLOSION_CHARGE_THRESHOLD);
		return switch (variant) {
			case VANGUARD -> enabled(lerp(thresholdProgress, 2.35F, 3.45F));
			case SWIFT -> enabled(lerp(thresholdProgress, 1.75F, 2.55F));
			case SENTINEL -> enabled(lerp(thresholdProgress, 2.85F, 4.15F));
		};
	}

	static ExplosionSpec coreExplosion(ArmorVariant variant, float charge) {
		if (!Float.isFinite(charge)) return NO_EXPLOSION;
		float normalized = unit(charge);
		return switch (variant) {
			case VANGUARD -> enabled(lerp(normalized, 2.90F, 4.45F));
			case SENTINEL -> enabled(lerp(normalized, 3.55F, 5.15F));
			case SWIFT -> normalized < SWIFT_CORE_EXPLOSION_CHARGE_THRESHOLD
					? NO_EXPLOSION
					: enabled(lerp(thresholdProgress(
							normalized, SWIFT_CORE_EXPLOSION_CHARGE_THRESHOLD),
							1.90F, 2.85F));
		};
	}

	static ExplosionSpec cruiseRamExplosion(double speed) {
		if (!Double.isFinite(speed)
				|| speed < CRUISE_RAM_EXPLOSION_SPEED_THRESHOLD) {
			return NO_EXPLOSION;
		}
		double progress = clamp((speed - CRUISE_RAM_EXPLOSION_SPEED_THRESHOLD)
				/ 1.55D, 0.0D, 1.0D);
		return enabled((float) lerp(progress, 2.25D, 4.05D));
	}

	static BladeProfile bladeProfile(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> new BladeProfile(16.0D, 2.55D, 18.0F, 1.35D, 10);
			case SWIFT -> new BladeProfile(24.0D, 1.65D, 13.0F, 0.55D, 14);
			case SENTINEL -> new BladeProfile(13.0D, 3.35D, 30.0F, 2.20D, 12);
		};
	}

	static boolean bladeStrikeReady(long elapsedTicks) {
		return elapsedTicks >= BLADE_SLASH_WINDUP_TICKS;
	}

	private static ExplosionSpec enabled(float power) {
		return Float.isFinite(power) && power > 0.0F
				? new ExplosionSpec(true, power) : NO_EXPLOSION;
	}

	private static float thresholdProgress(float value, float threshold) {
		return (float) clamp((value - threshold) / Math.max(0.0001F, 1.0F - threshold),
				0.0D, 1.0D);
	}

	private static float unit(float value) {
		return Float.isFinite(value) ? (float) clamp(value, 0.0D, 1.0D) : 0.0F;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static float lerp(float progress, float start, float end) {
		return start + (end - start) * progress;
	}

	private static double lerp(double progress, double start, double end) {
		return start + (end - start) * progress;
	}

	record ExplosionSpec(boolean enabled, float power) {
	}

	record BladeProfile(
			double range,
			double halfWidth,
			float damage,
			double knockback,
			int maxTargets
	) {
	}
}
