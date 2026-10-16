package dev.codex.flightarmor.flight;

/**
 * Pure policy for high-speed, low-altitude environmental feedback.
 *
 * <p>The policy deliberately has no input-mode parameter. Ctrl, dive, and
 * direction keys can only affect the result after they have changed realized
 * displacement speed. Keeping this class free of Minecraft types also makes
 * the trigger curve and its hard performance budgets testable offline.</p>
 */
public final class LowAltitudeFeedbackModel {
	public static final double MINIMUM_SPEED_BLOCKS_PER_TICK = 0.32D;
	public static final double FULL_SPEED_BLOCKS_PER_TICK = 1.20D;
	public static final double MINIMUM_CLEARANCE_BLOCKS = 0.20D;
	public static final double MAXIMUM_CLEARANCE_BLOCKS = 10.0D;
	public static final int SAMPLE_INTERVAL_TICKS = 2;
	public static final int SAMPLE_LIFETIME_TICKS = 14;
	public static final int MAX_ACTIVE_RIGS = 6;
	public static final int MAX_SAMPLES_PER_RIG = 8;
	public static final int FULL_VERTEX_BUDGET = 3072;
	public static final int FAST_VERTEX_BUDGET = 1536;
	public static final double MAX_RENDER_DISTANCE_BLOCKS = 72.0D;
	public static final double FIRST_PERSON_SAFE_RADIUS_BLOCKS = 4.0D;

	private static final Sample INACTIVE = new Sample(false, 0.0D, 0.0D,
			0.0D, 0.0D, 0.0D, SoundModifier.NEUTRAL);

	private LowAltitudeFeedbackModel() {
	}

	public static Sample sample(boolean clientEnabled, boolean flightActive,
			boolean submerged, double realizedSpeedBlocksPerTick,
			double clearanceBlocks) {
		if (!clientEnabled || !flightActive || submerged) return INACTIVE;
		double speed = finiteNonNegative(realizedSpeedBlocksPerTick);
		double clearance = finiteNonNegative(clearanceBlocks);
		if (speed < MINIMUM_SPEED_BLOCKS_PER_TICK
				|| clearance < MINIMUM_CLEARANCE_BLOCKS
				|| clearance > MAXIMUM_CLEARANCE_BLOCKS) {
			return INACTIVE;
		}

		double speedProgress = smoothstep(MINIMUM_SPEED_BLOCKS_PER_TICK,
				FULL_SPEED_BLOCKS_PER_TICK, speed);
		// Full ground coupling below 1.25 blocks, then a smooth ten-block falloff.
		double proximity = 1.0D - smoothstep(1.25D,
				MAXIMUM_CLEARANCE_BLOCKS, clearance);
		double intensity = clamp01(speedProgress * proximity);
		if (intensity <= 0.002D) return INACTIVE;

		double wakeLength = lerp(3.2D, 13.5D, intensity);
		double wakeHalfWidth = lerp(0.55D, 2.35D, intensity);
		double disturbanceRadius = lerp(0.70D, 3.80D, intensity);
		SoundModifier sound = new SoundModifier(
				1.0D + intensity * 0.11D,
				1.0D + intensity * 0.26D,
				-intensity * 0.008D,
				intensity * 0.035D);
		return new Sample(true, intensity, speedProgress, wakeLength,
				wakeHalfWidth, disturbanceRadius, sound);
	}

	private static double smoothstep(double edge0, double edge1, double value) {
		if (edge1 <= edge0) return value >= edge1 ? 1.0D : 0.0D;
		double progress = clamp01((value - edge0) / (edge1 - edge0));
		return progress * progress * (3.0D - 2.0D * progress);
	}

	private static double lerp(double from, double to, double progress) {
		return from + (to - from) * clamp01(progress);
	}

	private static double finiteNonNegative(double value) {
		return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	public record Sample(boolean active, double intensity, double speedProgress,
			double wakeLength, double wakeHalfWidth, double disturbanceRadius,
			SoundModifier sound) {
	}

	/** Multipliers/offsets applied to the existing servo and air loops. */
	public record SoundModifier(double servoVolumeMultiplier,
			double airVolumeMultiplier, double servoPitchOffset,
			double airPitchOffset) {
		public static final SoundModifier NEUTRAL = new SoundModifier(1.0D,
				1.0D, 0.0D, 0.0D);
	}
}
