package dev.codex.flightarmor.sound;

import dev.codex.flightarmor.cruise.ArmorCruisePhase;

/** Pure retention policy for client cruise phase-edge sound observations. */
public final class CruiseSoundObservationRetention {
	public static final long MISSING_PHASING_GRACE_TICKS =
			ArmorCruisePhase.PHASING_DURATION_TICKS + 2L;
	public static final long MISSING_OWNER_DOWN_GRACE_TICKS = 40L;

	private CruiseSoundObservationRetention() {
	}

	public static boolean shouldRetain(boolean stateStillPresent,
			boolean carrierPresent, ArmorCruisePhase phase, long phaseAgeTicks) {
		if (!stateStillPresent || phase == null) return false;
		if (carrierPresent) return true;
		long age = Math.max(0L, phaseAgeTicks);
		return switch (phase) {
			// Carrier UUID migration is an intentional hole in the middle of this
			// short phase; retain only for its bounded protocol duration.
			case PHASING -> age <= MISSING_PHASING_GRACE_TICKS;
			// Preserve a brief death/respawn edge, but never keep a vanished suit
			// observation for the rest of the client session.
			case OWNER_DOWN -> age <= MISSING_OWNER_DOWN_GRACE_TICKS;
			case IDLE, CRUISING, RECALLING, DEPLOYING, DOCKING -> false;
		};
	}
}
