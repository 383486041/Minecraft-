package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;

public final class ArmorWeaponRulesTest {
	private ArmorWeaponRulesTest() {
	}

	public static void main(String[] args) {
		wristRequiresRealPlayerCharge();
		coreExplosionPolicyDiffersByVariant();
		ramExplosionRequiresHighSpeed();
		bladeHasDelayedLongRangeStrike();
	}

	private static void wristRequiresRealPlayerCharge() {
		require(!ArmorWeaponRules.wristExplosion(
				ArmorVariant.VANGUARD,
				ArmorWeaponRules.WRIST_EXPLOSION_CHARGE_THRESHOLD - 0.001F,
				false).enabled(), "short wrist charge must never explode");
		require(ArmorWeaponRules.wristExplosion(
				ArmorVariant.VANGUARD,
				ArmorWeaponRules.WRIST_EXPLOSION_CHARGE_THRESHOLD,
				false).enabled(), "threshold wrist charge must explode");
		require(!ArmorWeaponRules.wristExplosion(
				ArmorVariant.SENTINEL, 1.0F, true).enabled(),
				"autonomous cruise wrist cannon must never explode");
		require(!ArmorWeaponRules.wristExplosion(
				ArmorVariant.SWIFT, 0.35F, true).enabled(),
				"the current 0.35 cruise wrist charge must remain non-explosive");
	}

	private static void coreExplosionPolicyDiffersByVariant() {
		require(ArmorWeaponRules.coreExplosion(ArmorVariant.VANGUARD, 0.0F).enabled(),
				"Vanguard chest cannon must have native blast capability");
		require(ArmorWeaponRules.coreExplosion(ArmorVariant.SENTINEL, 0.0F).enabled(),
				"Sentinel chest cannon must have native blast capability");
		require(!ArmorWeaponRules.coreExplosion(ArmorVariant.SWIFT,
				ArmorWeaponRules.SWIFT_CORE_EXPLOSION_CHARGE_THRESHOLD - 0.001F).enabled(),
				"Swift low-charge core shot must stay a penetrator");
		require(ArmorWeaponRules.coreExplosion(ArmorVariant.SWIFT,
				ArmorWeaponRules.SWIFT_CORE_EXPLOSION_CHARGE_THRESHOLD).enabled(),
				"Swift high-charge core shot must gain a smaller native blast");
		require(!ArmorWeaponRules.coreExplosion(ArmorVariant.SENTINEL, Float.NaN).enabled(),
				"non-finite core charge must fail closed");
		require(ArmorWeaponRules.coreExplosion(ArmorVariant.SENTINEL, 1.0F).power() <= 5.20F,
				"native core blast power must stay bounded");
	}

	private static void ramExplosionRequiresHighSpeed() {
		require(!ArmorWeaponRules.cruiseRamExplosion(
				ArmorWeaponRules.CRUISE_RAM_EXPLOSION_SPEED_THRESHOLD - 0.001D).enabled(),
				"low-speed cruise ram must not explode");
		ArmorWeaponRules.ExplosionSpec highSpeed = ArmorWeaponRules.cruiseRamExplosion(
				ArmorWeaponRules.CRUISE_RAM_EXPLOSION_SPEED_THRESHOLD);
		require(highSpeed.enabled() && highSpeed.power() > 0.0F,
				"high-speed cruise ram must create one bounded native explosion");
		require(ArmorWeaponRules.cruiseRamExplosion(1000.0D).power() <= 4.10F,
				"ram blast power must remain capped at extreme speed");
		require(!ArmorWeaponRules.cruiseRamExplosion(Double.NaN).enabled(),
				"non-finite speed must fail closed");
	}

	private static void bladeHasDelayedLongRangeStrike() {
		require(!ArmorWeaponRules.bladeStrikeReady(
				ArmorWeaponRules.BLADE_SLASH_WINDUP_TICKS - 1L),
				"blade damage must not land during wind-up");
		require(ArmorWeaponRules.bladeStrikeReady(
				ArmorWeaponRules.BLADE_SLASH_WINDUP_TICKS),
				"blade damage must land on the authored strike frame");
		require(ArmorWeaponRules.bladeProfile(ArmorVariant.VANGUARD).range() >= 16.0D,
				"Vanguard slash must be a ranged corridor attack");
		require(ArmorWeaponRules.bladeProfile(ArmorVariant.SWIFT).range()
				> ArmorWeaponRules.bladeProfile(ArmorVariant.VANGUARD).range(),
				"Swift slash must retain the longest reach");
		require(ArmorWeaponRules.bladeProfile(ArmorVariant.SENTINEL).halfWidth()
				> ArmorWeaponRules.bladeProfile(ArmorVariant.SWIFT).halfWidth(),
				"Sentinel slash must retain the widest corridor");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
