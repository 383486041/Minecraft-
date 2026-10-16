package dev.codex.flightarmor.core;

import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight state test that runs without launching Minecraft.
 * It protects the core A/B switching, legacy adoption and NBT network payload.
 */
public final class ArmorCoreNetworkStateTest {
	private ArmorCoreNetworkStateTest() {
	}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		verifyIndependentNetworksAndRoundTrip();
		verifyLegacyOrphanAdoption();
		verifyCrossNetworkConflictRemoval();
		verifyRefreshDoesNotMigrateAcrossCores();
		verifySuitAssociationDoesNotRequirePlatform();
		verifySuitOwnershipMigratesBetweenCores();
		verifyPlatformlessOnlineSuitRemainsCoreMember();
		verifyUnequipAndReequipPreserveNetwork();
		verifyActiveCoreRemovalUsesIdentityCasAndLeavesInactiveNetworks();
		verifyConfirmedDestructionRemovesEveryNetworkReference();
		verifyUnknownDestructionIsANoOp();
	}

	private static void verifyIndependentNetworksAndRoundTrip() {
		UUID owner = UUID.randomUUID();
		ArmorCoreSavedData.NetworkKey coreA =
				new ArmorCoreSavedData.NetworkKey(UUID.randomUUID(), UUID.randomUUID());
		ArmorCoreSavedData.NetworkKey coreB =
				new ArmorCoreSavedData.NetworkKey(UUID.randomUUID(), UUID.randomUUID());
		ArmorCoreSavedData.CoreRecord record = new ArmorCoreSavedData.CoreRecord(owner);

		record.activate(coreA);
		ArmorCoreSavedData.PlatformBinding bindingA = binding(1, "A");
		ArmorEscortProfile profileA = new ArmorEscortProfile(
				ArmorEscortProfile.CombatPolicy.ALL, 48, ArmorEscortProfile.EscortAnchor.HOLD,
				Level.OVERWORLD, new BlockPos(10, 70, 10));
		record.bindings.put(bindingA.suitId(), bindingA);
		record.escortProfiles.put(bindingA.suitId(), profileA);
		record.primarySuitId = bindingA.suitId();
		record.syncPrimary();

		record.activate(coreB);
		require(record.bindings.isEmpty(), "A bindings leaked into new core B");
		ArmorCoreSavedData.PlatformBinding bindingB = binding(2, "B");
		record.bindings.put(bindingB.suitId(), bindingB);
		record.primarySuitId = bindingB.suitId();
		record.syncPrimary();

		record.activate(coreA);
		require(record.bindings.equals(java.util.Map.of(bindingA.suitId(), bindingA)),
				"core A binding was not restored after A/B/A switch");
		require(profileA.equals(record.escortProfiles.get(bindingA.suitId())),
				"core A escort profile was not restored");
		require(bindingA.suitId().equals(record.primarySuitId), "core A primary suit was not restored");

		ArmorCoreSavedData.NetworkRecord source = record.networks.get(coreA);
		CompoundTag encoded = ArmorCoreSavedData.writeNetwork(coreA, source);
		require(encoded.getUUID("Core").equals(coreA.coreId()), "serialized network lost core id");
		require(encoded.getUUID("Nonce").equals(coreA.nonce()), "serialized network lost nonce");
		ArmorCoreSavedData.NetworkRecord decoded = ArmorCoreSavedData.readNetwork(encoded);
		require(decoded.bindings.equals(source.bindings), "network binding NBT round-trip failed");
		require(decoded.escortProfiles.equals(source.escortProfiles), "network profile NBT round-trip failed");
		require(decoded.primarySuitId.equals(source.primarySuitId), "network primary NBT round-trip failed");
	}

	private static void verifyLegacyOrphanAdoption() {
		ArmorCoreSavedData.CoreRecord record =
				new ArmorCoreSavedData.CoreRecord(UUID.randomUUID());
		ArmorCoreSavedData.PlatformBinding oldBinding = binding(3, "LEGACY");
		ArmorCoreSavedData.NetworkRecord legacy = new ArmorCoreSavedData.NetworkRecord();
		legacy.bindings.put(oldBinding.suitId(), oldBinding);
		legacy.primarySuitId = oldBinding.suitId();
		record.loadLegacy(legacy);
		require(record.activeKey() == null, "orphaned v2 network invented an identity");

		ArmorCoreSavedData.NetworkKey firstCore =
				new ArmorCoreSavedData.NetworkKey(UUID.randomUUID(), UUID.randomUUID());
		record.activate(firstCore);
		require(record.bindings.containsKey(oldBinding.suitId()),
				"first valid core did not adopt orphaned v2 network");
		require(record.orphanedLegacyNetwork == null, "legacy orphan remained duplicated after adoption");
	}

	private static void verifyCrossNetworkConflictRemoval() {
		ArmorCoreSavedData.NetworkRecord inactive = new ArmorCoreSavedData.NetworkRecord();
		ArmorCoreSavedData.PlatformBinding oldBinding = binding(4, "OLD");
		inactive.bindings.put(oldBinding.suitId(), oldBinding);
		inactive.primarySuitId = oldBinding.suitId();
		inactive.escortProfiles.put(oldBinding.suitId(), ArmorEscortProfile.defaults());

		ArmorCoreSavedData.PlatformBinding moved = new ArmorCoreSavedData.PlatformBinding(
				UUID.randomUUID(), oldBinding.platformId(), Level.OVERWORLD,
				new BlockPos(30, 64, 0), ArmorVariant.DEFAULT, "MOVED");
		require(inactive.removeConflicts(moved), "platform migration was not detected");
		require(inactive.bindings.isEmpty(), "old core retained a platform migrated to another core");
		require(inactive.escortProfiles.isEmpty(), "old escort profile survived platform migration");
		require(inactive.primarySuitId == null, "old primary survived platform migration");
	}

	private static void verifyRefreshDoesNotMigrateAcrossCores() {
		UUID owner = UUID.randomUUID();
		ArmorCoreSavedData data = new ArmorCoreSavedData();
		ArmorCoreSavedData.CoreRecord record = data.record(owner);
		ArmorCoreSavedData.NetworkKey coreA =
				new ArmorCoreSavedData.NetworkKey(UUID.randomUUID(), UUID.randomUUID());
		ArmorCoreSavedData.NetworkKey coreB =
				new ArmorCoreSavedData.NetworkKey(UUID.randomUUID(), UUID.randomUUID());
		record.activate(coreA);
		ArmorCoreSavedData.PlatformBinding original = binding(5, "A-OLD");
		record.bindings.put(original.suitId(), original);
		record.primarySuitId = original.suitId();
		record.syncPrimary();

		record.activate(coreB);
		require(record.bindings.isEmpty(), "test setup leaked core A into core B");
		ArmorCoreSavedData.PlatformBinding refreshed = new ArmorCoreSavedData.PlatformBinding(
				original.suitId(), original.platformId(), Level.OVERWORLD,
				new BlockPos(55, 70, 9), ArmorVariant.VANGUARD, "A-REFRESHED");
		require(data.refreshBoundPlatform(owner, refreshed), "bound platform metadata was not refreshed");
		require(record.bindings.isEmpty(), "platform refresh silently migrated into active core B");

		record.activate(coreA);
		require(refreshed.equals(record.bindings.get(original.suitId())),
				"platform refresh did not update the original core network");
		require(ArmorCoreSavedData.MAX_BINDINGS_PER_CORE >= 2,
				"binding limit no longer permits a primary and escort");
	}

	private static void verifySuitAssociationDoesNotRequirePlatform() {
		UUID owner = UUID.randomUUID();
		UUID suitId = UUID.randomUUID();
		ArmorCoreSavedData data = new ArmorCoreSavedData();
		ItemStack core = new ItemStack(Items.STICK);
		ArmorCoreIdentity.Identity identity = ArmorCoreIdentity.claim(core, owner).orElseThrow();
		data.setCore(owner, core);
		ArmorCoreSavedData.CoreRecord record = data.record(owner);

		require(data.associateSuit(owner, identity, suitId),
				"worn suit was not associated with the active core");
		require(record.linkedSuitIds.contains(suitId),
				"active core did not retain the physical suit identity");
		require(suitId.equals(record.primarySuitId),
				"newly worn suit did not become the active core primary");
		require(record.bindings.isEmpty(),
				"associating a worn suit fabricated a charging-platform binding");
		ArmorEscortProfile unboundProfile = new ArmorEscortProfile(
				ArmorEscortProfile.CombatPolicy.ALL, 64,
				ArmorEscortProfile.EscortAnchor.FOLLOW, null, null);
		require(data.setEscortProfile(owner, identity, suitId, unboundProfile),
				"platform-independent suit rejected its core combat profile");
		require(unboundProfile.equals(data.escortProfile(owner, suitId)),
				"platform-independent suit profile was hidden by the missing optional home");

		ArmorCoreSavedData.NetworkRecord source = record.networks.get(record.activeKey());
		CompoundTag encoded = ArmorCoreSavedData.writeNetwork(record.activeKey(), source);
		ArmorCoreSavedData.NetworkRecord decoded = ArmorCoreSavedData.readNetwork(encoded);
		require(decoded.linkedSuitIds.contains(suitId),
				"platform-independent suit identity was lost during NBT round-trip");
		require(decoded.bindings.isEmpty(),
				"platform-independent suit acquired a phantom binding after NBT round-trip");
		require(unboundProfile.equals(decoded.escortProfiles.get(suitId)),
				"platform-independent suit profile was lost during NBT round-trip");
	}

	private static void verifySuitOwnershipMigratesBetweenCores() {
		UUID owner = UUID.randomUUID();
		UUID suitId = UUID.randomUUID();
		ArmorCoreSavedData data = new ArmorCoreSavedData();
		ItemStack coreA = new ItemStack(Items.STICK);
		ArmorCoreIdentity.Identity identityA = ArmorCoreIdentity.claim(coreA, owner).orElseThrow();
		data.setCore(owner, coreA);
		require(data.associateSuit(owner, identityA, suitId), "core A did not accept the suit");

		ArmorCoreSavedData.CoreRecord record = data.record(owner);
		ArmorCoreSavedData.NetworkKey keyA = record.activeKey();
		ArmorCoreSavedData.PlatformBinding home = new ArmorCoreSavedData.PlatformBinding(
				suitId, UUID.randomUUID(), Level.OVERWORLD, new BlockPos(12, 70, 4),
				ArmorVariant.SWIFT, "MIGRATING");
		record.bindings.put(suitId, home);
		ArmorEscortProfile profile = new ArmorEscortProfile(
				ArmorEscortProfile.CombatPolicy.HOSTILE, 64,
				ArmorEscortProfile.EscortAnchor.FOLLOW, null, null);
		record.escortProfiles.put(suitId, profile);

		ItemStack coreB = new ItemStack(Items.BLAZE_ROD);
		ArmorCoreIdentity.Identity identityB = ArmorCoreIdentity.claim(coreB, owner).orElseThrow();
		data.setCore(owner, coreB);
		require(data.associateSuit(owner, identityB, suitId), "core B did not take ownership of the suit");

		ArmorCoreSavedData.NetworkRecord oldNetwork = record.networks.get(keyA);
		require(!oldNetwork.linkedSuitIds.contains(suitId),
				"one physical suit remained linked to both core networks");
		require(!oldNetwork.bindings.containsKey(suitId),
				"platform home remained duplicated on the old core");
		require(!oldNetwork.escortProfiles.containsKey(suitId),
				"escort profile remained duplicated on the old core");
		require(record.linkedSuitIds.contains(suitId), "new core lost the migrated suit identity");
		require(home.equals(record.bindings.get(suitId)), "platform home did not follow the physical suit");
		require(profile.equals(record.escortProfiles.get(suitId)), "escort profile did not follow the physical suit");

		UUID configuredPrimary = UUID.randomUUID();
		record.linkedSuitIds.add(configuredPrimary);
		record.primarySuitId = configuredPrimary;
		record.syncPrimary();
		require(data.associateSuit(owner, identityB, suitId), "repeat association was rejected");
		require(configuredPrimary.equals(record.primarySuitId),
				"automatic worn reconciliation overwrote the configured primary suit");
	}

	/**
	 * A platform binding is an optional home, not proof of core membership.
	 * Worn and independent-cruise suits can be online without any platform and
	 * must still remain selectable by the active core.
	 */
	private static void verifyPlatformlessOnlineSuitRemainsCoreMember() {
		UUID owner = UUID.randomUUID();
		UUID wornSuit = UUID.randomUUID();
		UUID onlineCruiseSuit = UUID.randomUUID();
		ArmorCoreSavedData data = new ArmorCoreSavedData();
		ItemStack core = new ItemStack(Items.STICK);
		ArmorCoreIdentity.Identity identity = ArmorCoreIdentity.claim(core, owner).orElseThrow();
		data.setCore(owner, core);
		ArmorCoreSavedData.CoreRecord record = data.record(owner);

		require(data.associateSuit(owner, identity, wornSuit),
				"platformless worn suit was not linked to the active core");
		require(data.associateSuit(owner, identity, onlineCruiseSuit),
				"platformless online cruise suit was not linked to the active core");
		require(record.bindings.isEmpty(),
				"online/worn association fabricated a platform home");
		require(record.linkedSuitIds.containsAll(java.util.Set.of(wornSuit, onlineCruiseSuit)),
				"active core membership lost a platformless online suit");
		require(data.setPrimary(owner, identity, onlineCruiseSuit),
				"selected online suit was rejected merely because it has no platform binding");
		require(onlineCruiseSuit.equals(record.primarySuitId),
				"selected platformless online suit did not become primary");
		require(!data.setPrimary(owner, identity, UUID.randomUUID()),
				"unlinked suit was accepted into the active core");
		ArmorEscortProfile unboundWornProfile = new ArmorEscortProfile(
				ArmorEscortProfile.CombatPolicy.OFF, 24,
				ArmorEscortProfile.EscortAnchor.FOLLOW, null, null);
		require(data.setEscortProfile(owner, identity, wornSuit, unboundWornProfile),
				"unbound worn suit rejected its per-suit profile");

		ArmorCoreSavedData.NetworkKey activeKey = record.activeKey();
		CompoundTag encoded = ArmorCoreSavedData.writeNetwork(
				activeKey, record.networks.get(activeKey));
		ArmorCoreSavedData.NetworkRecord decoded = ArmorCoreSavedData.readNetwork(encoded);
		require(decoded.linkedSuitIds.containsAll(java.util.Set.of(wornSuit, onlineCruiseSuit)),
				"platformless online membership was lost across NBT restart");
		require(decoded.bindings.isEmpty(),
				"NBT round-trip invented a platform for an online suit");

		ArmorCoreSavedData.PlatformBinding laterHome = new ArmorCoreSavedData.PlatformBinding(
				onlineCruiseSuit, UUID.randomUUID(), Level.OVERWORLD,
				new BlockPos(22, 72, -6), ArmorVariant.VANGUARD, "ONLINE-HOME");
		require(data.bind(owner, identity, laterHome),
				"adding an optional home to an online suit failed");
		require(record.linkedSuitIds.size() == 2
						&& record.linkedSuitIds.contains(onlineCruiseSuit),
				"adding a home duplicated or removed online core membership");
		require(unboundWornProfile.equals(data.escortProfile(owner, wornSuit)),
				"binding another suit's optional home erased the unbound worn suit profile");
	}

	private static void verifyUnequipAndReequipPreserveNetwork() {
		UUID owner = UUID.randomUUID();
		UUID suitId = UUID.randomUUID();
		ArmorCoreSavedData data = new ArmorCoreSavedData();
		ItemStack core = new ItemStack(Items.STICK);
		ArmorCoreIdentity.Identity identity = ArmorCoreIdentity.claim(core, owner).orElseThrow();
		data.setCore(owner, core);
		require(data.associateSuit(owner, identity, suitId),
				"test core could not link its suit");
		ArmorCoreSavedData.PlatformBinding home = new ArmorCoreSavedData.PlatformBinding(
				suitId, UUID.randomUUID(), Level.OVERWORLD,
				new BlockPos(-18, 68, 27), ArmorVariant.SENTINEL, "PERSISTENT");
		require(data.bind(owner, identity, home), "test core could not bind its platform");
		ArmorCoreSavedData.CoreRecord record = data.record(owner);
		ArmorCoreSavedData.NetworkKey key = record.activeKey();

		data.setCore(owner, ItemStack.EMPTY);
		require(record.core.isEmpty(),
				"unequipping did not clear the equipment slot");
		require(key.equals(record.activeKey()),
				"unequipping erased the cached network selector needed for safe re-equip");
		require(!record.matches(identity),
				"cached network identity authorized commands without the physical core equipped");
		require(record.networks.containsKey(key),
				"taking the core off deleted its saved network");
		require(ArmorCoreIdentity.read(core).orElseThrow().equals(identity),
				"taking the core off cleared the physical core identity");

		data.setCore(owner, core);
		require(key.equals(record.activeKey()),
				"putting the same core back selected a different network");
		require(record.linkedSuitIds.contains(suitId),
				"putting the core back lost its suit membership");
		require(home.equals(record.bindings.get(suitId)),
				"putting the core back lost its platform home");
	}

	private static void verifyActiveCoreRemovalUsesIdentityCasAndLeavesInactiveNetworks() {
		UUID owner = UUID.randomUUID();
		UUID removedSuit = UUID.randomUUID();
		ArmorCoreSavedData data = new ArmorCoreSavedData();

		ItemStack coreA = new ItemStack(Items.STICK);
		ArmorCoreIdentity.Identity identityA = ArmorCoreIdentity.claim(coreA, owner).orElseThrow();
		data.setCore(owner, coreA);
		ArmorCoreSavedData.CoreRecord record = data.record(owner);
		ArmorCoreSavedData.NetworkKey inactiveKey = record.activeKey();
		UUID inactiveFallback = UUID.randomUUID();
		ArmorCoreSavedData.NetworkRecord inactive = record.networks.get(inactiveKey);
		populateForDestruction(inactive, removedSuit, inactiveFallback, 20);

		ItemStack coreB = new ItemStack(Items.BLAZE_ROD);
		ArmorCoreIdentity.Identity identityB = ArmorCoreIdentity.claim(coreB, owner).orElseThrow();
		data.setCore(owner, coreB);
		ArmorCoreSavedData.NetworkKey activeKey = record.activeKey();
		UUID activeFallback = UUID.randomUUID();
		ArmorCoreSavedData.NetworkRecord active = record.networks.get(activeKey);
		populateForDestruction(active, removedSuit, activeFallback, 30);
		record.attach(active);

		ArmorCoreSavedData.NetworkRecord orphan = new ArmorCoreSavedData.NetworkRecord();
		UUID orphanFallback = UUID.randomUUID();
		populateForDestruction(orphan, removedSuit, orphanFallback, 35);
		record.orphanedLegacyNetwork = orphan;

		int recordCount = recordCount(data);
		require(!data.removeSuitFromActiveCore(UUID.randomUUID(), identityB, removedSuit),
				"active-core removal accepted an unknown owner");
		require(recordCount(data) == recordCount,
				"active-core removal created a record for an unknown owner");
		require(!data.removeSuitFromActiveCore(owner, identityA, removedSuit),
				"stale core identity passed the active-network compare-and-set guard");
		ArmorCoreIdentity.Identity staleNonce = new ArmorCoreIdentity.Identity(
				identityB.coreId(), owner, UUID.randomUUID());
		require(!data.removeSuitFromActiveCore(owner, staleNonce, removedSuit),
				"stale core nonce passed the active-network compare-and-set guard");
		require(active.linkedSuitIds.contains(removedSuit)
						&& inactive.linkedSuitIds.contains(removedSuit),
				"failed active-core CAS mutated a network");

		require(data.removeSuitFromActiveCore(owner, identityB, removedSuit),
				"current core identity could not unlink its active suit");
		assertSuitRemoved(active, removedSuit, activeFallback, "active core membership edit");
		require(activeFallback.equals(record.primarySuitId),
				"active-core membership edit did not refresh the attached primary");
		assertSuitRetained(inactive, removedSuit, inactiveFallback, "inactive core");
		assertSuitRetained(orphan, removedSuit, orphanFallback, "orphaned legacy core");
		require(!data.removeSuitFromActiveCore(owner, identityB, removedSuit),
				"active-core removal accepted a suit no longer linked to the active network");
	}

	private static void verifyConfirmedDestructionRemovesEveryNetworkReference() {
		UUID owner = UUID.randomUUID();
		UUID destroyedSuit = UUID.randomUUID();
		ArmorCoreSavedData data = new ArmorCoreSavedData();
		ArmorCoreSavedData.CoreRecord record = data.record(owner);
		ArmorCoreSavedData.NetworkKey inactiveKey =
				new ArmorCoreSavedData.NetworkKey(UUID.randomUUID(), UUID.randomUUID());
		ArmorCoreSavedData.NetworkKey activeKey =
				new ArmorCoreSavedData.NetworkKey(UUID.randomUUID(), UUID.randomUUID());

		record.activate(inactiveKey);
		UUID inactiveFallback = UUID.randomUUID();
		populateForDestruction(record.networks.get(inactiveKey), destroyedSuit, inactiveFallback, 40);

		record.activate(activeKey);
		UUID activeFallback = UUID.randomUUID();
		ArmorCoreSavedData.NetworkRecord active = record.networks.get(activeKey);
		populateForDestruction(active, destroyedSuit, activeFallback, 50);
		record.attach(active);

		UUID orphanFallback = UUID.randomUUID();
		ArmorCoreSavedData.NetworkRecord orphan = new ArmorCoreSavedData.NetworkRecord();
		populateForDestruction(orphan, destroyedSuit, orphanFallback, 60);
		record.orphanedLegacyNetwork = orphan;

		require(data.removeConfirmedDestroyedSuit(owner, destroyedSuit),
				"confirmed destruction did not report persisted cleanup");
		assertSuitRemoved(record.networks.get(inactiveKey), destroyedSuit, inactiveFallback,
				"inactive core");
		assertSuitRemoved(active, destroyedSuit, activeFallback, "active core");
		assertSuitRemoved(orphan, destroyedSuit, orphanFallback, "orphaned legacy core");
		require(record.linkedSuitIds == active.linkedSuitIds
						&& record.bindings == active.bindings
						&& record.escortProfiles == active.escortProfiles,
				"cleanup detached the owner's active network view");
		require(activeFallback.equals(record.primarySuitId),
				"owner's active primary view retained the destroyed suit");
		require(!data.removeConfirmedDestroyedSuit(owner, destroyedSuit),
				"repeated destruction cleanup reported a second mutation");
	}

	private static void verifyUnknownDestructionIsANoOp() {
		UUID owner = UUID.randomUUID();
		UUID existingSuit = UUID.randomUUID();
		UUID unknownSuit = UUID.randomUUID();
		ArmorCoreSavedData data = new ArmorCoreSavedData();
		ArmorCoreSavedData.CoreRecord record = data.record(owner);
		ArmorCoreSavedData.NetworkKey key =
				new ArmorCoreSavedData.NetworkKey(UUID.randomUUID(), UUID.randomUUID());
		record.activate(key);
		ArmorCoreSavedData.NetworkRecord network = record.networks.get(key);
		ArmorCoreSavedData.PlatformBinding binding = bindingForSuit(existingSuit, 70, "EXISTING");
		network.linkedSuitIds.add(existingSuit);
		network.bindings.put(existingSuit, binding);
		network.escortProfiles.put(existingSuit, ArmorEscortProfile.defaults());
		network.primarySuitId = existingSuit;
		record.attach(network);

		int recordCount = recordCount(data);
		require(!data.removeConfirmedDestroyedSuit(UUID.randomUUID(), existingSuit),
				"unknown owner was reported as modified");
		require(recordCount(data) == recordCount,
				"confirmed-destruction cleanup created a record for an unknown owner");
		require(!data.removeConfirmedDestroyedSuit(owner, unknownSuit),
				"unknown suit was reported as modified");
		require(network.linkedSuitIds.equals(java.util.Set.of(existingSuit))
						&& network.bindings.equals(java.util.Map.of(existingSuit, binding))
						&& network.escortProfiles.equals(
								java.util.Map.of(existingSuit, ArmorEscortProfile.defaults()))
						&& existingSuit.equals(network.primarySuitId),
				"unknown suit cleanup modified the owner's existing network");
		require(!data.removeConfirmedDestroyedSuit(null, existingSuit)
						&& !data.removeConfirmedDestroyedSuit(owner, null),
				"incomplete destruction identity was accepted");
	}

	private static void populateForDestruction(ArmorCoreSavedData.NetworkRecord network,
			UUID destroyedSuit, UUID fallbackSuit, int x) {
		network.linkedSuitIds.add(destroyedSuit);
		network.linkedSuitIds.add(fallbackSuit);
		network.bindings.put(destroyedSuit,
				bindingForSuit(destroyedSuit, x, "DESTROYED-" + x));
		network.bindings.put(fallbackSuit,
				bindingForSuit(fallbackSuit, x + 1, "SURVIVOR-" + x));
		network.escortProfiles.put(destroyedSuit, ArmorEscortProfile.defaults());
		network.escortProfiles.put(fallbackSuit, ArmorEscortProfile.defaults());
		network.primarySuitId = destroyedSuit;
	}

	private static void assertSuitRemoved(ArmorCoreSavedData.NetworkRecord network,
			UUID destroyedSuit, UUID expectedPrimary, String networkName) {
		require(!network.linkedSuitIds.contains(destroyedSuit),
				networkName + " retained destroyed membership");
		require(!network.bindings.containsKey(destroyedSuit),
				networkName + " retained destroyed platform binding");
		require(!network.escortProfiles.containsKey(destroyedSuit),
				networkName + " retained destroyed escort profile");
		require(network.linkedSuitIds.equals(java.util.Set.of(expectedPrimary))
						&& network.bindings.containsKey(expectedPrimary)
						&& network.escortProfiles.containsKey(expectedPrimary),
				networkName + " damaged the surviving suit associations");
		require(expectedPrimary.equals(network.primarySuitId),
				networkName + " did not normalize its primary");
	}

	private static void assertSuitRetained(ArmorCoreSavedData.NetworkRecord network,
			UUID suitId, UUID otherSuitId, String networkName) {
		require(network.linkedSuitIds.equals(java.util.Set.of(suitId, otherSuitId)),
				networkName + " membership was changed by an active-only removal");
		require(network.bindings.containsKey(suitId)
						&& network.escortProfiles.containsKey(suitId)
						&& suitId.equals(network.primarySuitId),
				networkName + " associations were changed by an active-only removal");
	}

	private static ArmorCoreSavedData.PlatformBinding bindingForSuit(
			UUID suitId, int x, String name) {
		return new ArmorCoreSavedData.PlatformBinding(
				suitId, UUID.randomUUID(), Level.OVERWORLD,
				new BlockPos(x, 64, 0), ArmorVariant.DEFAULT, name);
	}

	private static int recordCount(ArmorCoreSavedData data) {
		try {
			Field field = ArmorCoreSavedData.class.getDeclaredField("records");
			field.setAccessible(true);
			return ((Map<?, ?>) field.get(data)).size();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect owner records", exception);
		}
	}

	private static ArmorCoreSavedData.PlatformBinding binding(int x, String name) {
		return new ArmorCoreSavedData.PlatformBinding(
				UUID.randomUUID(), UUID.randomUUID(), Level.OVERWORLD,
				new BlockPos(x, 64, 0), ArmorVariant.DEFAULT, name);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
