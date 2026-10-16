package dev.codex.flightarmor.sound;

/**
 * Pure bounded Doppler policy driven by radial source/listener separation speed.
 *
 * <p>A positive closing speed means the source and listener moved closer during
 * the last tick. No control state is accepted, so Ctrl can only affect pitch
 * after it has produced real relative motion.</p>
 */
public final class RadialDopplerModel {
	public static final double MIN_FACTOR = 0.88D;
	public static final double MAX_FACTOR = 1.12D;
	public static final double MIN_OUTPUT_PITCH = 0.68D;
	public static final double MAX_OUTPUT_PITCH = 1.28D;
	public static final double REFERENCE_SOUND_SPEED_BLOCKS_PER_TICK =
			ArmorFlightSoundMix.SONIC_SPEED_BLOCKS_PER_TICK;
	public static final double NEAR_FIELD_NEUTRAL_DISTANCE = 2.5D;
	public static final double FULL_EFFECT_DISTANCE = 9.0D;
	public static final double MAX_VALID_RADIAL_DELTA = 128.0D;

	private static final double RESPONSE_ALPHA = 0.18D;
	private static final double RETURN_ALPHA = 0.12D;

	private RadialDopplerModel() {
	}

	public static double projectedClosingSpeed(
			double separationX, double separationY, double separationZ,
			double sourceVelocityX, double sourceVelocityY, double sourceVelocityZ,
			double listenerVelocityX, double listenerVelocityY, double listenerVelocityZ) {
		double separationLengthSquared = separationX * separationX
				+ separationY * separationY + separationZ * separationZ;
		double sourceSpeedSquared = sourceVelocityX * sourceVelocityX
				+ sourceVelocityY * sourceVelocityY + sourceVelocityZ * sourceVelocityZ;
		double listenerSpeedSquared = listenerVelocityX * listenerVelocityX
				+ listenerVelocityY * listenerVelocityY + listenerVelocityZ * listenerVelocityZ;
		if (!allFinite(separationX, separationY, separationZ,
				sourceVelocityX, sourceVelocityY, sourceVelocityZ,
				listenerVelocityX, listenerVelocityY, listenerVelocityZ)
				|| separationLengthSquared < 1.0E-10D
				|| sourceSpeedSquared > MAX_VALID_RADIAL_DELTA * MAX_VALID_RADIAL_DELTA
				|| listenerSpeedSquared > MAX_VALID_RADIAL_DELTA * MAX_VALID_RADIAL_DELTA) {
			return 0.0D;
		}
		double inverseLength = 1.0D / Math.sqrt(separationLengthSquared);
		// Separation points listener -> source. Listener minus source velocity
		// projected onto that axis is positive only while the gap is closing.
		return ((listenerVelocityX - sourceVelocityX) * separationX
				+ (listenerVelocityY - sourceVelocityY) * separationY
				+ (listenerVelocityZ - sourceVelocityZ) * separationZ)
				* inverseLength;
	}

	public static double targetFactor(boolean spatialSource, double distanceBlocks,
			double closingSpeedBlocksPerTick) {
		if (!spatialSource || !Double.isFinite(distanceBlocks)
				|| !Double.isFinite(closingSpeedBlocksPerTick)
				|| distanceBlocks <= NEAR_FIELD_NEUTRAL_DISTANCE
				|| Math.abs(closingSpeedBlocksPerTick) > MAX_VALID_RADIAL_DELTA) {
			return 1.0D;
		}
		double distanceWeight = smoothstep(NEAR_FIELD_NEUTRAL_DISTANCE,
				FULL_EFFECT_DISTANCE, distanceBlocks);
		// tanh remains continuous through Mach 1 and cannot explode when this
		// project's deliberately fantastic flight speeds exceed the reference.
		double radial = Math.tanh(closingSpeedBlocksPerTick
				/ REFERENCE_SOUND_SPEED_BLOCKS_PER_TICK * 0.72D);
		double factor = 1.0D + radial * (MAX_FACTOR - 1.0D) * distanceWeight;
		return clamp(factor, MIN_FACTOR, MAX_FACTOR);
	}

	public static double smoothFactor(double previousFactor, double targetFactor) {
		double previous = finiteOr(previousFactor, 1.0D);
		double target = clamp(finiteOr(targetFactor, 1.0D), MIN_FACTOR, MAX_FACTOR);
		double alpha = Math.abs(target - 1.0D) > Math.abs(previous - 1.0D)
				? RESPONSE_ALPHA : RETURN_ALPHA;
		return clamp(previous + (target - previous) * alpha, MIN_FACTOR, MAX_FACTOR);
	}

	public static double applyToPitch(double basePitch, double factor) {
		double base = finiteOr(basePitch, 1.0D);
		double safeFactor = clamp(finiteOr(factor, 1.0D), MIN_FACTOR, MAX_FACTOR);
		return clamp(base * safeFactor, MIN_OUTPUT_PITCH, MAX_OUTPUT_PITCH);
	}

	private static double smoothstep(double edge0, double edge1, double value) {
		double progress = clamp((value - edge0) / Math.max(1.0E-9D, edge1 - edge0),
				0.0D, 1.0D);
		return progress * progress * (3.0D - 2.0D * progress);
	}

	private static boolean allFinite(double... values) {
		for (double value : values) {
			if (!Double.isFinite(value)) return false;
		}
		return true;
	}

	private static double finiteOr(double value, double fallback) {
		return Double.isFinite(value) ? value : fallback;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
