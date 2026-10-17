package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.combat.ArmorCombatController;
import dev.codex.flightarmor.combat.ArmorCombatDenyReason;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.core.ArmorEscortProfile;
import dev.codex.flightarmor.flight.FlightController;
import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.integrity.ArmorIntegrityController;
import dev.codex.flightarmor.network.ArmorEscortStatePayload;
import dev.codex.flightarmor.platform.ArmorIdentity;
import dev.codex.flightarmor.platform.ChargingPlatformBlockEntity;
import dev.codex.flightarmor.platform.ChargingPlatformController;
import dev.codex.flightarmor.platform.ChargingPlatformDisplayMode;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Rotations;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多副战甲轻量控制器。主战甲仍由 ArmorCruiseController 负责着装、死亡和防坠；这里的每个
 * suitId 都拥有独立载体、编队位置与自动战斗节奏，不会覆盖主战甲 owner-keyed 状态。
 */
public final class ArmorEscortController {
	public static final String CARRIER_TAG = "flight_armor_escort_carrier";
	public static final int MAX_ACTIVE_ESCORTS_PER_OWNER = 7;
	private static final String OWNER_TAG_PREFIX = "flight_armor_escort_owner_";
	private static final String SUIT_TAG_PREFIX = "flight_armor_escort_suit_";
	private static final int LAUNCH_TICKS = 9;
	private static final int RETURN_TICKET_RADIUS = 2;
	private static final TicketType RETURN_TICKET =
			new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
	private static final TicketType RECOVERY_TICKET =
			new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
	private static final Map<UUID, EscortState> STATES = new ConcurrentHashMap<>();
	private static final Set<UUID> SPAWNING = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> MIGRATING = ConcurrentHashMap.newKeySet();
	private static final AtomicLong NEXT_CARRIER_REVISION = new AtomicLong();

	private ArmorEscortController() {}

	public static void initialize() {
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
				isEscortCarrier(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
				InteractionResult.PASS);
	}

	private static boolean escortCapacityAvailable(MinecraftServer server, UUID ownerId, UUID suitId) {
		Set<UUID> active = new HashSet<>();
		for (EscortState state : STATES.values()) {
			if (state.ownerId.equals(ownerId)) active.add(state.suitId);
		}
		for (ArmorEscortSavedData.Locator locator : ArmorEscortSavedData.findByOwner(server, ownerId)) {
			active.add(locator.suitId());
		}
		return active.contains(suitId) || active.size() < MAX_ACTIVE_ESCORTS_PER_OWNER;
	}

	public static LaunchResult launchFromPlatform(ServerPlayer owner, UUID suitId, UUID platformId) {
		if (owner == null || !owner.isAlive() || owner.isSpectator()) return LaunchResult.failed("玩家当前无法召回副战甲");
		if (ArmorCoreController.hasActiveQuickSwitch(owner) || ArmorAssemblyController.isAnimating(owner)) {
			return LaunchResult.failed("已有战甲切换或着装事务，不能同时启动副甲");
		}
		if (STATES.containsKey(suitId)) return LaunchResult.failed("该副战甲已经处于巡航状态");
		if (ArmorEscortSavedData.find(owner.level().getServer(), suitId) != null) {
			return LaunchResult.failed("该副战甲存在待恢复载体，已拒绝生成新载体");
		}
		if (!escortCapacityAvailable(owner.level().getServer(), owner.getUUID(), suitId)) {
			return LaunchResult.failed("\u540c\u4e00\u73a9\u5bb6\u6700\u591a\u540c\u65f6\u542f\u7528 7 \u5957\u62a4\u822a\u6218\u7532");
		}
		ChargingPlatformController.EscortTransfer transfer =
				ChargingPlatformController.extractForEscort(owner, suitId, platformId);
		if (!transfer.success()) return LaunchResult.failed(transfer.message());

		ServerLevel originLevel = owner.level().getServer().getLevel(transfer.originDimension());
		if (originLevel == null) {
			ChargingPlatformController.rollbackEscortTransfer(owner, transfer);
			return LaunchResult.failed("副战甲所在维度不可用");
		}
		Vec3 origin = Vec3.atBottomCenterOf(transfer.originPos()).add(0.0D, 0.625D, 0.0D);
		ArmorStand carrier = new ArmorStand(originLevel, origin.x, origin.y, origin.z);
		configureCarrier(carrier, owner.getUUID(), suitId);
		for (int index = 0; index < ChargingPlatformBlockEntity.ARMOR_SLOTS.size(); index++) {
			carrier.setItemSlot(ChargingPlatformBlockEntity.ARMOR_SLOTS.get(index), transfer.items().get(index));
		}
		SPAWNING.add(carrier.getUUID());
		boolean spawned;
		try {
			spawned = originLevel.addFreshEntity(carrier);
		} finally {
			SPAWNING.remove(carrier.getUUID());
		}
		if (!spawned) {
			for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) carrier.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
			ChargingPlatformController.rollbackEscortTransfer(owner, transfer);
			return LaunchResult.failed("副战甲载体生成失败，物品已返回平台");
		}

		long now = originLevel.getGameTime();
		EscortState state = new EscortState(owner.getUUID(), suitId, carrier, Phase.LAUNCHING,
				now, origin, true, transfer.platformId(), transfer.originDimension(),
				transfer.originPos(), transfer.displayMode());
		if (STATES.putIfAbsent(suitId, state) != null) {
			for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) carrier.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
			carrier.discard();
			ChargingPlatformController.rollbackEscortTransfer(owner, transfer);
			return LaunchResult.failed("副战甲状态冲突，物品已返回平台");
		}
		if (!syncLocator(state)) {
			STATES.remove(suitId, state);
			clearCarrierArmor(carrier);
			carrier.discard();
			ChargingPlatformController.rollbackEscortTransfer(owner, transfer);
			return LaunchResult.failed("副战甲持久索引冲突，物品已返回平台");
		}
		broadcast(owner.level().getServer(), state, true);
		return LaunchResult.success(suitId);
	}

	/**
	 * Reclassifies one stable independent carrier as an escort without moving or copying armor items.
	 * ArmorCruiseController temporarily removes its owner-keyed state before calling this method.
	 */
	static boolean isOptionalHomeComplete(UUID homePlatformId,
			ResourceKey<Level> homeDimension, BlockPos homePos) {
		int present = (homePlatformId == null ? 0 : 1)
				+ (homeDimension == null ? 0 : 1)
				+ (homePos == null ? 0 : 1);
		return present == 0 || present == 3;
	}

	static EscortCommandResult adoptIndependentCarrier(ServerPlayer owner, UUID suitId,
			ArmorStand carrier, UUID homePlatformId, ResourceKey<Level> homeDimension, BlockPos homePos) {
		if (owner == null || suitId == null || carrier == null
				|| !isOptionalHomeComplete(homePlatformId, homeDimension, homePos)
				|| !owner.isAlive() || owner.isSpectator()) {
			return EscortCommandResult.failed("独立巡航转护航参数不完整");
		}
		if (!escortCapacityAvailable(owner.level().getServer(), owner.getUUID(), suitId)) {
			return EscortCommandResult.failed("\u62a4\u822a\u6218\u7532\u5df2\u8fbe\u540c\u65f6\u542f\u7528\u4e0a\u9650");
		}
		if (ArmorEscortSavedData.find(owner.level().getServer(), suitId) != null) {
			return EscortCommandResult.failed("该战甲存在待恢复护航载体，不能覆盖持久索引");
		}
		if (STATES.containsKey(suitId) || carrier.isRemoved() || !carrier.isAlive()
				|| !(carrier.level() instanceof ServerLevel) || !hasFullSet(carrier)) {
			return EscortCommandResult.failed("独立巡航载体当前不能转入护航");
		}
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(carrier.getItemBySlot(slot));
			if (identity.isEmpty() || !identity.get().ownerId().equals(owner.getUUID())
					|| !identity.get().suitId().equals(suitId) || identity.get().slot() != slot) {
				return EscortCommandResult.failed("独立巡航载体的战甲身份不完整");
			}
		}
		long now = ((ServerLevel) carrier.level()).getGameTime();
		EscortState state = new EscortState(owner.getUUID(), suitId, carrier, Phase.ESCORTING,
				now, carrier.position(), true, homePlatformId, homeDimension, homePos,
				ChargingPlatformDisplayMode.ASSEMBLED);
		if (STATES.putIfAbsent(suitId, state) != null) {
			return EscortCommandResult.failed("该战甲已有护航状态");
		}
		if (!syncLocator(state)) {
			STATES.remove(suitId, state);
			return EscortCommandResult.failed("护航持久索引冲突，独立巡航状态保持不变");
		}
		carrier.removeTag(ArmorCruiseController.CARRIER_TAG);
		carrier.removeTag(ArmorCruiseController.OWNER_TAG_PREFIX + owner.getUUID());
		configureCarrier(carrier, owner.getUUID(), suitId);
		broadcast(owner.level().getServer(), state, true);
		return EscortCommandResult.started("独立巡航战甲已无缝接入副甲护航编队");
	}

	/**
	 * Atomically transfers the existing independent carrier into a platform-return state. The
	 * carrier remains the sole owner of all four ItemStacks; no armor item is copied or moved during
	 * controller handoff.
	 */
	static EscortCommandResult adoptIndependentCarrierForReturn(ServerPlayer owner, UUID suitId,
			ArmorStand carrier, UUID homePlatformId, ResourceKey<Level> homeDimension, BlockPos homePos,
			ChargingPlatformDisplayMode displayMode) {
		if (owner == null || suitId == null || carrier == null || homePlatformId == null
				|| homeDimension == null || homePos == null || displayMode == null
				|| !owner.isAlive() || owner.isSpectator()) {
			return EscortCommandResult.failed("独立巡航返台参数不完整");
		}
		if (ArmorEscortSavedData.find(owner.level().getServer(), suitId) != null) {
			return EscortCommandResult.failed("该战甲存在待恢复护航载体，不能覆盖持久索引");
		}
		if (STATES.containsKey(suitId) || carrier.isRemoved() || !carrier.isAlive()
				|| !(carrier.level() instanceof ServerLevel level) || !hasFullSet(carrier)) {
			return EscortCommandResult.failed("独立巡航载体当前不能返台");
		}
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(carrier.getItemBySlot(slot));
			if (identity.isEmpty() || !identity.get().ownerId().equals(owner.getUUID())
					|| !identity.get().suitId().equals(suitId) || identity.get().slot() != slot) {
				return EscortCommandResult.failed("独立巡航载体的战甲身份不完整");
			}
		}

		long now = level.getGameTime();
		EscortState state = new EscortState(owner.getUUID(), suitId, carrier, Phase.RETURNING,
				now, carrier.position(), false, homePlatformId, homeDimension, homePos, displayMode);
		if (STATES.putIfAbsent(suitId, state) != null) {
			return EscortCommandResult.failed("该战甲已有护航或返航状态");
		}
		if (!syncLocator(state)) {
			STATES.remove(suitId, state);
			return EscortCommandResult.failed("返航持久索引冲突，独立巡航状态保持不变");
		}
		ArmorCruiseController.clearTransferredCarrierTags(carrier, owner.getUUID());
		configureCarrier(carrier, owner.getUUID(), suitId);
		setCombatLifecycleReady(state, false);
		updateReturnTicket(state);
		broadcast(owner.level().getServer(), state, true);
		return EscortCommandResult.started("独立巡航战甲已直接开始返航原平台");
	}

	public static boolean isActive(UUID suitId) {
		return suitId != null && STATES.containsKey(suitId);
	}

	/** Physical loaded-state proof used by the runtime resolver; persisted/unloaded stays offline. */
	public static boolean isLoadedActive(UUID suitId) {
		EscortState state = suitId == null ? null : STATES.get(suitId);
		return state != null && state.carrier != null
				&& !state.carrier.isRemoved() && state.carrier.isAlive()
				&& hasOwnedFullSet(state.carrier, state.ownerId, state.suitId);
	}

	/**
	 * Exact loaded carrier proof for nearby manual commands. Persisted/unloaded, launching,
	 * returning and equipping carriers deliberately fail closed.
	 */
	static RemoteHandle remoteHandle(ServerPlayer owner, UUID suitId) {
		EscortState state = owner == null || suitId == null ? null : STATES.get(suitId);
		if (state == null || !state.ownerId.equals(owner.getUUID())
				|| state.phase != Phase.ESCORTING || state.carrier == null
				|| state.carrier.isRemoved() || !state.carrier.isAlive()
				|| !hasOwnedFullSet(state.carrier, state.ownerId, state.suitId)) {
			return null;
		}
		return new RemoteHandle(state.suitId, state.carrier, state.carrierRevision);
	}

	/** Stable loaded escort candidates for server-side nearest-carrier commands. */
	static List<RemoteHandle> remoteHandles(ServerPlayer owner) {
		if (owner == null) return List.of();
		return STATES.values().stream()
				.filter(state -> state.ownerId.equals(owner.getUUID())
						&& state.phase == Phase.ESCORTING
						&& state.carrier != null && !state.carrier.isRemoved()
						&& state.carrier.isAlive()
						&& hasOwnedFullSet(state.carrier, state.ownerId, state.suitId))
				.map(state -> new RemoteHandle(
						state.suitId, state.carrier, state.carrierRevision))
				.toList();
	}

	static record RemoteHandle(UUID suitId, ArmorStand carrier, long carrierRevision) {
	}

	/**
	 * 该玩家当前已加载护航甲的确定性快照（按 suitId 排序），供「一键循环任务位置」使用。
	 *
	 * <p>只包含真正处于 ESCORTING 且载体有效的护航甲：正在返航、着装或索引等待恢复的
	 * 副甲不会被写配置。锚点信息随快照一起返回，使整队切换原地待命时不必再猜位置。</p>
	 */
	public static List<LoadedEscort> loadedEscorts(ServerPlayer owner) {
		if (owner == null) return List.of();
		return STATES.values().stream()
				.filter(state -> state.ownerId.equals(owner.getUUID())
						&& state.phase == Phase.ESCORTING
						&& state.carrier != null && !state.carrier.isRemoved()
						&& state.carrier.isAlive()
						&& hasOwnedFullSet(state.carrier, state.ownerId, state.suitId))
				.sorted(Comparator.comparing((EscortState state) -> state.suitId.toString()))
				.map(state -> new LoadedEscort(state.suitId,
						state.carrier.level().dimension(), state.carrier.position()))
				.toList();
	}

	/** 已加载护航甲的位置证据；客户端不参与选择，一切位置都取自服务端载体。 */
	public record LoadedEscort(UUID suitId, ResourceKey<Level> dimension, Vec3 position) {
	}

	/** Includes an unloaded persisted carrier; callers must fail closed until it is recovered. */
	public static boolean hasRecoverableSuit(MinecraftServer server, UUID suitId) {
		return server != null && suitId != null
				&& (STATES.containsKey(suitId)
				|| ArmorEscortSavedData.find(server, suitId) != null);
	}

	/** Server-authoritative gate used by delayed missile impacts. */
	public static boolean isDelayedAttackValid(ServerPlayer owner, UUID suitId, UUID carrierId,
			long actionSequence, long carrierRevision) {
		EscortState state = suitId == null ? null : STATES.get(suitId);
		if (owner == null || state == null || carrierId == null
				|| !state.ownerId.equals(owner.getUUID())
				|| state.phase != Phase.ESCORTING || !state.guardEnabled
				|| !state.combatLifecycleReady
				|| state.actionSequence != actionSequence
				|| state.carrierRevision != carrierRevision
				|| state.carrier == null || state.carrier.isRemoved() || !state.carrier.isAlive()
				|| !state.carrier.getUUID().equals(carrierId)
				|| owner.isSpectator() || !owner.isAlive() || owner.level() != state.carrier.level()
				|| !hasFullSet(state.carrier)
				|| !ArmorIntegrityController.canAttack(
						owner.level().getServer(), state.ownerId, state.suitId)) return false;
		return ArmorCoreController.escortProfile(owner, suitId).attackMode()
				!= ArmorEscortProfile.CombatPolicy.OFF;
	}

	/** Whether an existing escort carrier can be selected as the inbound quick-switch suit. */
	public static boolean canRecallForWear(ServerPlayer owner, UUID suitId) {
		EscortState state = suitId == null ? null : STATES.get(suitId);
		return owner != null && state != null && state.ownerId.equals(owner.getUUID())
				&& state.phase != Phase.RETURNING && state.phase != Phase.EQUIPPING
				&& state.carrier != null && !state.carrier.isRemoved() && hasFullSet(state.carrier);
	}
	/** Quick-switch watchdog query; true only while this carrier still owns the inbound item set. */
	public static boolean isRecallForWearInProgress(ServerPlayer owner, UUID suitId) {
		EscortState state = suitId == null ? null : STATES.get(suitId);
		return owner != null && state != null && state.ownerId.equals(owner.getUUID())
				&& state.phase == Phase.EQUIPPING && state.carrier != null
				&& !state.carrier.isRemoved() && hasFullSet(state.carrier);
	}

	/**
	 * Cancels only the non-committed EQUIPPING phase. ItemStacks remain on the same carrier, so the
	 * outgoing suit can safely reclaim the player's empty armor slots without a late target race.
	 */
	public static EscortCommandResult cancelRecallForSwitchFailure(ServerPlayer owner, UUID suitId) {
		EscortState state = suitId == null ? null : STATES.get(suitId);
		if (owner == null || state == null || !state.ownerId.equals(owner.getUUID())) {
			return EscortCommandResult.failed("目标护航战甲已不在活动状态");
		}
		if (state.carrier == null || state.carrier.isRemoved() || !hasFullSet(state.carrier)) {
			return EscortCommandResult.failed("目标护航载体不可用或装备不完整");
		}
		if (state.phase == Phase.RETURNING) {
			return EscortCommandResult.failed("目标护航战甲已经进入返台提交阶段");
		}
		if (state.phase == Phase.EQUIPPING) {
			state.phase = Phase.ESCORTING;
			state.phaseStartTick = owner.level().getGameTime();
		}
		state.guardEnabled = true;
		state.combatTarget = null;
		broadcast(owner.level().getServer(), state, true);
		return EscortCommandResult.started("目标护航战甲已撤销着装并恢复护航");
	}

	/**
	 * Atomically releases one exact, complete worn suit into the suit-keyed escort controller.
	 *
	 * <p>This path is intentionally independent from the owner-keyed cruise state. It is used when
	 * another physical suit already owns that primary slot. Every ItemStack is moved, never copied;
	 * any carrier, ledger, state, or persistence failure restores the four original equipment
	 * references before returning.</p>
	 */
	public static SwitchDeploymentResult deployWornSuit(ServerPlayer owner, UUID suitId,
			UUID homePlatformId, ResourceKey<Level> homeDimension, BlockPos homePos) {
		return deployWornSuit(
				owner, suitId, homePlatformId, homeDimension, homePos, true);
	}

	/** Death-head handoff: preserve the worn suit on a passive carrier before vanilla drops run. */
	public static SwitchDeploymentResult captureWornSuitForDeath(ServerPlayer owner, UUID suitId,
			UUID homePlatformId, ResourceKey<Level> homeDimension, BlockPos homePos) {
		return deployWornSuit(
				owner, suitId, homePlatformId, homeDimension, homePos, false);
	}

	private static SwitchDeploymentResult deployWornSuit(ServerPlayer owner, UUID suitId,
			UUID homePlatformId, ResourceKey<Level> homeDimension, BlockPos homePos,
			boolean launchWithGuard) {
		if (owner == null || suitId == null
				|| (launchWithGuard && !owner.isAlive()) || owner.isSpectator()) {
			return SwitchDeploymentResult.failed("当前玩家无法释放这套战甲");
		}
		if (!isOptionalHomeComplete(homePlatformId, homeDimension, homePos)) {
			return SwitchDeploymentResult.failed("战甲平台归属信息不完整");
		}
		if (ArmorAssemblyController.isAnimating(owner)
				|| ArmorCoreController.hasActiveQuickSwitch(owner)) {
			return SwitchDeploymentResult.failed("玩家装甲槽已有移动事务");
		}
		if (STATES.containsKey(suitId)) {
			return SwitchDeploymentResult.failed("这套战甲已经拥有护航载体");
		}
		if (ArmorEscortSavedData.find(owner.level().getServer(), suitId) != null) {
			return SwitchDeploymentResult.failed("这套战甲存在待恢复护航载体");
		}
		if (launchWithGuard
				&& !escortCapacityAvailable(owner.level().getServer(), owner.getUUID(), suitId)) {
			return SwitchDeploymentResult.failed("护航战甲已达同时启用上限");
		}

		EnumMap<EquipmentSlot, ItemStack> originals = new EnumMap<>(EquipmentSlot.class);
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = owner.getItemBySlot(slot);
			if (!ArmorIdentity.belongsTo(stack, owner.getUUID(), suitId, slot)) {
				return SwitchDeploymentResult.failed("当前穿戴的四件战甲不是同一套目标战甲");
			}
			originals.put(slot, stack);
		}

		Vec3 horizontalLook = new Vec3(owner.getLookAngle().x, 0.0D, owner.getLookAngle().z);
		if (horizontalLook.lengthSqr() < 1.0E-6D) {
			horizontalLook = new Vec3(0.0D, 0.0D, 1.0D);
		}
		horizontalLook = horizontalLook.normalize();
		Vec3 spawnPosition = owner.position().subtract(horizontalLook.scale(1.75D))
				.add(0.0D, 0.28D, 0.0D);
		ArmorStand carrier = new ArmorStand(
				owner.level(), spawnPosition.x, spawnPosition.y, spawnPosition.z);
		configureCarrier(carrier, owner.getUUID(), suitId);
		carrier.setYRot(owner.getYRot());
		carrier.setYBodyRot(owner.getYRot());

		// Spawn an empty shell first. The main server thread cannot tick it until this transaction
		// returns, while a failed spawn still leaves every item in its original equipment slot.
		SPAWNING.add(carrier.getUUID());
		boolean spawned;
		try {
			spawned = owner.level().addFreshEntity(carrier);
		} finally {
			SPAWNING.remove(carrier.getUUID());
		}
		if (!spawned) {
			return SwitchDeploymentResult.failed("护航载体生成失败，战甲仍保持穿戴");
		}
		if (!ChargingPlatformController.markCarriedSuitCruising(owner, suitId)) {
			carrier.discard();
			return SwitchDeploymentResult.failed("战甲位置账本拒绝穿戴到护航的交接");
		}

		try {
			for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
				ItemStack moved = owner.getItemBySlot(slot);
				if (moved != originals.get(slot)
						|| !ArmorIdentity.belongsTo(moved, owner.getUUID(), suitId, slot)) {
					throw new IllegalStateException("Worn armor changed during escort deployment");
				}
				owner.setItemSlot(slot, ItemStack.EMPTY);
				carrier.setItemSlot(slot, moved);
			}
		} catch (RuntimeException exception) {
			boolean rolledBack = rollbackWornDeployment(
					owner, suitId, carrier, originals, "equipment_transfer");
			FlightArmorMod.LOGGER.error(
					"Worn-to-escort transfer rolled back owner={} suit={}",
					owner.getUUID(), suitId, exception);
			return SwitchDeploymentResult.failed(rolledBack
					? "战甲释放失败，四件部件已恢复穿戴"
					: "战甲释放异常，物品已保守留存但位置账本需要恢复");
		}

		long now = owner.level().getGameTime();
		EscortState state = new EscortState(
				owner.getUUID(), suitId, carrier,
				launchWithGuard ? Phase.LAUNCHING : Phase.ESCORTING,
				now, spawnPosition, launchWithGuard, homePlatformId, homeDimension, homePos,
				ChargingPlatformDisplayMode.ASSEMBLED);
		if (STATES.putIfAbsent(suitId, state) != null) {
			boolean rolledBack = rollbackWornDeployment(
					owner, suitId, carrier, originals, "state_conflict");
			return SwitchDeploymentResult.failed(rolledBack
					? "战甲护航状态冲突，四件部件已恢复穿戴"
					: "战甲护航状态冲突，物品已保守留存但位置账本需要恢复");
		}
		if (!syncLocator(state)) {
			STATES.remove(suitId, state);
			boolean rolledBack = rollbackWornDeployment(
					owner, suitId, carrier, originals, "persistence_conflict");
			return SwitchDeploymentResult.failed(rolledBack
					? "护航持久索引冲突，四件部件已恢复穿戴"
					: "护航持久索引冲突，物品已保守留存但位置账本需要恢复");
		}

		ArmorCombatController.cancel(owner, ArmorCombatDenyReason.CRUISE_ACTIVE);
		FlightController.remove(owner);
		owner.getInventory().setChanged();
		owner.containerMenu.broadcastChanges();
		broadcast(owner.level().getServer(), state, true);
		return SwitchDeploymentResult.started();
	}

	private static boolean rollbackWornDeployment(ServerPlayer owner, UUID suitId,
			ArmorStand carrier, Map<EquipmentSlot, ItemStack> originals, String context) {
		if (!restoreWornEquipment(owner, carrier, originals)) {
			FlightArmorMod.LOGGER.error(
					"Worn-to-escort physical rollback could not restore all slots; carrier retained owner={} suit={} context={}",
					owner.getUUID(), suitId, context);
			return false;
		}
		boolean ledgerRolledBack =
				ChargingPlatformController.rollbackCarriedSuitCruising(owner, suitId);
		if (!ledgerRolledBack) {
			FlightArmorMod.LOGGER.error(
					"Worn-to-escort ledger rollback failed owner={} suit={} context={}",
					owner.getUUID(), suitId, context);
		}
		carrier.discard();
		return ledgerRolledBack;
	}

	private static boolean restoreWornEquipment(ServerPlayer owner, ArmorStand carrier,
			Map<EquipmentSlot, ItemStack> originals) {
		List<EquipmentSlot> foreignSlots = new ArrayList<>();
		List<Integer> freeInventorySlots = new ArrayList<>();
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack current = owner.getItemBySlot(slot);
			ItemStack original = originals.getOrDefault(slot, ItemStack.EMPTY);
			if (!current.isEmpty() && current != original) foreignSlots.add(slot);
		}
		for (int index = 0; index < owner.getInventory().getNonEquipmentItems().size()
				&& freeInventorySlots.size() < foreignSlots.size(); index++) {
			if (owner.getInventory().getItem(index).isEmpty()) freeInventorySlots.add(index);
		}
		if (freeInventorySlots.size() < foreignSlots.size()) {
			// Do not clear either source when every unexpected destination cannot be reserved.
			return false;
		}
		for (int index = 0; index < foreignSlots.size(); index++) {
			EquipmentSlot slot = foreignSlots.get(index);
			ItemStack foreign = owner.getItemBySlot(slot);
			owner.setItemSlot(slot, ItemStack.EMPTY);
			owner.getInventory().setItem(freeInventorySlots.get(index), foreign);
		}
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack original = originals.getOrDefault(slot, ItemStack.EMPTY);
			carrier.setItemSlot(slot, ItemStack.EMPTY);
			owner.setItemSlot(slot, original);
		}
		owner.getInventory().setChanged();
		owner.containerMenu.broadcastChanges();
		return true;
	}

	/**
	 * Moves the just-retracted worn suit out of the inventory and into one unique escort carrier.
	 * This is the old half of a quick switch; it never copies an ItemStack.
	 */
	public static SwitchDeploymentResult deployCarriedSuitForSwitch(ServerPlayer owner, UUID suitId,
			UUID homePlatformId, ResourceKey<Level> homeDimension, BlockPos homePos) {
		if (owner == null || suitId == null || !owner.isAlive() || owner.isSpectator()) {
			return SwitchDeploymentResult.failed("Armor owner is not available");
		}
		if (STATES.containsKey(suitId)) {
			return SwitchDeploymentResult.failed("The outgoing suit already has an escort carrier");
		}
		if (ArmorEscortSavedData.find(owner.level().getServer(), suitId) != null) {
			return SwitchDeploymentResult.failed("The outgoing suit has a persisted carrier awaiting recovery");
		}

		EnumMap<EquipmentSlot, Integer> sourceSlots = new EnumMap<>(EquipmentSlot.class);
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			int found = -1;
			for (int index = 0; index < owner.getInventory().getNonEquipmentItems().size(); index++) {
				if (!ArmorIdentity.belongsTo(owner.getInventory().getNonEquipmentItems().get(index),
						owner.getUUID(), suitId, slot)) continue;
				if (found >= 0) return SwitchDeploymentResult.failed(
						"Duplicate claimed armor part detected; switch canceled");
				found = index;
			}
			if (found < 0) return SwitchDeploymentResult.failed(
					"The outgoing suit is incomplete after separation");
			sourceSlots.put(slot, found);
		}

		Vec3 horizontalLook = new Vec3(owner.getLookAngle().x, 0.0D, owner.getLookAngle().z);
		if (horizontalLook.lengthSqr() < 1.0E-6D) horizontalLook = new Vec3(0.0D, 0.0D, 1.0D);
		horizontalLook = horizontalLook.normalize();
		Vec3 spawnPosition = owner.position().subtract(horizontalLook.scale(1.75D)).add(0.0D, 0.28D, 0.0D);
		ArmorStand carrier = new ArmorStand(owner.level(), spawnPosition.x, spawnPosition.y, spawnPosition.z);
		configureCarrier(carrier, owner.getUUID(), suitId);
		carrier.setYRot(owner.getYRot());
		carrier.setYBodyRot(owner.getYRot());

		try {
			for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
				int source = sourceSlots.get(slot);
				ItemStack moved = owner.getInventory().removeItem(source, 1);
				if (!ArmorIdentity.belongsTo(moved, owner.getUUID(), suitId, slot)) {
					throw new IllegalStateException("Quick-switch inventory plan changed");
				}
				carrier.setItemSlot(slot, moved);
			}
		} catch (RuntimeException exception) {
			restoreSwitchInventory(owner, carrier, sourceSlots);
			FlightArmorMod.LOGGER.error("Quick-switch extraction rolled back owner={} suit={}",
					owner.getUUID(), suitId, exception);
			return SwitchDeploymentResult.failed("Outgoing armor transfer failed and was rolled back");
		}

		if (!ChargingPlatformController.markCarriedSuitCruising(owner, suitId)) {
			restoreSwitchInventory(owner, carrier, sourceSlots);
			return SwitchDeploymentResult.failed("Outgoing armor position ledger rejected the handoff");
		}

		SPAWNING.add(carrier.getUUID());
		boolean spawned;
		try {
			spawned = owner.level().addFreshEntity(carrier);
		} finally {
			SPAWNING.remove(carrier.getUUID());
		}
		if (!spawned) {
			boolean ledgerRolledBack =
					rollbackSwitchLedger(owner, suitId, "carrier_spawn_failed");
			restoreSwitchInventory(owner, carrier, sourceSlots);
			return SwitchDeploymentResult.failed(ledgerRolledBack
					? "Outgoing armor carrier could not be created"
					: "Outgoing armor carrier failed and its position ledger requires recovery");
		}

		long now = owner.level().getGameTime();
		EscortState state = new EscortState(owner.getUUID(), suitId, carrier, Phase.ESCORTING,
				now, spawnPosition, false, homePlatformId, homeDimension, homePos,
				ChargingPlatformDisplayMode.ASSEMBLED);
		if (STATES.putIfAbsent(suitId, state) != null) {
			boolean ledgerRolledBack =
					rollbackSwitchLedger(owner, suitId, "carrier_state_conflict");
			restoreSwitchInventory(owner, carrier, sourceSlots);
			carrier.discard();
			return SwitchDeploymentResult.failed(ledgerRolledBack
					? "Outgoing armor carrier state conflicted"
					: "Outgoing armor state conflicted and its position ledger requires recovery");
		}
		if (!syncLocator(state)) {
			STATES.remove(suitId, state);
			boolean ledgerRolledBack =
					rollbackSwitchLedger(owner, suitId, "carrier_persistence_conflict");
			restoreSwitchInventory(owner, carrier, sourceSlots);
			carrier.discard();
			return SwitchDeploymentResult.failed(ledgerRolledBack
					? "Outgoing armor persistence index conflicted"
					: "Outgoing armor index conflicted and its position ledger requires recovery");
		}
		owner.getInventory().setChanged();
		owner.containerMenu.broadcastChanges();
		broadcast(owner.level().getServer(), state, true);
		return SwitchDeploymentResult.started();
	}

	private static boolean rollbackSwitchLedger(
			ServerPlayer owner, UUID suitId, String context) {
		boolean rolledBack =
				ChargingPlatformController.rollbackCarriedSuitCruising(owner, suitId);
		if (!rolledBack) {
			FlightArmorMod.LOGGER.error(
					"Quick-switch escort ledger rollback failed owner={} suit={} context={}",
					owner.getUUID(), suitId, context);
		}
		return rolledBack;
	}

	private static void restoreSwitchInventory(ServerPlayer owner, ArmorStand carrier,
			Map<EquipmentSlot, Integer> sourceSlots) {
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = carrier.getItemBySlot(slot);
			if (stack.isEmpty()) continue;
			carrier.setItemSlot(slot, ItemStack.EMPTY);
			Integer destination = sourceSlots.get(slot);
			if (destination != null && owner.getInventory().getItem(destination).isEmpty()) {
				owner.getInventory().setItem(destination, stack);
			} else if (!owner.getInventory().add(stack)) {
				owner.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
			}
		}
		owner.getInventory().setChanged();
		owner.containerMenu.broadcastChanges();
	}

	public static Optional<EscortWorldAnchor> currentAnchor(UUID suitId) {
		EscortState state = suitId == null ? null : STATES.get(suitId);
		if (state == null || state.carrier == null || state.carrier.isRemoved()
				|| !(state.carrier.level() instanceof ServerLevel level)) return Optional.empty();
		return Optional.of(new EscortWorldAnchor(
				level.dimension(), BlockPos.containing(state.carrier.position())));
	}

	public static boolean isReturning(UUID suitId) {
		EscortState state = suitId == null ? null : STATES.get(suitId);
		return state != null && state.phase == Phase.RETURNING;
	}

	/** Idempotence probe for a repeated core command targeting the same platform instance. */
	public static boolean isReturningTo(ServerPlayer owner, UUID suitId, UUID platformId,
			ResourceKey<Level> dimension, BlockPos pos) {
		EscortState state = suitId == null ? null : STATES.get(suitId);
		return owner != null && platformId != null && dimension != null && pos != null
				&& state != null && state.phase == Phase.RETURNING
				&& state.ownerId.equals(owner.getUUID())
				&& platformId.equals(state.homePlatformId)
				&& dimension.equals(state.homeDimension)
				&& pos.equals(state.homePos);
	}

	/** 核心面板命令：停止作战并飞回该套战甲原先绑定的平台。 */
	public static EscortCommandResult returnToPlatform(ServerPlayer owner, UUID suitId,
			UUID platformId, ResourceKey<Level> dimension, BlockPos pos) {
		if (owner == null || suitId == null || platformId == null || dimension == null || pos == null) {
			return EscortCommandResult.failed("护卫返航命令数据不完整");
		}
		if (isReturningTo(owner, suitId, platformId, dimension, pos)) {
			return EscortCommandResult.started("所选护卫已在返回同一平台");
		}
		EscortState state = suitId == null ? null : STATES.get(suitId);
		if (state == null || !state.ownerId.equals(owner.getUUID())) {
			return EscortCommandResult.failed("所选战甲当前不在护卫巡航中");
		}
		if (state.phase == Phase.RETURNING) {
			return EscortCommandResult.failed("所选护卫已经在返航途中");
		}
		if (state.phase == Phase.EQUIPPING) {
			return EscortCommandResult.failed("所选战甲正在贴近主人并着装");
		}
		ServerLevel home = owner.level().getServer().getLevel(dimension);
		if (home == null) return EscortCommandResult.failed("绑定平台所在维度不可用");
		home.getChunkAt(pos);
		if (!(home.getBlockEntity(pos) instanceof ChargingPlatformBlockEntity platform)
				|| !platform.getPlatformId().equals(platformId)) {
			return EscortCommandResult.failed("原绑定平台已不存在或实例已更换");
		}
		if (!platform.isEmpty() || platform.getOwnerUuid().isPresent()
				|| platform.getSuitId().isPresent()) {
			return EscortCommandResult.failed("原绑定平台已被占用，护卫无法返航入库");
		}
		state.homePlatformId = platformId;
		state.homeDimension = dimension;
		state.homePos = pos.immutable();
		if (state.homeDisplayMode == null) state.homeDisplayMode = platform.getDisplayMode();
		invalidateDelayedAttacks(state);
		state.guardEnabled = false;
		state.phase = Phase.RETURNING;
		state.phaseStartTick = owner.level().getGameTime();
		updateReturnTicket(state);
		broadcast(owner.level().getServer(), state, true);
		return EscortCommandResult.started("护卫已停止战斗，正在返回原绑定平台");
	}

	/** 普通 B：当核心主甲正作为副护卫活动时，直接近身接甲，不再要求先回平台。 */
	public static EscortCommandResult recallToOwnerForWear(ServerPlayer owner, UUID suitId) {
		EscortState state = suitId == null ? null : STATES.get(suitId);
		if (state == null || !state.ownerId.equals(owner.getUUID())) {
			return EscortCommandResult.failed("核心主甲当前不在护卫巡航中");
		}
		if (state.phase == Phase.RETURNING) {
			return EscortCommandResult.failed("核心主甲正在返航平台，请等待入库完成");
		}
		if (state.phase == Phase.EQUIPPING) {
			return EscortCommandResult.failed("核心主甲已经在近身着装途中");
		}
		if (ArmorAssemblyController.isAnimating(owner)) {
			return EscortCommandResult.failed("战甲部件正在移动，请稍候");
		}
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			if (!owner.getItemBySlot(slot).isEmpty()) {
				return EscortCommandResult.failed("请先清空四个装甲槽再召回核心主甲");
			}
		}
		invalidateDelayedAttacks(state);
		state.guardEnabled = false;
		state.phase = Phase.EQUIPPING;
		state.phaseStartTick = owner.level().getGameTime();
		broadcast(owner.level().getServer(), state, true);
		return EscortCommandResult.started("核心主甲已脱离护卫编队，正在近身着装");
	}


	public static boolean isEscortCarrier(Entity entity) {
		return entity instanceof ArmorStand && entity.entityTags().contains(CARRIER_TAG);
	}
	private static long nextActionSequence(long current) {
		return current <= 0L || current == Long.MAX_VALUE ? 1L : current + 1L;
	}

	private static void invalidateDelayedAttacks(EscortState state) {
		state.actionSequence = nextActionSequence(state.actionSequence);
		state.combatLifecycleReady = false;
		resetVanguardRam(state);
		ArmorCombatController.cancelDelayedCruiseAttacks(
				state.suitId, state.carrier.getUUID(), state.carrierRevision);
	}

	private static void setCombatLifecycleReady(EscortState state, boolean ready) {
		if (!ready && state.combatLifecycleReady) {
			invalidateDelayedAttacks(state);
		} else {
			state.combatLifecycleReady = ready;
			if (!ready) {
				resetVanguardRam(state);
				ArmorCombatController.cancelDelayedCruiseAttacks(
						state.suitId, state.carrier.getUUID(), state.carrierRevision);
			}
		}
	}

	private static long beginCombatAction(EscortState state) {
		ArmorCombatController.cancelDelayedCruiseAttacks(
				state.suitId, state.carrier.getUUID(), state.carrierRevision);
		state.actionSequence = nextActionSequence(state.actionSequence);
		return state.actionSequence;
	}


	public static void tickServer(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		Set<UUID> resourceTicked = new HashSet<>();
		for (EscortState state : new ArrayList<>(STATES.values())) {
			if (state.phase != Phase.RETURNING) releaseReturnTicket(state);
			ArmorStand carrier = state.carrier;
			if (carrier == null || carrier.isRemoved()) {
				setCombatLifecycleReady(state, false);
				releaseReturnTicket(state);
				if (carrier != null && carrier.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK) continue;
				if (STATES.remove(state.suitId, state)) {
					broadcast(server, state, false);
					FlightArmorMod.LOGGER.error(
							"护航载体失效，持久索引保留等待原 UUID 恢复 suit={} carrier={}",
							state.suitId, carrier == null ? null : carrier.getUUID());
				}
				continue;
			}
			syncLocator(state);
			ServerPlayer owner = server.getPlayerList().getPlayer(state.ownerId);
			if (owner == null) {
				setCombatLifecycleReady(state, false);
				releaseReturnTicket(state);
				if (ArmorIntegrityController.isDisabled(carrier)) {
					ArmorIntegrityController.tickDisabledCarrier(carrier);
				} else {
					setHoverPose(carrier, now, state.suitId);
				}
				continue;
			}
			if (state.recoveryAwaitingOwnerProfile) {
				state.recoveryAwaitingOwnerProfile = false;
				state.guardEnabled = state.phase == Phase.ESCORTING
						&& state.recoveryGuardRequested;
				setCombatLifecycleReady(state, false);
				broadcast(server, state, true);
			}
			if (ArmorIntegrityController.isDisabled(carrier)
					&& state.phase != Phase.RETURNING && state.phase != Phase.EQUIPPING) {
				setCombatLifecycleReady(state, false);
				state.combatTarget = null;
				ArmorIntegrityController.tickDisabledCarrier(carrier);
				continue;
			}
			if (state.phase == Phase.RETURNING) {
				setCombatLifecycleReady(state, false);
				tickReturning(server, owner, state, now);
				continue;
			}
			if (state.phase == Phase.EQUIPPING) {
				setCombatLifecycleReady(state, false);
				tickEquipping(owner, state, now);
				continue;
			}
			if (!owner.isAlive() || owner.isSpectator()) {
				setCombatLifecycleReady(state, false);
				setHoverPose(carrier, now, state.suitId);
				continue;
			}
			if (state.phase == Phase.LAUNCHING) {
				setCombatLifecycleReady(state, false);
				tickLaunching(owner, state, now);
				continue;
			}
			ArmorEscortProfile profile = ArmorCoreController.escortProfile(owner, state.suitId);
			boolean holdCommand = profile.stance() == ArmorEscortProfile.EscortAnchor.HOLD;
			if (holdCommand && profile.holdDimension() != null
					&& !carrier.level().dimension().equals(profile.holdDimension())) {
				ServerLevel destination = server.getLevel(profile.holdDimension());
				if (destination == null || !migrateToHold(server, state, destination, profile)) {
					state.combatTarget = null;
					setHoverPose(carrier, now, state.suitId);
					continue;
				}
				carrier = state.carrier;
			} else if (!holdCommand && carrier.level() != owner.level()) {
				if (!migrateToOwner(owner, state)) continue;
				carrier = state.carrier;
			}
			setCombatLifecycleReady(state, state.phase == Phase.ESCORTING
					&& state.guardEnabled && carrier.level() == owner.level()
					&& profile.attackMode() != ArmorEscortProfile.CombatPolicy.OFF
					&& ArmorIntegrityController.canAttack(server, state.ownerId, state.suitId));

			/*
			 * A selected nearby manual command has exclusive movement ownership. This check must
			 * precede autonomous target selection, otherwise remote approach and escort AI both
			 * write the ArmorStand position in one tick.
			 */
			if (state.phase == Phase.ESCORTING
					&& ArmorRemoteCommandController.tickEscort(
							owner, state.suitId, carrier, now)) {
				if (resourceTicked.add(carrier.getUUID())) {
					ArmorCombatController.tickCruiseResources(owner, carrier);
				}
				continue;
			}

			Vec3 commandAnchor = commandAnchor(owner, carrier, profile);
			Vec3 idleTarget = holdCommand ? commandAnchor
					: formationTarget(owner, state.suitId, now, profile.stance());
			ArmorVariant variant = ModItems.fullSetVariant(carrier).orElse(ArmorVariant.DEFAULT);
			LivingEntity combatTarget = state.phase == Phase.ESCORTING
					? combatTarget(owner, state, profile, commandAnchor, variant, now) : null;
			boolean vanguardCombatCycle = state.phase == Phase.ESCORTING
					&& variant == ArmorVariant.VANGUARD
					&& (combatTarget != null || state.vanguardPhase != null);
			if (vanguardCombatCycle) {
				if (resourceTicked.add(carrier.getUUID())) {
					ArmorCombatController.tickCruiseResources(owner, carrier);
				}
				tickVanguardRam(owner, state, now, idleTarget, profile, commandAnchor, combatTarget);
				continue;
			}
			resetVanguardRam(state);
			Vec3 movementTarget = combatTarget == null ? idleTarget
					: ArmorEscortAi.engagementPosition(carrier, combatTarget, commandAnchor,
							profile.rangeBlocks(), variant, now, state.suitId);
			double distance = carrier.position().distanceTo(movementTarget);
			double movementSpeed = state.phase == Phase.TRANSIT ? 8.0D
					: combatTarget == null ? 1.25D : ArmorEscortAi.pursuitSpeed(variant);
			Vec3 movementStart = carrier.position();
			moveCarrier(carrier, movementTarget, movementSpeed);
			state.lastMovement = carrier.position().subtract(movementStart);
			if (state.phase == Phase.TRANSIT && distance < 1.45D) {
				state.phase = Phase.ESCORTING;
				state.phaseStartTick = now;
				broadcast(server, state, true);
			}
			if (state.phase == Phase.ESCORTING) {
				if (resourceTicked.add(carrier.getUUID())) {
					ArmorCombatController.tickCruiseResources(owner, carrier);
				}
				tickGuardCombat(owner, state, now, idleTarget, profile,
						commandAnchor, variant, combatTarget);
			}
		}
	}
	private static void tickReturning(MinecraftServer server, ServerPlayer owner,
			EscortState state, long now) {
		if (state.homeDimension == null || state.homePos == null || state.homePlatformId == null) {
			restoreReturningToSafeCruise(server, owner, state, now,
					"护卫缺少返航平台数据，已恢复巡航");
			return;
		}
		ServerLevel home = server.getLevel(state.homeDimension);
		if (home == null) {
			setHoverPose(state.carrier, now, state.suitId);
			updateReturnTicket(state);
			return;
		}
		home.getChunkAt(state.homePos);
		if (!(home.getBlockEntity(state.homePos) instanceof ChargingPlatformBlockEntity platform)
				|| !platform.getPlatformId().equals(state.homePlatformId)) {
			restoreReturningToSafeCruise(server, owner, state, now,
					"原绑定平台已不存在或实例已更换，战甲已恢复巡航");
			return;
		}
		if (!platform.isEmpty() || platform.getOwnerUuid().isPresent()
				|| platform.getSuitId().isPresent()) {
			restoreReturningToSafeCruise(server, owner, state, now,
					"原绑定平台已被占用，战甲已恢复巡航");
			return;
		}

		Vec3 dock = Vec3.atBottomCenterOf(state.homePos).add(0.0D, 0.625D, 0.0D);
		if (state.carrier.level() != home) {
			Vec3 reentry = dock.add(0.0D, 5.5D, -14.0D);
			if (!migrateToPlatform(server, state, home, reentry)) {
				setHoverPose(state.carrier, now, state.suitId);
				updateReturnTicket(state);
				return;
			}
		}

		double dockDistance = state.carrier.position().distanceTo(dock);
		IndependentCruiseReturnHandoff.Approach approach =
				IndependentCruiseReturnHandoff.approach(dockDistance);
		Vec3 target = dock.add(0.0D, approach.targetHeightAboveDock(), 0.0D);
		moveCarrier(state.carrier, target, approach.maxSpeed());
		updateReturnTicket(state);
		if (state.carrier.position().distanceTo(dock) > 0.12D) return;
		if (owner == null || !owner.isAlive() || owner.isSpectator()) {
			setHoverPose(state.carrier, now, state.suitId);
			return;
		}

		List<ItemStack> returningItems = carrierItems(state.carrier);
		clearCarrierArmor(state.carrier);
		ChargingPlatformController.EscortReturnResult result =
				ChargingPlatformController.returnEscortToPlatform(owner, state.suitId,
						state.homePlatformId, state.homeDimension, state.homePos,
						state.homeDisplayMode == null
								? ChargingPlatformDisplayMode.ASSEMBLED : state.homeDisplayMode,
						returningItems);
		if (!result.success()) {
			restoreCarrierArmor(state.carrier, returningItems);
			restoreReturningToSafeCruise(server, owner, state, now, result.message());
			return;
		}

		boolean removed = STATES.remove(state.suitId, state);
		removeLocatorAfterCommit(server, state);
		releaseReturnTicket(state);
		if (removed) broadcast(server, state, false);
		state.carrier.discard();
		owner.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
				"护卫已平滑落台并恢复整甲待机"));
		ArmorCoreController.sendSnapshot(owner);
	}

	private static void restoreReturningToSafeCruise(MinecraftServer server, ServerPlayer owner,
			EscortState state, long now, String message) {
		invalidateDelayedAttacks(state);
		resetVanguardRam(state);
		setCombatLifecycleReady(state, false);
		state.phase = Phase.ESCORTING;
		state.guardEnabled = true;
		state.phaseStartTick = now;
		releaseReturnTicket(state);
		broadcast(server, state, true);
		if (owner != null) {
			owner.sendOverlayMessage(net.minecraft.network.chat.Component.literal(message));
			ArmorCoreController.sendSnapshot(owner);
		}
	}

	private static void tickEquipping(ServerPlayer owner, EscortState state, long now) {
		if (owner == null || !owner.isAlive() || owner.isSpectator()) {
			setHoverPose(state.carrier, now, state.suitId);
			return;
		}
		if (state.carrier.level() != owner.level()) {
			if (!migrateToOwner(owner, state)) return;
			state.phase = Phase.EQUIPPING;
			state.phaseStartTick = now;
			broadcast(owner.level().getServer(), state, true);
		}
		Vec3 target = owner.position().add(0.0D, 0.45D, 0.0D);
		double distance = state.carrier.position().distanceTo(target);
		moveCarrier(state.carrier, target, distance > 12.0D ? 8.0D : 1.45D);
		if (state.carrier.position().distanceTo(target) > 0.55D) return;

		List<ItemStack> equippingItems = carrierItems(state.carrier);
		clearCarrierArmor(state.carrier);
		ChargingPlatformController.EscortReturnResult result =
				ChargingPlatformController.equipEscortToOwner(
						owner, state.suitId, equippingItems);
		if (!result.success()) {
			restoreCarrierArmor(state.carrier, equippingItems);
			state.phase = Phase.ESCORTING;
			state.guardEnabled = true;
			state.phaseStartTick = now;
			owner.sendOverlayMessage(net.minecraft.network.chat.Component.literal(result.message()));
			broadcast(owner.level().getServer(), state, true);
			ArmorCoreController.sendSnapshot(owner);
			return;
		}
		boolean removed = STATES.remove(state.suitId, state);
		removeLocatorAfterCommit(owner.level().getServer(), state);
		if (removed) broadcast(owner.level().getServer(), state, false);
		state.carrier.discard();
		boolean animated = ArmorAssemblyController.beginAssemblyForEquippedSet(owner);
		FlightPhysics.applyPreBootFallArrest(owner);
		owner.syncVelocity = true;
		owner.sendOverlayMessage(net.minecraft.network.chat.Component.literal(animated
				? "核心主甲已贴近并开始整体着装"
				: "核心主甲已完成物品交接"));
		ArmorCoreController.sendSnapshot(owner);
	}

	private static List<ItemStack> carrierItems(ArmorStand carrier) {
		List<ItemStack> items = new ArrayList<>(ChargingPlatformBlockEntity.ARMOR_SLOTS.size());
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			items.add(carrier.getItemBySlot(slot));
		}
		return List.copyOf(items);
	}

	private static void clearCarrierArmor(ArmorStand carrier) {
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			carrier.setItemSlot(slot, ItemStack.EMPTY);
		}
	}


	private static void restoreCarrierArmor(ArmorStand carrier, List<ItemStack> items) {
		for (int index = 0; index < ChargingPlatformBlockEntity.ARMOR_SLOTS.size(); index++) {
			EquipmentSlot slot = ChargingPlatformBlockEntity.ARMOR_SLOTS.get(index);
			carrier.setItemSlot(slot, items.get(index));
		}
	}

	private static void tickLaunching(ServerPlayer owner, EscortState state, long now) {
		float progress = Mth.clamp((now - state.phaseStartTick) / (float) LAUNCH_TICKS, 0.0F, 1.0F);
		float eased = progress * progress * (3.0F - 2.0F * progress);
		Vec3 target = state.launchOrigin.add(0.0D, 1.8D * eased, 0.0D);
		state.carrier.setPos(target.x, target.y, target.z);
		state.carrier.setYRot(Mth.rotLerp(0.18F, state.carrier.getYRot(), owner.getYRot()));
		setFlightPose(state.carrier);
		if (progress >= 1.0F) {
			boolean sameDimension = state.carrier.level() == owner.level();
			double distance = sameDimension
					? state.carrier.position().distanceTo(formationTarget(owner, state.suitId, now,
							ArmorCoreController.escortProfile(owner, state.suitId).stance()))
					: Double.POSITIVE_INFINITY;
			state.phase = distance > 12.0D ? Phase.TRANSIT : Phase.ESCORTING;
			state.phaseStartTick = now;
			broadcast(owner.level().getServer(), state, true);
		}
	}

	private static boolean migrateToOwner(ServerPlayer owner, EscortState state) {
		if (MIGRATING.contains(state.suitId)) return false;
		setCombatLifecycleReady(state, false);
		MIGRATING.add(state.suitId);
		try {
			Vec3 reentry = owner.position().subtract(owner.getLookAngle().scale(18.0D)).add(0.0D, 5.0D, 0.0D);
			Entity changed = state.carrier.teleport(new net.minecraft.world.level.portal.TeleportTransition(
					owner.level(), reentry, Vec3.ZERO, owner.getYRot(), 0.0F,
					net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
			if (!(changed instanceof ArmorStand stand)) return false;
			configureCarrier(stand, state.ownerId, state.suitId);
			stand.setPos(reentry.x, reentry.y, reentry.z);
			state.carrier = stand;
			state.carrierRevision = NEXT_CARRIER_REVISION.incrementAndGet();
			state.phase = Phase.TRANSIT;
			broadcast(owner.level().getServer(), state, true);
			return true;
		} finally {
			MIGRATING.remove(state.suitId);
		}
	}
	private static boolean migrateToPlatform(MinecraftServer server, EscortState state,
			ServerLevel destination, Vec3 reentry) {
		if (MIGRATING.contains(state.suitId)) return false;
		setCombatLifecycleReady(state, false);
		MIGRATING.add(state.suitId);
		try {
			Entity changed = state.carrier.teleport(
					new net.minecraft.world.level.portal.TeleportTransition(
							destination, reentry, Vec3.ZERO, state.carrier.getYRot(), 0.0F,
							net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
			if (!(changed instanceof ArmorStand stand)) return false;
			configureCarrier(stand, state.ownerId, state.suitId);
			stand.setPos(reentry.x, reentry.y, reentry.z);
			state.carrier = stand;
			state.carrierRevision = NEXT_CARRIER_REVISION.incrementAndGet();
			state.phase = Phase.RETURNING;
			updateReturnTicket(state);
			broadcast(server, state, true);
			return true;
		} finally {
			MIGRATING.remove(state.suitId);
		}
	}


	private static boolean migrateToHold(MinecraftServer server, EscortState state,
			ServerLevel destination, ArmorEscortProfile profile) {
		if (profile.holdPosition() == null || MIGRATING.contains(state.suitId)) return false;
		setCombatLifecycleReady(state, false);
		MIGRATING.add(state.suitId);
		try {
			Vec3 anchor = Vec3.atBottomCenterOf(profile.holdPosition()).add(0.0D, 1.15D, 0.0D);
			Vec3 reentry = anchor.add(0.0D, 4.0D, -8.0D);
			Entity changed = state.carrier.teleport(
					new net.minecraft.world.level.portal.TeleportTransition(
							destination, reentry, Vec3.ZERO, state.carrier.getYRot(), 0.0F,
							net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
			if (!(changed instanceof ArmorStand stand)) return false;
			configureCarrier(stand, state.ownerId, state.suitId);
			stand.setPos(reentry.x, reentry.y, reentry.z);
			state.carrier = stand;
			state.carrierRevision = NEXT_CARRIER_REVISION.incrementAndGet();
			state.combatTarget = null;
			state.phase = Phase.TRANSIT;
			state.phaseStartTick = destination.getGameTime();
			broadcast(server, state, true);
			return true;
		} finally {
			MIGRATING.remove(state.suitId);
		}
	}

	private static Vec3 commandAnchor(ServerPlayer owner, ArmorStand carrier,
			ArmorEscortProfile profile) {
		if (profile.stance() == ArmorEscortProfile.EscortAnchor.HOLD) {
			if (carrier.level().dimension().equals(profile.holdDimension())
					&& profile.holdPosition() != null) {
				return Vec3.atBottomCenterOf(profile.holdPosition()).add(0.0D, 1.15D, 0.0D);
			}
			return carrier.position();
		}
		return owner.position().add(0.0D, 0.9D, 0.0D);
	}

	private static LivingEntity combatTarget(ServerPlayer owner, EscortState state,
			ArmorEscortProfile profile, Vec3 commandAnchor, ArmorVariant variant, long now) {
		// 贴身跟随是纯伴随档位：压到主人身边并且不主动索敌，任何已锁定的目标都必须立即释放。
		if (profile.stance() == ArmorEscortProfile.EscortAnchor.CLOSE
				|| !state.guardEnabled || profile.attackMode() == ArmorEscortProfile.CombatPolicy.OFF
				|| state.carrier.level() != owner.level()
				|| !ArmorIntegrityController.canAttack(
						owner.level().getServer(), state.ownerId, state.suitId)) {
			state.combatTarget = null;
			return null;
		}
		boolean includeNonHostile = profile.attackMode() == ArmorEscortProfile.CombatPolicy.ALL;
		if (state.combatTarget != null && !ArmorEscortAi.isStillValid(
				owner, state.carrier, state.combatTarget, commandAnchor,
				profile.rangeBlocks(), includeNonHostile)) {
			state.combatTarget = null;
		}
		if (state.combatTarget == null || now >= state.nextTargetScanTick) {
			state.combatTarget = ArmorEscortAi.selectTarget(owner, state.carrier, commandAnchor,
					profile.rangeBlocks(), includeNonHostile, variant);
			state.nextTargetScanTick = now + 10L;
		}
		return state.combatTarget;
	}

	private static void tickVanguardRam(ServerPlayer owner, EscortState state, long now,
			Vec3 idleTarget, ArmorEscortProfile profile, Vec3 commandAnchor,
			LivingEntity selectedTarget) {
		if (!state.guardEnabled || !state.combatLifecycleReady
				|| state.carrier.level() != owner.level()) {
			resetVanguardRam(state);
			return;
		}
		if (state.vanguardPhase == null) {
			if (selectedTarget == null || now < state.nextAttackTick) return;
			beginVanguardRam(state, selectedTarget, commandAnchor, profile.rangeBlocks(), now);
		}

		LivingEntity lockedTarget = selectedTarget != null
				&& selectedTarget.getUUID().equals(state.vanguardTargetId) ? selectedTarget : null;
		if (lockedTarget == null && state.vanguardPhase != VanguardRamPhase.IMPACT
				&& state.vanguardPhase != VanguardRamPhase.MISS
				&& state.vanguardPhase != VanguardRamPhase.PULL_UP
				&& state.vanguardPhase != VanguardRamPhase.RECOVER) {
			markVanguardOutcome(state, VanguardRamPhase.MISS, VanguardRamResult.ABORTED, now);
		}

		switch (state.vanguardPhase) {
			case CLIMB -> tickVanguardClimb(state, lockedTarget, now);
			case LOCK -> tickVanguardLock(state, lockedTarget, now);
			case DIVE -> tickVanguardDive(owner, state, lockedTarget, now);
			case IMPACT, MISS -> {
				beginVanguardPullUp(state, commandAnchor, profile.rangeBlocks(), now);
				tickVanguardPullUp(state, now);
			}
			case PULL_UP -> tickVanguardPullUp(state, now);
			case RECOVER -> tickVanguardRecover(owner, state, lockedTarget, idleTarget, now);
		}
	}

	private static void beginVanguardRam(EscortState state, LivingEntity target,
			Vec3 commandAnchor, double configuredRange, long now) {
		resetVanguardRam(state);
		state.vanguardPhase = VanguardRamPhase.CLIMB;
		state.vanguardPhaseStartTick = now;
		state.vanguardTargetId = target.getUUID();
		state.vanguardResult = VanguardRamResult.NONE;
		state.vanguardStagingPoint = vanguardStagingPoint(
				state.carrier, target, commandAnchor, configuredRange, state.suitId);
		beginCombatAction(state);
	}

	private static Vec3 vanguardStagingPoint(ArmorStand carrier, LivingEntity target,
			Vec3 commandAnchor, double configuredRange, UUID suitId) {
		Vec3 targetCenter = target.getBoundingBox().getCenter();
		Vec3 targetMotion = new Vec3(target.getDeltaMovement().x, 0.0D,
				target.getDeltaMovement().z);
		Vec3 backwards;
		if (targetMotion.lengthSqr() > 1.0E-4D) {
			backwards = targetMotion.normalize().scale(-1.0D);
		} else {
			backwards = new Vec3(carrier.getX() - targetCenter.x, 0.0D,
					carrier.getZ() - targetCenter.z);
			if (backwards.lengthSqr() < 1.0E-4D) backwards = new Vec3(0.0D, 0.0D, 1.0D);
			else backwards = backwards.normalize();
		}
		Vec3 right = new Vec3(-backwards.z, 0.0D, backwards.x);
		double laneOffset = (Math.floorMod(suitId.hashCode(), 3) - 1) * 2.0D;
		double stagingY = Math.max(carrier.getY() + 8.0D,
				target.getBoundingBox().maxY + 8.0D);
		Vec3 desired = new Vec3(targetCenter.x, stagingY, targetCenter.z)
				.add(backwards.scale(7.0D)).add(right.scale(laneOffset));
		return clampRamHorizontal(desired, commandAnchor,
				Math.max(8.0D, ArmorEscortAi.sanitizeRange(configuredRange) - 1.5D));
	}

	private static Vec3 clampRamHorizontal(Vec3 desired, Vec3 anchor, double radius) {
		Vec3 horizontal = new Vec3(desired.x - anchor.x, 0.0D, desired.z - anchor.z);
		double distance = horizontal.length();
		if (distance <= radius || distance < 1.0E-5D) return desired;
		Vec3 clamped = horizontal.scale(radius / distance);
		return new Vec3(anchor.x + clamped.x, desired.y, anchor.z + clamped.z);
	}

	private static void tickVanguardClimb(EscortState state, LivingEntity target, long now) {
		if (target == null || state.vanguardStagingPoint == null) {
			markVanguardOutcome(state, VanguardRamPhase.MISS, VanguardRamResult.ABORTED, now);
			return;
		}
		Vec3 movementStart = state.carrier.position();
		moveCarrier(state.carrier, state.vanguardStagingPoint, 1.65D);
		state.lastMovement = state.carrier.position().subtract(movementStart);
		aimCarrier(state.carrier, target);
		long elapsed = now - state.vanguardPhaseStartTick;
		if (elapsed >= 10L && state.carrier.position().distanceToSqr(
				state.vanguardStagingPoint) <= 0.55D * 0.55D) {
			state.vanguardPhase = VanguardRamPhase.LOCK;
			state.vanguardPhaseStartTick = now;
			state.lastMovement = Vec3.ZERO;
		} else if (elapsed >= 30L) {
			markVanguardOutcome(state, VanguardRamPhase.MISS, VanguardRamResult.ABORTED, now);
		}
	}

	private static void tickVanguardLock(EscortState state, LivingEntity target, long now) {
		state.lastMovement = Vec3.ZERO;
		if (target == null) {
			markVanguardOutcome(state, VanguardRamPhase.MISS, VanguardRamResult.ABORTED, now);
			return;
		}
		aimCarrier(state.carrier, target);
		if (now - state.vanguardPhaseStartTick < 6L) return;

		Vec3 targetMotion = target.getDeltaMovement();
		if (targetMotion.length() > 0.55D) targetMotion = targetMotion.normalize().scale(0.55D);
		state.vanguardLockedTargetPosition = target.getBoundingBox().getCenter();
		state.vanguardInterceptPoint = state.vanguardLockedTargetPosition.add(targetMotion.scale(7.0D));
		Vec3 direction = state.vanguardInterceptPoint.subtract(state.carrier.getBoundingBox().getCenter());
		if (direction.lengthSqr() < 1.0E-6D) {
			markVanguardOutcome(state, VanguardRamPhase.MISS, VanguardRamResult.MISSED, now);
			return;
		}
		state.vanguardDiveDirection = direction.normalize();
		state.vanguardPhase = VanguardRamPhase.DIVE;
		state.vanguardPhaseStartTick = now;
		state.vanguardHitCommitted = false;
	}

	private static void tickVanguardDive(ServerPlayer owner, EscortState state,
			LivingEntity target, long now) {
		if (target == null || state.vanguardInterceptPoint == null
				|| state.vanguardDiveDirection.lengthSqr() < 0.99D) {
			markVanguardOutcome(state, VanguardRamPhase.MISS, VanguardRamResult.ABORTED, now);
			return;
		}
		if (state.vanguardLockedTargetPosition != null
				&& target.getBoundingBox().getCenter().distanceToSqr(
						state.vanguardLockedTargetPosition) > 100.0D) {
			markVanguardOutcome(state, VanguardRamPhase.MISS, VanguardRamResult.MISSED, now);
			return;
		}

		long elapsed = now - state.vanguardPhaseStartTick;
		AABB previousBounds = state.carrier.getBoundingBox();
		Vec3 movementStart = state.carrier.position();
		double speed = VanguardRamKinematics.diveSpeed(elapsed);
		moveCarrierLinear(state.carrier, state.vanguardDiveDirection, speed);
		Vec3 candidateDelta = state.carrier.position().subtract(movementStart);
		state.lastMovement = candidateDelta;
		VanguardRamKinematics.SweepResult sweep = sweptTargetHit(
				previousBounds, candidateDelta, target.getBoundingBox());
		if (sweep.hit() && !state.vanguardHitCommitted) {
			Vec3 contactPosition = movementStart.add(candidateDelta.scale(sweep.fraction()));
			state.carrier.setPos(contactPosition.x, contactPosition.y, contactPosition.z);
			setVanguardDivePose(state.carrier);
			state.vanguardHitCommitted = true;
			ArmorCombatController.CruiseWeaponResult result =
					ArmorCombatController.tryCruiseImpact(
							owner, state.carrier, target, candidateDelta);
			long delay = result.fired() ? 8L : 4L;
			state.nextAttackTick = now + ArmorIntegrityController.scaledAttackDelay(
					owner.level().getServer(), state.ownerId, state.suitId, delay);
			markVanguardOutcome(state, VanguardRamPhase.IMPACT,
					result.fired() ? VanguardRamResult.HIT : VanguardRamResult.CONTACT, now);
			return;
		}

		Vec3 endCenter = state.carrier.getBoundingBox().getCenter();
		if (elapsed >= 18L || VanguardRamKinematics.passedInterceptPlane(
				endCenter.x, endCenter.y, endCenter.z,
				state.vanguardInterceptPoint.x, state.vanguardInterceptPoint.y,
				state.vanguardInterceptPoint.z,
				state.vanguardDiveDirection.x, state.vanguardDiveDirection.y,
				state.vanguardDiveDirection.z)) {
			markVanguardOutcome(state, VanguardRamPhase.MISS, VanguardRamResult.MISSED, now);
		}
	}

	private static VanguardRamKinematics.SweepResult sweptTargetHit(AABB previousCarrierBounds,
			Vec3 delta, AABB targetBounds) {
		AABB sweptBounds = previousCarrierBounds.expandTowards(delta).inflate(0.18D);
		if (!sweptBounds.intersects(targetBounds)) {
			return VanguardRamKinematics.SweepResult.miss();
		}
		Vec3 center = previousCarrierBounds.getCenter();
		return VanguardRamKinematics.sweep(
				center.x, center.y, center.z,
				previousCarrierBounds.getXsize() * 0.5D,
				previousCarrierBounds.getYsize() * 0.5D,
				previousCarrierBounds.getZsize() * 0.5D,
				delta.x, delta.y, delta.z,
				targetBounds.minX, targetBounds.minY, targetBounds.minZ,
				targetBounds.maxX, targetBounds.maxY, targetBounds.maxZ, 0.18D);
	}

	private static void markVanguardOutcome(EscortState state, VanguardRamPhase phase,
			VanguardRamResult result, long now) {
		state.vanguardPhase = phase;
		state.vanguardResult = result;
		state.vanguardPhaseStartTick = now;
	}

	private static void beginVanguardPullUp(EscortState state, Vec3 commandAnchor,
			double configuredRange, long now) {
		Vec3 horizontal = new Vec3(state.vanguardDiveDirection.x, 0.0D,
				state.vanguardDiveDirection.z);
		if (horizontal.lengthSqr() < 1.0E-5D) {
			double yaw = Math.toRadians(state.carrier.getYRot());
			horizontal = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
		} else {
			horizontal = horizontal.normalize();
		}
		state.vanguardPullUpStartY = state.carrier.getY();
		Vec3 desired = state.carrier.position().add(horizontal.scale(8.0D))
				.add(0.0D, 6.5D, 0.0D);
		state.vanguardPullUpPoint = clampRamHorizontal(desired, commandAnchor,
				Math.max(8.0D, ArmorEscortAi.sanitizeRange(configuredRange) - 1.5D));
		state.vanguardPhase = VanguardRamPhase.PULL_UP;
		state.vanguardPhaseStartTick = now;
	}

	private static void tickVanguardPullUp(EscortState state, long now) {
		if (state.vanguardPullUpPoint == null) {
			state.vanguardPhase = VanguardRamPhase.RECOVER;
			state.vanguardPhaseStartTick = now;
			return;
		}
		Vec3 movementStart = state.carrier.position();
		moveCarrier(state.carrier, state.vanguardPullUpPoint, 2.20D);
		state.lastMovement = state.carrier.position().subtract(movementStart);
		long elapsed = now - state.vanguardPhaseStartTick;
		double climbed = state.carrier.getY() - state.vanguardPullUpStartY;
		if (elapsed >= 8L && (climbed >= 6.0D || elapsed >= 14L)) {
			state.vanguardPhase = VanguardRamPhase.RECOVER;
			state.vanguardPhaseStartTick = now;
			state.vanguardRecoveryShotAttempted = false;
		}
	}

	private static void tickVanguardRecover(ServerPlayer owner, EscortState state,
			LivingEntity target, Vec3 idleTarget, long now) {
		Vec3 movementStart = state.carrier.position();
		moveCarrier(state.carrier, idleTarget, 1.35D);
		state.lastMovement = state.carrier.position().subtract(movementStart);
		long elapsed = now - state.vanguardPhaseStartTick;
		if (target != null && !state.vanguardRecoveryShotAttempted
				&& elapsed >= 4L && now >= state.nextAttackTick) {
			aimCarrier(state.carrier, target);
			boolean fired = ArmorCombatController.tryFireCruiseWrist(
					owner, state.carrier, target,
					state.carrier.isEyeInFluid(FluidTags.WATER)).fired();
			state.vanguardRecoveryShotAttempted = true;
			long delay = fired
					? ArmorEscortAi.successfulFireDelay(ArmorVariant.VANGUARD,
							activeCombatSuitCount(owner, ArmorVariant.VANGUARD))
					: ArmorEscortAi.retryDelay(ArmorVariant.VANGUARD);
			state.nextAttackTick = now + ArmorIntegrityController.scaledAttackDelay(
					owner.level().getServer(), state.ownerId, state.suitId, delay);
		}
		if (elapsed >= 12L) resetVanguardRam(state);
	}

	private static void moveCarrierLinear(ArmorStand carrier, Vec3 direction, double speed) {
		if (direction.lengthSqr() < 1.0E-8D || !Double.isFinite(speed) || speed <= 0.0D) return;
		ArmorIntegrityController.prepareControlledMovement(carrier);
		speed *= ArmorIntegrityController.movementMultiplier(carrier);
		Vec3 normalized = direction.normalize();
		Vec3 next = carrier.position().add(normalized.scale(speed));
		carrier.setPos(next.x, next.y, next.z);
		float yaw = (float) Math.toDegrees(Math.atan2(-normalized.x, normalized.z));
		carrier.setYRot(Mth.rotLerp(0.55F, carrier.getYRot(), yaw));
		carrier.setYBodyRot(carrier.getYRot());
		setVanguardDivePose(carrier);
	}

	private static void resetVanguardRam(EscortState state) {
		state.vanguardPhase = null;
		state.vanguardTargetId = null;
		state.vanguardPhaseStartTick = 0L;
		state.vanguardStagingPoint = null;
		state.vanguardLockedTargetPosition = null;
		state.vanguardInterceptPoint = null;
		state.vanguardDiveDirection = Vec3.ZERO;
		state.vanguardPullUpPoint = null;
		state.vanguardPullUpStartY = 0.0D;
		state.vanguardHitCommitted = false;
		state.vanguardRecoveryShotAttempted = false;
		state.vanguardResult = VanguardRamResult.NONE;
	}

	private static void tickGuardCombat(ServerPlayer owner, EscortState state, long now,
			Vec3 idleTarget, ArmorEscortProfile profile, Vec3 commandAnchor,
			ArmorVariant variant, LivingEntity target) {
		if (!state.guardEnabled || state.carrier.level() != owner.level()) return;
		if (target == null) {
			if (state.carrier.position().distanceToSqr(idleTarget) < 0.08D) {
				setHoverPose(state.carrier, now, state.suitId);
			}
			return;
		}
		aimCarrier(state.carrier, target);
		if (now < state.nextAttackTick) return;
		boolean fired = tryTacticalWeapon(owner, state, profile, commandAnchor, variant, target);
		long delay = fired
				? ArmorEscortAi.successfulFireDelay(variant, activeCombatSuitCount(owner, variant))
				: ArmorEscortAi.retryDelay(variant);
		state.nextAttackTick = now + ArmorIntegrityController.scaledAttackDelay(
				owner.level().getServer(), state.ownerId, state.suitId, delay);
	}

	private static boolean tryTacticalWeapon(ServerPlayer owner, EscortState state,
			ArmorEscortProfile profile, Vec3 commandAnchor,
			ArmorVariant variant, LivingEntity target) {
		long actionSequence = beginCombatAction(state);
		ArmorCombatController.CruiseWeaponResult result = switch (
				ArmorEscortAi.preferredWeapon(variant)) {
			case IMPACT -> {
				if (state.carrier.distanceToSqr(target) <= 12.25D
						&& state.lastMovement.lengthSqr() >= 0.08D) {
					yield ArmorCombatController.tryCruiseImpact(
							owner, state.carrier, target, state.lastMovement);
				}
				yield new ArmorCombatController.CruiseWeaponResult(false, 0L);
			}
			case CORE_BEAM -> ArmorCombatController.tryFireCruiseCore(
					owner, state.carrier, target);
			case MISSILE_VOLLEY -> {
				boolean includeNonHostile =
						profile.attackMode() == ArmorEscortProfile.CombatPolicy.ALL;
				List<LivingEntity> volleyTargets = ArmorEscortAi.selectVolleyTargets(
						owner, state.carrier, commandAnchor, profile.rangeBlocks(),
						includeNonHostile, variant, target, 6);
				yield ArmorCombatController.tryFireCruiseMissileVolley(
						owner, state.carrier, state.suitId, actionSequence,
						state.carrierRevision, volleyTargets);
			}
		};
		if (result.fired()) return true;
		if (variant == ArmorVariant.SENTINEL
				&& ArmorCombatController.tryFireSentinelDronePulse(
						owner, state.carrier, target).fired()) {
			return true;
		}
		return ArmorCombatController.tryFireCruiseWrist(
				owner, state.carrier, target,
				state.carrier.isEyeInFluid(FluidTags.WATER)).fired();
	}


	private static int activeCombatSuitCount(ServerPlayer owner, ArmorVariant variant) {
		int count = 0;
		for (EscortState candidate : STATES.values()) {
			if (!candidate.ownerId.equals(owner.getUUID()) || candidate.phase != Phase.ESCORTING
					|| !candidate.guardEnabled || candidate.carrier.level() != owner.level()) continue;
			if (ModItems.fullSetVariant(candidate.carrier).orElse(ArmorVariant.DEFAULT) != variant) continue;
			if (ArmorCoreController.escortProfile(owner, candidate.suitId).attackMode()
					!= ArmorEscortProfile.CombatPolicy.OFF) count++;
		}
		return Math.max(1, count);
	}

	private static Vec3 formationTarget(ServerPlayer owner, UUID suitId, long now,
			ArmorEscortProfile.EscortAnchor stance) {
		List<UUID> suits = STATES.values().stream().filter(state -> state.ownerId.equals(owner.getUUID()))
				.map(state -> state.suitId).sorted().toList();
		int index = Math.max(0, suits.indexOf(suitId));
		int count = Math.max(1, suits.size());
		double angle = owner.getYRot() * Mth.DEG_TO_RAD + Math.PI
				+ index * Math.PI * 2.0D / count + now * 0.008D;
		// 贴身跟随把编队半径压到约 1 格并降低高度，让战甲真正贴在主人身边；
		// 跟随自主保持原有编队尺寸，两者都不改变接敌与移动逻辑之外的东西。
		boolean close = stance == ArmorEscortProfile.EscortAnchor.CLOSE;
		double radius = close ? 1.05D
				: count == 1 ? 2.35D : 2.7D + Math.min(1.0D, count * 0.12D);
		double height = close ? 0.75D + 0.12D * (index % 2) : 1.15D + 0.22D * (index % 2);
		return owner.position().add(Math.sin(angle) * radius, height, Math.cos(angle) * radius);
	}

	private static void moveCarrier(ArmorStand carrier, Vec3 target, double maxSpeed) {
		ArmorIntegrityController.prepareControlledMovement(carrier);
		Vec3 delta = target.subtract(carrier.position());
		double distance = delta.length();
		if (distance < 1.0E-5D) return;
		double speed = Math.min(distance, Math.min(maxSpeed, 0.28D + distance * 0.18D))
				* ArmorIntegrityController.movementMultiplier(carrier);
		Vec3 next = carrier.position().add(delta.scale(speed / distance));
		carrier.setPos(next.x, next.y, next.z);
		float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
		carrier.setYRot(Mth.rotLerp(0.35F, carrier.getYRot(), yaw));
		carrier.setYBodyRot(carrier.getYRot());
		setFlightPose(carrier);
	}

	private static void configureCarrier(ArmorStand carrier, UUID ownerId, UUID suitId) {
		carrier.addTag(CARRIER_TAG);
		carrier.addTag(OWNER_TAG_PREFIX + ownerId);
		carrier.addTag(SUIT_TAG_PREFIX + suitId);
		carrier.setPermanentlyInvulnerable(true);
		carrier.setSilent(true);
		carrier.setNoGravity(true);
		carrier.noPhysics = true;
		carrier.setShowArms(true);
		carrier.setNoBasePlate(true);
		setFlightPose(carrier);
	}

	private static void setFlightPose(ArmorStand carrier) {
		carrier.setHeadPose(new Rotations(-3.0F, 0.0F, -2.0F));
		carrier.setBodyPose(new Rotations(5.0F, 0.0F, 0.0F));
		carrier.setLeftArmPose(new Rotations(-14.0F, 0.0F, -12.0F));
		carrier.setRightArmPose(new Rotations(-10.0F, 0.0F, 14.0F));
		carrier.setLeftLegPose(new Rotations(8.0F, 0.0F, -5.0F));
		carrier.setRightLegPose(new Rotations(-6.0F, 0.0F, 5.0F));
	}

	private static void setVanguardDivePose(ArmorStand carrier) {
		carrier.setHeadPose(new Rotations(14.0F, 0.0F, 0.0F));
		carrier.setBodyPose(new Rotations(18.0F, 0.0F, 0.0F));
		carrier.setLeftArmPose(new Rotations(6.0F, 0.0F, -4.0F));
		carrier.setRightArmPose(new Rotations(6.0F, 0.0F, 4.0F));
		carrier.setLeftLegPose(new Rotations(9.0F, 0.0F, -3.0F));
		carrier.setRightLegPose(new Rotations(4.0F, 0.0F, 3.0F));
	}

	private static void setHoverPose(ArmorStand carrier, long now, UUID suitId) {
		float phase = (float) ((now + Math.floorMod(suitId.hashCode(), 40)) * 0.08D);
		carrier.setHeadPose(new Rotations(-3.0F, 4.0F * Mth.sin(phase), -2.0F));
		carrier.setBodyPose(new Rotations(3.0F, 0.0F, 0.0F));
		carrier.setLeftArmPose(new Rotations(-18.0F, 0.0F, -14.0F));
		carrier.setRightArmPose(new Rotations(-11.0F, 0.0F, 16.0F));
		carrier.setLeftLegPose(new Rotations(9.0F, 0.0F, -6.0F));
		carrier.setRightLegPose(new Rotations(-5.0F, 0.0F, 6.0F));
	}

	private static void aimCarrier(ArmorStand carrier, Entity target) {
		Vec3 delta = target.getBoundingBox().getCenter().subtract(carrier.position().add(0.0D, 1.25D, 0.0D));
		float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
		carrier.setYRot(Mth.rotLerp(0.42F, carrier.getYRot(), yaw));
		carrier.setYBodyRot(carrier.getYRot());
		carrier.setLeftArmPose(new Rotations(-31.0F, 0.0F, -8.0F));
		carrier.setRightArmPose(new Rotations(-26.0F, 0.0F, 9.0F));
	}


	/** Restores every indexed escort owned by this player using a temporary exact-chunk ticket. */
	public static void handleJoin(ServerPlayer player) {
		if (player == null) return;
		for (ArmorEscortSavedData.Locator locator :
				ArmorEscortSavedData.findByOwner(player.level().getServer(), player.getUUID())) {
			restoreIndexedCarrier(player.level().getServer(), locator, player);
		}
	}

	/** Owner logout never deletes the durable locator and never keeps a return chunk ticket alive. */
	public static void handleDisconnect(ServerPlayer player) {
		if (player == null) return;
		for (EscortState state : new ArrayList<>(STATES.values())) {
			if (!state.ownerId.equals(player.getUUID())) continue;
			setCombatLifecycleReady(state, false);
			resetVanguardRam(state);
			syncLocator(state);
			releaseReturnTicket(state);
		}
	}

	/** Startup validation loads only indexed chunks; no carrier or armor item is ever created here. */
	public static void recoverLoadedCarriers(MinecraftServer server) {
		int recovered = 0;
		for (ArmorEscortSavedData.Locator locator : ArmorEscortSavedData.snapshot(server)) {
			if (restoreIndexedCarrier(server, locator,
					server.getPlayerList().getPlayer(locator.ownerId()))) recovered++;
		}
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof ArmorStand stand && recoverCarrier(stand, level)) recovered++;
			}
		}
		if (recovered > 0) {
			FlightArmorMod.LOGGER.info("已验证或恢复 {} 个持久护航载体", recovered);
		}
	}

	private static boolean restoreIndexedCarrier(MinecraftServer server,
			ArmorEscortSavedData.Locator locator, ServerPlayer onlineOwner) {
		ServerLevel level = server.getLevel(locator.dimension());
		if (level == null) {
			FlightArmorMod.LOGGER.error(
					"护航索引指向不存在的维度 suit={} carrier={} dimension={}",
					locator.suitId(), locator.carrierId(), locator.dimension().identifier());
			return false;
		}
		if (onlineOwner != null && !onlineOwner.getUUID().equals(locator.ownerId())) {
			FlightArmorMod.LOGGER.error("护航恢复请求主人不匹配 suit={} owner={}",
					locator.suitId(), locator.ownerId());
			return false;
		}

		ChunkPos indexedChunk = new ChunkPos(locator.chunkX(), locator.chunkZ());
		ArmorStand found = null;
		ChunkPos foundChunk = null;
		for (int radius = 0; radius <= 1 && found == null; radius++) {
			for (int dx = -radius; dx <= radius && found == null; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
					ChunkPos candidateChunk =
							new ChunkPos(indexedChunk.x() + dx, indexedChunk.z() + dz);
					level.getChunkSource().addTicketWithRadius(
							RECOVERY_TICKET, candidateChunk, RETURN_TICKET_RADIUS);
					try {
						level.getChunk(candidateChunk.x(), candidateChunk.z());
						Entity entity = level.getEntity(locator.carrierId());
						if (entity instanceof ArmorStand stand) {
							found = stand;
							foundChunk = candidateChunk;
							break;
						}
					} finally {
						level.getChunkSource().removeTicketWithRadius(
								RECOVERY_TICKET, candidateChunk, RETURN_TICKET_RADIUS);
					}
				}
			}
		}
		if (found == null) {
			FlightArmorMod.LOGGER.warn(
					"护航索引仍在等待原载体 suit={} carrier={} chunk={}",
					locator.suitId(), locator.carrierId(), indexedChunk);
			return false;
		}
		boolean recovered = recoverCarrier(found, level);
		if (!recovered) {
			FlightArmorMod.LOGGER.error(
					"护航索引载体验证失败，索引保留且不会生成新甲 suit={} carrier={}",
					locator.suitId(), locator.carrierId());
		} else if (!indexedChunk.equals(foundChunk)) {
			FlightArmorMod.LOGGER.info(
					"护航载体在相邻区块找回 suit={} indexed={} actual={}",
					locator.suitId(), indexedChunk, foundChunk);
		}
		return recovered;
	}

	/** Chunk-load recovery accepts only an exact indexed carrier or one legacy tagged carrier. */
	public static void handleEntityLoad(Entity entity, ServerLevel level) {
		if (entity instanceof ArmorStand stand && !SPAWNING.contains(stand.getUUID())
				&& idFromTag(stand, SUIT_TAG_PREFIX)
						.map(suit -> !MIGRATING.contains(suit)).orElse(true)) {
			recoverCarrier(stand, level);
		}
	}

	public static void handleEntityUnload(Entity entity, ServerLevel level) {
		if (!(entity instanceof ArmorStand stand) || !isEscortCarrier(stand)) return;
		Optional<UUID> suitId = idFromTag(stand, SUIT_TAG_PREFIX);
		if (suitId.filter(MIGRATING::contains).isPresent()) return;
		EscortState state = suitId.map(STATES::get).orElse(null);
		if (state == null || state.carrier == null
				|| !state.carrier.getUUID().equals(stand.getUUID())) return;
		syncLocator(state);
		releaseReturnTicket(state);
	}

	public static void flushPersistentState(MinecraftServer server) {
		for (EscortState state : new ArrayList<>(STATES.values())) syncLocator(state);
	}

	private static boolean recoverCarrier(ArmorStand stand, ServerLevel level) {
		if (!isEscortCarrier(stand)) return false;
		Optional<UUID> ownerTag = idFromTag(stand, OWNER_TAG_PREFIX);
		Optional<UUID> suitTag = idFromTag(stand, SUIT_TAG_PREFIX);
		if (ownerTag.isEmpty() || suitTag.isEmpty()) {
			FlightArmorMod.LOGGER.warn("忽略缺少主人或套装标签的护航载体 {}", stand.getUUID());
			return false;
		}

		UUID ownerId = ownerTag.get();
		UUID suitId = suitTag.get();
		ArmorEscortSavedData.Locator locator = ArmorEscortSavedData.find(level.getServer(), suitId);
		EscortState existing = STATES.get(suitId);
		EscortRecoveryPolicy.Decision decision = EscortRecoveryPolicy.decide(
				new EscortRecoveryPolicy.Evidence(
						locator != null,
						true,
						locator == null || locator.ownerId().equals(ownerId),
						locator == null || locator.suitId().equals(suitId),
						hasOwnedFullSet(stand, ownerId, suitId),
						locator == null || locator.carrierId().equals(stand.getUUID()),
						existing != null,
						existing == null || (existing.carrier != null
								&& existing.carrier.getUUID().equals(stand.getUUID()))));
		if (decision != EscortRecoveryPolicy.Decision.RESTORE_INDEXED_PASSIVE
				&& decision != EscortRecoveryPolicy.Decision.ADOPT_LEGACY_PASSIVE
				&& decision != EscortRecoveryPolicy.Decision.REUSE_ACTIVE_STATE) {
			FlightArmorMod.LOGGER.error(
					"护航载体恢复被隔离 decision={} owner={} suit={} carrier={} indexed={}",
					decision, ownerId, suitId, stand.getUUID(),
					locator == null ? null : locator.carrierId());
			return false;
		}

		configureCarrier(stand, ownerId, suitId);
		if (decision == EscortRecoveryPolicy.Decision.REUSE_ACTIVE_STATE) {
			existing.carrier = stand;
			invalidateDelayedAttacks(existing);
			long persistedCarrierRevision = locator == null
					? existing.carrierRevision : locator.carrierRevision();
			existing.carrierRevision = nextRecoveryRevision(
					Math.max(existing.carrierRevision, persistedCarrierRevision));
			existing.recoveryGuardRequested = existing.recoveryAwaitingOwnerProfile
					? existing.recoveryGuardRequested : existing.guardEnabled;
			existing.recoveryAwaitingOwnerProfile = true;
			if (locator != null) existing.persistenceRevision = locator.revision();
			if (!syncLocator(existing)) return false;
			return true;
		}

		ArmorEscortSavedData.Locator effective = locator;
		if (effective == null) {
			long carrierRevision = NEXT_CARRIER_REVISION.incrementAndGet();
			effective = new ArmorEscortSavedData.Locator(
					ownerId, suitId, stand.getUUID(), level.dimension(),
					stand.chunkPosition().x(), stand.chunkPosition().z(),
					ArmorEscortSavedData.StoredPhase.ESCORTING,
					null, null, null, ChargingPlatformDisplayMode.ASSEMBLED,
					true, 1L, carrierRevision);
			if (!ArmorEscortSavedData.create(level.getServer(), effective)) {
				FlightArmorMod.LOGGER.error(
						"旧护航载体无法建立唯一索引 suit={} carrier={}", suitId, stand.getUUID());
				return false;
			}
		} else {
			ArmorEscortSavedData.Locator relocated = new ArmorEscortSavedData.Locator(
					effective.ownerId(), effective.suitId(), effective.carrierId(), level.dimension(),
					stand.chunkPosition().x(), stand.chunkPosition().z(), effective.phase(),
					effective.homePlatformId(), effective.homeDimension(), effective.homePos(),
					effective.displayMode(), effective.guardRequested(),
					effective.revision() + 1L, effective.carrierRevision());
			if (!effective.sameState(relocated)) {
				if (!ArmorEscortSavedData.compareAndSet(level.getServer(), suitId, stand.getUUID(),
						effective.revision(), relocated)) return false;
				effective = relocated;
			}
		}

		ServerPlayer onlineOwner = level.getServer().getPlayerList().getPlayer(ownerId);
		if (onlineOwner == null) return true;
		boolean completeHome = effective.hasCompleteHome();
		EscortRecoveryPolicy.SafePhase safe =
				EscortRecoveryPolicy.safePhase(effective.phase(), completeHome);
		Phase restoredPhase = safe == EscortRecoveryPolicy.SafePhase.RETURNING
				? Phase.RETURNING : Phase.ESCORTING;
		EscortState recovered = new EscortState(
				ownerId, suitId, stand, restoredPhase, level.getGameTime(), stand.position(), false,
				effective.homePlatformId(), effective.homeDimension(), effective.homePos(),
				effective.displayMode());
		recovered.persistenceRevision = effective.revision();
		recovered.carrierRevision = nextRecoveryRevision(effective.carrierRevision());
		recovered.actionSequence = nextActionSequence(0L);
		recovered.recoveryAwaitingOwnerProfile = true;
		recovered.recoveryGuardRequested = effective.guardRequested();
		if (STATES.putIfAbsent(suitId, recovered) != null) {
			FlightArmorMod.LOGGER.error(
					"恢复时检测到重复护航状态 suit={} carrier={}", suitId, stand.getUUID());
			return false;
		}
		if (restoredPhase == Phase.RETURNING) updateReturnTicket(recovered);
		if (!syncLocator(recovered)) {
			STATES.remove(suitId, recovered);
			releaseReturnTicket(recovered);
			return false;
		}
		broadcast(level.getServer(), recovered, true);
		return true;
	}

	private static long nextRecoveryRevision(long persistedRevision) {
		return NEXT_CARRIER_REVISION.updateAndGet(
				current -> Math.max(current, persistedRevision) + 1L);
	}

	private static boolean syncLocator(EscortState state) {
		if (state == null || state.carrier == null
				|| (state.carrier.isRemoved()
						&& state.carrier.getRemovalReason() != Entity.RemovalReason.UNLOADED_TO_CHUNK)
				|| !(state.carrier.level() instanceof ServerLevel level)
				|| !hasOwnedFullSet(state.carrier, state.ownerId, state.suitId)) {
			return false;
		}
		boolean completeHome = state.homePlatformId != null
				&& state.homeDimension != null && state.homePos != null;
		ArmorEscortSavedData.StoredPhase storedPhase =
				state.phase == Phase.RETURNING && completeHome
						? ArmorEscortSavedData.StoredPhase.RETURNING
						: ArmorEscortSavedData.StoredPhase.ESCORTING;
		boolean guardRequested = state.recoveryAwaitingOwnerProfile
				? state.recoveryGuardRequested : state.guardEnabled;
		long nextRevision = state.persistenceRevision + 1L;
		ArmorEscortSavedData.Locator locator = new ArmorEscortSavedData.Locator(
				state.ownerId, state.suitId, state.carrier.getUUID(), level.dimension(),
				state.carrier.chunkPosition().x(), state.carrier.chunkPosition().z(), storedPhase,
				completeHome ? state.homePlatformId : null,
				completeHome ? state.homeDimension : null,
				completeHome ? state.homePos : null,
				state.homeDisplayMode == null
						? ChargingPlatformDisplayMode.ASSEMBLED : state.homeDisplayMode,
				guardRequested, nextRevision, state.carrierRevision);
		ArmorEscortSavedData.Locator indexed =
				ArmorEscortSavedData.find(level.getServer(), state.suitId);
		if (indexed != null && indexed.revision() == state.persistenceRevision
				&& indexed.sameState(locator)) return true;
		boolean updated = state.persistenceRevision == 0L
				? ArmorEscortSavedData.create(level.getServer(), locator)
				: ArmorEscortSavedData.compareAndSet(level.getServer(), state.suitId,
						state.carrier.getUUID(), state.persistenceRevision, locator);
		if (updated) {
			state.persistenceRevision = nextRevision;
			return true;
		}
		FlightArmorMod.LOGGER.error(
				"护航持久索引 CAS 冲突 suit={} activeCarrier={} expectedRevision={} indexedCarrier={} indexedRevision={}",
				state.suitId, state.carrier.getUUID(), state.persistenceRevision,
				indexed == null ? null : indexed.carrierId(),
				indexed == null ? null : indexed.revision());
		return false;
	}

	private static void removeLocatorAfterCommit(MinecraftServer server, EscortState state) {
		if (!ArmorEscortSavedData.removeIfMatches(
				server, state.suitId, state.carrier.getUUID(), state.persistenceRevision)) {
			FlightArmorMod.LOGGER.error(
					"护航物品已提交但持久索引版本不匹配 suit={} carrier={} recordRevision={}",
					state.suitId, state.carrier.getUUID(), state.persistenceRevision);
		}
	}

	public static void syncCarrierTo(Entity entity, ServerPlayer recipient) {
		if (!(entity instanceof ArmorStand stand)) return;
		for (EscortState state : STATES.values()) {
			if (state.carrier == stand) send(recipient, state, true);
		}
	}

	public static void syncAllTo(ServerPlayer recipient) {
		for (EscortState state : STATES.values()) send(recipient, state, true);
	}

	private static void updateReturnTicket(EscortState state) {
		if (state == null || state.phase != Phase.RETURNING || state.carrier == null
				|| state.carrier.isRemoved() || !(state.carrier.level() instanceof ServerLevel level)) {
			return;
		}
		ChunkPos nextChunk = state.carrier.chunkPosition();
		if (level == state.returnTicketLevel && nextChunk.equals(state.returnTicketChunk)) return;

		// Acquire the destination ticket first so controller handoff and cross-chunk travel have no gap.
		level.getChunkSource().addTicketWithRadius(
				RETURN_TICKET, nextChunk, RETURN_TICKET_RADIUS);
		if (state.returnTicketLevel != null && state.returnTicketChunk != null) {
			state.returnTicketLevel.getChunkSource().removeTicketWithRadius(
					RETURN_TICKET, state.returnTicketChunk, RETURN_TICKET_RADIUS);
		}
		state.returnTicketLevel = level;
		state.returnTicketChunk = nextChunk;
	}

	private static void releaseReturnTicket(EscortState state) {
		if (state != null && state.returnTicketLevel != null && state.returnTicketChunk != null) {
			state.returnTicketLevel.getChunkSource().removeTicketWithRadius(
					RETURN_TICKET, state.returnTicketChunk, RETURN_TICKET_RADIUS);
			state.returnTicketLevel = null;
			state.returnTicketChunk = null;
		}
	}

	public static void handleServerStopping(MinecraftServer server) {
		for (EscortState state : STATES.values()) {
			setCombatLifecycleReady(state, false);
			syncLocator(state);
			releaseReturnTicket(state);
		}
		STATES.clear();
		SPAWNING.clear();
		MIGRATING.clear();
	}

	private static boolean hasFullSet(ArmorStand stand) {
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			if (!ModItems.isExactFlightArmor(stand.getItemBySlot(slot), slot)) return false;
		}
		return true;
	}

	private static boolean hasOwnedFullSet(ArmorStand stand, UUID ownerId, UUID suitId) {
		if (stand == null || ownerId == null || suitId == null) return false;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			if (!ModItems.isExactFlightArmor(stand.getItemBySlot(slot), slot)) return false;
			Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(stand.getItemBySlot(slot));
			if (identity.isEmpty() || !identity.get().ownerId().equals(ownerId)
					|| !identity.get().suitId().equals(suitId) || identity.get().slot() != slot) {
				return false;
			}
		}
		return true;
	}

	private static Optional<UUID> idFromTag(ArmorStand stand, String prefix) {
		for (String tag : stand.entityTags()) if (tag.startsWith(prefix)) {
			try { return Optional.of(UUID.fromString(tag.substring(prefix.length()))); }
			catch (IllegalArgumentException ignored) {}
		}
		return Optional.empty();
	}

	private static void broadcast(MinecraftServer server, EscortState state, boolean active) {
		if (active) syncLocator(state);
		Set<ServerPlayer> recipients = state.carrier == null
				? new HashSet<>() : new HashSet<>(PlayerLookup.tracking(state.carrier));
		if (!active) recipients.addAll(server.getPlayerList().getPlayers());
		ServerPlayer owner = server.getPlayerList().getPlayer(state.ownerId);
		if (owner != null) recipients.add(owner);
		for (ServerPlayer recipient : recipients) send(recipient, state, active);
	}

	private static void send(ServerPlayer recipient, EscortState state, boolean active) {
		if (!ServerPlayNetworking.canSend(recipient, ArmorEscortStatePayload.TYPE)) return;
		ServerPlayNetworking.send(recipient, new ArmorEscortStatePayload(state.ownerId, state.suitId,
				active ? state.carrier.getUUID() : ArmorEscortStatePayload.NO_CARRIER,
				active ? state.carrier.getId() : -1, active, state.guardEnabled,
				switch (state.phase) {
					case LAUNCHING -> ArmorEscortStatePayload.EscortPhase.LAUNCHING;
					case TRANSIT, RETURNING, EQUIPPING -> ArmorEscortStatePayload.EscortPhase.TRANSIT;
					case ESCORTING -> ArmorEscortStatePayload.EscortPhase.ESCORTING;
				}));
	}

	private enum Phase { LAUNCHING, TRANSIT, ESCORTING, RETURNING, EQUIPPING }

	private enum VanguardRamPhase {
		CLIMB,
		LOCK,
		DIVE,
		IMPACT,
		MISS,
		PULL_UP,
		RECOVER
	}

	private enum VanguardRamResult { NONE, HIT, CONTACT, MISSED, ABORTED }

	private static final class EscortState {
		final UUID ownerId;
		final UUID suitId;
		ArmorStand carrier;
		Phase phase;
		long phaseStartTick;
		final Vec3 launchOrigin;
		boolean guardEnabled;
		UUID homePlatformId;
		ResourceKey<Level> homeDimension;
		BlockPos homePos;
		ChargingPlatformDisplayMode homeDisplayMode;
		LivingEntity combatTarget;
		Vec3 lastMovement = Vec3.ZERO;
		long actionSequence;
		long carrierRevision;
		long persistenceRevision;
		boolean combatLifecycleReady;
		boolean recoveryAwaitingOwnerProfile;
		boolean recoveryGuardRequested;
		ServerLevel returnTicketLevel;
		ChunkPos returnTicketChunk;
		long nextAttackTick;
		long nextTargetScanTick;
		VanguardRamPhase vanguardPhase;
		VanguardRamResult vanguardResult = VanguardRamResult.NONE;
		UUID vanguardTargetId;
		long vanguardPhaseStartTick;
		Vec3 vanguardStagingPoint;
		Vec3 vanguardLockedTargetPosition;
		Vec3 vanguardInterceptPoint;
		Vec3 vanguardDiveDirection = Vec3.ZERO;
		Vec3 vanguardPullUpPoint;
		double vanguardPullUpStartY;
		boolean vanguardHitCommitted;
		boolean vanguardRecoveryShotAttempted;

		EscortState(UUID ownerId, UUID suitId, ArmorStand carrier, Phase phase,
				long phaseStartTick, Vec3 launchOrigin, boolean guardEnabled,
				UUID homePlatformId, ResourceKey<Level> homeDimension, BlockPos homePos,
				ChargingPlatformDisplayMode homeDisplayMode) {
			this.ownerId = ownerId; this.suitId = suitId; this.carrier = carrier; this.phase = phase;
			this.carrierRevision = NEXT_CARRIER_REVISION.incrementAndGet();
			this.phaseStartTick = phaseStartTick; this.launchOrigin = launchOrigin;
			this.guardEnabled = guardEnabled;
			this.recoveryGuardRequested = guardEnabled;
			this.nextAttackTick = phaseStartTick + Math.floorMod(suitId.hashCode(), 12);
			this.nextTargetScanTick = phaseStartTick + Math.floorMod(suitId.hashCode(), 5);
			this.homePlatformId = homePlatformId; this.homeDimension = homeDimension;
			this.homePos = homePos == null ? null : homePos.immutable(); this.homeDisplayMode = homeDisplayMode;
		}
	}

	public record SwitchDeploymentResult(boolean success, String message) {
		static SwitchDeploymentResult started() { return new SwitchDeploymentResult(true, ""); }
		static SwitchDeploymentResult failed(String message) {
			return new SwitchDeploymentResult(false, message);
		}
	}

	public record LaunchResult(boolean success, String message, UUID suitId) {
		static LaunchResult success(UUID suitId) { return new LaunchResult(true, "", suitId); }
		static LaunchResult failed(String message) { return new LaunchResult(false, message, null); }
	}

	public record EscortWorldAnchor(ResourceKey<Level> dimension, BlockPos position) {}

	public record EscortCommandResult(boolean success, String message) {
		static EscortCommandResult started(String message) { return new EscortCommandResult(true, message); }
		static EscortCommandResult failed(String message) { return new EscortCommandResult(false, message); }
	}
}
