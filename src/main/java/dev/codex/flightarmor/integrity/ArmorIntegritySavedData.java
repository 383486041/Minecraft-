package dev.codex.flightarmor.integrity;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.util.TagUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-authoritative suit integrity. No ItemStack, inventory or carrier copy is stored here. */
public final class ArmorIntegritySavedData extends SavedData {
	private static final String DATA_NAME = "flight_armor_integrity";
	private static final int VERSION = 1;
	private static final SavedDataType<ArmorIntegritySavedData> TYPE = new SavedDataType<>(
			FlightArmorMod.id(DATA_NAME),
			ArmorIntegritySavedData::new,
			CompoundTag.CODEC.xmap(ArmorIntegritySavedData::load, data -> data.save(new CompoundTag())),
			DataFixTypes.LEVEL);
	private final Map<SuitKey, Record> records = new HashMap<>();

	public static ArmorIntegritySavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public Snapshot snapshot(UUID ownerId, UUID suitId) {
		Record record = records.get(new SuitKey(ownerId, suitId));
		return record == null ? Snapshot.full(ownerId, suitId) : record.snapshot();
	}

	public Mutation damage(UUID ownerId, UUID suitId, int amount,
			UUID directAttackerId, UUID causingAttackerId, long gameTime) {
		SuitKey key = new SuitKey(ownerId, suitId);
		Record before = records.get(key);
		int oldValue = before == null ? ArmorIntegrityRules.MAX_INTEGRITY : before.integrity();
		int nextValue = ArmorIntegrityRules.applyDamage(oldValue, amount);
		if (nextValue == oldValue) return new Mutation(oldValue, nextValue, false);
		long revision = before == null ? 1L : before.revision() + 1L;
		records.put(key, new Record(key, nextValue, revision, directAttackerId,
				causingAttackerId, Math.max(0L, gameTime)));
		setDirty();
		return new Mutation(oldValue, nextValue, true);
	}

	public Mutation repair(UUID ownerId, UUID suitId, int amount, long gameTime) {
		SuitKey key = new SuitKey(ownerId, suitId);
		Record before = records.get(key);
		int oldValue = before == null ? ArmorIntegrityRules.MAX_INTEGRITY : before.integrity();
		int nextValue = ArmorIntegrityRules.repair(oldValue, amount);
		if (nextValue == oldValue) return new Mutation(oldValue, nextValue, false);
		long revision = before == null ? 1L : before.revision() + 1L;
		records.put(key, new Record(key, nextValue, revision,
				before == null ? null : before.lastDirectAttackerId(),
				before == null ? null : before.lastCausingAttackerId(), Math.max(0L, gameTime)));
		setDirty();
		return new Mutation(oldValue, nextValue, true);
	}

	static ArmorIntegritySavedData load(CompoundTag root) {
		ArmorIntegritySavedData data = new ArmorIntegritySavedData();
		if (root.getIntOr("Version", 0) != VERSION) data.setDirty();
		ListTag list = root.getListOrEmpty("Suits");
		for (int index = 0; index < list.size(); index++) {
			try {
				Record candidate = readRecord(list.getCompoundOrEmpty(index));
				Record current = data.records.get(candidate.key());
				if (current == null || candidate.revision() > current.revision()
						|| candidate.revision() == current.revision()
						&& candidate.integrity() < current.integrity()) data.records.put(candidate.key(), candidate);
			} catch (RuntimeException ignored) {
				// One corrupt identity record must not prevent the remaining suits from loading.
			}
		}
		return data;
	}

	public CompoundTag save(CompoundTag root) {
		root.putInt("Version", VERSION);
		ListTag list = new ListTag();
		for (Record record : records.values()) list.add(writeRecord(record));
		root.put("Suits", list);
		return root;
	}

	static CompoundTag writeRecord(Record record) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION);
		TagUtils.putUUID(tag, "Owner", record.key().ownerId());
		TagUtils.putUUID(tag, "Suit", record.key().suitId());
		tag.putInt("Integrity", ArmorIntegrityRules.clamp(record.integrity()));
		tag.putLong("Revision", Math.max(1L, record.revision()));
		if (record.lastDirectAttackerId() != null) TagUtils.putUUID(tag, "LastDirect", record.lastDirectAttackerId());
		if (record.lastCausingAttackerId() != null) TagUtils.putUUID(tag, "LastCausing", record.lastCausingAttackerId());
		tag.putLong("UpdatedAt", Math.max(0L, record.updatedAt()));
		return tag;
	}

	static Record readRecord(CompoundTag tag) {
		UUID owner = TagUtils.getUUID(tag, "Owner");
		UUID suit = TagUtils.getUUID(tag, "Suit");
		UUID direct = TagUtils.hasUUID(tag, "LastDirect") ? TagUtils.getUUID(tag, "LastDirect") : null;
		UUID causing = TagUtils.hasUUID(tag, "LastCausing") ? TagUtils.getUUID(tag, "LastCausing") : null;
		SuitKey key = new SuitKey(owner, suit);
		return new Record(key, ArmorIntegrityRules.clamp(tag.getIntOr("Integrity", 0)),
				Math.max(1L, tag.getLongOr("Revision", 1L)), direct, causing,
				Math.max(0L, tag.getLongOr("UpdatedAt", 0L)));
	}

	public record SuitKey(UUID ownerId, UUID suitId) {
		public SuitKey {
			Objects.requireNonNull(ownerId, "ownerId");
			Objects.requireNonNull(suitId, "suitId");
		}
	}

	public record Snapshot(UUID ownerId, UUID suitId, int integrity, long revision,
			UUID lastDirectAttackerId, UUID lastCausingAttackerId, long updatedAt) {
		static Snapshot full(UUID ownerId, UUID suitId) {
			return new Snapshot(ownerId, suitId, ArmorIntegrityRules.MAX_INTEGRITY,
					0L, null, null, 0L);
		}
	}

	record Record(SuitKey key, int integrity, long revision,
			UUID lastDirectAttackerId, UUID lastCausingAttackerId, long updatedAt) {
		Snapshot snapshot() {
			return new Snapshot(key.ownerId(), key.suitId(), integrity, revision,
					lastDirectAttackerId, lastCausingAttackerId, updatedAt);
		}
	}

	public record Mutation(int previous, int current, boolean changed) {}
}
