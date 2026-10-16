package dev.codex.flightarmor.flight;

/**
 * Pure-math timing and geometry envelope for the client sonic-pressure effect.
 * Keeping this independent from Minecraft makes the first-person safety gap,
 * lifetime and expansion curves directly testable.
 */
public final class SonicBoomVisualProfile {
	public static final double LIFETIME_TICKS = 18.0D;
	public static final double MAX_RENDER_DISTANCE_BLOCKS = 144.0D;
	public static final double FIRST_PERSON_CAMERA_GAP_BLOCKS = 2.35D;
	public static final double THIRD_PERSON_CAMERA_GAP_BLOCKS = 0.08D;

	private SonicBoomVisualProfile() {
	}

	public static double progress(double ageTicks) {
		if (!Double.isFinite(ageTicks)) return 1.0D;
		return clamp(ageTicks / LIFETIME_TICKS, 0.0D, 1.0D);
	}

	public static double pressureConeLength(double progress) {
		double eased = easeOutCubic(clamp(progress, 0.0D, 1.0D));
		return 2.2D + eased * 8.8D;
	}

	public static double pressureConeRadius(double axialDistance, double mach) {
		double distance = Math.max(0.0D, finiteOrZero(axialDistance));
		double boundedMach = clamp(finiteOrZero(mach), 1.0D, 2.4D);
		// A slightly narrower cone at higher Mach keeps the silhouette readable.
		double slope = 0.38D - (boundedMach - 1.0D) * 0.045D;
		return 0.24D + distance * slope;
	}

	public static double firstRingRadius(double progress) {
		double p = easeOutCubic(clamp(progress, 0.0D, 1.0D));
		return 0.82D + p * 5.8D;
	}

	public static double secondRingRadius(double progress) {
		double p = easeOutCubic(clamp(progress, 0.0D, 1.0D));
		return 0.54D + p * 4.55D;
	}

	public static double condensationOpacity(double progress) {
		double p = clamp(progress, 0.0D, 1.0D);
		double fadeIn = smoothstep(0.0D, 0.13D, p);
		double fadeOut = 1.0D - smoothstep(0.42D, 1.0D, p);
		return clamp(fadeIn * fadeOut, 0.0D, 1.0D);
	}

	public static boolean outsideCameraGap(double distanceToCamera, boolean localFirstPerson) {
		double gap = localFirstPerson
				? FIRST_PERSON_CAMERA_GAP_BLOCKS : THIRD_PERSON_CAMERA_GAP_BLOCKS;
		return Double.isFinite(distanceToCamera) && distanceToCamera >= gap;
	}

	private static double smoothstep(double edge0, double edge1, double value) {
		double width = edge1 - edge0;
		if (width <= 1.0E-9D) return value >= edge1 ? 1.0D : 0.0D;
		double t = clamp((value - edge0) / width, 0.0D, 1.0D);
		return t * t * (3.0D - 2.0D * t);
	}

	private static double easeOutCubic(double value) {
		double remaining = 1.0D - value;
		return 1.0D - remaining * remaining * remaining;
	}

	private static double finiteOrZero(double value) {
		return Double.isFinite(value) ? value : 0.0D;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
