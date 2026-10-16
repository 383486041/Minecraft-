package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.platform.ChargingPlatformDisplayMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable escort-carrier locator. It intentionally stores no ItemStack or inventory data; the exact
 * ArmorStand entity remains the only owner of the four armor objects while cruising.
 */
final class ArmorEscortSavedData extends SavedData {
	private static final String DATA_NAME = "flight_armor_escort_index";
	private static final SavedData.Factory<ArmorEscortSavedData> FACTORY = new SavedData.Factory<>(
			ArmorEscortSavedData::new,
			ArmorEscortSavedData::load,
			DataFixTypes.LEVEL);

	private final Map<UUID, Locator> locators = new ConcurrentHashMap<>();

	static ArmorEscortSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	static boolean create(MinecraftServer server, Locator locator) {
		return get(server).createLocator(locator);
	}

	static boolean compareAndSet(MinecraftServer server, UUID suitId, UUID carrierId,
			long expectedRevision, Locator replacement) {
		return get(server).compareAndSetLocator(suitId, carrierId, expectedRevision, replacement);
	}

	/**
	 * A suit id can never silently change carrier UUID. The old locator must be explicitly removed
	 * by the successful platform/equip transaction before a different physical carrier is accepted.
	 */
	boolean createLocator(Locator locator) {
		Objects.requireNonNull(locator, "locator");
		if (locator.revision() != 1L) return false;
		if (locators.putIfAbsent(locator.suitId(), locator) != null) return false;
		setDirty();
		return true;
	}

	boolean compareAndSetLocator(UUID suitId, UUID carrierId,
			long expectedRevision, Locator replacement) {
		Objects.requireNonNull(suitId, "suitId");
		Objects.requireNonNull(carrierId, "carrierId");
		Objects.requireNonNull(replacement, "replacement");
		if (!replacement.suitId().equals(suitId) || !replacement.carrierId().equals(carrierId)
				|| replacement.revision() != expectedRevision + 1L) return false;
		Locator current = locators.get(suitId);
		if (current == null || !current.carrierId().equals(carrierId)
				|| current.revision() != expectedRevision
				|| !locators.replace(suitId, current, replacement)) return false;
		setDirty();
		return true;
	}

	static Locator find(MinecraftServer server, UUID suitId) {
		return suitId == null ? null : get(server).locators.get(suitId);
	}

	Locator findLocal(UUID suitId) {
		return suitId == null ? null : locators.get(suitId);
	}

	static List<Locator> findByOwner(MinecraftServer server, UUID ownerId) {
		if (ownerId == null) return List.of();
		return get(server).locators.values().stream()
				.filter(locator -> locator.ownerId().equals(ownerId))
				.toList();
	}

	static Collection<Locator> snapshot(MinecraftServer server) {
		return new ArrayList<>(get(server).locators.values());
	}

	static boolean removeIfMatches(MinecraftServer server, UUID suitId, UUID carrierId, long revision) {
		ArmorEscortSavedData data = get(server);
		Locator current = data.locators.get(suitId);
		if (current == null || !current.carrierId().equals(carrierId)
				|| current.revision() != revision || !data.locators.remove(suitId, current)) {
			return false;
		}
		data.setDirty();
		return true;
	}

	private static ArmorEscortSavedData load(CompoundTag root, HolderLookup.Provider registries) {
		ArmorEscortSavedData data = new ArmorEscortSavedData();
		ListTag list = root.getList("Carriers", Tag.TAG_COMPOUND);
		if (root.getInt("Version") != 1) data.setDirty();
		for (int index = 0; index < list.size(); index++) {
			try {
				Locator locator = readLocator(list.getCompound(index));
				data.putLoaded(locator);
			} catch (RuntimeException ignored) {
				// A corrupt locator cannot justify creating armor; other valid records still recover.
			}
		}
		return data;
	}

	private void putLoaded(Locator locator) {
		Locator previous = locators.get(locator.suitId());
		if (previous == null || (previous.carrierId().equals(locator.carrierId())
				&& locator.revision() >= previous.revision())) {
			locators.put(locator.suitId(), locator);
		}
	}

	@Override
	public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
		root.putInt("Version", 1);
		ListTag list = new ListTag();
		for (Locator locator : locators.values()) list.add(writeLocator(locator));
		root.put("Carriers", list);
		return root;
	}

	static CompoundTag writeLocator(Locator locator) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", 1);
		tag.putUUID("Owner", locator.ownerId());
		tag.putUUID("Suit", locator.suitId());
		tag.putUUID("Carrier", locator.carrierId());
		tag.putString("Dimension", locator.dimension().location().toString());
		tag.putInt("ChunkX", locator.chunkX());
		tag.putInt("ChunkZ", locator.chunkZ());
		tag.putString("Phase", locator.phase().name());
		tag.putBoolean("GuardRequested", locator.guardRequested());
		tag.putLong("Revision", locator.revision());
		tag.putLong("CarrierRevision", locator.carrierRevision());
		tag.putString("DisplayMode", locator.displayMode().name());
		if (locator.hasCompleteHome()) {
			tag.putUUID("HomePlatform", locator.homePlatformId());
			tag.putString("HomeDimension", locator.homeDimension().location().toString());
			tag.putInt("HomeX", locator.homePos().getX());
			tag.putInt("HomeY", locator.homePos().getY());
			tag.putInt("HomeZ", locator.homePos().getZ());
		}
		return tag;
	}

	static Locator readLocator(CompoundTag tag) {
		UUID owner = tag.getUUID("Owner");
		UUID suit = tag.getUUID("Suit");
		UUID carrier = tag.getUUID("Carrier");
		ResourceKey<Level> dimension = dimension(tag.getString("Dimension"));
		StoredPhase phase = StoredPhase.fromName(tag.getString("Phase"));
		ChargingPlatformDisplayMode displayMode = displayMode(tag.getString("DisplayMode"));
		UUID homePlatform = null;
		ResourceKey<Level> homeDimension = null;
		BlockPos homePos = null;
		if (tag.hasUUID("HomePlatform") && tag.contains("HomeDimension", Tag.TAG_STRING)
				&& tag.contains("HomeX", Tag.TAG_INT) && tag.contains("HomeY", Tag.TAG_INT)
				&& tag.contains("HomeZ", Tag.TAG_INT)) {
			homePlatform = tag.getUUID("HomePlatform");
			homeDimension = dimension(tag.getString("HomeDimension"));
			homePos = new BlockPos(tag.getInt("HomeX"), tag.getInt("HomeY"), tag.getInt("HomeZ"));
		}
		return new Locator(owner, suit, carrier, dimension,
				tag.getInt("ChunkX"), tag.getInt("ChunkZ"), phase,
				homePlatform, homeDimension, homePos, displayMode,
				tag.getBoolean("GuardRequested"), Math.max(1L, tag.getLong("Revision")),
				Math.max(1L, tag.getLong("CarrierRevision")));
	}

	private static ResourceKey<Level> dimension(String value) {
		return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(value));
	}

	private static ChargingPlatformDisplayMode displayMode(String value) {
		try {
			return ChargingPlatformDisplayMode.valueOf(value);
		} catch (IllegalArgumentException ignored) {
			return ChargingPlatformDisplayMode.ASSEMBLED;
		}
	}

	enum StoredPhase {
		ESCORTING,
		RETURNING;

		static StoredPhase fromName(String value) {
			try {
				return valueOf(value);
			} catch (IllegalArgumentException ignored) {
				return ESCORTING;
			}
		}
	}

	record Locator(
			UUID ownerId,
			UUID suitId,
			UUID carrierId,
			ResourceKey<Level> dimension,
			int chunkX,
			int chunkZ,
			StoredPhase phase,
			UUID homePlatformId,
			ResourceKey<Level> homeDimension,
			BlockPos homePos,
			ChargingPlatformDisplayMode displayMode,
			boolean guardRequested,
			long revision,
			long carrierRevision
	) {
		Locator {
			Objects.requireNonNull(ownerId, "ownerId");
			Objects.requireNonNull(suitId, "suitId");
			Objects.requireNonNull(carrierId, "carrierId");
			Objects.requireNonNull(dimension, "dimension");
			phase = phase == null ? StoredPhase.ESCORTING : phase;
			displayMode = displayMode == null ? ChargingPlatformDisplayMode.ASSEMBLED : displayMode;
			homePos = homePos == null ? null : homePos.immutable();
			revision = Math.max(1L, revision);
			carrierRevision = Math.max(1L, carrierRevision);
			boolean anyHome = homePlatformId != null || homeDimension != null || homePos != null;
			if (anyHome && (homePlatformId == null || homeDimension == null || homePos == null)) {
				throw new IllegalArgumentException("partial escort home locator");
			}
		}

		boolean hasCompleteHome() {
			return homePlatformId != null && homeDimension != null && homePos != null;
		}

		boolean sameState(Locator other) {
			return other != null && ownerId.equals(other.ownerId)
					&& suitId.equals(other.suitId) && carrierId.equals(other.carrierId)
					&& dimension.equals(other.dimension) && chunkX == other.chunkX
					&& chunkZ == other.chunkZ && phase == other.phase
					&& Objects.equals(homePlatformId, other.homePlatformId)
					&& Objects.equals(homeDimension, other.homeDimension)
					&& Objects.equals(homePos, other.homePos)
					&& displayMode == other.displayMode
					&& guardRequested == other.guardRequested
					&& carrierRevision == other.carrierRevision;
		}
	}
}
