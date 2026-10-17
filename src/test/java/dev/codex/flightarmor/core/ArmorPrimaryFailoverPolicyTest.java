package dev.codex.flightarmor.core;

import dev.codex.flightarmor.runtime.SuitRuntimeSnapshot;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Offline coverage for deterministic, non-flapping primary armor failover. */
public final class ArmorPrimaryFailoverPolicyTest {
	private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID CORE = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID PRIMARY = UUID.fromString("10000000-0000-0000-0000-000000000003");
	private static final UUID PLATFORM = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID CARRIER = UUID.fromString("10000000-0000-0000-0000-000000000005");
	private static final UUID WORN = UUID.fromString("10000000-0000-0000-0000-000000000006");

	private ArmorPrimaryFailoverPolicyTest() {
	}

	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		confirmedMissingPrimaryPromotesWornBeforeCarrierAndPlatform();
		carrierOutranksPlatformWhenNoWornSuitExists();
		offlineUnloadedPrimaryDoesNotFailOver();
		knownLoosePrimaryDoesNotFailOver();
		healthyPrimaryDoesNotFailOver();
		unsafeAndTransitioningCandidatesAreSkipped();
		activeTransactionSuppressesRoleMutation();
		missingPrimaryReferenceCanRecoverToHealthySuit();
		bKeyKeepsRecallablePrimary();
		bKeyPromotesRecallableSuitWhenPrimaryCannotBeRecalled();
	}

	private static void confirmedMissingPrimaryPromotesWornBeforeCarrierAndPlatform() {
		SuitRuntimeSnapshot snapshot = snapshot(PRIMARY,
				missing(PRIMARY), platform(PLATFORM), carrier(CARRIER), worn(WORN));
		require(ArmorPrimaryFailoverPolicy.selectReplacement(snapshot).orElseThrow().equals(WORN),
				"a complete physically worn suit must be the first failover choice");
	}

	private static void carrierOutranksPlatformWhenNoWornSuitExists() {
		SuitRuntimeSnapshot snapshot = snapshot(PRIMARY,
				missing(PRIMARY), platform(PLATFORM), carrier(CARRIER));
		require(ArmorPrimaryFailoverPolicy.selectReplacement(snapshot).orElseThrow().equals(CARRIER),
				"a loaded stable carrier must outrank a platform suit");
	}

	private static void offlineUnloadedPrimaryDoesNotFailOver() {
		SuitRuntimeSnapshot.SuitState offline = suit(PRIMARY,
				SuitRuntimeSnapshot.Location.UNKNOWN, SuitRuntimeSnapshot.CarrierKind.NONE,
				SuitRuntimeSnapshot.Phase.IDLE, SuitRuntimeSnapshot.HomeHealth.UNLOADED,
				Level.OVERWORLD, new BlockPos(20, 70, 20), Set.of(), Set.of(), null);
		require(ArmorPrimaryFailoverPolicy.selectReplacement(
				snapshot(PRIMARY, offline, worn(WORN))).isEmpty(),
				"an unloaded remote primary is not confirmed lost and must retain its role");
	}

	private static void knownLoosePrimaryDoesNotFailOver() {
		SuitRuntimeSnapshot.SuitState loose = suit(PRIMARY,
				SuitRuntimeSnapshot.Location.LOOSE, SuitRuntimeSnapshot.CarrierKind.NONE,
				SuitRuntimeSnapshot.Phase.IDLE, SuitRuntimeSnapshot.HomeHealth.UNBOUND,
				Level.OVERWORLD, new BlockPos(6, 65, 6),
				Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_FORGET_MISSING), Set.of(), null);
		require(ArmorPrimaryFailoverPolicy.selectReplacement(
				snapshot(PRIMARY, loose, worn(WORN))).isEmpty(),
				"a primary with a known loose-item position must not be silently demoted");
	}

	private static void healthyPrimaryDoesNotFailOver() {
		require(ArmorPrimaryFailoverPolicy.selectReplacement(
				snapshot(PRIMARY, worn(PRIMARY), carrier(CARRIER))).isEmpty(),
				"a healthy primary must never be replaced");
	}

	private static void unsafeAndTransitioningCandidatesAreSkipped() {
		SuitRuntimeSnapshot.SuitState unsafeWorn = suit(WORN,
				SuitRuntimeSnapshot.Location.WORN, SuitRuntimeSnapshot.CarrierKind.NONE,
				SuitRuntimeSnapshot.Phase.IDLE, SuitRuntimeSnapshot.HomeHealth.UNBOUND,
				Level.OVERWORLD, new BlockPos(0, 64, 0), Set.of(),
				Set.of(SuitRuntimeSnapshot.Conflict.IDENTITY_MISMATCH), null);
		SuitRuntimeSnapshot.SuitState transitioningCarrier = suit(CARRIER,
				SuitRuntimeSnapshot.Location.CARRIER, SuitRuntimeSnapshot.CarrierKind.ESCORT,
				SuitRuntimeSnapshot.Phase.RECALLING_OWNER, SuitRuntimeSnapshot.HomeHealth.UNLOADED,
				Level.OVERWORLD, new BlockPos(8, 68, 8), Set.of(), Set.of(), null);
		require(ArmorPrimaryFailoverPolicy.selectReplacement(snapshot(PRIMARY,
				missing(PRIMARY), unsafeWorn, transitioningCarrier, platform(PLATFORM)))
				.orElseThrow().equals(PLATFORM),
				"unsafe or moving suits must be skipped during failover");
	}

	private static void activeTransactionSuppressesRoleMutation() {
		SuitRuntimeSnapshot.SuitState transactional = suit(WORN,
				SuitRuntimeSnapshot.Location.WORN, SuitRuntimeSnapshot.CarrierKind.NONE,
				SuitRuntimeSnapshot.Phase.IDLE, SuitRuntimeSnapshot.HomeHealth.UNBOUND,
				Level.OVERWORLD, new BlockPos(0, 64, 0), Set.of(), Set.of(), PRIMARY);
		require(ArmorPrimaryFailoverPolicy.selectReplacement(
				snapshot(PRIMARY, missing(PRIMARY), transactional)).isEmpty(),
				"an active physical transfer must suppress primary role mutation");
	}

	private static void missingPrimaryReferenceCanRecoverToHealthySuit() {
		require(ArmorPrimaryFailoverPolicy.selectReplacement(
				snapshot(PRIMARY, platform(PLATFORM))).orElseThrow().equals(PLATFORM),
				"a stale primary id absent from linked runtime states must recover deterministically");
	}

	private static void bKeyKeepsRecallablePrimary() {
		require(ArmorPrimaryFailoverPolicy.selectRecallablePrimaryForB(
				snapshot(PRIMARY, platform(PRIMARY), carrier(CARRIER))).isEmpty(),
				"B must retain a primary that can already be recalled");
	}
	private static void bKeyPromotesRecallableSuitWhenPrimaryCannotBeRecalled() {
		require(ArmorPrimaryFailoverPolicy.selectRecallablePrimaryForB(
				snapshot(PRIMARY, missing(PRIMARY), platform(PLATFORM), carrier(CARRIER)))
				.orElseThrow().equals(CARRIER),
				"B must promote and recall the best available suit in the same press");
	}
	private static SuitRuntimeSnapshot.SuitState missing(UUID id) {
		return suit(id, SuitRuntimeSnapshot.Location.UNKNOWN, SuitRuntimeSnapshot.CarrierKind.NONE,
				SuitRuntimeSnapshot.Phase.IDLE, SuitRuntimeSnapshot.HomeHealth.VALID_EMPTY_RESERVED,
				null, null, Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_FORGET_MISSING), Set.of(), null);
	}

	private static SuitRuntimeSnapshot.SuitState worn(UUID id) {
		return suit(id, SuitRuntimeSnapshot.Location.WORN, SuitRuntimeSnapshot.CarrierKind.NONE,
				SuitRuntimeSnapshot.Phase.IDLE, SuitRuntimeSnapshot.HomeHealth.UNBOUND,
				Level.OVERWORLD, new BlockPos(0, 64, 0), Set.of(), Set.of(), null);
	}

	private static SuitRuntimeSnapshot.SuitState carrier(UUID id) {
		return suit(id, SuitRuntimeSnapshot.Location.CARRIER, SuitRuntimeSnapshot.CarrierKind.ESCORT,
				SuitRuntimeSnapshot.Phase.GUARDING, SuitRuntimeSnapshot.HomeHealth.UNLOADED,
				Level.OVERWORLD, new BlockPos(8, 68, 8),
				Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR), Set.of(), null);
	}

	private static SuitRuntimeSnapshot.SuitState platform(UUID id) {
		return suit(id, SuitRuntimeSnapshot.Location.PLATFORM, SuitRuntimeSnapshot.CarrierKind.NONE,
				SuitRuntimeSnapshot.Phase.IDLE, SuitRuntimeSnapshot.HomeHealth.VALID_OCCUPIED,
				Level.OVERWORLD, new BlockPos(16, 64, 16),
				Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR), Set.of(), null);
	}

	private static SuitRuntimeSnapshot snapshot(UUID primary,
			SuitRuntimeSnapshot.SuitState... suits) {
		return new SuitRuntimeSnapshot(1L, OWNER, true, CORE, primary, List.of(suits), Set.of());
	}

	private static SuitRuntimeSnapshot.SuitState suit(UUID id,
			SuitRuntimeSnapshot.Location location, SuitRuntimeSnapshot.CarrierKind carrierKind,
			SuitRuntimeSnapshot.Phase phase, SuitRuntimeSnapshot.HomeHealth homeHealth,
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> currentDimension,
			BlockPos currentPosition, Set<SuitRuntimeSnapshot.AllowedAction> allowed,
			Set<SuitRuntimeSnapshot.Conflict> conflicts, UUID transactionTarget) {
		return new SuitRuntimeSnapshot.SuitState(id, OWNER, CORE, UUID.randomUUID(),
				"SUIT-" + id.toString().substring(0, 8), ArmorVariant.DEFAULT,
				location, carrierKind, id.equals(PRIMARY)
						? SuitRuntimeSnapshot.Role.PRIMARY : SuitRuntimeSnapshot.Role.ESCORT,
				phase, homeHealth, Level.OVERWORLD, new BlockPos(16, 64, 16),
				currentDimension, currentPosition, ArmorEscortProfile.defaults(), allowed, conflicts,
				transactionTarget, transactionTarget == null ? 0L : 1L,
				transactionTarget == null ? 0L : 2L, transactionTarget == null ? 0L : 100L);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}