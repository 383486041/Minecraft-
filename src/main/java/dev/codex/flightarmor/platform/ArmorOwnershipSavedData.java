package dev.codex.flightarmor.platform;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 以 suitId 为主键的权威战甲位置索引。
 *
 * <p>2.16 及更早版本按 ownerId 只能保存一套战甲，导致第二座平台和创造模式换装都会被
 * 误判为复制。新格式允许同一所有者拥有多套战甲，但每个 suitId 仍只能处于一个位置。</p>
 */
final class ArmorOwnershipSavedData extends SavedData {
	private static final String DATA_NAME = "flight_armor_ownership";
	private static final SavedData.Factory<ArmorOwnershipSavedData> FACTORY = new SavedData.Factory<>(
			ArmorOwnershipSavedData::new,
			ArmorOwnershipSavedData::load,
			DataFixTypes.LEVEL);

	private final Map<UUID, Binding> bindingsBySuit = new HashMap<>();

	static ArmorOwnershipSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	/** 兼容单套调用：优先当前穿戴，其次最近更新的平台套装。 */
	static Optional<Binding> find(UUID ownerId, MinecraftServer server) {
		return findAll(ownerId, server).stream()
				.sorted(Comparator.comparing((Binding binding) -> binding.location() != Location.CARRIED)
						.thenComparing(Binding::revision, Comparator.reverseOrder()))
				.findFirst();
	}

	static Optional<Binding> findBySuit(UUID suitId, MinecraftServer server) {
		return Optional.ofNullable(get(server).bindingsBySuit.get(suitId));
	}

	/**
	 * Removes only a lifecycle-confirmed destroyed suit. Missing chunks, offline owners, broken
	 * platforms, and unresolved locators are not destruction evidence and must never call this.
	 */
	static boolean removeConfirmedDestroyedSuit(MinecraftServer server, UUID ownerId, UUID suitId) {
		if (server == null || ownerId == null || suitId == null) return false;
		ArmorOwnershipSavedData data = get(server);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present == null || !present.ownerId().equals(ownerId)
				|| !data.bindingsBySuit.remove(suitId, present)) return false;
		data.setDirty();
		return true;
	}

	static List<Binding> findAll(UUID ownerId, MinecraftServer server) {
		List<Binding> result = new ArrayList<>();
		for (Binding binding : get(server).bindingsBySuit.values()) {
			if (binding.ownerId().equals(ownerId)) result.add(binding);
		}
		result.sort(Comparator.comparingLong(Binding::revision).reversed());
		return List.copyOf(result);
	}

	static List<Binding> findPlatformBindings(UUID ownerId, MinecraftServer server) {
		return findAll(ownerId, server).stream()
				.filter(binding -> binding.location() == Location.PLATFORM
						&& binding.dimension() != null && binding.pos() != null)
				.toList();
	}

	static DepositDecision prepareDeposit(MinecraftServer server, UUID ownerId, Optional<UUID> itemSuitId,
			ResourceKey<Level> dimension, BlockPos pos, boolean samePlatformAlreadyOwnsItems) {
		ArmorOwnershipSavedData data = get(server);
		UUID suitId = itemSuitId.orElseGet(UUID::randomUUID);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present != null) {
			if (!present.ownerId().equals(ownerId)) {
				return DepositDecision.denied("该部件属于其他玩家的认主战甲");
			}
			if (present.location() == Location.PLATFORM && !present.matchesPlatform(dimension, pos)) {
				return DepositDecision.denied("这套战甲已经存放在另一座充能平台上");
			}
		} else if (itemSuitId.isPresent()) {
			// 已带身份但索引缺失时允许从真实物品修复索引。
			present = null;
		} else if (samePlatformAlreadyOwnsItems) {
			return DepositDecision.denied("平台已有认主部件，不能混入新的无身份部件");
		}

		Binding next = new Binding(ownerId, suitId, Location.PLATFORM, dimension, pos.immutable(),
				present == null ? 1L : present.revision() + 1L);
		data.bindingsBySuit.put(suitId, next);
		data.setDirty();
		return DepositDecision.allowed(suitId, next.revision());
	}

	static DepositPlan planCompleteDeposit(MinecraftServer server, UUID ownerId, Optional<UUID> itemSuitId,
			ResourceKey<Level> dimension, BlockPos pos) {
		ArmorOwnershipSavedData data = get(server);
		UUID plannedSuitId = itemSuitId.orElseGet(UUID::randomUUID);
		Binding present = data.bindingsBySuit.get(plannedSuitId);
		if (present != null) {
			if (!present.ownerId().equals(ownerId)) {
				return DepositPlan.denied("该套战甲已经认主给其他玩家");
			}
			if (present.location() == Location.CRUISE) {
				return DepositPlan.denied("该套战甲仍处于独立巡航，不能把复制部件接入平台");
			}
			if (present.location() == Location.PLATFORM && !present.matchesPlatform(dimension, pos)) {
				return DepositPlan.denied("这套战甲已经绑定在另一座充能平台上");
			}
		}
		Binding next = new Binding(ownerId, plannedSuitId, Location.PLATFORM, dimension, pos.immutable(),
				present == null ? 1L : present.revision() + 1L);
		return DepositPlan.allowed(ownerId, plannedSuitId, present, next);
	}

	static boolean commitCompleteDeposit(MinecraftServer server, DepositPlan plan) {
		if (plan == null || !plan.allowed()) return false;
		ArmorOwnershipSavedData data = get(server);
		if (!Objects.equals(data.bindingsBySuit.get(plan.suitId()), plan.expectedBinding())) return false;
		data.bindingsBySuit.put(plan.suitId(), plan.nextBinding());
		data.setDirty();
		return true;
	}

	static boolean rollbackCompleteDeposit(MinecraftServer server, DepositPlan plan) {
		if (plan == null || !plan.allowed()) return false;
		ArmorOwnershipSavedData data = get(server);
		if (!Objects.equals(data.bindingsBySuit.get(plan.suitId()), plan.nextBinding())) return false;
		if (plan.expectedBinding() == null) data.bindingsBySuit.remove(plan.suitId());
		else data.bindingsBySuit.put(plan.suitId(), plan.expectedBinding());
		data.setDirty();
		return true;
	}

	static DepositDecision prepareCarriedClaim(MinecraftServer server, UUID ownerId, Optional<UUID> itemSuitId) {
		ArmorOwnershipSavedData data = get(server);
		UUID suitId = itemSuitId.orElseGet(UUID::randomUUID);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present != null) {
			if (!present.ownerId().equals(ownerId)) {
				return DepositDecision.denied("身上的部件不属于你的认主战甲");
			}
			if (present.location() == Location.PLATFORM) {
				return DepositDecision.denied("这套战甲仍在充能平台上，不能同时认领复制套装");
			}
		}
		Binding next = new Binding(ownerId, suitId, Location.CARRIED, null, null,
				present == null ? 1L : present.revision() + 1L);
		data.bindingsBySuit.put(suitId, next);
		data.setDirty();
		return DepositDecision.allowed(suitId, next.revision());
	}

	static boolean markCarriedIfMatches(MinecraftServer server, UUID ownerId, UUID suitId,
			ResourceKey<Level> dimension, BlockPos pos) {
		ArmorOwnershipSavedData data = get(server);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present == null || !present.ownerId().equals(ownerId)
				|| !present.matchesPlatform(dimension, pos)) return false;
		data.bindingsBySuit.put(suitId, present.withLocation(Location.CARRIED, null, null));
		data.setDirty();
		return true;
	}

	static boolean markPlatformIfCarried(MinecraftServer server, UUID ownerId, UUID suitId,
			ResourceKey<Level> dimension, BlockPos pos) {
		ArmorOwnershipSavedData data = get(server);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present == null || !present.ownerId().equals(ownerId)
				|| present.location() != Location.CARRIED) return false;
		data.bindingsBySuit.put(suitId, new Binding(ownerId, suitId, Location.PLATFORM,
				dimension, pos.immutable(), present.revision() + 1L));
		data.setDirty();
		return true;
	}

	static void markPlatform(MinecraftServer server, UUID ownerId, UUID suitId,
			ResourceKey<Level> dimension, BlockPos pos) {
		ArmorOwnershipSavedData data = get(server);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present != null && !present.ownerId().equals(ownerId)) return;
		data.bindingsBySuit.put(suitId, new Binding(ownerId, suitId, Location.PLATFORM,
				dimension, pos.immutable(), present == null ? 1L : present.revision() + 1L));
		data.setDirty();
	}

	static void markLooseIfMatches(MinecraftServer server, UUID ownerId, UUID suitId,
			ResourceKey<Level> dimension, BlockPos pos) {
		ArmorOwnershipSavedData data = get(server);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present == null || !present.ownerId().equals(ownerId)
				|| !present.matchesPlatform(dimension, pos)) return;
		data.bindingsBySuit.put(suitId, present.withLocation(Location.LOOSE, dimension, pos));
		data.setDirty();
	}

	static boolean markCruiseIfMatches(MinecraftServer server, UUID ownerId, UUID suitId,
			ResourceKey<Level> dimension, BlockPos pos) {
		ArmorOwnershipSavedData data = get(server);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present == null || !present.ownerId().equals(ownerId)
				|| !present.matchesPlatform(dimension, pos)) return false;
		data.bindingsBySuit.put(suitId, present.withLocation(Location.CRUISE, null, null));
		data.setDirty();
		return true;
	}

	/** Quick switching moves a worn suit from CARRIED to an escort carrier. */
	static boolean markCruiseIfCarried(MinecraftServer server, UUID ownerId, UUID suitId) {
		ArmorOwnershipSavedData data = get(server);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present == null || !present.ownerId().equals(ownerId)
				|| present.location() != Location.CARRIED) return false;
		data.bindingsBySuit.put(suitId,
				present.withLocation(Location.CRUISE, null, null));
		data.setDirty();
		return true;
	}

	static boolean markPlatformIfCruise(MinecraftServer server, UUID ownerId, UUID suitId,
			ResourceKey<Level> dimension, BlockPos pos) {
		ArmorOwnershipSavedData data = get(server);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present == null || !present.ownerId().equals(ownerId)
				|| present.location() != Location.CRUISE) return false;
		data.bindingsBySuit.put(suitId, new Binding(ownerId, suitId, Location.PLATFORM,
				dimension, pos.immutable(), present.revision() + 1L));
		data.setDirty();
		return true;
	}

	/** 护卫载体近身着装时，把唯一位置从巡航原子切换为玩家携带。 */
	static boolean markCarriedIfCruise(MinecraftServer server, UUID ownerId, UUID suitId) {
		ArmorOwnershipSavedData data = get(server);
		Binding present = data.bindingsBySuit.get(suitId);
		if (present == null || !present.ownerId().equals(ownerId)
				|| present.location() != Location.CRUISE) return false;
		data.bindingsBySuit.put(suitId,
				present.withLocation(Location.CARRIED, null, null));
		data.setDirty();
		return true;
	}

	private static ArmorOwnershipSavedData load(CompoundTag root, HolderLookup.Provider registries) {
		ArmorOwnershipSavedData data = new ArmorOwnershipSavedData();
		ListTag list = root.getList("Bindings", Tag.TAG_COMPOUND);
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = list.getCompound(index);
			try {
				UUID ownerId = tag.getUUID("Owner");
				UUID suitId = tag.getUUID("Suit");
				Location location = Location.valueOf(tag.getString("Location"));
				ResourceKey<Level> dimension = null;
				BlockPos pos = null;
				if (tag.contains("Dimension", Tag.TAG_STRING)) {
					dimension = ResourceKey.create(Registries.DIMENSION,
							ResourceLocation.parse(tag.getString("Dimension")));
				}
				if (tag.contains("X", Tag.TAG_INT)) {
					pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
				}
				Binding binding = new Binding(ownerId, suitId, location, dimension, pos, tag.getLong("Revision"));
				Binding older = data.bindingsBySuit.get(suitId);
				if (older == null || binding.revision() >= older.revision()) {
					data.bindingsBySuit.put(suitId, binding);
				}
			} catch (RuntimeException ignored) {
				// 单条损坏记录不影响其他套装；真实 ItemStack 仍是最终物品事实。
			}
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		for (Binding binding : bindingsBySuit.values()) {
			CompoundTag tag = new CompoundTag();
			tag.putUUID("Owner", binding.ownerId());
			tag.putUUID("Suit", binding.suitId());
			tag.putString("Location", binding.location().name());
			tag.putLong("Revision", binding.revision());
			if (binding.dimension() != null) tag.putString("Dimension", binding.dimension().location().toString());
			if (binding.pos() != null) {
				tag.putInt("X", binding.pos().getX());
				tag.putInt("Y", binding.pos().getY());
				tag.putInt("Z", binding.pos().getZ());
			}
			list.add(tag);
		}
		root.put("Bindings", list);
		root.putInt("FormatVersion", 2);
		return root;
	}

	enum Location { CARRIED, PLATFORM, CRUISE, LOOSE }

	record Binding(UUID ownerId, UUID suitId, Location location,
			ResourceKey<Level> dimension, BlockPos pos, long revision) {
		boolean matchesPlatform(ResourceKey<Level> expectedDimension, BlockPos expectedPos) {
			return location == Location.PLATFORM && dimension != null && pos != null
					&& dimension.equals(expectedDimension) && pos.equals(expectedPos);
		}

		Binding withLocation(Location nextLocation, ResourceKey<Level> nextDimension, BlockPos nextPos) {
			return new Binding(ownerId, suitId, nextLocation, nextDimension,
					nextPos == null ? null : nextPos.immutable(), revision + 1L);
		}
	}

	record DepositPlan(boolean allowed, UUID ownerId, UUID suitId,
			Binding expectedBinding, Binding nextBinding, String message) {
		static DepositPlan allowed(UUID ownerId, UUID suitId, Binding expectedBinding, Binding nextBinding) {
			return new DepositPlan(true, ownerId, suitId, expectedBinding, nextBinding, "");
		}

		static DepositPlan denied(String message) {
			return new DepositPlan(false, null, null, null, null, message);
		}
	}

	record DepositDecision(boolean allowed, UUID suitId, long revision, String message) {
		static DepositDecision allowed(UUID suitId, long revision) {
			return new DepositDecision(true, suitId, revision, "");
		}

		static DepositDecision denied(String message) {
			return new DepositDecision(false, null, 0L, message);
		}
	}
}
