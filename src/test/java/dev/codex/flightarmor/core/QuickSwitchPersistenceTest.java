package dev.codex.flightarmor.core;

import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import java.util.UUID;

/** Offline NBT, recovery-phase and stale-tick CAS regression. */
public final class QuickSwitchPersistenceTest {
	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		ArmorCoreSavedData.QuickSwitchRecord inbound = transaction(3L, "INBOUND", "TARGET_FAILED", 2000L);
		CompoundTag encoded = ArmorCoreSavedData.writeQuickSwitch(inbound);
		ArmorCoreSavedData.QuickSwitchRecord decoded = ArmorCoreSavedData.readQuickSwitch(encoded);
		require(decoded.equals(inbound), "quick-switch NBT round-trip failed");
		require(!encoded.toString().contains("Item") && !encoded.toString().contains("Inventory"),
				"SavedData duplicated an ItemStack");
		require(decoded.outgoingBinding().platformId().equals(inbound.outgoingBinding().platformId()),
				"recovery platform reference was lost");

		ArmorCoreSavedData data = new ArmorCoreSavedData();
		require(data.beginQuickSwitch(inbound), "transaction begin failed");
		require(!data.beginQuickSwitch(inbound), "duplicate transaction was accepted");
		ArmorCoreSavedData.QuickSwitchRecord recovery = copy(inbound, 4L, "RECOVERING", "PLAYER_DIED", 2020L);
		require(!data.compareAndSetQuickSwitch(inbound.ownerId(), inbound.transactionId(), 2L, recovery),
				"stale phase CAS committed");
		require(data.compareAndSetQuickSwitch(inbound.ownerId(), inbound.transactionId(), 3L, recovery),
				"current phase CAS failed");
		require(!data.removeQuickSwitch(inbound.ownerId(), inbound.transactionId(), 3L),
				"stale completion deleted recovery state");
		ArmorCoreSavedData.QuickSwitchRecord restored = data.quickSwitch(inbound.ownerId()).orElseThrow();
		require(restored.phase().equals("RECOVERING") && restored.recoveryCause().equals("PLAYER_DIED")
				&& restored.nextRecoveryAttemptTick() == 2020L, "recovery phase did not restore");
		require(data.removeQuickSwitch(inbound.ownerId(), inbound.transactionId(), 4L),
				"current completion failed");
	}

	private static ArmorCoreSavedData.QuickSwitchRecord transaction(long rev, String phase, String cause, long retry) {
		UUID suit = UUID.randomUUID();
		ArmorCoreSavedData.PlatformBinding platform = new ArmorCoreSavedData.PlatformBinding(
				suit, UUID.randomUUID(), Level.OVERWORLD, new BlockPos(8, 70, -4), ArmorVariant.DEFAULT, "OUT");
		return new ArmorCoreSavedData.QuickSwitchRecord(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), suit, platform, UUID.randomUUID(), "TARGET",
				"ESCORT", phase, 1000L, 1120L, cause, retry, true, false, true, false, "waiting", rev);
	}

	private static ArmorCoreSavedData.QuickSwitchRecord copy(ArmorCoreSavedData.QuickSwitchRecord s,
			long rev, String phase, String cause, long retry) {
		return new ArmorCoreSavedData.QuickSwitchRecord(s.transactionId(), s.ownerId(), s.coreId(),
				s.coreNonce(), s.outgoingSuitId(), s.outgoingBinding(), s.targetSuitId(), s.targetName(),
				s.targetSource(), phase, s.startGameTime(), s.phaseStartGameTime(), cause, retry,
				s.outgoingDeployed(), s.targetCancellationComplete(), s.recoveryRequestAnnounced(),
				s.recoveryWaitingAnnounced(), s.lastRecoveryDetail(), rev);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}