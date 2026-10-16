package dev.codex.flightarmor.sound;

/** Pure edge latch used to prevent tick/state replays of flight startup audio. */
public final class ArmorSoundEdgeGate {
	private final boolean emitInitialStart;
	private boolean initialized;
	private boolean previousActive;

	public ArmorSoundEdgeGate(boolean emitInitialStart) {
		this.emitInitialStart = emitInitialStart;
	}

	public Edge observe(boolean active) {
		if (!initialized) {
			initialized = true;
			previousActive = active;
			return active && emitInitialStart ? Edge.START : Edge.NONE;
		}
		if (active == previousActive) return Edge.NONE;
		previousActive = active;
		return active ? Edge.START : Edge.STOP;
	}

	public enum Edge {
		NONE,
		START,
		STOP
	}
}
