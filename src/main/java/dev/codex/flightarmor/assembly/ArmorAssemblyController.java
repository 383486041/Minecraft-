package dev.codex.flightarmor.assembly;

import com.mojang.datafixers.util.Pair;
import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.combat.ArmorCombatController;
import dev.codex.flightarmor.combat.ArmorCombatDenyReason;
import dev.codex.flightarmor.flight.FlightController;
import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorAssemblyStatePayload;
import dev.codex.flightarmor.network.AutomaticHoverSource;
import dev.codex.flightarmor.platform.ArmorIdentity;
import dev.codex.flightarmor.platform.ChargingPlatformController;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/** 服务端权威的按装备槽分件动画状态机与物品迁移入口。 */
public final class ArmorAssemblyController {
	public static final int ASSEMBLY_DURATION_TICKS = ArmorAssemblyTiming.TOTAL_DURATION_TICKS;
	private static final long ANY_ATTEMPT_ID = Long.MIN_VALUE;

	private static final AtomicLong NEXT_SEQUENCE_ID = new AtomicLong();
	private static final Map<UUID, AssemblyState> STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> BOOT_HOVER_LOCK_OVERRIDES = new ConcurrentHashMap<>();
	private static final Set<UUID> SWITCH_HANDOFF_RETRACTIONS = ConcurrentHashMap.newKeySet();
	private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
			EquipmentSlot.FEET,
			EquipmentSlot.LEGS,
			EquipmentSlot.CHEST,
			EquipmentSlot.HEAD
	);

	private ArmorAssemblyController() {
	}

	/** 普通 B 的背包装甲入口：只给本次真正缺失的槽位播放动画。 */
	public static void handleRequest(ServerPlayer player) {
		handleRequest(player, null);
	}

	/**
	 * Empty-slot B arbitration uses this before remote recall. A complete local set may be worn
	 * while other exact suits remain physical cruise carriers, but a duplicated carrier identity
	 * or a non-CARRIED ownership ledger is rejected without falling through to remote recall.
	 */
	public static LocalAssemblyResult tryHandleCompleteInventorySet(
			ServerPlayer player, Set<UUID> carrierSuitIds) {
		if (player == null || !player.isAlive() || player.isSpectator()) {
			return LocalAssemblyResult.UNAVAILABLE;
		}
		Set<UUID> forbidden = carrierSuitIds == null ? Set.of() : Set.copyOf(carrierSuitIds);
		AssemblySetKey anyComplete = chooseInventoryAssemblySet(
				player, player.getInventory(), ARMOR_SLOTS);
		if (anyComplete == null) return LocalAssemblyResult.UNAVAILABLE;

		AssemblySetKey admitted = chooseInventoryAssemblySetMatching(
				player, player.getInventory(), ARMOR_SLOTS, candidate -> {
					if (candidate.suitId() == null) return true;
					if (forbidden.contains(candidate.suitId())) return false;
					ChargingPlatformController.OwnershipRuntimeView ownership =
							ChargingPlatformController.ownershipRuntimeView(
									player, candidate.suitId()).orElse(null);
					return ownership != null
							&& ownership.location()
							== ChargingPlatformController.OwnershipLocation.CARRIED;
				});
		if (admitted == null) {
			deny(player, "背包战甲与巡航载体身份重复或所有权位置异常，已拒绝穿戴以防复制");
			return LocalAssemblyResult.HANDLED;
		}
		handleRequest(player, admitted);
		return LocalAssemblyResult.HANDLED;
	}

	private static void handleRequest(ServerPlayer player, AssemblySetKey preferredSet) {
		if (!player.isAlive() || player.isSpectator()) {
			deny(player, "当前状态无法召回战甲");
			return;
		}
		if (isAnimating(player)) {
			deny(player, "战甲部件正在移动，请稍候");
			return;
		}

		boolean hadFlightGear = ModItems.hasFlightGear(player);
		EquipResult result = equipSlotsAtomically(player, ARMOR_SLOTS, preferredSet);
		if (!result.success()) {
			deny(player, result.message());
			return;
		}
		if (result.affectedSlotMask() == 0) {
			deny(player, "战甲已经完整穿戴，无需重新组装");
			return;
		}

		boolean locksFlight = !hadFlightGear
				&& ArmorAssemblySlotMask.contains(result.affectedSlotMask(), EquipmentSlot.FEET);
		beginAnimation(player, result.affectedSlotMask(), false, true, locksFlight, Map.of());
		player.sendOverlayMessage(Component.literal(
				result.affectedSlotMask() == ArmorAssemblySlotMask.FULL_SET
						? "战甲分件召回已启动" : "正在补装缺失的战甲部件"));
	}

	/** 巡航载体完成物品交接后，保留原有的整甲收束动画。 */
	public static boolean beginAssemblyForEquippedSet(ServerPlayer player) {
		return beginAssemblyForEquippedSet(player, ArmorAssemblyTiming.BOOTS_LOCK_TICKS);
	}

	public static boolean beginAssemblyForEquippedSet(ServerPlayer player, int bootHoverLockTick) {
		if (!player.isAlive() || player.isSpectator() || isAnimating(player) || !ModItems.hasFullSet(player)) {
			return false;
		}
		beginAnimation(player, ArmorAssemblySlotMask.FULL_SET, false, true, true, Map.of());
		int clampedLockTick = Math.max(ArmorAssemblyTiming.BOOTS_LOCK_TICKS,
				Math.min(ArmorAssemblyTiming.TOTAL_DURATION_TICKS, bootHoverLockTick));
		if (clampedLockTick > ArmorAssemblyTiming.BOOTS_LOCK_TICKS) {
			BOOT_HOVER_LOCK_OVERRIDES.put(player.getUUID(), clampedLockTick);
		}
		return true;
	}

	/** Ctrl+↑ 使用：先唯一地移动目标物品，再只播放该装备槽的飞入和锁定动画。 */
	public static boolean beginEquipPart(ServerPlayer player, EquipmentSlot slot) {
		if (!isArmorSlot(slot) || !player.isAlive() || player.isSpectator() || isAnimating(player)) {
			return false;
		}
		boolean hadFlightGear = ModItems.hasFlightGear(player);
		EquipResult result = equipSlotsAtomically(player, List.of(slot));
		if (!result.success()) {
			deny(player, result.message());
			return false;
		}
		if (result.affectedSlotMask() == 0) {
			deny(player, partName(slot) + "已经穿戴");
			return false;
		}
		boolean locksFlight = !hadFlightGear && slot == EquipmentSlot.FEET;
		beginAnimation(player, result.affectedSlotMask(), false, false, locksFlight, Map.of());
		player.sendOverlayMessage(Component.literal("正在穿戴" + partName(slot)));
		return true;
	}

	/**
	 * Ctrl+↓ / Ctrl+B 收甲使用。物品在脱出动画结束前仍留在装备槽，保证模型能完整飞离；
	 * 完成瞬间才原子移入背包，动画失败或断线不会丢失物品。
	 */
	public static boolean beginRetraction(ServerPlayer player, List<EquipmentSlot> requestedSlots) {
		return beginRetraction(player, requestedSlots, false);
	}

	/** Quick-switch path: preserve the full outward animation before handing the suit to a carrier. */
	public static boolean beginSwitchRetraction(ServerPlayer player) {
		return beginRetraction(player, ARMOR_SLOTS, true);
	}

	private static boolean beginRetraction(ServerPlayer player, List<EquipmentSlot> requestedSlots,
			boolean switchingSuit) {
		if (!player.isAlive() || player.isSpectator() || isAnimating(player)) return false;

		Map<EquipmentSlot, ItemStack> pending = new EnumMap<>(EquipmentSlot.class);
		int mask = 0;
		for (EquipmentSlot slot : requestedSlots) {
			if (!isArmorSlot(slot)) continue;
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.is(ModItems.flightArmorForSlot(slot))) {
				pending.put(slot, stack);
				mask |= ArmorAssemblySlotMask.bit(slot);
			}
		}
		if (pending.isEmpty()) {
			deny(player, "身上没有可以收起的战甲部件");
			return false;
		}
		if (findEmptyInventorySlots(player.getInventory(), pending.size()).size() < pending.size()) {
			deny(player, "背包空位不足，无法收起战甲");
			return false;
		}

		beginAnimation(player, mask, true, false, false, Map.copyOf(pending));
		if (switchingSuit) {
			SWITCH_HANDOFF_RETRACTIONS.add(player.getUUID());
			player.sendOverlayMessage(Component.translatable(
					"message.flight_armor.quick_switch.separating"));
			return true;
		}
		SWITCH_HANDOFF_RETRACTIONS.remove(player.getUUID());
        player.sendOverlayMessage(Component.literal(pending.size() == 1
				? "正在解除" + partName(pending.keySet().iterator().next())
				: "正在分件收起战甲"));
		return true;
	}

	private static void beginAnimation(ServerPlayer player, int affectedSlotMask, boolean retracting,
			boolean fullAssembly, boolean locksFlight, Map<EquipmentSlot, ItemStack> pendingRetraction) {
		BOOT_HOVER_LOCK_OVERRIDES.remove(player.getUUID());
		long sequenceId = NEXT_SEQUENCE_ID.incrementAndGet();
		long startGameTime = player.level().getGameTime();
		int timelineStart = ArmorAssemblySlotMask.timelineStart(affectedSlotMask);
		int timelineEnd = ArmorAssemblySlotMask.timelineEnd(
				affectedSlotMask, locksFlight && !retracting);
		AssemblyState state = new AssemblyState(
				sequenceId,
				startGameTime,
				Math.max(1, timelineEnd - timelineStart),
				timelineStart,
				timelineEnd,
				affectedSlotMask,
				retracting,
				fullAssembly,
				locksFlight,
				true,
				false,
				player.level().dimension(),
				pendingRetraction
		);
		STATES.put(player.getUUID(), state);
		// 装甲部件开始移动后立即清除腕炮姿势，防止装备事务与攻击状态交叉。
		ArmorCombatController.cancel(player, ArmorCombatDenyReason.ASSEMBLY_ACTIVE);
		broadcast(player, state.toPayload(player.getUUID(), true));
	}

	/** 更新动画、战靴悬停授权，并在脱甲动画结束时提交背包事务。 */
	public static void tickServer(MinecraftServer server) {
		for (Map.Entry<UUID, AssemblyState> entry : STATES.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				STATES.remove(entry.getKey(), entry.getValue());
				BOOT_HOVER_LOCK_OVERRIDES.remove(entry.getKey());
				continue;
			}

			AssemblyState state = entry.getValue();
			long elapsed = Math.max(0L, player.level().getGameTime() - state.startGameTime());
			// 脱甲完成后保留 2 tick 的“已飞离”视觉墓碑，覆盖装备同步包可能稍晚到达的一帧。
			if (state.retracting() && !state.animating()) {
				if (elapsed >= state.durationTicks() + 2L && STATES.remove(entry.getKey(), state)) {
					broadcast(player, state.toPayload(player.getUUID(), false));
				}
				continue;
			}
			if (!stateIsValid(player, state)) {
				cancelState(player, entry.getKey(), state);
				continue;
			}
			if (!state.animating()) continue;

			if (state.locksFlight() && !state.retracting() && !state.bootHoverTriggered()
					&& ArmorAssemblySlotMask.contains(state.affectedSlotMask(), EquipmentSlot.FEET)) {
				// 输入不在此阶段生效；这里只避免低空召回在本次战靴锁定时间点前先结算摔落伤害。
				FlightPhysics.applyPreBootFallArrest(player);
				player.syncVelocity = true;
			}
			if (state.locksFlight() && !state.retracting() && !state.bootHoverTriggered()
					&& ArmorAssemblySlotMask.contains(state.affectedSlotMask(), EquipmentSlot.FEET)
					&& canonicalTick(state, elapsed) >= bootHoverLockTick(entry.getKey())
					&& FlightController.tryEngageHoverAfterBootLock(
							player, AutomaticHoverSource.ASSEMBLY_BOOTS,
							state.sequenceId(), state.startGameTime())) {
				AssemblyState bootsOnline = state.withBootHoverTriggered(true);
				if (STATES.replace(entry.getKey(), state, bootsOnline)) {
					state = bootsOnline;
					player.sendOverlayMessage(Component.literal("战靴锁定完成，反重力悬停已接管"));
				}
			}

			if (elapsed < state.durationTicks()) continue;

			if (state.retracting()) {
				RetractionResult result = commitRetraction(player, state.pendingRetraction());
				if (!result.success()) {
					if (STATES.remove(entry.getKey(), state)) {
						broadcast(player, state.toPayload(player.getUUID(), false));
						SWITCH_HANDOFF_RETRACTIONS.remove(entry.getKey());
						deny(player, result.message());
					}
					continue;
				}
				AssemblyState completedRetraction = state.retractionCompleted();
				if (STATES.replace(entry.getKey(), state, completedRetraction)) {
					broadcast(player, completedRetraction.toPayload(player.getUUID(), true));
					boolean switchHandoff = SWITCH_HANDOFF_RETRACTIONS.remove(entry.getKey());
					if (switchHandoff) player.sendOverlayMessage(Component.translatable(
							"message.flight_armor.quick_switch.separated"));
					if (!ModItems.hasFlightGear(player)) FlightController.remove(player);
					if (!switchHandoff) player.sendOverlayMessage(Component.literal(result.movedCount() == 1
							? "已解除" + partName(result.singleSlot())
							: "已将 " + result.movedCount() + " 件战甲部件收进背包"));
				}
				continue;
			}

			AssemblyState completed = state.completed();
			if (STATES.replace(entry.getKey(), state, completed)) {
				BOOT_HOVER_LOCK_OVERRIDES.remove(entry.getKey());
				broadcast(player, completed.toPayload(player.getUUID(), true));
				if (state.fullAssembly()) {
					player.sendOverlayMessage(Component.literal(
							state.affectedSlotMask() == ArmorAssemblySlotMask.FULL_SET
									? "战甲组装完成，核心已上线" : "缺失战甲部件补装完成"));
					FlightController.tryEngageHoverAfterArmorReady(
							player, AutomaticHoverSource.ASSEMBLY_COMPLETE,
							state.sequenceId(), state.startGameTime());
				} else {
					player.sendOverlayMessage(Component.literal(
							"已穿戴" + partName(singleSlot(state.affectedSlotMask()))));
				}
			}
		}
	}

	/** 仍在播放任何穿入或脱出动画；用于拒绝第二个槽位事务。 */
	public static boolean isAnimating(ServerPlayer player) {
		AssemblyState state = STATES.get(player.getUUID());
		return state != null && state.animating();
	}

	/** 着装/脱甲事务的不可变只读投影，不暴露待移动 ItemStack。 */
	public static java.util.Optional<AssemblyRuntimeView> runtimeView(ServerPlayer player) {
		if (player == null) return java.util.Optional.empty();
		AssemblyState state = STATES.get(player.getUUID());
		if (state == null) return java.util.Optional.empty();
		return java.util.Optional.of(new AssemblyRuntimeView(state.sequenceId(), state.startGameTime(),
				state.durationTicks(), state.affectedSlotMask(), state.retracting(),
				state.fullAssembly(), state.locksFlight(), state.animating(),
				state.bootHoverTriggered(), state.dimension()));
	}

	public record AssemblyRuntimeView(long sequenceId, long startGameTime, int durationTicks,
			int affectedSlotMask, boolean retracting, boolean fullAssembly, boolean locksFlight,
			boolean animating, boolean bootHoverTriggered, ResourceKey<Level> dimension) {
	}

	public static boolean isAnimating(UUID playerId) {
		AssemblyState state = STATES.get(playerId);
		return state != null && state.animating();
	}

	/** 只有战靴尚未锁定的起飞着甲过程会锁住常规飞行控制。 */
	public static boolean isAssembling(ServerPlayer player) {
		return isAssembling(player.getUUID());
	}

	public static boolean isAssembling(UUID playerId) {
		AssemblyState state = STATES.get(playerId);
		return state != null && state.animating() && state.locksFlight();
	}

	public static boolean isBootHoverReady(ServerPlayer player) {
		return isBootHoverReady(player, 0L, ANY_ATTEMPT_ID);
	}

	public static boolean isBootHoverReady(ServerPlayer player, long sequenceId, long attemptId) {
		AssemblyState state = STATES.get(player.getUUID());
		return state != null && state.animating() && !state.retracting()
				&& ArmorAssemblySlotMask.contains(state.affectedSlotMask(), EquipmentSlot.FEET)
				&& (sequenceId == 0L || state.sequenceId() == sequenceId)
				&& (attemptId == ANY_ATTEMPT_ID || state.startGameTime() == attemptId)
				&& state.dimension().equals(player.level().dimension())
				&& canonicalTick(state, player.level().getGameTime() - state.startGameTime())
				>= bootHoverLockTick(player.getUUID());
	}

	public static boolean isBootHoverAuthorized(ServerPlayer player) {
		return isBootHoverAuthorized(player, 0L, ANY_ATTEMPT_ID);
	}

	public static boolean isBootHoverAuthorized(ServerPlayer player, long sequenceId, long attemptId) {
		AssemblyState state = STATES.get(player.getUUID());
		return state != null && state.animating() && !state.retracting() && state.bootHoverTriggered()
				&& ArmorAssemblySlotMask.contains(state.affectedSlotMask(), EquipmentSlot.FEET)
				&& (sequenceId == 0L || state.sequenceId() == sequenceId)
				&& (attemptId == ANY_ATTEMPT_ID || state.startGameTime() == attemptId)
				&& state.dimension().equals(player.level().dimension())
				&& canonicalTick(state, player.level().getGameTime() - state.startGameTime())
				>= ArmorAssemblyTiming.BOOTS_LOCK_TICKS;
	}

	public static void revokeBootHoverAuthorization(ServerPlayer player, long sequenceId, long attemptId) {
		AssemblyState state = STATES.get(player.getUUID());
		if (state == null || !state.animating() || !state.bootHoverTriggered()
				|| state.sequenceId() != sequenceId || state.startGameTime() != attemptId) return;
		STATES.replace(player.getUUID(), state, state.withBootHoverTriggered(false));
	}

	public static void syncStateTo(ServerPlayer trackedPlayer, ServerPlayer recipient) {
		AssemblyState state = STATES.get(trackedPlayer.getUUID());
		if (state != null) send(recipient, state.toPayload(trackedPlayer.getUUID(), true));
	}

	/** 断线时只清理视觉状态；待脱物品尚未离开装备槽，因此不存在丢失窗口。 */
	public static void remove(ServerPlayer player) {
		STATES.remove(player.getUUID());
		BOOT_HOVER_LOCK_OVERRIDES.remove(player.getUUID());
		SWITCH_HANDOFF_RETRACTIONS.remove(player.getUUID());
	}

	private static EquipResult equipSlotsAtomically(ServerPlayer player, List<EquipmentSlot> slots) {
		return equipSlotsAtomically(player, slots, null);
	}

	private static EquipResult equipSlotsAtomically(ServerPlayer player,
			List<EquipmentSlot> slots, AssemblySetKey preferredSet) {
		Inventory inventory = player.getInventory();
		List<PlannedMove> plannedMoves = new ArrayList<>();
		Set<Integer> reservedInventorySlots = new HashSet<>();
		List<EquipmentSlot> missingSlots = new ArrayList<>();
		int affectedSlotMask = 0;
		AssemblySetKey selectedSet = preferredSet;

		// An already worn part is an explicit suit selection. Every additional part must match its
		// owner, suit id and variant; an identified suit can never be completed with anonymous or
		// differently identified parts.
		for (EquipmentSlot equipmentSlot : ARMOR_SLOTS) {
			ItemStack equipped = player.getItemBySlot(equipmentSlot);
			if (!ModItems.isExactFlightArmor(equipped, equipmentSlot)) continue;
			AssemblySetKey observed = assemblySetKey(player, equipped, equipmentSlot);
			if (observed == null) {
				return EquipResult.failure("已穿戴的战甲部件身份无效或不属于当前玩家");
			}
			if (selectedSet != null && !selectedSet.equals(observed)) {
				return EquipResult.failure("已穿戴部件来自不同战甲或型号，不能继续混装");
			}
			selectedSet = observed;
		}

		for (EquipmentSlot equipmentSlot : slots) {
			ItemStack equipped = player.getItemBySlot(equipmentSlot);
			if (ModItems.isExactFlightArmor(equipped, equipmentSlot)) continue;
			if (!equipped.isEmpty()) {
				return EquipResult.failure("无法穿戴：" + partName(equipmentSlot) + "槽位已被占用");
			}
			missingSlots.add(equipmentSlot);
		}

		if (missingSlots.isEmpty()) {
			return EquipResult.success(0);
		}
		if (selectedSet == null) {
			selectedSet = chooseInventoryAssemblySet(player, inventory, missingSlots);
			if (selectedSet == null) {
				return EquipResult.failure("背包中没有可组成同一套、同一型号战甲的完整部件");
			}
		}

		for (EquipmentSlot equipmentSlot : missingSlots) {
			int inventorySlot = findInventorySlot(
					player, inventory, equipmentSlot, selectedSet, reservedInventorySlots);
			if (inventorySlot < 0) {
				return EquipResult.failure("所选战甲缺少" + partName(equipmentSlot)
						+ "，不会使用其他战甲的同类部件补位");
			}
			reservedInventorySlots.add(inventorySlot);
			plannedMoves.add(new PlannedMove(inventorySlot, equipmentSlot, selectedSet));
			affectedSlotMask |= ArmorAssemblySlotMask.bit(equipmentSlot);
		}

		List<CommittedMove> committed = new ArrayList<>();
		try {
			for (PlannedMove move : plannedMoves) {
				if (!player.getItemBySlot(move.equipmentSlot()).isEmpty()
						|| !matchesAssemblySet(player, inventory.getItem(move.inventorySlot()),
								move.equipmentSlot(), move.expectedSet())) {
					throw new IllegalStateException("战甲穿戴提交前槽位发生变化");
				}
				ItemStack moved = inventory.removeItem(move.inventorySlot(), 1);
				if (!matchesAssemblySet(player, moved, move.equipmentSlot(), move.expectedSet())) {
					throw new IllegalStateException("战甲物品迁移计划失效");
				}
				committed.add(new CommittedMove(move.inventorySlot(), move.equipmentSlot(), moved));
				player.setItemSlot(move.equipmentSlot(), moved);
			}
		} catch (RuntimeException exception) {
			rollbackEquip(player, committed);
			FlightArmorMod.LOGGER.error("战甲穿戴事务异常，已回滚 player={}", player.getUUID(), exception);
			return EquipResult.failure("战甲穿戴失败，物品已恢复");
		}

		if (!plannedMoves.isEmpty()) {
			syncInventory(player);
			syncEquipment(player, plannedMoves.stream().map(PlannedMove::equipmentSlot).toList());
		}
		return EquipResult.success(affectedSlotMask);
	}

	private static RetractionResult commitRetraction(ServerPlayer player,
			Map<EquipmentSlot, ItemStack> pending) {
		Inventory inventory = player.getInventory();
		List<Integer> destinations = findEmptyInventorySlots(inventory, pending.size());
		if (destinations.size() < pending.size()) {
			return RetractionResult.failure("背包空位被占用，解除动作已取消");
		}

		List<CommittedRetraction> committed = new ArrayList<>();
		try {
			int destinationIndex = 0;
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				ItemStack expected = pending.get(slot);
				if (expected == null) continue;
				int destination = destinations.get(destinationIndex++);
				if (player.getItemBySlot(slot) != expected || !inventory.getItem(destination).isEmpty()) {
					throw new IllegalStateException("战甲脱出提交前槽位发生变化");
				}
				committed.add(new CommittedRetraction(destination, slot, expected));
				player.setItemSlot(slot, ItemStack.EMPTY);
				inventory.setItem(destination, expected);
			}
		} catch (RuntimeException exception) {
			rollbackRetraction(player, committed);
			FlightArmorMod.LOGGER.error("战甲脱出事务异常，已回滚 player={}", player.getUUID(), exception);
			return RetractionResult.failure("战甲解除失败，物品已恢复");
		}

		syncInventory(player);
		syncEquipment(player, new ArrayList<>(pending.keySet()));
		EquipmentSlot single = pending.size() == 1 ? pending.keySet().iterator().next() : null;
		return RetractionResult.success(pending.size(), single);
	}

	private static void rollbackEquip(ServerPlayer player, List<CommittedMove> committed) {
		for (int index = committed.size() - 1; index >= 0; index--) {
			CommittedMove move = committed.get(index);
			if (player.getItemBySlot(move.equipmentSlot()) == move.stack()) {
				player.setItemSlot(move.equipmentSlot(), ItemStack.EMPTY);
			}
			player.getInventory().setItem(move.inventorySlot(), move.stack());
		}
		syncInventory(player);
	}

	private static void rollbackRetraction(ServerPlayer player, List<CommittedRetraction> committed) {
		for (int index = committed.size() - 1; index >= 0; index--) {
			CommittedRetraction move = committed.get(index);
			if (player.getInventory().getItem(move.inventorySlot()) == move.stack()) {
				player.getInventory().setItem(move.inventorySlot(), ItemStack.EMPTY);
			}
			player.setItemSlot(move.equipmentSlot(), move.stack());
		}
		syncInventory(player);
	}

	private static boolean stateIsValid(ServerPlayer player, AssemblyState state) {
		if (!player.isAlive() || player.isSpectator()
				|| !state.dimension().equals(player.level().dimension())) return false;
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			if (!ArmorAssemblySlotMask.contains(state.affectedSlotMask(), slot)) continue;
			ItemStack equipped = player.getItemBySlot(slot);
			if (!equipped.is(ModItems.flightArmorForSlot(slot))) return false;
			ItemStack pending = state.pendingRetraction().get(slot);
			if (state.animating() && state.retracting() && pending != null && equipped != pending) return false;
		}
		return !state.fullAssembly() || ModItems.hasFullSet(player);
	}
	private static int bootHoverLockTick(UUID playerId) {
		return BOOT_HOVER_LOCK_OVERRIDES.getOrDefault(
				playerId, ArmorAssemblyTiming.BOOTS_LOCK_TICKS);
	}

	private static void cancelState(ServerPlayer player, UUID playerId, AssemblyState state) {
		if (!STATES.remove(playerId, state)) return;
		BOOT_HOVER_LOCK_OVERRIDES.remove(playerId);
		SWITCH_HANDOFF_RETRACTIONS.remove(playerId);
		broadcast(player, state.toPayload(playerId, false));
		if (state.animating() && state.bootHoverTriggered()) FlightController.remove(player);
	}

	private static double canonicalTick(AssemblyState state, long elapsed) {
		double normalized = Math.min(1.0D, Math.max(0.0D, elapsed / (double) state.durationTicks()));
		return state.timelineStartTick()
				+ normalized * (state.timelineEndTick() - state.timelineStartTick());
	}

	private static AssemblySetKey chooseInventoryAssemblySet(ServerPlayer player, Inventory inventory,
			List<EquipmentSlot> requiredSlots) {
		return chooseInventoryAssemblySetMatching(
				player, inventory, requiredSlots, ignored -> true);
	}

	private static AssemblySetKey chooseInventoryAssemblySetMatching(
			ServerPlayer player, Inventory inventory, List<EquipmentSlot> requiredSlots,
			Predicate<AssemblySetKey> admission) {
		if (requiredSlots.isEmpty()) return null;
		EquipmentSlot anchorSlot = requiredSlots.getFirst();
		for (int index = 0; index < inventory.getNonEquipmentItems().size(); index++) {
			ItemStack candidate = inventory.getNonEquipmentItems().get(index);
			AssemblySetKey candidateSet = assemblySetKey(player, candidate, anchorSlot);
			if (candidateSet == null || !admission.test(candidateSet)) continue;
			boolean complete = true;
			for (EquipmentSlot required : requiredSlots) {
				if (findInventorySlot(player, inventory, required, candidateSet, Set.of()) < 0) {
					complete = false;
					break;
				}
			}
			if (complete) return candidateSet;
		}
		return null;
	}

	private static int findInventorySlot(ServerPlayer player, Inventory inventory,
			EquipmentSlot equipmentSlot, AssemblySetKey expectedSet, Set<Integer> reservedSlots) {
		for (int index = 0; index < inventory.getNonEquipmentItems().size(); index++) {
			if (!reservedSlots.contains(index)
					&& matchesAssemblySet(player, inventory.getNonEquipmentItems().get(index), equipmentSlot, expectedSet)) {
				return index;
			}
		}
		return -1;
	}

	private static boolean matchesAssemblySet(ServerPlayer player, ItemStack stack,
			EquipmentSlot equipmentSlot, AssemblySetKey expectedSet) {
		AssemblySetKey observed = assemblySetKey(player, stack, equipmentSlot);
		return observed != null && observed.equals(expectedSet);
	}

	private static AssemblySetKey assemblySetKey(ServerPlayer player, ItemStack stack,
			EquipmentSlot equipmentSlot) {
		if (!ModItems.isExactFlightArmor(stack, equipmentSlot)) return null;
		java.util.Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(stack);
		if (identity.isPresent()) {
			ArmorIdentity.Identity claimed = identity.get();
			if (!claimed.ownerId().equals(player.getUUID()) || claimed.slot() != equipmentSlot) return null;
			return new AssemblySetKey(claimed.suitId(), ArmorVariantData.get(stack), true);
		}
		if (!ArmorIdentity.canBeUsedBy(stack, player.getUUID(), equipmentSlot)) return null;
		return new AssemblySetKey(null, ArmorVariantData.get(stack), false);
	}

	private static List<Integer> findEmptyInventorySlots(Inventory inventory, int count) {
		List<Integer> result = new ArrayList<>(count);
		for (int index = 0; index < inventory.getNonEquipmentItems().size() && result.size() < count; index++) {
			if (inventory.getNonEquipmentItems().get(index).isEmpty()) result.add(index);
		}
		return result;
	}

	private static boolean isArmorSlot(EquipmentSlot slot) {
		return ARMOR_SLOTS.contains(slot);
	}

	private static EquipmentSlot singleSlot(int mask) {
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			if (ArmorAssemblySlotMask.contains(mask, slot)) return slot;
		}
		return EquipmentSlot.CHEST;
	}

	private static String partName(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> "头盔";
			case CHEST -> "胸甲";
			case LEGS -> "护腿";
			case FEET -> "战靴";
			default -> "战甲";
		};
	}

	private static void syncInventory(ServerPlayer player) {
		player.getInventory().setChanged();
		player.containerMenu.broadcastChanges();
	}

	/** 显式按同一连接顺序推送装备槽，再广播动画快照，远端观察者不会首尾闪回一帧。 */
	private static void syncEquipment(ServerPlayer player, List<EquipmentSlot> slots) {
		if (slots.isEmpty()) return;
		List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>(slots.size());
		for (EquipmentSlot slot : slots) {
			equipment.add(Pair.of(slot, player.getItemBySlot(slot).copy()));
		}
		ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(player.getId(), equipment);
		player.connection.send(packet);
		for (ServerPlayer trackingPlayer : PlayerLookup.tracking(player)) {
			if (trackingPlayer != player) trackingPlayer.connection.send(packet);
		}
	}

	private static void deny(ServerPlayer player, String message) {
		player.sendOverlayMessage(Component.literal(message));
	}

	private static void broadcast(ServerPlayer player, ArmorAssemblyStatePayload payload) {
		send(player, payload);
		for (ServerPlayer trackingPlayer : PlayerLookup.tracking(player)) {
			if (trackingPlayer != player) send(trackingPlayer, payload);
		}
	}

	private static void send(ServerPlayer recipient, ArmorAssemblyStatePayload payload) {
		if (ServerPlayNetworking.canSend(recipient, ArmorAssemblyStatePayload.TYPE)) {
			ServerPlayNetworking.send(recipient, payload);
		}
	}

	private record AssemblySetKey(UUID suitId, ArmorVariant variant, boolean claimed) {
		private AssemblySetKey {
			if (variant == null || claimed != (suitId != null)) {
				throw new IllegalArgumentException("Invalid assembly set identity");
			}
		}
	}

	public enum LocalAssemblyResult {
		HANDLED,
		UNAVAILABLE
	}

	private record PlannedMove(int inventorySlot, EquipmentSlot equipmentSlot,
			AssemblySetKey expectedSet) {
	}

	private record CommittedMove(int inventorySlot, EquipmentSlot equipmentSlot, ItemStack stack) {
	}

	private record CommittedRetraction(int inventorySlot, EquipmentSlot equipmentSlot, ItemStack stack) {
	}

	private record EquipResult(boolean success, String message, int affectedSlotMask) {
		private static EquipResult success(int mask) {
			return new EquipResult(true, "", mask);
		}

		private static EquipResult failure(String message) {
			return new EquipResult(false, message, 0);
		}
	}

	private record RetractionResult(boolean success, String message, int movedCount,
			EquipmentSlot singleSlot) {
		private static RetractionResult success(int count, EquipmentSlot singleSlot) {
			return new RetractionResult(true, "", count, singleSlot);
		}

		private static RetractionResult failure(String message) {
			return new RetractionResult(false, message, 0, null);
		}
	}

	private record AssemblyState(
			long sequenceId,
			long startGameTime,
			int durationTicks,
			int timelineStartTick,
			int timelineEndTick,
			int affectedSlotMask,
			boolean retracting,
			boolean fullAssembly,
			boolean locksFlight,
			boolean animating,
			boolean bootHoverTriggered,
			ResourceKey<Level> dimension,
			Map<EquipmentSlot, ItemStack> pendingRetraction
	) {
		private AssemblyState withBootHoverTriggered(boolean value) {
			return new AssemblyState(sequenceId, startGameTime, durationTicks,
					timelineStartTick, timelineEndTick, affectedSlotMask, retracting,
					fullAssembly, locksFlight, animating, value, dimension, pendingRetraction);
		}

		private AssemblyState completed() {
			return new AssemblyState(sequenceId, startGameTime, durationTicks,
					timelineStartTick, timelineEndTick, affectedSlotMask, retracting,
					fullAssembly, locksFlight, false, bootHoverTriggered, dimension, Map.of());
		}

		private AssemblyState retractionCompleted() {
			return new AssemblyState(sequenceId, startGameTime, durationTicks,
					timelineStartTick, timelineEndTick, affectedSlotMask, true,
					false, false, false, false, dimension, Map.of());
		}

		private ArmorAssemblyStatePayload toPayload(UUID playerId, boolean active) {
			return new ArmorAssemblyStatePayload(
					playerId,
					sequenceId,
					startGameTime,
					durationTicks,
					active,
					active && animating,
					affectedSlotMask,
					retracting,
					timelineStartTick,
					timelineEndTick,
					locksFlight
			);
		}
	}
}
