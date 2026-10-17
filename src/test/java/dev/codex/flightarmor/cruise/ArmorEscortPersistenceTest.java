package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.platform.ChargingPlatformDisplayMode;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Offline NBT, CAS, safe-phase and duplicate-carrier recovery fault injection. */
public final class ArmorEscortPersistenceTest {
	private ArmorEscortPersistenceTest() {
	}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		verifyNbtRoundTripWithoutItems();
		verifyRevisionCasAndCarrierImmutability();
		verifyRecoveryAdmissionFaults();
		verifySafePhaseDowngrade();
		verifyOptionalHomePolicy();
		System.out.println("ArmorEscortPersistenceTest passed");
	}

	private static void verifyOptionalHomePolicy() {
		UUID platformId = UUID.randomUUID();
		BlockPos pos = new BlockPos(4, 70, -3);
		require(ArmorEscortController.isOptionalHomeComplete(null, null, null),
				"a platformless independent carrier could not become an escort");
		require(ArmorEscortController.isOptionalHomeComplete(platformId, Level.OVERWORLD, pos),
				"a complete escort return home was rejected");
		require(!ArmorEscortController.isOptionalHomeComplete(platformId, null, null),
				"a partial escort home with only platform id was accepted");
		require(!ArmorEscortController.isOptionalHomeComplete(null, Level.OVERWORLD, pos),
				"a partial escort home without platform id was accepted");
		require(!ArmorEscortController.isOptionalHomeComplete(platformId, Level.OVERWORLD, null),
				"a partial escort home without position was accepted");
	}

	private static void verifyNbtRoundTripWithoutItems() {
		ArmorEscortSavedData.Locator source = locator(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				ArmorEscortSavedData.StoredPhase.RETURNING, 7L, 19L, 4, -3, true);
		CompoundTag encoded = ArmorEscortSavedData.writeLocator(source);
		ArmorEscortSavedData.Locator decoded = ArmorEscortSavedData.readLocator(encoded);
		require(decoded.equals(source), "escort locator NBT round-trip failed");
		require(encoded.getIntOr("Version", 0) == 1, "locator schema version missing");
		require(!encoded.toString().contains("Item")
				&& !encoded.toString().contains("Inventory")
				&& !encoded.toString().contains("ArmorItems"),
				"escort SavedData duplicated entity ItemStacks");
		require(decoded.displayMode() == ChargingPlatformDisplayMode.PARTS
				&& decoded.carrierRevision() == 19L,
				"display mode or carrier generation was lost");
	}

	private static void verifyRevisionCasAndCarrierImmutability() {
		ArmorEscortSavedData data = new ArmorEscortSavedData();
		UUID owner = UUID.randomUUID();
		UUID suit = UUID.randomUUID();
		UUID carrier = UUID.randomUUID();
		ArmorEscortSavedData.Locator first = locator(owner, suit, carrier,
				ArmorEscortSavedData.StoredPhase.ESCORTING, 1L, 4L, 0, 0, false);
		require(data.createLocator(first), "initial locator create failed");
		require(!data.createLocator(first), "duplicate create replaced an existing locator");

		ArmorEscortSavedData.Locator moved = locator(owner, suit, carrier,
				ArmorEscortSavedData.StoredPhase.RETURNING, 2L, 5L, 3, 2, true);
		require(!data.compareAndSetLocator(suit, carrier, 0L, moved),
				"stale expected revision committed");
		require(data.compareAndSetLocator(suit, carrier, 1L, moved),
				"current revision CAS failed");
		require(!data.compareAndSetLocator(suit, carrier, 1L,
				locator(owner, suit, carrier, ArmorEscortSavedData.StoredPhase.ESCORTING,
						2L, 6L, 9, 9, false)),
				"equal-revision stale snapshot overwrote the current locator");

		UUID duplicateCarrier = UUID.randomUUID();
		ArmorEscortSavedData.Locator duplicate = locator(owner, suit, duplicateCarrier,
				ArmorEscortSavedData.StoredPhase.ESCORTING, 3L, 6L, 4, 4, false);
		require(!data.compareAndSetLocator(suit, carrier, 2L, duplicate),
				"different physical carrier replaced the indexed UUID");
		require(data.findLocal(suit).equals(moved),
				"failed CAS mutated the durable locator");
	}

	private static void verifyRecoveryAdmissionFaults() {
		require(decide(true, true, true, true, true, true, false, true)
				== EscortRecoveryPolicy.Decision.RESTORE_INDEXED_PASSIVE,
				"exact indexed carrier did not restore");
		require(decide(true, false, true, true, true, true, false, true)
				== EscortRecoveryPolicy.Decision.WAIT_FOR_INDEXED_CARRIER,
				"missing entity should wait instead of creating armor");
		require(decide(true, true, true, true, true, false, false, true)
				== EscortRecoveryPolicy.Decision.REJECT_INDEXED_CARRIER_MISMATCH,
				"wrong carrier UUID was accepted");
		require(decide(true, true, true, true, false, true, false, true)
				== EscortRecoveryPolicy.Decision.REJECT_INCOMPLETE_CARRIER,
				"incomplete carrier was accepted");
		require(decide(true, true, true, true, true, true, true, false)
				== EscortRecoveryPolicy.Decision.REJECT_DUPLICATE_ACTIVE_CARRIER,
				"duplicate active carrier was accepted");
		require(decide(false, true, true, true, true, true, false, true)
				== EscortRecoveryPolicy.Decision.ADOPT_LEGACY_PASSIVE,
				"legacy tagged carrier was not adopted passively");
		require(decide(true, true, true, true, true, true, true, true)
				== EscortRecoveryPolicy.Decision.REUSE_ACTIVE_STATE,
				"same active carrier did not reuse its state");
	}

	private static void verifySafePhaseDowngrade() {
		require(EscortRecoveryPolicy.safePhase(
				ArmorEscortSavedData.StoredPhase.RETURNING, true)
				== EscortRecoveryPolicy.SafePhase.RETURNING,
				"complete return-home state was not resumed");
		require(EscortRecoveryPolicy.safePhase(
				ArmorEscortSavedData.StoredPhase.RETURNING, false)
				== EscortRecoveryPolicy.SafePhase.ESCORTING,
				"return without complete home did not downgrade safely");
		require(EscortRecoveryPolicy.safePhase(
				ArmorEscortSavedData.StoredPhase.ESCORTING, true)
				== EscortRecoveryPolicy.SafePhase.ESCORTING,
				"stable escort phase changed unexpectedly");
	}

	private static EscortRecoveryPolicy.Decision decide(
			boolean index, boolean candidate, boolean owner, boolean suit,
			boolean fullSet, boolean carrier, boolean active, boolean activeCarrier) {
		return EscortRecoveryPolicy.decide(new EscortRecoveryPolicy.Evidence(
				index, candidate, owner, suit, fullSet, carrier, active, activeCarrier));
	}

	private static ArmorEscortSavedData.Locator locator(
			UUID owner, UUID suit, UUID carrier, ArmorEscortSavedData.StoredPhase phase,
			long revision, long carrierRevision, int chunkX, int chunkZ, boolean home) {
		return new ArmorEscortSavedData.Locator(
				owner, suit, carrier, Level.OVERWORLD, chunkX, chunkZ, phase,
				home ? UUID.randomUUID() : null,
				home ? Level.NETHER : null,
				home ? new BlockPos(12, 70, -8) : null,
				home ? ChargingPlatformDisplayMode.PARTS : ChargingPlatformDisplayMode.ASSEMBLED,
				true, revision, carrierRevision);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
