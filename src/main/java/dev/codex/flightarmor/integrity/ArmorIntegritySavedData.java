package dev.codex.flightarmor.integrity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-authoritative suit integrity. No ItemStack, inventory or carrier copy is stored here. */
public final class ArmorIntegritySavedData extends SavedData {
	private static final String DATA_NAME = "flight_armor_integrity";
	private static final int VERSION = 1;
	private static final SavedData.Factory<ArmorIntegritySavedData> FACTORY = new SavedData.Factory<>(
			ArmorIntegritySavedData::new, ArmorIntegritySavedData::load, DataFixTypes.LEVEL);
	private final Map<SuitKey, Record> records = new HashMap<>();

	public static ArmorIntegritySavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
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

	static ArmorIntegritySavedData load(CompoundTag root, HolderLookup.Provider registries) {
		ArmorIntegritySavedData data = new ArmorIntegritySavedData();
		if (root.getInt("Version") != VERSION) data.setDirty();
		ListTag list = root.getList("Suits", Tag.TAG_COMPOUND);
		for (int index = 0; index < list.size(); index++) {
			try {
				Record candidate = readRecord(list.getCompound(index));
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

	@Override
	public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
		root.putInt("Version", VERSION);
		ListTag list = new ListTag();
		for (Record record : records.values()) list.add(writeRecord(record));
		root.put("Suits", list);
		return root;
	}

	static CompoundTag writeRecord(Record record) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION);
		tag.putUUID("Owner", record.key().ownerId());
		tag.putUUID("Suit", record.key().suitId());
		tag.putInt("Integrity", ArmorIntegrityRules.clamp(record.integrity()));
		tag.putLong("Revision", Math.max(1L, record.revision()));
		if (record.lastDirectAttackerId() != null) tag.putUUID("LastDirect", record.lastDirectAttackerId());
		if (record.lastCausingAttackerId() != null) tag.putUUID("LastCausing", record.lastCausingAttackerId());
		tag.putLong("UpdatedAt", Math.max(0L, record.updatedAt()));
		return tag;
	}

	static Record readRecord(CompoundTag tag) {
		UUID owner = tag.getUUID("Owner");
		UUID suit = tag.getUUID("Suit");
		UUID direct = tag.hasUUID("LastDirect") ? tag.getUUID("LastDirect") : null;
		UUID causing = tag.hasUUID("LastCausing") ? tag.getUUID("LastCausing") : null;
		SuitKey key = new SuitKey(owner, suit);
		return new Record(key, ArmorIntegrityRules.clamp(tag.getInt("Integrity")),
				Math.max(1L, tag.getLong("Revision")), direct, causing,
				Math.max(0L, tag.getLong("UpdatedAt")));
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
