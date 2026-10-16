package dev.codex.flightarmor.sound;

import dev.codex.flightarmor.variant.ArmorVariant;

/** Offline regression checks for realized-speed mixing and one-shot edges. */
public final class ArmorFlightSoundMixTest {
	private ArmorFlightSoundMixTest() {
	}

	public static void main(String[] args) {
		checkRealizedSpeedCrossfade();
		checkAccelerationIsContinuousLoadNotAKeyLayer();
		checkUnderwaterLowPassApproximation();
		checkVariantLoudnessNormalization();
		checkStartupEdgesAreSingleShot();
		System.out.println("ArmorFlightSoundMixTest passed");
	}

	private static void checkRealizedSpeedCrossfade() {
		ArmorFlightSoundMix.Mix hover = mix(0.0D, 0.0D, 0.0D, ArmorVariant.VANGUARD);
		ArmorFlightSoundMix.Mix cruise = mix(0.42D, 0.0D, 0.0D, ArmorVariant.VANGUARD);
		ArmorFlightSoundMix.Mix sonic = mix(
				ArmorFlightSoundMix.SONIC_SPEED_BLOCKS_PER_TICK,
				0.0D, 0.0D, ArmorVariant.VANGUARD);
		ArmorFlightSoundMix.Mix fast = mix(4.0D, 0.0D, 0.0D, ArmorVariant.VANGUARD);
		require(hover.humVolume() > hover.servoVolume(), "hover must be low-frequency dominant");
		require(cruise.servoVolume() > hover.servoVolume(), "cruise must expose more mechanical servo");
		require(sonic.driveVolume() > cruise.driveVolume() + 0.10D,
				"real speed must audibly load the propulsion layer before Mach 1");
		require(sonic.pressureVolume() > cruise.pressureVolume(),
				"transonic flight must add a structural pressure layer");
		require(sonic.airVolume() > hover.airVolume() + 0.15D,
				"Mach 1 must bring in the air-pressure layer");
		require(fast.airVolume() > sonic.airVolume(), "supersonic speed must continue strengthening air");
	}

	private static void checkAccelerationIsContinuousLoadNotAKeyLayer() {
		ArmorFlightSoundMix.Mix steady = mix(0.42D, 0.0D, 0.0D, ArmorVariant.SWIFT);
		ArmorFlightSoundMix.Mix lightAcceleration = mix(0.42D, 0.004D, 0.0D, ArmorVariant.SWIFT);
		ArmorFlightSoundMix.Mix strongAcceleration = mix(0.42D, 0.020D, 0.0D, ArmorVariant.SWIFT);
		require(lightAcceleration.accelerationLoad() > steady.accelerationLoad(),
				"real acceleration must begin loading the mix");
		require(strongAcceleration.accelerationLoad() > lightAcceleration.accelerationLoad(),
				"acceleration load must remain continuous");
		require(strongAcceleration.airVolume() - steady.airVolume() < 0.08D,
				"acceleration texture must not become an instant second engine layer");
	}

	private static void checkUnderwaterLowPassApproximation() {
		ArmorFlightSoundMix.Mix air = mix(0.8D, 0.01D, 0.0D, ArmorVariant.VANGUARD);
		ArmorFlightSoundMix.Mix water = mix(0.8D, 0.01D, 1.0D, ArmorVariant.VANGUARD);
		require(water.waterHoverVolume() > 0.15D && water.waterDriveVolume() > 0.0D,
				"water must use dedicated submerged motor layers");
		require(water.humVolume() < air.humVolume() * 0.40D,
				"dry hover recording must crossfade out underwater");
		require(water.airVolume() < air.airVolume() * 0.12D,
				"water should strongly suppress high-frequency air");
		require(water.airPitch() < air.airPitch() && water.servoPitch() < air.servoPitch(),
				"water should lower high and mid layers as a low-pass approximation");
	}

	private static void checkVariantLoudnessNormalization() {
		double vanguard = ArmorFlightSoundMix.sample(true, true, 0.8D, 0.006D, 0.0D,
				0.42D, 18.0D, ArmorVariant.VANGUARD).continuousLoudness();
		double swift = ArmorFlightSoundMix.sample(true, true, 0.8D, 0.006D, 0.0D,
				0.42D, 18.0D, ArmorVariant.SWIFT).continuousLoudness();
		double sentinel = ArmorFlightSoundMix.sample(true, true, 0.8D, 0.006D, 0.0D,
				0.42D, 18.0D, ArmorVariant.SENTINEL).continuousLoudness();
		require(Math.abs(vanguard - swift) < 1.0E-6D
				&& Math.abs(vanguard - sentinel) < 1.0E-6D,
				"variant timbres must share one loudness target");
	}

	private static void checkStartupEdgesAreSingleShot() {
		ArmorSoundEdgeGate local = new ArmorSoundEdgeGate(true);
		require(local.observe(true) == ArmorSoundEdgeGate.Edge.START,
				"local mid-flight initialization should receive one startup cue");
		require(local.observe(true) == ArmorSoundEdgeGate.Edge.NONE,
				"unchanged ticks must never replay startup");
		require(local.observe(false) == ArmorSoundEdgeGate.Edge.STOP,
				"flight stop should be a single edge");
		require(local.observe(false) == ArmorSoundEdgeGate.Edge.NONE,
				"stopped ticks must not replay shutdown");
		require(local.observe(true) == ArmorSoundEdgeGate.Edge.START,
				"a later genuine start should produce one new edge");
		ArmorSoundEdgeGate remote = new ArmorSoundEdgeGate(false);
		require(remote.observe(true) == ArmorSoundEdgeGate.Edge.NONE,
				"a remote player entering view mid-flight must not replay old startup");
	}

	private static ArmorFlightSoundMix.Mix mix(double speed, double acceleration,
			double water, ArmorVariant variant) {
		return ArmorFlightSoundMix.sample(true, true, speed, acceleration, water,
				variant.fullFlightProfile().cruise().targetSpeed(),
				variant.fullFlightProfile().dive().targetSpeed(), variant);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
