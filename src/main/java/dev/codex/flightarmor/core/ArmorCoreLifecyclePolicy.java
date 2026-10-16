package dev.codex.flightarmor.core;

/** Pure fail-closed policy for turning a dropped armor part removal into a suit tombstone. */
final class ArmorCoreLifecyclePolicy {
	static final int VANILLA_ITEM_LIFETIME_TICKS = 6000;

	private ArmorCoreLifecyclePolicy() {
	}

	/**
	 * Chunk unload, dimension transfer, pickup, and merge are deliberately excluded by the caller.
	 * A non-empty KILLED entity is definitive; DISCARDED is definitive only at vanilla despawn age.
	 */
	static boolean confirmsDroppedPartDestruction(
			boolean identifiedStackPresent, boolean killed, boolean discarded, int ageTicks) {
		if (!identifiedStackPresent) return false;
		if (killed) return true;
		return discarded && ageTicks >= VANILLA_ITEM_LIFETIME_TICKS;
	}
}
