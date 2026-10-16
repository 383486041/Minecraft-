package dev.codex.flightarmor.flight;

public final class SonicBoomLatchTest {
	private SonicBoomLatchTest() {
	}

	public static void main(String[] args) {
		SonicBoomLatch latch = new SonicBoomLatch();
		require(!latch.sample(0.99D), "subsonic sample triggered");
		require(latch.sample(1.0D), "Mach-1 crossing did not trigger");
		require(!latch.sample(1.01D), "supersonic hold retriggered");
		require(!latch.sample(0.99D), "transonic jitter rearmed too early");
		require(!latch.sample(0.83D), "hysteresis edge rearmed too early");
		require(!latch.sample(0.82D), "reset sample itself must not trigger");
		require(latch.armed(), "reset threshold did not rearm");
		require(latch.sample(1.04D), "second complete crossing did not trigger");

		latch.reset();
		require(latch.armed(), "explicit reset did not arm");
		require(!latch.sample(Double.NaN), "invalid sample triggered");
		require(!latch.armed(), "invalid displacement did not disarm the latch");
		require(!latch.sample(2.0D), "invalid-frame recovery triggered while still supersonic");
		require(!latch.sample(0.5D), "rearm frame triggered");
		require(latch.sample(1.0D), "clean crossing after recovery did not trigger");

		require(close(FlightPhysics.machForBlocksPerTick(3.75D), 1.0D),
				"150m/s with two metres per block must cross at 3.75 blocks/tick");
		require(SonicBoomVisualProfile.pressureConeLength(1.0D)
						> SonicBoomVisualProfile.pressureConeLength(0.0D),
				"pressure cone must expand after the event");
		require(SonicBoomVisualProfile.firstRingRadius(0.6D)
						> SonicBoomVisualProfile.secondRingRadius(0.6D),
				"the two pressure rings must remain visually distinct");
		require(!SonicBoomVisualProfile.outsideCameraGap(1.0D, true),
				"local first-person geometry entered the camera safety gap");
		require(SonicBoomVisualProfile.outsideCameraGap(3.0D, true),
				"first-person safety gap hid geometry that is safely behind the camera");
		require(close(SonicBoomVisualProfile.condensationOpacity(1.0D), 0.0D),
				"condensation cloud did not fade out by the event lifetime");
	}

	private static boolean close(double actual, double expected) {
		return Math.abs(actual - expected) < 1.0E-9D;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
