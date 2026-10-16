package dev.codex.flightarmor.flight;

import dev.codex.flightarmor.configuration.ArmorConfigurationData;
import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import dev.codex.flightarmor.configuration.SentinelArmorMode;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.VariantFlightProfile;

/**
 * Pure flight-authority table for the two configurations of one complete Sentinel suit.
 *
 * <p>Both integrated client prediction and the authoritative server resolve this table through
 * {@link FlightPhysics#resolveFlightProfile}. No input mode owns a second copy of these values.</p>
 */
public final class SentinelFlightProfiles {
	/**
	 * Shared mechanical transition window. Overdrive cannot unlock before the wings finish
	 * deploying, and combat reads the same authority clock.
	 */
	public static final long TRANSITION_DURATION_TICKS =
			ArmorConfigurationAccess.TRANSITION_DURATION_TICKS;

	/** The original Sentinel profile remains the heavy breach form's authoritative tuning. */
	public static final VariantFlightProfile BREACH_MELEE =
			ArmorVariant.SENTINEL.fullFlightProfile();

	/**
	 * Hypersonic configuration. 36 block/tick is 720 block/s and, at the project's 2 m/block
	 * scale, 1440 m/s. The 18-second nominal ramp is intentionally long: W raises a commanded
	 * throttle continuously while Ctrl increases thrust, never replaces velocity with a preset.
	 */
	public static final VariantFlightProfile OVERDRIVE_FLIGHT = profile(
			mode(0.72D, 1.05D, 2.40D, 1.35D, 260.0D,
					0.34D, -0.72D, 0.62D),
			mode(6.00D, 7.20D, 12.00D, 3.20D, 120.0D,
					0.55D, -2.20D, 1.40D),
			mode(36.00D, 42.00D, 18.00D, 5.20D, 20.0D,
					4.80D, -38.00D, 12.00D),
			0.14D);

	/**
	 * Switching from overdrive into breach must not clamp a 30+ block/tick player to the heavy
	 * form's 13.5 cap in one tick. This transition profile keeps the old absolute safety envelope
	 * but commands the original 12 block/tick breach target and removes the excess over the full
	 * mechanical transition. Once the transition completes, BREACH_MELEE takes over.
	 */
	public static final VariantFlightProfile BRAKING_TO_BREACH = profile(
			mode(0.27D, 1.05D, 1.80D, 0.90D, 180.0D,
					0.18D, -0.48D, 0.36D),
			mode(0.55D, 7.20D, 14.00D, 0.90D, 90.0D,
					0.26D, -0.68D, 0.48D),
			mode(12.00D, 42.00D, 14.00D, 0.55D, 20.0D,
					1.45D, -38.00D, 8.00D),
			0.18D);

	private SentinelFlightProfiles() {
	}

	public static VariantFlightProfile resolve(ArmorConfigurationData.Snapshot configuration,
			long gameTime) {
		ArmorConfigurationData.Snapshot safe = configuration == null
				? new ArmorConfigurationData.Snapshot(SentinelArmorMode.DEFAULT, 0L, 0L)
				: configuration;
		boolean transitioning = ArmorConfigurationAccess.isTransitioning(safe, gameTime);
		if (transitioning) {
			// Flight authority is conservative in both directions: entering overdrive retains the
			// heavy profile until deployment locks; entering breach starts deterministic braking.
			return safe.mode() == SentinelArmorMode.BREACH_MELEE
					? BRAKING_TO_BREACH : BREACH_MELEE;
		}
		return safe.mode() == SentinelArmorMode.BREACH_MELEE
				? BREACH_MELEE : OVERDRIVE_FLIGHT;
	}

	public static boolean isOverdrive(ArmorConfigurationData.Snapshot configuration,
			long gameTime) {
		return resolve(configuration, gameTime) == OVERDRIVE_FLIGHT;
	}

	private static VariantFlightProfile profile(VariantFlightProfile.Mode cruise,
			VariantFlightProfile.Mode boost, VariantFlightProfile.Mode dive,
			double collisionHorizontalRetention) {
		return new VariantFlightProfile(cruise, boost, dive, collisionHorizontalRetention);
	}

	private static VariantFlightProfile.Mode mode(double targetSpeed, double hardSpeedCap,
			double accelerationSeconds, double brakingSeconds, double turnRateDegreesPerSecond,
			double verticalSpeed, double minVerticalSpeed, double maxVerticalSpeed) {
		return new VariantFlightProfile.Mode(targetSpeed, hardSpeedCap,
				accelerationSeconds, brakingSeconds, turnRateDegreesPerSecond,
				verticalSpeed, minVerticalSpeed, maxVerticalSpeed);
	}
}
