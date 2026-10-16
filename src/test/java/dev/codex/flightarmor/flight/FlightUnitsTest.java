package dev.codex.flightarmor.flight;

public final class FlightUnitsTest {
	private FlightUnitsTest() {
	}

	public static void main(String[] args) {
		require(close(FlightUnits.metersPerSecond(1.0D), 40.0D),
				"one block/tick must equal forty metres/second");
		require(close(FlightUnits.kilometersPerHour(1.0D), 144.0D),
				"one block/tick must equal 144 km/h");
		require(close(FlightPhysics.machForBlocksPerTick(3.75D), 1.0D),
				"one hundred and fifty metres per second must be the configured gameplay Mach threshold");
		System.out.println("FlightUnitsTest passed");
	}

	private static boolean close(double actual, double expected) {
		return Math.abs(actual - expected) < 1.0E-9D;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
