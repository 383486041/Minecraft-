package dev.codex.flightarmor.core;

/** Offline coverage for destructive ItemEntity lifecycle evidence. */
public final class ArmorCoreLifecyclePolicyTest {
	private ArmorCoreLifecyclePolicyTest() {
	}

	public static void main(String[] args) {
		require(ArmorCoreLifecyclePolicy.confirmsDroppedPartDestruction(
				true, true, false, 1), "killed identified drop was not tombstoned");
		require(ArmorCoreLifecyclePolicy.confirmsDroppedPartDestruction(
				true, false, true, 6000), "vanilla despawn was not tombstoned");
		require(!ArmorCoreLifecyclePolicy.confirmsDroppedPartDestruction(
				true, false, true, 5999), "ordinary discard/pickup was treated as destruction");
		require(!ArmorCoreLifecyclePolicy.confirmsDroppedPartDestruction(
				false, true, false, 6000), "empty or unidentified entity created a tombstone");
		require(!ArmorCoreLifecyclePolicy.confirmsDroppedPartDestruction(
				true, false, false, Integer.MAX_VALUE), "chunk unload was treated as destruction");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
