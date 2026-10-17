package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.util.TagUtils;
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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 战甲载体的轻量持久索引。这里只保存定位信息，真正的四件 ItemStack 始终只存在于玩家或实体 NBT 中。
 */
final class ArmorCruiseSavedData extends SavedData {
	private static final String DATA_NAME = "flight_armor_cruise_index";
	private static final SavedDataType<ArmorCruiseSavedData> TYPE = new SavedDataType<>(
			FlightArmorMod.id(DATA_NAME),
			ArmorCruiseSavedData::new,
			CompoundTag.CODEC.xmap(ArmorCruiseSavedData::load, data -> data.save(new CompoundTag())),
			DataFixTypes.LEVEL);

	private final Map<UUID, Locator> locators = new HashMap<>();

	static ArmorCruiseSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	static void put(MinecraftServer server, Locator locator) {
		ArmorCruiseSavedData data = get(server);
		Locator old = data.locators.put(locator.ownerId, locator);
		if (!locator.equals(old)) {
			data.setDirty();
		}
	}

	static void removeIfMatches(MinecraftServer server, UUID ownerId, UUID carrierId, long sequenceId) {
		ArmorCruiseSavedData data = get(server);
		Locator current = data.locators.get(ownerId);
		if (current != null && current.carrierId.equals(carrierId) && current.sequenceId == sequenceId
				&& data.locators.remove(ownerId, current)) {
			data.setDirty();
		}
	}

	static Locator find(MinecraftServer server, UUID ownerId) {
		return get(server).locators.get(ownerId);
	}

	static Collection<Locator> snapshot(MinecraftServer server) {
		return new ArrayList<>(get(server).locators.values());
	}

	private static ArmorCruiseSavedData load(CompoundTag root) {
		ArmorCruiseSavedData data = new ArmorCruiseSavedData();
		ListTag list = root.getListOrEmpty("Carriers");
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = list.getCompoundOrEmpty(index);
			try {
				UUID owner = TagUtils.getUUID(tag, "Owner");
				UUID carrier = TagUtils.getUUID(tag, "Carrier");
				UUID suit = TagUtils.hasUUID(tag, "Suit") ? TagUtils.getUUID(tag, "Suit") : null;
				Identifier dimensionId = Identifier.parse(tag.getStringOr("Dimension", ""));
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
				ArmorCruisePhase phase;
				try {
					phase = ArmorCruisePhase.valueOf(tag.getStringOr("PhaseName", ""));
				} catch (IllegalArgumentException ignored) {
					phase = ArmorCruisePhase.fromNetworkId(tag.getIntOr("Phase", 0));
				}
				Locator locator = new Locator(owner, carrier, suit, dimension,
						tag.getIntOr("ChunkX", 0), tag.getIntOr("ChunkZ", 0), phase,
						new Vec3(tag.getDoubleOr("AnchorX", 0.0), tag.getDoubleOr("AnchorY", 0.0), tag.getDoubleOr("AnchorZ", 0.0)),
						tag.getLongOr("Sequence", 0L));
				data.locators.put(owner, locator);
			} catch (RuntimeException ignored) {
				// 单条损坏记录不应阻止其余战甲恢复；实体本身仍保留完整物品 NBT。
			}
		}
		return data;
	}

	public CompoundTag save(CompoundTag root) {
		ListTag list = new ListTag();
		for (Locator locator : locators.values()) {
			CompoundTag tag = new CompoundTag();
			TagUtils.putUUID(tag, "Owner", locator.ownerId);
			TagUtils.putUUID(tag, "Carrier", locator.carrierId);
			if (locator.suitId != null) TagUtils.putUUID(tag, "Suit", locator.suitId);
			tag.putString("Dimension", locator.dimension.identifier().toString());
			tag.putInt("ChunkX", locator.chunkX);
			tag.putInt("ChunkZ", locator.chunkZ);
			tag.putInt("Phase", locator.phase.ordinal());
			tag.putString("PhaseName", locator.phase.name());
			tag.putDouble("AnchorX", locator.anchor.x);
			tag.putDouble("AnchorY", locator.anchor.y);
			tag.putDouble("AnchorZ", locator.anchor.z);
			tag.putLong("Sequence", locator.sequenceId);
			list.add(tag);
		}
		root.put("Carriers", list);
		return root;
	}

	record Locator(UUID ownerId, UUID carrierId, UUID suitId, ResourceKey<Level> dimension,
			int chunkX, int chunkZ, ArmorCruisePhase phase, Vec3 anchor, long sequenceId) {
	}
}
