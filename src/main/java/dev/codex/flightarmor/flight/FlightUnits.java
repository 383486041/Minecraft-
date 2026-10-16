package dev.codex.flightarmor.flight;

/**
 * Canonical gameplay distance and speed conversions.
 *
 * <p>The simulation remains in blocks/tick. Reader-facing telemetry uses the
 * project's explicit scale of one block = two metres.</p>
 */
public final class FlightUnits {
	public static final double TICKS_PER_SECOND = 20.0D;
	public static final double METERS_PER_BLOCK = 2.0D;
	public static final double SECONDS_PER_HOUR = 3600.0D;
	public static final double METERS_PER_KILOMETER = 1000.0D;

	private FlightUnits() {
	}

	public static double blocksPerSecond(double blocksPerTick) {
		return finiteNonNegative(blocksPerTick) * TICKS_PER_SECOND;
	}

	public static double metersPerSecond(double blocksPerTick) {
		return blocksPerSecond(blocksPerTick) * METERS_PER_BLOCK;
	}

	public static double kilometersPerHour(double blocksPerTick) {
		return metersPerSecond(blocksPerTick)
				* SECONDS_PER_HOUR / METERS_PER_KILOMETER;
	}

	private static double finiteNonNegative(double value) {
		return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
	}
}
