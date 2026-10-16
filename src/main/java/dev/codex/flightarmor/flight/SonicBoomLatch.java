package dev.codex.flightarmor.flight;

/**
 * One-shot Mach crossing gate. The lower reset threshold prevents a speed value hovering around
 * Mach 1 from producing an explosion every tick.
 */
public final class SonicBoomLatch {
	public static final double TRIGGER_MACH = 1.0D;
	public static final double RESET_MACH = 0.82D;
	private boolean armed = true;

	public boolean sample(double mach) {
		if (!Double.isFinite(mach) || mach < 0.0D) {
			armed = false;
			return false;
		}
		if (!armed) {
			if (mach <= RESET_MACH) armed = true;
			return false;
		}
		if (mach < TRIGGER_MACH) return false;
		armed = false;
		return true;
	}

	public void reset() {
		armed = true;
	}

	public boolean armed() {
		return armed;
	}
}
