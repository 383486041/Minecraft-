package dev.codex.flightarmor.flight;

import dev.codex.flightarmor.configuration.ArmorConfigurationData;
import dev.codex.flightarmor.configuration.SentinelArmorMode;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.VariantFlightProfile;

/** Pure tuning and state-transition checks for Sentinel's two flight authorities. */
public final class SentinelFlightProfilesTest {
	private SentinelFlightProfilesTest() {
	}

	public static void main(String[] args) {
		overdriveIsActuallyHypersonic();
		overdriveUsesAContinuousLongRamp();
		highSpeedThrottleUsesWSCtrlWithoutPresetJumps();
		breachPreservesTheOriginalHeavyProfile();
		transitionLocksDeploymentAndBrakesWithoutAOneTickCap();
		nonSentinelProfilesRemainUntouched();
		System.out.println("SentinelFlightProfilesTest passed");
	}

	private static void overdriveIsActuallyHypersonic() {
		VariantFlightProfile profile = SentinelFlightProfiles.OVERDRIVE_FLIGHT;
		require(profile.dive().targetSpeed() >= 30.0D
						&& profile.dive().targetSpeed() <= 36.0D,
				"overdrive high-speed target left the requested 30..36 block/tick band");
		require(profile.dive().hardSpeedCap() >= profile.dive().targetSpeed()
						&& profile.dive().hardSpeedCap() <= 64.0D,
				"overdrive hard cap is unsafe");
		require(profile.boost().hardSpeedCap()
						> FlightPhysics.APPROXIMATE_SOUND_SPEED_BLOCKS_PER_TICK,
				"Ctrl thrust can no longer cross the 150 m/s sonic threshold");
	}

	private static void overdriveUsesAContinuousLongRamp() {
		VariantFlightProfile.Mode highSpeed =
				SentinelFlightProfiles.OVERDRIVE_FLIGHT.dive();
		require(highSpeed.accelerationSeconds() >= 14.0D,
				"overdrive reverted to an instant or short acceleration ramp");
		double nominalStep = highSpeed.targetSpeed()
				/ (highSpeed.accelerationSeconds() * FlightPhysics.TICKS_PER_SECOND);
		require(nominalStep > 0.0D && nominalStep < 0.15D,
				"nominal W throttle step must remain continuous");
		double ctrlStep = nominalStep * 1.70D;
		require(ctrlStep > nominalStep && ctrlStep < highSpeed.targetSpeed(),
				"Ctrl must strengthen acceleration rather than select a preset speed");
	}

	private static void highSpeedThrottleUsesWSCtrlWithoutPresetJumps() {
		VariantFlightProfile.Mode tuning =
				SentinelFlightProfiles.OVERDRIVE_FLIGHT.dive();
		double idle = HighSpeedThrottle.advance(0.0D,
				false, false, false, tuning);
		double w = HighSpeedThrottle.advance(0.0D,
				true, false, false, tuning);
		double ctrlW = HighSpeedThrottle.advance(0.0D,
				true, false, true, tuning);
		require(idle == 0.0D && w > 0.0D && ctrlW > w,
				"W must raise throttle and Ctrl must only strengthen that ramp");
		require(ctrlW < tuning.targetSpeed() * 0.01D,
				"one Ctrl+W tick jumped to a preset high speed");

		double command = 0.0D;
		int ticksToTarget = 0;
		while (command < tuning.targetSpeed() - 1.0E-9D && ticksToTarget < 2000) {
			command = HighSpeedThrottle.advance(command,
					true, false, true, tuning);
			ticksToTarget++;
		}
		require(ticksToTarget >= 200 && ticksToTarget <= 280,
				"Ctrl+W command ramp left the intended roughly 10..14 second window: "
						+ ticksToTarget + " ticks");
		double braked = HighSpeedThrottle.advance(command,
				false, true, false, tuning);
		require(braked < command,
				"S must reduce commanded high-speed throttle");
	}

	private static void breachPreservesTheOriginalHeavyProfile() {
		require(SentinelFlightProfiles.BREACH_MELEE
						== ArmorVariant.SENTINEL.fullFlightProfile(),
				"breach mode stopped using the original Sentinel heavy profile");
		var breach = snapshot(SentinelArmorMode.BREACH_MELEE, 1L, 100L);
		require(SentinelFlightProfiles.resolve(breach, 140L)
						== ArmorVariant.SENTINEL.fullFlightProfile(),
				"settled breach configuration did not resolve to the heavy profile");
	}

	private static void transitionLocksDeploymentAndBrakesWithoutAOneTickCap() {
		var deploying = snapshot(SentinelArmorMode.OVERDRIVE_FLIGHT, 2L, 200L);
		require(SentinelFlightProfiles.resolve(deploying, 200L)
						== SentinelFlightProfiles.BREACH_MELEE,
				"overdrive unlocked before the mechanical deployment completed");
		require(SentinelFlightProfiles.resolve(deploying,
						200L + SentinelFlightProfiles.TRANSITION_DURATION_TICKS)
						== SentinelFlightProfiles.OVERDRIVE_FLIGHT,
				"overdrive stayed locked after deployment completed");

		var folding = snapshot(SentinelArmorMode.BREACH_MELEE, 3L, 300L);
		VariantFlightProfile braking = SentinelFlightProfiles.resolve(folding, 305L);
		require(braking == SentinelFlightProfiles.BRAKING_TO_BREACH,
				"breach transition did not select the deterministic braking profile");
		require(braking.dive().hardSpeedCap()
						== SentinelFlightProfiles.OVERDRIVE_FLIGHT.dive().hardSpeedCap(),
				"breach transition would clamp overdrive velocity in one tick");
		require(braking.dive().targetSpeed()
						== ArmorVariant.SENTINEL.fullFlightProfile().dive().targetSpeed(),
				"breach transition does not command the original heavy target");
	}

	private static void nonSentinelProfilesRemainUntouched() {
		require(ArmorVariant.VANGUARD.fullFlightProfile().dive().targetSpeed() == 18.0D,
				"Vanguard flight profile regressed");
		require(ArmorVariant.SWIFT.fullFlightProfile().dive().targetSpeed() == 24.0D,
				"Swift flight profile regressed");
		require(ArmorVariant.SENTINEL.bootsFlightProfile().dive().targetSpeed() == 7.0D,
				"single Sentinel boots incorrectly inherited overdrive authority");
	}

	private static ArmorConfigurationData.Snapshot snapshot(SentinelArmorMode mode,
			long revision, long startTick) {
		return new ArmorConfigurationData.Snapshot(mode, revision, startTick);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
