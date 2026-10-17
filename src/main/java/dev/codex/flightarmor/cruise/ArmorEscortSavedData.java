package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.platform.ChargingPlatformDisplayMode;
import dev.codex.flightarmor.util.TagUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

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
	private static final SavedDataType<ArmorEscortSavedData> TYPE = new SavedDataType<>(
			FlightArmorMod.id(DATA_NAME),
			ArmorEscortSavedData::new,
			CompoundTag.CODEC.xmap(ArmorEscortSavedData::load, data -> data.save(new CompoundTag())),
			DataFixTypes.LEVEL);

	private final Map<UUID, Locator> locators = new ConcurrentHashMap<>();

	static ArmorEscortSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
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

	private static ArmorEscortSavedData load(CompoundTag root) {
		ArmorEscortSavedData data = new ArmorEscortSavedData();
		ListTag list = root.getListOrEmpty("Carriers");
		if (root.getIntOr("Version", 0) != 1) data.setDirty();
		for (int index = 0; index < list.size(); index++) {
			try {
				Locator locator = readLocator(list.getCompoundOrEmpty(index));
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

	public CompoundTag save(CompoundTag root) {
		root.putInt("Version", 1);
		ListTag list = new ListTag();
		for (Locator locator : locators.values()) list.add(writeLocator(locator));
		root.put("Carriers", list);
		return root;
	}

	static CompoundTag writeLocator(Locator locator) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", 1);
		TagUtils.putUUID(tag, "Owner", locator.ownerId());
		TagUtils.putUUID(tag, "Suit", locator.suitId());
		TagUtils.putUUID(tag, "Carrier", locator.carrierId());
		tag.putString("Dimension", locator.dimension().identifier().toString());
		tag.putInt("ChunkX", locator.chunkX());
		tag.putInt("ChunkZ", locator.chunkZ());
		tag.putString("Phase", locator.phase().name());
		tag.putBoolean("GuardRequested", locator.guardRequested());
		tag.putLong("Revision", locator.revision());
		tag.putLong("CarrierRevision", locator.carrierRevision());
		tag.putString("DisplayMode", locator.displayMode().name());
		if (locator.hasCompleteHome()) {
			TagUtils.putUUID(tag, "HomePlatform", locator.homePlatformId());
			tag.putString("HomeDimension", locator.homeDimension().identifier().toString());
			tag.putInt("HomeX", locator.homePos().getX());
			tag.putInt("HomeY", locator.homePos().getY());
			tag.putInt("HomeZ", locator.homePos().getZ());
		}
		return tag;
	}

	static Locator readLocator(CompoundTag tag) {
		UUID owner = TagUtils.getUUID(tag, "Owner");
		UUID suit = TagUtils.getUUID(tag, "Suit");
		UUID carrier = TagUtils.getUUID(tag, "Carrier");
		ResourceKey<Level> dimension = dimension(tag.getStringOr("Dimension", ""));
		StoredPhase phase = StoredPhase.fromName(tag.getStringOr("Phase", ""));
		ChargingPlatformDisplayMode displayMode = displayMode(tag.getStringOr("DisplayMode", ""));
		UUID homePlatform = null;
		ResourceKey<Level> homeDimension = null;
		BlockPos homePos = null;
		if (TagUtils.hasUUID(tag, "HomePlatform") && tag.contains("HomeDimension")
				&& tag.contains("HomeX") && tag.contains("HomeY")
				&& tag.contains("HomeZ")) {
			homePlatform = TagUtils.getUUID(tag, "HomePlatform");
			homeDimension = dimension(tag.getStringOr("HomeDimension", ""));
			homePos = new BlockPos(tag.getIntOr("HomeX", 0), tag.getIntOr("HomeY", 0), tag.getIntOr("HomeZ", 0));
		}
		return new Locator(owner, suit, carrier, dimension,
				tag.getIntOr("ChunkX", 0), tag.getIntOr("ChunkZ", 0), phase,
				homePlatform, homeDimension, homePos, displayMode,
				tag.getBooleanOr("GuardRequested", false), Math.max(1L, tag.getLongOr("Revision", 1L)),
				Math.max(1L, tag.getLongOr("CarrierRevision", 1L)));
	}

	private static ResourceKey<Level> dimension(String value) {
		return ResourceKey.create(Registries.DIMENSION, Identifier.parse(value));
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
