package dev.codex.flightarmor.sound;

import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.variant.ArmorVariant;

/**
 * Pure, deterministic mixer for the layered armor-flight sound field.
 *
 * <p>The curve intentionally has no input for Ctrl, dive, or key state. It
 * reacts only to realized displacement speed and realized acceleration, so a
 * requested boost cannot make the sound jump ahead of the armor.</p>
 */
public final class ArmorFlightSoundMix {
	/** Shared with HUD/effects: 150 metres per second under the two-metres-per-block scale. */
	public static final double SONIC_SPEED_BLOCKS_PER_TICK =
			FlightPhysics.APPROXIMATE_SOUND_SPEED_BLOCKS_PER_TICK;

	private ArmorFlightSoundMix() {
	}

	public static Mix sample(boolean flightActive, boolean standbyCore,
			double speedBlocksPerTick, double accelerationBlocksPerTickPerTick,
			double underwaterBlend, double cruiseReferenceSpeed,
			double maximumReferenceSpeed, ArmorVariant variant) {
		ArmorVariant safeVariant = variant == null ? ArmorVariant.DEFAULT : variant;
		double speed = finiteNonNegative(speedBlocksPerTick);
		double acceleration = finite(accelerationBlocksPerTickPerTick);
		double water = clamp01(underwaterBlend);
		double cruiseReference = Math.max(0.08D, finiteNonNegative(cruiseReferenceSpeed));
		double maximumReference = Math.max(cruiseReference + 0.01D,
				finiteNonNegative(maximumReferenceSpeed));

		Timbre timbre = timbre(safeVariant);
		double performance = smoothstep(0.04D, maximumReference, speed);
		double cruise = smoothstep(0.035D, cruiseReference, speed);
		double sonic = smoothstep(SONIC_SPEED_BLOCKS_PER_TICK * 0.44D,
				SONIC_SPEED_BLOCKS_PER_TICK, speed);
		double supersonic = smoothstep(SONIC_SPEED_BLOCKS_PER_TICK,
				Math.max(SONIC_SPEED_BLOCKS_PER_TICK + 0.20D, cruiseReference * 2.4D), speed);
		// The propulsion load reaches an audible working range before Mach 1. It is
		// deliberately referenced to real-world Mach speed rather than a 18-26 block/tick
		// hard cap that normal play almost never reaches.
		double driveLoad = smoothstep(Math.min(cruiseReference * 0.30D,
						SONIC_SPEED_BLOCKS_PER_TICK * 0.12D),
				SONIC_SPEED_BLOCKS_PER_TICK * 0.92D, speed);
		double pressureLoad = smoothstep(SONIC_SPEED_BLOCKS_PER_TICK * 0.62D,
				SONIC_SPEED_BLOCKS_PER_TICK * 1.18D, speed);
		// Acceleration is measured from consecutive realized-speed samples. The
		// envelope is deliberately small: it adds load texture but never becomes a
		// separate "boost layer" or a key-driven transient.
		double accelerationLoad = smoothstep(0.0008D, 0.028D, Math.max(0.0D, acceleration));

		double core;
		double hum;
		double servo;
		double drive;
		double pressure;
		double air;
		double waterHover;
		double waterField;
		double waterDrive;
		if (flightActive) {
			core = 0.105D + driveLoad * 0.035D;
			hum = 0.36D - cruise * 0.08D + accelerationLoad * 0.025D;
			servo = 0.075D + cruise * 0.27D + accelerationLoad * 0.055D;
			drive = driveLoad * 0.23D + accelerationLoad * 0.12D;
			pressure = pressureLoad * 0.18D + supersonic * 0.10D;
			air = sonic * 0.21D + supersonic * 0.15D + performance * 0.08D
					+ accelerationLoad * 0.035D;
			waterHover = water * (0.24D - driveLoad * 0.07D);
			waterField = water * (0.07D + cruise * 0.15D);
			waterDrive = water * (driveLoad * 0.19D + pressureLoad * 0.17D);
		} else if (standbyCore) {
			core = 0.060D;
			hum = 0.048D;
			servo = 0.0D;
			drive = 0.0D;
			pressure = 0.0D;
			air = 0.0D;
			waterHover = 0.0D;
			waterField = 0.0D;
			waterDrive = 0.0D;
		} else {
			core = 0.0D;
			hum = 0.0D;
			servo = 0.0D;
			drive = 0.0D;
			pressure = 0.0D;
			air = 0.0D;
			waterHover = 0.0D;
			waterField = 0.0D;
			waterDrive = 0.0D;
		}

		// The variants change spectral balance, then receive a common loudness
		// normalization. They stay recognizable without one suit simply being
		// louder than another.
		double uncoloredTotal = hum + servo + drive + pressure + air;
		hum *= timbre.humWeight;
		servo *= timbre.servoWeight;
		drive *= timbre.servoWeight;
		pressure *= timbre.airWeight;
		air *= timbre.airWeight;
		double coloredTotal = hum + servo + drive + pressure + air;
		if (coloredTotal > 1.0E-8D && uncoloredTotal > 0.0D) {
			double loudnessGain = uncoloredTotal / coloredTotal;
			hum *= loudnessGain;
			servo *= loudnessGain;
			drive *= loudnessGain;
			pressure *= loudnessGain;
			air *= loudnessGain;
		}

		// Dry layers crossfade into purpose-built underwater motors instead of merely
		// pitching the same air recording down.
		hum *= lerp(1.0D, 0.30D, water);
		servo *= lerp(1.0D, 0.18D, water);
		drive *= lerp(1.0D, 0.12D, water);
		pressure *= lerp(1.0D, 0.04D, water);
		air *= lerp(1.0D, 0.025D, water);

		double corePitch = 0.90D + timbre.pitchOffset * 0.45D
				+ driveLoad * 0.055D + accelerationLoad * 0.018D - water * 0.055D;
		double humPitch = 0.935D + timbre.pitchOffset + performance * 0.018D
				+ accelerationLoad * 0.008D - water * 0.075D;
		double servoPitch = 0.955D + timbre.pitchOffset + cruise * 0.045D
				+ accelerationLoad * 0.018D - water * 0.105D;
		double drivePitch = 0.80D + timbre.pitchOffset + driveLoad * 0.24D
				+ accelerationLoad * 0.085D - water * 0.12D;
		double pressurePitch = 0.84D + timbre.pitchOffset + pressureLoad * 0.16D
				+ supersonic * 0.07D - water * 0.14D;
		double airPitch = 0.895D + timbre.pitchOffset + sonic * 0.055D
				+ supersonic * 0.045D - water * 0.155D;
		double waterPitch = 0.77D + driveLoad * 0.13D + accelerationLoad * 0.04D;

		return new Mix(clamp01(core), clamp01(hum), clamp01(servo),
				clamp01(drive), clamp01(pressure), clamp01(air),
				clamp01(waterHover), clamp01(waterField), clamp01(waterDrive),
				clamp(corePitch, 0.68D, 1.14D),
				clamp(humPitch, 0.72D, 1.16D),
				clamp(servoPitch, 0.72D, 1.18D),
				clamp(drivePitch, 0.66D, 1.22D),
				clamp(pressurePitch, 0.66D, 1.22D),
				clamp(airPitch, 0.68D, 1.20D),
				clamp(waterPitch, 0.62D, 1.08D),
				clamp01(accelerationLoad), clamp01(driveLoad), clamp01(sonic));
	}

	private static Timbre timbre(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> new Timbre(1.00D, 1.05D, 0.95D, 0.0D);
			case SWIFT -> new Timbre(0.86D, 0.92D, 1.12D, 0.045D);
			case SENTINEL -> new Timbre(1.12D, 1.08D, 0.76D, -0.045D);
		};
	}

	private static double smoothstep(double edge0, double edge1, double value) {
		if (edge1 <= edge0) return value >= edge1 ? 1.0D : 0.0D;
		double t = clamp01((value - edge0) / (edge1 - edge0));
		return t * t * (3.0D - 2.0D * t);
	}

	private static double lerp(double from, double to, double progress) {
		return from + (to - from) * progress;
	}

	private static double finiteNonNegative(double value) {
		return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
	}

	private static double finite(double value) {
		return Double.isFinite(value) ? value : 0.0D;
	}

	private static double clamp01(double value) {
		return clamp(value, 0.0D, 1.0D);
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	public record Mix(double coreVolume, double humVolume, double servoVolume,
			double driveVolume, double pressureVolume, double airVolume,
			double waterHoverVolume, double waterFieldVolume, double waterDriveVolume,
			double corePitch, double humPitch, double servoPitch,
			double drivePitch, double pressurePitch, double airPitch, double waterPitch,
			double accelerationLoad, double driveLoad, double sonicProgress) {
		public double continuousLoudness() {
			return coreVolume + humVolume + servoVolume + driveVolume
					+ pressureVolume + airVolume
					+ waterHoverVolume + waterFieldVolume + waterDriveVolume;
		}
	}

	private record Timbre(double humWeight, double servoWeight, double airWeight,
			double pitchOffset) {
	}
}
