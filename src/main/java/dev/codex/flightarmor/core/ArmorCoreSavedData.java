package dev.codex.flightarmor.core;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.util.TagUtils;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 玩家独立核心槽及核心到多个平台的服务器权威索引。 */
final class ArmorCoreSavedData extends SavedData {
	static final int MAX_BINDINGS_PER_CORE = 8;
	private static final String DATA_NAME = "flight_armor_core_network";
	private static final HolderLookup.Provider DEFAULT_LOOKUP = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	static final SavedDataType<ArmorCoreSavedData> TYPE = new SavedDataType<>(
			FlightArmorMod.id(DATA_NAME),
			ArmorCoreSavedData::new,
			CompoundTag.CODEC.xmap(ArmorCoreSavedData::load, data -> data.save(new CompoundTag())),
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	private final Map<UUID, CoreRecord> records = new LinkedHashMap<>();
	private final Map<UUID, QuickSwitchRecord> quickSwitches = new LinkedHashMap<>();

	static ArmorCoreSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	CoreRecord record(UUID ownerId) {
		return records.computeIfAbsent(ownerId, CoreRecord::new);
	}

	Optional<QuickSwitchRecord> quickSwitch(UUID ownerId) {
		return Optional.ofNullable(quickSwitches.get(ownerId));
	}

	List<QuickSwitchRecord> quickSwitches() {
		return List.copyOf(quickSwitches.values());
	}

	boolean beginQuickSwitch(QuickSwitchRecord transaction) {
		if (transaction == null || quickSwitches.containsKey(transaction.ownerId())) return false;
		quickSwitches.put(transaction.ownerId(), transaction);
		setDirty();
		return true;
	}

	/** Revision + transaction identity CAS prevents a delayed tick from committing over a restored transaction. */
	boolean compareAndSetQuickSwitch(UUID ownerId, UUID transactionId, long expectedRevision,
			QuickSwitchRecord replacement) {
		QuickSwitchRecord current = quickSwitches.get(ownerId);
		if (current == null || replacement == null
				|| !current.transactionId().equals(transactionId)
				|| current.revision() != expectedRevision
				|| !replacement.ownerId().equals(ownerId)
				|| !replacement.transactionId().equals(transactionId)
				|| replacement.revision() != expectedRevision + 1L) return false;
		quickSwitches.put(ownerId, replacement);
		setDirty();
		return true;
	}

	boolean removeQuickSwitch(UUID ownerId, UUID transactionId, long expectedRevision) {
		QuickSwitchRecord current = quickSwitches.get(ownerId);
		if (current == null || !current.transactionId().equals(transactionId)
				|| current.revision() != expectedRevision) return false;
		quickSwitches.remove(ownerId);
		setDirty();
		return true;
	}

	void setCore(UUID ownerId, ItemStack core) {
		CoreRecord record = record(ownerId);
		Optional<ArmorCoreIdentity.Identity> nextIdentity = ArmorCoreIdentity.read(core);
		if (nextIdentity.isPresent() && !nextIdentity.get().ownerId().equals(ownerId)) return;
		record.core = core.isEmpty() ? ItemStack.EMPTY : core.copyWithCount(1);
		if (nextIdentity.isPresent()) {
			ArmorCoreIdentity.Identity identity = nextIdentity.get();
			record.activate(new NetworkKey(identity.coreId(), identity.instanceNonce()));
		}
		setDirty();
	}

	boolean bind(UUID ownerId, ArmorCoreIdentity.Identity identity, PlatformBinding binding) {
		CoreRecord record = record(ownerId);
		if (!record.matches(identity)) return false;
		NetworkKey activeKey = record.activeKey();
		boolean replacesExisting = record.bindings.containsKey(binding.suitId())
				|| record.bindings.values().stream().anyMatch(existing ->
						existing.platformId().equals(binding.platformId()));
		if (!replacesExisting && record.bindings.size() >= MAX_BINDINGS_PER_CORE) return false;

		boolean migratedFromAnotherCore = false;
		for (Map.Entry<NetworkKey, NetworkRecord> entry : record.networks.entrySet()) {
			if (entry.getKey().equals(activeKey)) continue;
			migratedFromAnotherCore |= entry.getValue().removeConflicts(binding);
		}
		boolean removedOldPlatformBinding = record.bindings.entrySet().removeIf(entry ->
				!entry.getKey().equals(binding.suitId())
						&& entry.getValue().platformId().equals(binding.platformId()));
		PlatformBinding previous = record.bindings.get(binding.suitId());
		if (binding.equals(previous) && !removedOldPlatformBinding && !migratedFromAnotherCore) return false;
		record.bindings.put(binding.suitId(), binding);
		record.linkedSuitIds.add(binding.suitId());
		record.escortProfiles.keySet().retainAll(record.linkedSuitIds);
		if (record.primarySuitId == null || !record.linkedSuitIds.contains(record.primarySuitId)) {
			record.primarySuitId = binding.suitId();
		}
		record.syncPrimary();
		setDirty();
		return true;
	}

	boolean canBind(UUID ownerId, ArmorCoreIdentity.Identity identity, PlatformBinding binding) {
		if (ownerId == null || identity == null || binding == null
				|| !identity.ownerId().equals(ownerId)) return false;
		CoreRecord record = record(ownerId);
		NetworkKey key = new NetworkKey(identity.coreId(), identity.instanceNonce());
		NetworkRecord target = record.networks.get(key);
		if (target == null) return true;
		boolean replacesExisting = target.bindings.containsKey(binding.suitId())
				|| target.bindings.values().stream().anyMatch(existing ->
						existing.platformId().equals(binding.platformId()));
		return replacesExisting || target.bindings.size() < MAX_BINDINGS_PER_CORE;
	}

	/**
	 * Associates the physical suit identity with the active core network.
	 * A charging platform is only an optional home location and is deliberately
	 * not fabricated here.
	 */
	boolean associateSuit(UUID ownerId, ArmorCoreIdentity.Identity identity, UUID suitId) {
		if (suitId == null) return false;
		CoreRecord record = record(ownerId);
		if (!record.matches(identity)) return false;
		NetworkKey activeKey = record.activeKey();
		NetworkRecord active = activeKey == null ? null : record.networks.get(activeKey);
		if (active == null) return false;

		boolean changed = false;
		PlatformBinding migratedBinding = null;
		ArmorEscortProfile migratedProfile = null;
		for (Map.Entry<NetworkKey, NetworkRecord> entry : record.networks.entrySet()) {
			if (entry.getKey().equals(activeKey)) continue;
			NetworkRecord inactive = entry.getValue();
			if (inactive.linkedSuitIds.remove(suitId)) changed = true;
			PlatformBinding binding = inactive.bindings.remove(suitId);
			if (binding != null) {
				migratedBinding = binding;
				changed = true;
			}
			ArmorEscortProfile profile = inactive.escortProfiles.remove(suitId);
			if (profile != null) {
				migratedProfile = profile;
				changed = true;
			}
			if (suitId.equals(inactive.primarySuitId)) {
				inactive.primarySuitId = null;
				changed = true;
			}
			inactive.normalize();
		}

		changed |= active.linkedSuitIds.add(suitId);
		if (migratedBinding != null
				&& !migratedBinding.equals(active.bindings.put(suitId, migratedBinding))) {
			changed = true;
		}
		if (migratedProfile != null
				&& !migratedProfile.equals(active.escortProfiles.put(suitId, migratedProfile))) {
			changed = true;
		}
		// Automatic worn-suit reconciliation must not overwrite a primary/escort choice
		// explicitly configured in the core panel.
		if (active.primarySuitId == null || !active.linkedSuitIds.contains(active.primarySuitId)) {
			active.primarySuitId = suitId;
			changed = true;
		}
		active.normalize();
		record.attach(active);
		if (changed) setDirty();
		return true;
	}

	/**
	 * Automatically discovers a physically worn suit only when it does not already belong to
	 * any navigation-core network. This is deliberately stricter than {@link #associateSuit}:
	 * opening or refreshing another core must never migrate an existing membership as a side
	 * effect of a status query.
	 */
	boolean associateUnlinkedSuit(UUID ownerId, ArmorCoreIdentity.Identity identity, UUID suitId) {
		if (ownerId == null || suitId == null) return false;
		CoreRecord record = record(ownerId);
		if (!record.matches(identity)) return false;
		for (NetworkRecord network : record.networks.values()) {
			if (network.linkedSuitIds.contains(suitId)) return false;
		}
		if (record.orphanedLegacyNetwork != null
				&& record.orphanedLegacyNetwork.linkedSuitIds.contains(suitId)) {
			return false;
		}
		return associateSuit(ownerId, identity, suitId);
	}

	/**
	 * Removes membership from the currently equipped core using its full identity as a
	 * compare-and-set guard. This is not physical destruction: inactive networks and
	 * external ownership records deliberately remain untouched.
	 */
	boolean removeSuitFromActiveCore(UUID ownerId, ArmorCoreIdentity.Identity identity, UUID suitId) {
		if (ownerId == null || suitId == null) return false;
		CoreRecord record = records.get(ownerId);
		if (record == null || !record.matches(identity)) return false;
		NetworkKey activeKey = record.activeKey();
		NetworkRecord active = activeKey == null ? null : record.networks.get(activeKey);
		if (active == null || !active.linkedSuitIds.contains(suitId)) return false;

		active.removeSuit(suitId);
		record.attach(active);
		setDirty();
		return true;
	}

	/**
	 * Forgets a suit only after an authoritative lifecycle path has confirmed that the
	 * physical suit was destroyed. An unloaded or offline suit is not destruction and
	 * must never call this method.
	 */
	boolean removeConfirmedDestroyedSuit(UUID ownerId, UUID suitId) {
		if (ownerId == null || suitId == null) return false;
		CoreRecord record = records.get(ownerId);
		if (record == null) return false;

		boolean changed = false;
		for (NetworkRecord network : record.networks.values()) {
			changed |= network.removeSuit(suitId);
		}
		if (record.orphanedLegacyNetwork != null) {
			changed |= record.orphanedLegacyNetwork.removeSuit(suitId);
		}
		if (!changed) return false;

		NetworkKey activeKey = record.activeKey();
		NetworkRecord active = activeKey == null
				? record.orphanedLegacyNetwork : record.networks.get(activeKey);
		if (active != null) record.attach(active);
		setDirty();
		return true;
	}

	/** Refreshes metadata in the core network that already owns this platform; never migrates it. */
	boolean refreshBoundPlatform(UUID ownerId, PlatformBinding binding) {
		CoreRecord record = record(ownerId);
		for (NetworkRecord network : record.networks.values()) {
			PlatformBinding previous = network.bindings.get(binding.suitId());
			if (previous == null || !previous.platformId().equals(binding.platformId())) continue;
			if (previous.equals(binding)) return false;
			network.bindings.put(binding.suitId(), binding);
			if (record.bindings == network.bindings) record.attach(network);
			setDirty();
			return true;
		}
		if (record.orphanedLegacyNetwork != null) {
			PlatformBinding previous = record.orphanedLegacyNetwork.bindings.get(binding.suitId());
			if (previous != null && previous.platformId().equals(binding.platformId())) {
				record.orphanedLegacyNetwork.bindings.put(binding.suitId(), binding);
				setDirty();

				return true;
			}
		}
		return false;
	}
	boolean setPrimary(UUID ownerId, ArmorCoreIdentity.Identity identity, UUID suitId) {
		CoreRecord record = record(ownerId);
		if (!record.matches(identity) || !record.linkedSuitIds.contains(suitId)) return false;
		record.primarySuitId = suitId;
		record.syncPrimary();
		setDirty();
		return true;
	}

	Optional<PlatformBinding> binding(UUID ownerId, UUID suitId) {
		return Optional.ofNullable(record(ownerId).bindings.get(suitId));
	}

	List<PlatformBinding> bindings(UUID ownerId) {
		return List.copyOf(record(ownerId).bindings.values());
	}
	ArmorEscortProfile escortProfile(UUID ownerId, UUID suitId) {
		CoreRecord record = record(ownerId);
		if (record.linkedSuitIds.contains(suitId)) {
			return record.escortProfiles.getOrDefault(suitId, ArmorEscortProfile.defaults());
		}
		for (NetworkRecord network : record.networks.values()) {
			if (network.linkedSuitIds.contains(suitId)) {
				return network.escortProfiles.getOrDefault(suitId, ArmorEscortProfile.defaults());
			}
		}
		if (record.orphanedLegacyNetwork != null
				&& record.orphanedLegacyNetwork.linkedSuitIds.contains(suitId)) {
			return record.orphanedLegacyNetwork.escortProfiles.getOrDefault(suitId, ArmorEscortProfile.defaults());
		}
		return ArmorEscortProfile.defaults();
	}

	boolean setEscortProfile(UUID ownerId, ArmorCoreIdentity.Identity identity,
			UUID suitId, ArmorEscortProfile profile) {
		CoreRecord record = record(ownerId);
		if (!record.matches(identity) || !record.linkedSuitIds.contains(suitId) || profile == null) return false;
		record.escortProfiles.put(suitId, profile);
		setDirty();
		return true;
	}


	private static ArmorCoreSavedData load(CompoundTag root) {
		ArmorCoreSavedData data = new ArmorCoreSavedData();
		int loadedFormat = root.getIntOr("FormatVersion", 0);
		ListTag players = root.getListOrEmpty("Players");
		for (int index = 0; index < players.size(); index++) {
			CompoundTag tag = players.getCompoundOrEmpty(index);
			try {
				UUID ownerId = TagUtils.getUUID(tag, "Owner");
				CoreRecord record = new CoreRecord(ownerId);
				NonNullList<ItemStack> coreItems = NonNullList.withSize(1, ItemStack.EMPTY);
				ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, DEFAULT_LOOKUP, tag.getCompoundOrEmpty("CoreInventory"));
				ContainerHelper.loadAllItems(input, coreItems);
				ItemStack loadedCore = coreItems.getFirst();
				record.core = loadedCore.isEmpty() ? ItemStack.EMPTY : loadedCore.copyWithCount(1);
				Optional<ArmorCoreIdentity.Identity> storedIdentity = ArmorCoreIdentity.read(record.core);
				record.networkCoreId = TagUtils.hasUUID(tag, "NetworkCore") ? TagUtils.getUUID(tag, "NetworkCore")
						: storedIdentity.map(ArmorCoreIdentity.Identity::coreId).orElse(null);
				record.networkNonce = TagUtils.hasUUID(tag, "NetworkNonce") ? TagUtils.getUUID(tag, "NetworkNonce")
						: storedIdentity.map(ArmorCoreIdentity.Identity::instanceNonce).orElse(null);
				if (TagUtils.hasUUID(tag, "PrimarySuit")) record.primarySuitId = TagUtils.getUUID(tag, "PrimarySuit");
				readLinkedSuits(tag, record.linkedSuitIds);
				ListTag bindings = tag.getListOrEmpty("Bindings");
				for (int bindingIndex = 0; bindingIndex < bindings.size(); bindingIndex++) {
					try {
						CompoundTag bindingTag = bindings.getCompoundOrEmpty(bindingIndex);
						UUID suitId = TagUtils.getUUID(bindingTag, "Suit");
						UUID platformId = TagUtils.getUUID(bindingTag, "Platform");
						ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
								Identifier.parse(bindingTag.getStringOr("Dimension", "")));
						BlockPos pos = new BlockPos(bindingTag.getIntOr("X", 0), bindingTag.getIntOr("Y", 0), bindingTag.getIntOr("Z", 0));
						ArmorVariant variant = ArmorVariant.fromSerializedName(bindingTag.getStringOr("Variant", ""));
						String name = bindingTag.getStringOr("Name", "");
						record.bindings.put(suitId, new PlatformBinding(suitId, platformId, dimension, pos,
								variant, name.isBlank() ? defaultName(variant, suitId) : name));
						record.linkedSuitIds.add(suitId);
						ArmorEscortProfile.CombatPolicy attackMode =
								ArmorEscortProfile.CombatPolicy.fromName(bindingTag.getStringOr("CombatPolicy", ""));
						int attackRange = bindingTag.getIntOr("AttackRange", 32);
						ArmorEscortProfile.EscortAnchor stance =
								ArmorEscortProfile.EscortAnchor.fromName(bindingTag.getStringOr("EscortAnchor", ""));
						ResourceKey<Level> holdDimension = null;
						BlockPos holdPosition = null;
						if (bindingTag.contains("HoldDimension") && bindingTag.contains("HoldX")) {
							holdDimension = ResourceKey.create(Registries.DIMENSION,
									Identifier.parse(bindingTag.getStringOr("HoldDimension", "")));
							holdPosition = new BlockPos(bindingTag.getIntOr("HoldX", 0),
									bindingTag.getIntOr("HoldY", 0), bindingTag.getIntOr("HoldZ", 0));
						}
						record.escortProfiles.put(suitId, new ArmorEscortProfile(
								attackMode, attackRange, stance, holdDimension, holdPosition));

					} catch (RuntimeException ignored) {
						// 单个平台引用损坏不影响核心和其余正常平台。
					}
				}
				readEscortProfiles(tag, record.linkedSuitIds, record.escortProfiles);
				NetworkRecord legacyNetwork = new NetworkRecord(
						record.primarySuitId, record.linkedSuitIds, record.bindings, record.escortProfiles);
				ListTag networkTags = tag.getListOrEmpty("Networks");
				for (int networkIndex = 0; networkIndex < networkTags.size(); networkIndex++) {
					try {
						CompoundTag networkTag = networkTags.getCompoundOrEmpty(networkIndex);
						if (!TagUtils.hasUUID(networkTag, "Core") || !TagUtils.hasUUID(networkTag, "Nonce")) continue;
						NetworkKey key = new NetworkKey(TagUtils.getUUID(networkTag, "Core"), TagUtils.getUUID(networkTag, "Nonce"));
						record.networks.put(key, readNetwork(networkTag));
					} catch (RuntimeException ignored) {
						// A damaged inactive network does not block the owner's other cores.
					}
				}
				record.loadLegacy(legacyNetwork);

				data.records.put(ownerId, record);
			} catch (RuntimeException ignored) {
				// 单名玩家记录损坏不会阻止其他核心加载。
			}
		}
		ListTag quickSwitches = root.getListOrEmpty("QuickSwitches");
		for (int index = 0; index < quickSwitches.size(); index++) {
			try {
				QuickSwitchRecord transaction = readQuickSwitch(quickSwitches.getCompoundOrEmpty(index));
				data.quickSwitches.put(transaction.ownerId(), transaction);
			} catch (RuntimeException ignored) {
				// One corrupt transaction must not prevent core networks or other recoveries from loading.
			}
		}
		if (loadedFormat < 6) data.setDirty();
		return data;
	}

	public CompoundTag save(CompoundTag root) {
		ListTag players = new ListTag();
		for (CoreRecord record : records.values()) {
			CompoundTag tag = new CompoundTag();
			TagUtils.putUUID(tag, "Owner", record.ownerId);
			NonNullList<ItemStack> coreItems = NonNullList.withSize(1, ItemStack.EMPTY);
			coreItems.set(0, record.core.copy());
			TagValueOutput coreOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, DEFAULT_LOOKUP);
			ContainerHelper.saveAllItems(coreOutput, coreItems, true);
			tag.put("CoreInventory", coreOutput.buildResult());
			if (record.networkCoreId != null) TagUtils.putUUID(tag, "NetworkCore", record.networkCoreId);
			if (record.networkNonce != null) TagUtils.putUUID(tag, "NetworkNonce", record.networkNonce);
			if (record.primarySuitId != null) TagUtils.putUUID(tag, "PrimarySuit", record.primarySuitId);
			tag.put("LinkedSuits", writeLinkedSuits(record.linkedSuitIds));
			tag.put("EscortProfiles", writeEscortProfiles(record.linkedSuitIds, record.escortProfiles));
			ListTag bindings = new ListTag();
			for (PlatformBinding binding : record.bindings.values()) {
				CompoundTag bindingTag = new CompoundTag();
				TagUtils.putUUID(bindingTag, "Suit", binding.suitId());
				TagUtils.putUUID(bindingTag, "Platform", binding.platformId());
				bindingTag.putString("Dimension", binding.dimension().identifier().toString());
				bindingTag.putInt("X", binding.pos().getX());
				bindingTag.putInt("Y", binding.pos().getY());
				bindingTag.putInt("Z", binding.pos().getZ());
				bindingTag.putString("Variant", binding.variant().serializedName());
				ArmorEscortProfile profile = record.escortProfiles.getOrDefault(
						binding.suitId(), ArmorEscortProfile.defaults());
				bindingTag.putString("CombatPolicy", profile.attackMode().name());
				bindingTag.putInt("AttackRange", profile.rangeBlocks());
				bindingTag.putString("EscortAnchor", profile.stance().name());
				if (profile.holdDimension() != null && profile.holdPosition() != null) {
					bindingTag.putString("HoldDimension", profile.holdDimension().identifier().toString());
					bindingTag.putInt("HoldX", profile.holdPosition().getX());
					bindingTag.putInt("HoldY", profile.holdPosition().getY());
					bindingTag.putInt("HoldZ", profile.holdPosition().getZ());
				}

				bindingTag.putString("Name", binding.displayName());
				bindings.add(bindingTag);
			}
			tag.put("Bindings", bindings);
			ListTag networkTags = new ListTag();
			for (Map.Entry<NetworkKey, NetworkRecord> entry : record.networks.entrySet()) {
				networkTags.add(writeNetwork(entry.getKey(), entry.getValue()));
			}
			tag.put("Networks", networkTags);

			players.add(tag);
		}
		root.put("Players", players);
		ListTag quickSwitchTags = new ListTag();
		for (QuickSwitchRecord transaction : quickSwitches.values()) {
			quickSwitchTags.add(writeQuickSwitch(transaction));
		}
		root.put("QuickSwitches", quickSwitchTags);
		root.putInt("FormatVersion", 6);
		return root;
	}

	static CompoundTag writeQuickSwitch(QuickSwitchRecord transaction) {
		CompoundTag tag = new CompoundTag();
		TagUtils.putUUID(tag, "Transaction", transaction.transactionId());
		TagUtils.putUUID(tag, "Owner", transaction.ownerId());
		TagUtils.putUUID(tag, "Core", transaction.coreId());
		TagUtils.putUUID(tag, "CoreNonce", transaction.coreNonce());
		TagUtils.putUUID(tag, "OutgoingSuit", transaction.outgoingSuitId());
		tag.put("OutgoingPlatform", writePlatformBinding(transaction.outgoingBinding()));
		TagUtils.putUUID(tag, "TargetSuit", transaction.targetSuitId());
		tag.putString("TargetName", transaction.targetName());
		if (!transaction.targetSource().isBlank()) tag.putString("TargetSource", transaction.targetSource());
		tag.putString("Phase", transaction.phase());
		tag.putLong("StartGameTime", transaction.startGameTime());
		tag.putLong("PhaseStartGameTime", transaction.phaseStartGameTime());
		tag.putString("RecoveryCause", transaction.recoveryCause());
		tag.putLong("NextRecoveryAttempt", transaction.nextRecoveryAttemptTick());
		tag.putBoolean("OutgoingDeployed", transaction.outgoingDeployed());
		tag.putBoolean("TargetCancellationComplete", transaction.targetCancellationComplete());
		tag.putBoolean("RecoveryRequestAnnounced", transaction.recoveryRequestAnnounced());
		tag.putBoolean("RecoveryWaitingAnnounced", transaction.recoveryWaitingAnnounced());
		tag.putString("LastRecoveryDetail", transaction.lastRecoveryDetail());
		tag.putLong("Revision", transaction.revision());
		return tag;
	}

	static QuickSwitchRecord readQuickSwitch(CompoundTag tag) {
		UUID transactionId = TagUtils.getUUID(tag, "Transaction");
		UUID ownerId = TagUtils.getUUID(tag, "Owner");
		UUID coreId = TagUtils.getUUID(tag, "Core");
		UUID coreNonce = TagUtils.getUUID(tag, "CoreNonce");
		UUID outgoingSuitId = TagUtils.getUUID(tag, "OutgoingSuit");
		PlatformBinding outgoingBinding = readPlatformBinding(tag.getCompoundOrEmpty("OutgoingPlatform"));
		UUID targetSuitId = TagUtils.getUUID(tag, "TargetSuit");
		return new QuickSwitchRecord(transactionId, ownerId, coreId, coreNonce,
				outgoingSuitId, outgoingBinding, targetSuitId, tag.getStringOr("TargetName", ""),
				tag.getStringOr("TargetSource", ""), tag.getStringOr("Phase", ""),
				tag.getLongOr("StartGameTime", 0L), tag.getLongOr("PhaseStartGameTime", 0L),
				tag.getStringOr("RecoveryCause", ""), tag.getLongOr("NextRecoveryAttempt", 0L),
				tag.getBooleanOr("OutgoingDeployed", false), tag.getBooleanOr("TargetCancellationComplete", false),
				tag.getBooleanOr("RecoveryRequestAnnounced", false), tag.getBooleanOr("RecoveryWaitingAnnounced", false),
				tag.getStringOr("LastRecoveryDetail", ""), Math.max(0L, tag.getLongOr("Revision", 0L)));
	}

	private static CompoundTag writePlatformBinding(PlatformBinding binding) {
		CompoundTag tag = new CompoundTag();
		TagUtils.putUUID(tag, "Suit", binding.suitId());
		TagUtils.putUUID(tag, "Platform", binding.platformId());
		tag.putString("Dimension", binding.dimension().identifier().toString());
		tag.putInt("X", binding.pos().getX());
		tag.putInt("Y", binding.pos().getY());
		tag.putInt("Z", binding.pos().getZ());
		tag.putString("Variant", binding.variant().serializedName());
		tag.putString("Name", binding.displayName());
		return tag;
	}

	private static PlatformBinding readPlatformBinding(CompoundTag tag) {
		UUID suitId = TagUtils.getUUID(tag, "Suit");
		UUID platformId = TagUtils.getUUID(tag, "Platform");
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
				Identifier.parse(tag.getStringOr("Dimension", "")));
		BlockPos pos = new BlockPos(tag.getIntOr("X", 0), tag.getIntOr("Y", 0), tag.getIntOr("Z", 0));
		ArmorVariant variant = ArmorVariant.fromSerializedName(tag.getStringOr("Variant", ""));
		String name = tag.getStringOr("Name", "");
		return new PlatformBinding(suitId, platformId, dimension, pos, variant,
				name.isBlank() ? defaultName(variant, suitId) : name);
	}

	static NetworkRecord readNetwork(CompoundTag tag) {
		NetworkRecord network = new NetworkRecord();
		if (TagUtils.hasUUID(tag, "PrimarySuit")) network.primarySuitId = TagUtils.getUUID(tag, "PrimarySuit");
		readLinkedSuits(tag, network.linkedSuitIds);
		ListTag bindings = tag.getListOrEmpty("Bindings");
		for (int index = 0; index < bindings.size(); index++) {
			try {
				CompoundTag bindingTag = bindings.getCompoundOrEmpty(index);
				UUID suitId = TagUtils.getUUID(bindingTag, "Suit");
				UUID platformId = TagUtils.getUUID(bindingTag, "Platform");
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
						Identifier.parse(bindingTag.getStringOr("Dimension", "")));
				BlockPos pos = new BlockPos(bindingTag.getIntOr("X", 0), bindingTag.getIntOr("Y", 0),
						bindingTag.getIntOr("Z", 0));
				ArmorVariant variant = ArmorVariant.fromSerializedName(bindingTag.getStringOr("Variant", ""));
				String name = bindingTag.getStringOr("Name", "");
				network.bindings.put(suitId, new PlatformBinding(suitId, platformId, dimension, pos,
						variant, name.isBlank() ? defaultName(variant, suitId) : name));
				network.linkedSuitIds.add(suitId);

				ArmorEscortProfile.CombatPolicy attackMode =
						ArmorEscortProfile.CombatPolicy.fromName(bindingTag.getStringOr("CombatPolicy", ""));
				int attackRange = bindingTag.getIntOr("AttackRange", 32);
				ArmorEscortProfile.EscortAnchor stance =
						ArmorEscortProfile.EscortAnchor.fromName(bindingTag.getStringOr("EscortAnchor", ""));
				ResourceKey<Level> holdDimension = null;
				BlockPos holdPosition = null;
				if (bindingTag.contains("HoldDimension") && bindingTag.contains("HoldX")) {
					holdDimension = ResourceKey.create(Registries.DIMENSION,
							Identifier.parse(bindingTag.getStringOr("HoldDimension", "")));
					holdPosition = new BlockPos(bindingTag.getIntOr("HoldX", 0),
							bindingTag.getIntOr("HoldY", 0), bindingTag.getIntOr("HoldZ", 0));
				}
				network.escortProfiles.put(suitId, new ArmorEscortProfile(
						attackMode, attackRange, stance, holdDimension, holdPosition));
			} catch (RuntimeException ignored) {
				// Preserve every other valid binding when one entry is damaged.
			}
		}
		readEscortProfiles(tag, network.linkedSuitIds, network.escortProfiles);
		network.normalize();
		return network;
	}

	static CompoundTag writeNetwork(NetworkKey key, NetworkRecord network) {
		CompoundTag tag = new CompoundTag();
		TagUtils.putUUID(tag, "Core", key.coreId());
		TagUtils.putUUID(tag, "Nonce", key.nonce());
		if (network.primarySuitId != null) TagUtils.putUUID(tag, "PrimarySuit", network.primarySuitId);
		tag.put("LinkedSuits", writeLinkedSuits(network.linkedSuitIds));
		tag.put("Bindings", writeBindings(network.bindings, network.escortProfiles));
		tag.put("EscortProfiles", writeEscortProfiles(network.linkedSuitIds, network.escortProfiles));
		return tag;
	}

	private static ListTag writeEscortProfiles(Set<UUID> linkedSuitIds,
			Map<UUID, ArmorEscortProfile> escortProfiles) {
		ListTag result = new ListTag();
		for (Map.Entry<UUID, ArmorEscortProfile> entry : escortProfiles.entrySet()) {
			if (!linkedSuitIds.contains(entry.getKey()) || entry.getValue() == null) continue;
			ArmorEscortProfile profile = entry.getValue();
			CompoundTag profileTag = new CompoundTag();
			TagUtils.putUUID(profileTag, "Suit", entry.getKey());
			profileTag.putString("CombatPolicy", profile.attackMode().name());
			profileTag.putInt("AttackRange", profile.rangeBlocks());
			profileTag.putString("EscortAnchor", profile.stance().name());
			if (profile.holdDimension() != null && profile.holdPosition() != null) {
				profileTag.putString("HoldDimension", profile.holdDimension().identifier().toString());
				profileTag.putInt("HoldX", profile.holdPosition().getX());
				profileTag.putInt("HoldY", profile.holdPosition().getY());
				profileTag.putInt("HoldZ", profile.holdPosition().getZ());
			}
			result.add(profileTag);
		}
		return result;
	}

	private static void readEscortProfiles(CompoundTag owner, Set<UUID> linkedSuitIds,
			Map<UUID, ArmorEscortProfile> escortProfiles) {
		ListTag profiles = owner.getListOrEmpty("EscortProfiles");
		for (int index = 0; index < profiles.size(); index++) {
			try {
				CompoundTag profileTag = profiles.getCompoundOrEmpty(index);
				if (!TagUtils.hasUUID(profileTag, "Suit")) continue;
				UUID suitId = TagUtils.getUUID(profileTag, "Suit");
				if (!linkedSuitIds.contains(suitId)) continue;
				ArmorEscortProfile.CombatPolicy attackMode =
						ArmorEscortProfile.CombatPolicy.fromName(profileTag.getStringOr("CombatPolicy", ""));
				int attackRange = profileTag.getIntOr("AttackRange", 32);
				ArmorEscortProfile.EscortAnchor stance =
						ArmorEscortProfile.EscortAnchor.fromName(profileTag.getStringOr("EscortAnchor", ""));
				ResourceKey<Level> holdDimension = null;
				BlockPos holdPosition = null;
				if (profileTag.contains("HoldDimension") && profileTag.contains("HoldX")) {
					holdDimension = ResourceKey.create(Registries.DIMENSION,
							Identifier.parse(profileTag.getStringOr("HoldDimension", "")));
					holdPosition = new BlockPos(profileTag.getIntOr("HoldX", 0),
							profileTag.getIntOr("HoldY", 0), profileTag.getIntOr("HoldZ", 0));
				}
				escortProfiles.put(suitId, new ArmorEscortProfile(
						attackMode, attackRange, stance, holdDimension, holdPosition));
			} catch (RuntimeException ignored) {
				// One damaged optional profile must not hide the suit or its home binding.
			}
		}
	}

	private static ListTag writeLinkedSuits(Set<UUID> linkedSuitIds) {
		ListTag result = new ListTag();
		for (UUID suitId : linkedSuitIds) {
			CompoundTag link = new CompoundTag();
			TagUtils.putUUID(link, "Suit", suitId);
			result.add(link);
		}
		return result;
	}

	private static void readLinkedSuits(CompoundTag owner, Set<UUID> target) {
		ListTag links = owner.getListOrEmpty("LinkedSuits");
		for (int index = 0; index < links.size(); index++) {
			CompoundTag link = links.getCompoundOrEmpty(index);
			if (TagUtils.hasUUID(link, "Suit")) target.add(TagUtils.getUUID(link, "Suit"));
		}
	}

	private static ListTag writeBindings(Map<UUID, PlatformBinding> bindings,
			Map<UUID, ArmorEscortProfile> escortProfiles) {
		ListTag result = new ListTag();
		for (PlatformBinding binding : bindings.values()) {
			CompoundTag bindingTag = new CompoundTag();
			TagUtils.putUUID(bindingTag, "Suit", binding.suitId());
			TagUtils.putUUID(bindingTag, "Platform", binding.platformId());
			bindingTag.putString("Dimension", binding.dimension().identifier().toString());
			bindingTag.putInt("X", binding.pos().getX());
			bindingTag.putInt("Y", binding.pos().getY());
			bindingTag.putInt("Z", binding.pos().getZ());
			bindingTag.putString("Variant", binding.variant().serializedName());
			ArmorEscortProfile profile = escortProfiles.getOrDefault(
					binding.suitId(), ArmorEscortProfile.defaults());
			bindingTag.putString("CombatPolicy", profile.attackMode().name());
			bindingTag.putInt("AttackRange", profile.rangeBlocks());
			bindingTag.putString("EscortAnchor", profile.stance().name());
			if (profile.holdDimension() != null && profile.holdPosition() != null) {
				bindingTag.putString("HoldDimension", profile.holdDimension().identifier().toString());
				bindingTag.putInt("HoldX", profile.holdPosition().getX());
				bindingTag.putInt("HoldY", profile.holdPosition().getY());
				bindingTag.putInt("HoldZ", profile.holdPosition().getZ());
			}
			bindingTag.putString("Name", binding.displayName());
			result.add(bindingTag);
		}
		return result;
	}

	static String defaultName(ArmorVariant variant, UUID suitId) {
		return variant.chineseName() + "-" + suitId.toString().substring(0, 4).toUpperCase();
	}

	static final class CoreRecord {
		final UUID ownerId;
		ItemStack core = ItemStack.EMPTY;
		UUID networkCoreId;
		UUID networkNonce;
		UUID primarySuitId;
		Set<UUID> linkedSuitIds = new LinkedHashSet<>();
		Map<UUID, PlatformBinding> bindings = new LinkedHashMap<>();
		Map<UUID, ArmorEscortProfile> escortProfiles = new LinkedHashMap<>();
		final Map<NetworkKey, NetworkRecord> networks = new LinkedHashMap<>();
		NetworkRecord orphanedLegacyNetwork;

		CoreRecord(UUID ownerId) {
			this.ownerId = ownerId;
		}

		NetworkKey activeKey() {
			return networkCoreId == null || networkNonce == null
					? null : new NetworkKey(networkCoreId, networkNonce);
		}

		void activate(NetworkKey key) {
			if (key == null) return;
			NetworkKey previousKey = activeKey();
			NetworkRecord target = networks.get(key);
			if (target == null && key.equals(previousKey)
					&& (primarySuitId != null || !linkedSuitIds.isEmpty()
					|| !bindings.isEmpty() || !escortProfiles.isEmpty())) {
				target = new NetworkRecord(primarySuitId, linkedSuitIds, bindings, escortProfiles);
			}
			if (target == null && orphanedLegacyNetwork != null) {
				target = orphanedLegacyNetwork;
				orphanedLegacyNetwork = null;
			}
			if (target == null) target = new NetworkRecord();
			networks.put(key, target);
			networkCoreId = key.coreId();
			networkNonce = key.nonce();
			attach(target);
		}

		void attach(NetworkRecord network) {
			primarySuitId = network.primarySuitId;
			linkedSuitIds = network.linkedSuitIds;
			bindings = network.bindings;
			escortProfiles = network.escortProfiles;
		}

		void syncPrimary() {
			NetworkKey key = activeKey();
			NetworkRecord active = key == null ? null : networks.get(key);
			if (active != null) active.primarySuitId = primarySuitId;
			else if (orphanedLegacyNetwork != null) orphanedLegacyNetwork.primarySuitId = primarySuitId;
		}

		void loadLegacy(NetworkRecord legacy) {
			NetworkKey key = activeKey();
			if (key == null) {
				orphanedLegacyNetwork = legacy;
				attach(legacy);
				return;
			}
			networks.putIfAbsent(key, legacy);
			attach(networks.get(key));
		}

		boolean matches(ArmorCoreIdentity.Identity identity) {
			return identity != null && identity.ownerId().equals(ownerId)
					&& networkCoreId != null && networkNonce != null
					&& networkCoreId.equals(identity.coreId())
					&& networkNonce.equals(identity.instanceNonce())
					&& ArmorCoreIdentity.read(core).filter(value -> value.coreId().equals(identity.coreId())
					&& value.instanceNonce().equals(identity.instanceNonce())).isPresent();
		}
	}

	record NetworkKey(UUID coreId, UUID nonce) {
		NetworkKey {
			if (coreId == null || nonce == null) throw new IllegalArgumentException("Core network identity is incomplete");
		}
	}

	static final class NetworkRecord {
		UUID primarySuitId;
		final Set<UUID> linkedSuitIds = new LinkedHashSet<>();
		final Map<UUID, PlatformBinding> bindings = new LinkedHashMap<>();
		final Map<UUID, ArmorEscortProfile> escortProfiles = new LinkedHashMap<>();

		NetworkRecord() {
		}

		NetworkRecord(UUID primarySuitId, Set<UUID> linkedSuitIds,
				Map<UUID, PlatformBinding> bindings,
				Map<UUID, ArmorEscortProfile> escortProfiles) {
			this.primarySuitId = primarySuitId;
			this.linkedSuitIds.addAll(linkedSuitIds);
			this.bindings.putAll(bindings);
			this.escortProfiles.putAll(escortProfiles);
			normalize();
		}

		private boolean removeSuit(UUID suitId) {
			boolean changed = linkedSuitIds.remove(suitId);
			changed |= bindings.remove(suitId) != null;
			changed |= escortProfiles.remove(suitId) != null;
			if (suitId.equals(primarySuitId)) {
				primarySuitId = null;
				changed = true;
			}
			if (changed) normalize();
			return changed;
		}

		boolean removeConflicts(PlatformBinding binding) {
			Set<UUID> removedSuitIds = new LinkedHashSet<>();
			boolean changed = bindings.entrySet().removeIf(entry -> {
				boolean remove = entry.getKey().equals(binding.suitId())
						|| entry.getValue().platformId().equals(binding.platformId());
				if (remove) removedSuitIds.add(entry.getKey());
				return remove;
			});
			if (removedSuitIds.contains(binding.suitId())) {
				linkedSuitIds.remove(binding.suitId());
			}
			if (changed) normalize();
			return changed;
		}

		void normalize() {
			linkedSuitIds.addAll(bindings.keySet());
			escortProfiles.keySet().retainAll(linkedSuitIds);
			if (primarySuitId != null && !linkedSuitIds.contains(primarySuitId)) {
				primarySuitId = linkedSuitIds.stream().findFirst().orElse(null);
			}
			if (primarySuitId == null && !linkedSuitIds.isEmpty()) {
				primarySuitId = linkedSuitIds.iterator().next();
			}
		}
	}

	record QuickSwitchRecord(UUID transactionId, UUID ownerId, UUID coreId, UUID coreNonce,
			UUID outgoingSuitId, PlatformBinding outgoingBinding, UUID targetSuitId,
			String targetName, String targetSource, String phase, long startGameTime,
			long phaseStartGameTime, String recoveryCause, long nextRecoveryAttemptTick,
			boolean outgoingDeployed, boolean targetCancellationComplete,
			boolean recoveryRequestAnnounced, boolean recoveryWaitingAnnounced,
			String lastRecoveryDetail, long revision) {
		QuickSwitchRecord {
			if (transactionId == null || ownerId == null || coreId == null || coreNonce == null
					|| outgoingSuitId == null || outgoingBinding == null || targetSuitId == null) {
				throw new IllegalArgumentException("Quick-switch identity is incomplete");
			}
			if (!outgoingSuitId.equals(outgoingBinding.suitId())) {
				throw new IllegalArgumentException("Outgoing suit does not match its platform binding");
			}
			targetName = targetName == null ? "" : targetName;
			targetSource = targetSource == null ? "" : targetSource;
			phase = phase == null || phase.isBlank() ? "RECOVERING" : phase;
			recoveryCause = recoveryCause == null || recoveryCause.isBlank()
					? "SEPARATION_FAILED" : recoveryCause;
			lastRecoveryDetail = lastRecoveryDetail == null ? "" : lastRecoveryDetail;
			revision = Math.max(0L, revision);
		}
	}

	record PlatformBinding(UUID suitId, UUID platformId, ResourceKey<Level> dimension,
			BlockPos pos, ArmorVariant variant, String displayName) {
		PlatformBinding {
			pos = pos.immutable();
		}
	}
}
