package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.util.TagUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 战甲武器的跨连接资源快照。
 *
 * <p>仅保存玩家真正拥有的资源：能量、热量、过热锁、腕炮冷却剩余量、
 * 被动恢复延迟剩余量和下一次发射手。
 * 攻击阶段、客户端序列号、攻击事件号等连接内瞬时状态禁止写入磁盘。</p>
 */
final class ArmorCombatSavedData extends SavedData {
	private static final String DATA_NAME = "flight_armor_combat_resources";
	private static final int DATA_VERSION = 3;
	private static final int LEGACY_PERCENT_ENERGY_MAX_VERSION = 2;
	private static final SavedDataType<ArmorCombatSavedData> TYPE = new SavedDataType<>(
			FlightArmorMod.id(DATA_NAME),
			ArmorCombatSavedData::new,
			CompoundTag.CODEC.xmap(ArmorCombatSavedData::load, data -> data.save(new CompoundTag())),
			DataFixTypes.LEVEL);

	private final Map<UUID, Entry> entries = new HashMap<>();

	static ArmorCombatSavedData get(MinecraftServer server) {
		// 所有维度共用主世界数据存储，冷却时钟也统一使用主世界 gameTime。
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	static Entry find(MinecraftServer server, UUID ownerId) {
		return get(server).entries.get(ownerId);
	}

	/**
	 * 始终更新内存快照；只有 markDirty=true 时才通知世界存档系统写盘。
	 * 这样连续回能/散热可按控制器的固定周期合并磁盘脏标记。
	 */
	static void update(MinecraftServer server, UUID ownerId, Entry entry, boolean markDirty) {
		ArmorCombatSavedData data = get(server);
		data.entries.put(ownerId, entry);
		if (markDirty) {
			// 此前若只更新了内存快照，old 可能与本次相同，但磁盘仍是更早的值。
			data.setDirty();
		}
	}

	/** 断线时即使快照数值刚好相同也强制进入正常 SavedData 保存流程。 */
	static void flush(MinecraftServer server, UUID ownerId, Entry entry) {
		ArmorCombatSavedData data = get(server);
		data.entries.put(ownerId, entry);
		data.setDirty();
	}

	private static ArmorCombatSavedData load(CompoundTag root) {
		ArmorCombatSavedData data = new ArmorCombatSavedData();
		int storedVersion = root.getIntOr("Version", 0);
		ListTag players = root.getListOrEmpty("Players");
		for (int index = 0; index < players.size(); index++) {
			CompoundTag tag = players.getCompoundOrEmpty(index);
			try {
				UUID ownerId = TagUtils.getUUID(tag, "Owner");
				float storedEnergy = tag.getFloatOr("Energy", 0.0F);
				// v1/v2 使用 100 点上限；升级时保持原来的百分比，避免满能核心只剩 1%。
				float energy = storedVersion <= LEGACY_PERCENT_ENERGY_MAX_VERSION ? Mth.clamp(storedEnergy / 100.0F, 0.0F, 1.0F)
						* ArmorCombatTuning.MAX_ENERGY : Mth.clamp(storedEnergy, 0.0F, ArmorCombatTuning.MAX_ENERGY);
				float heat = Mth.clamp(tag.getFloatOr("Heat", 0.0F), 0.0F, ArmorCombatTuning.MAX_HEAT);
				boolean overheated = tag.getBooleanOr("Overheated", false);
				long wristCooldownRemaining = Math.max(0L, tag.getLongOr("WristCooldownRemaining", 0L));
				long coreCooldownRemaining = Math.max(0L, tag.getLongOr("CoreCooldownRemaining", 0L));
				long diveImpactCooldownRemaining = Math.max(0L, tag.getLongOr("DiveImpactCooldownRemaining", 0L));
				long recoveryDelayRemaining = Math.max(0L, tag.getLongOr("RecoveryDelayRemaining", 0L));
				boolean nextLeftHand = tag.getBooleanOr("NextLeftHand", false);
				data.entries.put(ownerId, new Entry(
						energy, heat, overheated, wristCooldownRemaining,
						coreCooldownRemaining, diveImpactCooldownRemaining,
						recoveryDelayRemaining, nextLeftHand));
			} catch (RuntimeException ignored) {
				// 单条损坏记录回退为首次使用默认值，不影响其他玩家资源恢复。
			}
		}
		if (storedVersion < DATA_VERSION) data.setDirty();
		return data;
	}

	public CompoundTag save(CompoundTag root) {
		root.putInt("Version", DATA_VERSION);
		ListTag players = new ListTag();
		for (Map.Entry<UUID, Entry> mapEntry : entries.entrySet()) {
			Entry entry = mapEntry.getValue();
			CompoundTag tag = new CompoundTag();
			TagUtils.putUUID(tag, "Owner", mapEntry.getKey());
			tag.putFloat("Energy", entry.energy());
			tag.putFloat("Heat", entry.heat());
			tag.putBoolean("Overheated", entry.overheated());
			tag.putLong("WristCooldownRemaining", entry.wristCooldownRemaining());
			tag.putLong("CoreCooldownRemaining", entry.coreCooldownRemaining());
			tag.putLong("DiveImpactCooldownRemaining", entry.diveImpactCooldownRemaining());
			tag.putLong("RecoveryDelayRemaining", entry.recoveryDelayRemaining());
			tag.putBoolean("NextLeftHand", entry.nextLeftHand());
			players.add(tag);
		}
		root.put("Players", players);
		return root;
	}

	record Entry(float energy, float heat, boolean overheated,
			long wristCooldownRemaining, long coreCooldownRemaining,
			long diveImpactCooldownRemaining, long recoveryDelayRemaining, boolean nextLeftHand) {
		Entry {
			energy = Float.isFinite(energy)
					? Mth.clamp(energy, 0.0F, ArmorCombatTuning.MAX_ENERGY)
					: ArmorCombatTuning.MAX_ENERGY;
			heat = Float.isFinite(heat)
					? Mth.clamp(heat, 0.0F, ArmorCombatTuning.MAX_HEAT)
					: 0.0F;
			wristCooldownRemaining = Math.min(Math.max(0L, wristCooldownRemaining),
					ArmorCombatTuning.WRIST_COOLDOWN_TICKS);
			coreCooldownRemaining = Math.min(Math.max(0L, coreCooldownRemaining),
					ArmorCombatTuning.CORE_COOLDOWN_TICKS);
			
			diveImpactCooldownRemaining = Math.min(Math.max(0L, diveImpactCooldownRemaining),
					ArmorCombatTuning.DIVE_IMPACT_COOLDOWN_TICKS);
			recoveryDelayRemaining = Math.min(Math.max(0L, recoveryDelayRemaining),
					ArmorCombatTuning.PASSIVE_RECOVERY_DELAY_TICKS);
			overheated = heat >= ArmorCombatTuning.MAX_HEAT
					|| overheated && heat > ArmorCombatTuning.OVERHEAT_UNLOCK_HEAT;
		}
	}
}
