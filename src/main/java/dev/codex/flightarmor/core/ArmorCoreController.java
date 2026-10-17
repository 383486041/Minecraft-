package dev.codex.flightarmor.core;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.cruise.ArmorCruiseController;
import dev.codex.flightarmor.cruise.ArmorEscortController;
import dev.codex.flightarmor.cruise.ArmorRemoteCommandController;
import dev.codex.flightarmor.flight.FlightController;
import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorCoreActionPayload;
import dev.codex.flightarmor.network.ArmorCoreCreativeSyncPayload;
import dev.codex.flightarmor.network.ArmorCoreSnapshotPayload;
import dev.codex.flightarmor.network.ArmorQuickSwitchPayload;
import dev.codex.flightarmor.platform.ArmorIdentity;
import dev.codex.flightarmor.platform.ChargingPlatformBlockEntity;
import dev.codex.flightarmor.platform.ChargingPlatformController;
import dev.codex.flightarmor.runtime.SuitRuntimeSnapshot;
import dev.codex.flightarmor.runtime.SuitStateResolver;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 独立核心槽、平台绑定和主副召回命令的服务器权威入口。 */
public final class ArmorCoreController {
	private static final Map<UUID, Long> LAST_CLIENT_SEQUENCE = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_QUICK_SWITCH_SEQUENCE = new ConcurrentHashMap<>();
	private static final Map<UUID, QuickSwitchState> QUICK_SWITCHES = new ConcurrentHashMap<>();
	private static final Map<SuitKey, Long> CONFIRMED_DESTROYED_SUITS = new ConcurrentHashMap<>();
	private static final long QUICK_SWITCH_TIMEOUT_TICKS = 20L * 60L;
	private static final long QUICK_SWITCH_RECOVERY_RETRY_TICKS = 20L;
	private static final long WORN_RECONCILE_INTERVAL_TICKS = 20L;

	private ArmorCoreController() {}
	static boolean isCoreChangeLocked(UUID playerId) {
		return playerId != null && QUICK_SWITCHES.containsKey(playerId);
	}

	static boolean isCoreChangeLocked(ServerPlayer player) {
		return player != null && (isCoreChangeLocked(player.getUUID())
				|| ArmorAssemblyController.isAnimating(player));
	}

	public static boolean hasActiveQuickSwitch(ServerPlayer player) {
		if (player == null) return false;
		hydrateQuickSwitches(player.level().getServer());
		return isCoreChangeLocked(player.getUUID());
	}

	/**
	 * 核心在位才解锁「网络能力」：形态切换、遥控指令，以及本来就需要 activeIdentity 的
	 * 编队召回、单甲关联与快速换甲。
	 *
	 * <p>飞行与基础战斗刻意不在此列：拔掉核心仍可裸装飞行与开火，只是无法使用核心网络。
	 * 这是「核心赋能」的唯一定义点，任何新的网络能力都应从这里取判据，不要各自复制一套。</p>
	 */
	public static boolean coreNetworkActive(ServerPlayer player) {
		if (player == null || player.level().getServer() == null) return false;
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		return activeIdentity(player, data.record(player.getUUID())).isPresent();
	}


	public static void initialize() {
		ModCoreContent.initialize();
	}

	/** Synchronizes the appended core equipment slot after a creative-inventory click. */
	public static void handleCreativeCoreSync(ServerPlayer player, ArmorCoreCreativeSyncPayload payload) {
		if (player == null || payload == null || !player.getAbilities().instabuild) return;
		if (isCoreChangeLocked(player)) {
			player.sendOverlayMessage(Component.literal(
					"战甲移动事务进行中，完成或回滚前不能更换导航核心"));
			player.inventoryMenu.sendAllDataToRemote();
			return;
		}
		ItemStack requested = payload.core();
		ItemStack expected = payload.expectedCore();
		ItemStack carriedAfter = payload.carriedAfter();
		if (!isValidCreativeCoreStack(requested, player) || !isValidCreativeCoreStack(expected, player)
				|| !isValidCreativeCoreStack(carriedAfter, player)) {
			player.inventoryMenu.sendAllDataToRemote();
			return;
		}
		ArmorCoreInventory inventory = new ArmorCoreInventory(player);
		ItemStack current = inventory.getItem(0);
		// Duplicate/delayed creative packets must never remove a newer equipped core or
		// insert an old cursor stack into the backpack.
		if (!ItemStack.matches(current, expected)) {
			player.inventoryMenu.sendAllDataToRemote();
			return;
		}
		boolean removing = !current.isEmpty() && requested.isEmpty()
				&& ItemStack.matches(carriedAfter, current);
		boolean inserting = current.isEmpty() && !requested.isEmpty() && carriedAfter.isEmpty();
		boolean swapping = !current.isEmpty() && !requested.isEmpty()
				&& !ItemStack.matches(current, requested)
				&& ItemStack.matches(carriedAfter, current);
		if (!removing && !inserting && !swapping) {
			player.inventoryMenu.sendAllDataToRemote();
			return;
		}
		inventory.setItem(0, requested.isEmpty() ? ItemStack.EMPTY : requested.copyWithCount(1));
		player.inventoryMenu.setCarried(carriedAfter.isEmpty()
				? ItemStack.EMPTY : carriedAfter.copyWithCount(1));
		// Vanilla creative-slot packets ignore appended index 46. Force an authoritative
		// refresh after committing both halves of the pickup transaction.
		player.inventoryMenu.sendAllDataToRemote();
	}

	/**
	 * 创造模式核心槽同步的准入判据。除了物品类型，还必须校验认主归属：
	 * 已认主给其他玩家的核心在写入侧会被拒绝，若不在这里提前拦住，
	 * 创造模式流程会先清空客户端光标再拒绝写入，等于凭空吞掉一枚核心。
	 */
	private static boolean isValidCreativeCoreStack(ItemStack stack, ServerPlayer player) {
		if (stack.isEmpty()) return true;
		if (!stack.is(ModItems.ARMOR_NAVIGATION_CORE)) return false;
		return ArmorCoreIdentity.read(stack)
				.map(identity -> identity.ownerId().equals(player.getUUID()))
				.orElse(true);
	}
	/**
	 * 右键手持核心时直接把真实物品转移到独立核心记录并打开配置面板。
	 * 切换另一枚核心会先把旧核心安全放回背包，避免无界面的隐藏槽吞物品。
	 */
	public static boolean activateAndOpen(ServerPlayer player, InteractionHand hand) {
		if (player == null || hand == null) return false;
		if (isCoreChangeLocked(player)) {
			player.sendOverlayMessage(Component.literal(
					"战甲移动事务进行中，完成或回滚前不能切换导航核心"));
			return false;
		}
		ItemStack held = player.getItemInHand(hand);
		if (!held.is(ModItems.ARMOR_NAVIGATION_CORE)) return false;
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		ItemStack activated = held.copyWithCount(1);
		Optional<ArmorCoreIdentity.Identity> claimed =
				ArmorCoreIdentity.claim(activated, player.getUUID());
		if (claimed.isEmpty()) {
			player.sendOverlayMessage(Component.literal("这枚装甲核心已认主给其他玩家"));
			return false;
		}

		ItemStack previous = record.core.isEmpty() ? ItemStack.EMPTY : record.core.copyWithCount(1);
		boolean sameCore = ArmorCoreIdentity.read(previous)
				.map(identity -> identity.coreId().equals(claimed.get().coreId())
						&& identity.instanceNonce().equals(claimed.get().instanceNonce()))
				.orElse(false);
		if (sameCore) {
			player.inventoryMenu.broadcastChanges();
			openMenu(player);
			return true;
		}
		held.shrink(1);
		if (!previous.isEmpty()) {
			int returnSlot = player.getInventory().getFreeSlot();
			if (returnSlot < 0) {
				held.grow(1);
				player.sendOverlayMessage(Component.literal(
						"\u80CC\u5305\u5DF2\u6EE1\uFF0C\u65E0\u6CD5\u5B89\u5168\u5207\u6362\u88C5\u7532\u6838\u5FC3"));
				return false;
			}
			player.getInventory().setItem(returnSlot, previous);
		}
		data.setCore(player.getUUID(), activated);
		player.getInventory().setChanged();
		player.inventoryMenu.broadcastChanges();
		openMenu(player);
		return true;
	}

	/** 护航 AI 的稳定只读入口；缺失配置自动返回 HOSTILE / 32 / FOLLOW。 */
	public static ArmorEscortProfile escortProfile(ServerPlayer owner, UUID suitId) {
		if (owner == null || suitId == null) return ArmorEscortProfile.defaults();
		return ArmorCoreSavedData.get(owner.level().getServer()).escortProfile(owner.getUUID(), suitId);
	}

	public static CoreRuntimeView runtimeView(ServerPlayer player) {
		if (player == null) return CoreRuntimeView.empty();
		hydrateQuickSwitches(player.level().getServer());
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		Optional<ArmorCoreIdentity.Identity> active = activeIdentity(player, record);
		List<CoreBindingRuntimeView> bindings = new ArrayList<>(record.linkedSuitIds.size());
		for (UUID suitId : record.linkedSuitIds) {
			ArmorCoreSavedData.PlatformBinding binding = record.bindings.get(suitId);
			if (binding != null) {
				bindings.add(new CoreBindingRuntimeView(binding.suitId(), binding.platformId(),
						binding.dimension(), binding.pos(), binding.variant(), binding.displayName(),
						data.escortProfile(player.getUUID(), binding.suitId())));
				continue;
			}
			ArmorVariant variant = wornVariant(player, suitId).orElse(ArmorVariant.DEFAULT);
			bindings.add(new CoreBindingRuntimeView(suitId, null, null, null, variant,
					ArmorCoreSavedData.defaultName(variant, suitId),
					data.escortProfile(player.getUUID(), suitId)));
		}
		QuickSwitchState quickSwitch = QUICK_SWITCHES.get(player.getUUID());
		QuickSwitchRuntimeView switchView = quickSwitch == null ? null
				: new QuickSwitchRuntimeView(quickSwitch.transactionId, quickSwitch.revision,
						quickSwitch.outgoingSuitId, quickSwitch.targetSuitId,
						quickSwitch.phase.name(), quickSwitch.startGameTime,
						quickSwitch.phaseStartGameTime, QUICK_SWITCH_TIMEOUT_TICKS);
		return new CoreRuntimeView(active.isPresent(),
				active.map(ArmorCoreIdentity.Identity::coreId).orElse(null),
				active.map(ArmorCoreIdentity.Identity::instanceNonce).orElse(null),
				record.primarySuitId, List.copyOf(bindings), switchView);
	}

	public record CoreRuntimeView(boolean active, UUID coreId, UUID coreNonce, UUID primarySuitId,
			List<CoreBindingRuntimeView> bindings, QuickSwitchRuntimeView quickSwitch) {
		private static CoreRuntimeView empty() {
			return new CoreRuntimeView(false, null, null, null, List.of(), null);
		}
	}

	public record CoreBindingRuntimeView(UUID suitId, UUID platformId,
			ResourceKey<Level> dimension, BlockPos pos, ArmorVariant variant, String displayName,
			ArmorEscortProfile escortProfile) {
		public CoreBindingRuntimeView {
			pos = pos == null ? null : pos.immutable();
		}
	}

	public record QuickSwitchRuntimeView(UUID transactionId, long revision,
			UUID outgoingSuitId, UUID targetSuitId, String phase,
			long startGameTime, long phaseStartGameTime, long timeoutTicks) {
	}

	private record SuitKey(UUID ownerId, UUID suitId) {
		private SuitKey {
			if (ownerId == null || suitId == null) {
				throw new IllegalArgumentException("Suit lifecycle key is incomplete");
			}
		}
	}


	public static void openMenu(ServerPlayer player) {
		player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, ignored) ->
						new ArmorCoreMenu(containerId, inventory, new ArmorCoreInventory(player)),
				Component.literal("装甲核心网络")));
		sendSnapshot(player);
	}

	public static Optional<UUID> primaryRecallSuit(ServerPlayer player) {
		ArmorCoreSavedData.CoreRecord record = ArmorCoreSavedData.get(player.level().getServer()).record(player.getUUID());
		Optional<ArmorCoreIdentity.Identity> identity = activeIdentity(player, record);
		if (identity.isEmpty() || record.linkedSuitIds.isEmpty()) return Optional.empty();
		if (record.primarySuitId != null && record.linkedSuitIds.contains(record.primarySuitId)) {
			return Optional.of(record.primarySuitId);
		}
		return record.linkedSuitIds.stream().findFirst();
	}

	/**
	 * Associates one physically worn complete suit with the equipped core before the items leave
	 * the player. This is identity membership only; a platform home remains optional.
	 */
	public static boolean associateWornSuitForDeployment(ServerPlayer player, UUID suitId) {
		if (player == null || suitId == null
				|| wornFullSuitId(player).filter(suitId::equals).isEmpty()) {
			return false;
		}
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		ArmorCoreIdentity.Identity active = activeIdentity(player, record).orElse(null);
		if (record.linkedSuitIds.contains(suitId)) return true;
		return active != null && data.associateUnlinkedSuit(player.getUUID(), active, suitId);
	}

	/**
	 * Executes the configured formation only after the B-key entrypoint has established that the
	 * player is not wearing a complete physical suit. A complete worn suit is always handled by
	 * its exact suitId before this method is reached; N/the panel, rather than B, switches roles.
	 */
	public static BKeyPlanResult executeConfiguredRecallPlanForB(ServerPlayer player) {
		SuitRuntimeSnapshot snapshot = SuitStateResolver.resolve(player);
		if (promoteRecallablePrimaryForB(player, snapshot)) {
			// Resolve again so this same B press executes the newly promoted primary.
			snapshot = SuitStateResolver.resolve(player);
		}
		ArmorFormationPlanner.Plan plan = ArmorFormationPlanner.plan(snapshot);
		if (plan.status() == ArmorFormationPlanner.Status.INACTIVE) {
			return BKeyPlanResult.NO_ACTIVE_CORE;
		}
		FormationExecution result = reconcileConfiguredFormation(player, null);
		if (!result.coreHandled()) return BKeyPlanResult.NO_ACTIVE_CORE;
		if (!result.message().isBlank()) {
			player.sendOverlayMessage(Component.literal(result.message()));
		}
		sendSnapshot(player, result.message());
		return BKeyPlanResult.HANDLED;
	}

	public enum BKeyPlanResult { NO_ACTIVE_CORE, HANDLED }

	/** Server-authoritative N-key suit cycle within the currently active core network. */
	public static void handleQuickSwitch(ServerPlayer player, ArmorQuickSwitchPayload payload) {
		if (player == null || payload == null) return;
		long previous = LAST_QUICK_SWITCH_SEQUENCE.getOrDefault(player.getUUID(), -1L);
		if (payload.clientSequence() <= previous) return;
		LAST_QUICK_SWITCH_SEQUENCE.put(player.getUUID(), payload.clientSequence());

		if (!player.isAlive() || player.isSpectator()) {
			switchMessage(player, "message.flight_armor.quick_switch.unavailable");
			return;
		}
		if (QUICK_SWITCHES.containsKey(player.getUUID()) || ArmorAssemblyController.isAnimating(player)) {
			switchMessage(player, "message.flight_armor.quick_switch.busy");
			return;
		}

		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		ArmorCoreIdentity.Identity coreIdentity = activeIdentity(player, record).orElse(null);
		if (coreIdentity == null) {
			switchMessage(player, "message.flight_armor.quick_switch.no_core");
			return;
		}
		UUID currentSuitId = wornFullSuitId(player).orElse(null);
		if (currentSuitId == null) {
			switchMessage(player, "message.flight_armor.quick_switch.full_set_required");
			return;
		}
		ArmorCoreSavedData.PlatformBinding currentBinding = record.bindings.get(currentSuitId);
		if (currentBinding == null) {
			switchMessage(player, "message.flight_armor.quick_switch.current_unbound");
			return;
		}
		if (record.linkedSuitIds.size() < 2) {
			switchMessage(player, "message.flight_armor.quick_switch.no_alternative");
			return;
		}

		SwitchTarget target = nextAvailableSwitchTarget(
				player, record, currentSuitId);
		if (target == null) {
			switchMessage(player, "message.flight_armor.quick_switch.no_available_target");
			return;
		}

		QuickSwitchState state = new QuickSwitchState(coreIdentity, currentSuitId, currentBinding,
				target.suitId(), target.displayName(), player.level().getGameTime());
		state.targetSource = target.source();
		if (!beginQuickSwitch(player, state)) {
			switchMessage(player, "message.flight_armor.quick_switch.busy");
			return;
		}
		if (!ArmorAssemblyController.beginSwitchRetraction(player)) {
			discardQuickSwitch(player, state);
			switchMessage(player, "message.flight_armor.quick_switch.separation_failed");
			return;
		}
		// 快速换甲是一段受控事务，不沿用旧飞行会话的自由输入。事务结束前只由
		// tickServer 的临时反重力保护托住玩家，成功或回滚完成后再重建正式悬停。
		FlightController.remove(player);
		applyQuickSwitchFallSafety(player);
		player.sendOverlayMessage(Component.translatable(
				"message.flight_armor.quick_switch.started", target.displayName()));
	}

	/** Returns whether the exact suit selected by a status panel can enter the existing switch transaction. */
	public static boolean canTargetedQuickSwitch(ServerPlayer player, UUID targetSuitId) {
		if (player == null || targetSuitId == null || !player.isAlive() || player.isSpectator()
				|| QUICK_SWITCHES.containsKey(player.getUUID()) || ArmorAssemblyController.isAnimating(player)) {
			return false;
		}
		ArmorCoreSavedData.CoreRecord record =
				ArmorCoreSavedData.get(player.level().getServer()).record(player.getUUID());
		if (activeIdentity(player, record).isEmpty()) return false;
		UUID currentSuitId = wornFullSuitId(player).orElse(null);
		if (currentSuitId == null || currentSuitId.equals(targetSuitId)
				|| !record.bindings.containsKey(currentSuitId)) return false;
		if (!record.linkedSuitIds.contains(targetSuitId)) return false;
		return isSwitchTargetAvailable(player, record, targetSuitId);
	}

	/** Starts a switch to the exact clicked suit while reusing the normal rollback-safe quick-switch state machine. */
	public static TargetedSwitchResult beginTargetedQuickSwitch(ServerPlayer player, UUID targetSuitId) {
		if (player == null || targetSuitId == null || !player.isAlive() || player.isSpectator()) {
			return TargetedSwitchResult.failed("\u5F53\u524D\u72B6\u6001\u65E0\u6CD5\u5207\u6362\u6218\u7532");
		}
		if (QUICK_SWITCHES.containsKey(player.getUUID()) || ArmorAssemblyController.isAnimating(player)) {
			return TargetedSwitchResult.failed("\u6218\u7532\u6B63\u5728\u5207\u6362\u6216\u7EC4\u88C5\uFF0C\u8BF7\u7A0D\u5019");
		}
		ArmorCoreSavedData.CoreRecord record =
				ArmorCoreSavedData.get(player.level().getServer()).record(player.getUUID());
		ArmorCoreIdentity.Identity coreIdentity = activeIdentity(player, record).orElse(null);
		if (coreIdentity == null) {
			return TargetedSwitchResult.failed("\u9700\u8981\u5148\u6FC0\u6D3B\u5E76\u88C5\u5907\u7ED1\u5B9A\u8FD9\u4E24\u5957\u6218\u7532\u7684\u5BFC\u822A\u6838\u5FC3");
		}
		UUID currentSuitId = wornFullSuitId(player).orElse(null);
		if (currentSuitId == null) {
			return TargetedSwitchResult.failed("\u9700\u8981\u5148\u7A7F\u9F50\u5F53\u524D\u6218\u7532\u624D\u80FD\u6267\u884C\u5207\u6362");
		}
		if (currentSuitId.equals(targetSuitId)) {
			return TargetedSwitchResult.failed("\u4F60\u5DF2\u7ECF\u7A7F\u7740\u8FD9\u5957\u6218\u7532");
		}
		ArmorCoreSavedData.PlatformBinding currentBinding = record.bindings.get(currentSuitId);
		if (currentBinding == null) {
			return TargetedSwitchResult.failed("\u8EAB\u4E0A\u6218\u7532\u6CA1\u6709\u5B89\u5168\u56DE\u6EDA\u5E73\u53F0\uFF0C\u6682\u65F6\u4E0D\u80FD\u5FEB\u901F\u6362\u7532");
		}
		if (!record.linkedSuitIds.contains(targetSuitId)) {
			return TargetedSwitchResult.failed("\u76EE\u6807\u6218\u7532\u4E0D\u5728\u5F53\u524D\u6838\u5FC3\u7F51\u7EDC\u4E2D");
		}
		SwitchTarget target = resolveSwitchTarget(player, record, targetSuitId);
		if (target == null) {
			return TargetedSwitchResult.failed("\u76EE\u6807\u6218\u7532\u6B63\u5728\u8FD4\u822A\u6216\u53D8\u5F62\uFF0C\u6682\u65F6\u4E0D\u80FD\u5207\u6362");
		}

		QuickSwitchState state = new QuickSwitchState(coreIdentity, currentSuitId, currentBinding,
				target.suitId(), target.displayName(), player.level().getGameTime());
		state.targetSource = target.source();
		if (!beginQuickSwitch(player, state)) {
			return TargetedSwitchResult.failed("\u6218\u7532\u5207\u6362\u4E8B\u52A1\u5DF2\u5728\u8FD0\u884C");
		}
		if (!ArmorAssemblyController.beginSwitchRetraction(player)) {
			discardQuickSwitch(player, state);
			return TargetedSwitchResult.failed("\u8EAB\u4E0A\u6218\u7532\u89E3\u4F53\u52A8\u753B\u672A\u80FD\u542F\u52A8");
		}
		FlightController.remove(player);
		applyQuickSwitchFallSafety(player);
		return TargetedSwitchResult.started("\u8EAB\u4E0A\u6218\u7532\u6B63\u5728\u89E3\u4F53\uFF0C" + target.displayName()
				+ "\u5DF2\u5F00\u59CB\u53EC\u56DE");
	}

	public record TargetedSwitchResult(boolean success, String message) {
		private static TargetedSwitchResult started(String message) {
			return new TargetedSwitchResult(true, message);
		}

		private static TargetedSwitchResult failed(String message) {
			return new TargetedSwitchResult(false, message);
		}
	}

	/**
	 * 推进快速换甲事务。旧甲在目标真正着装完成前始终保留为无攻击护航载体；
	 * 任一异步失败都会转入 RECOVERING，并在服务器持续防坠期间重试召回旧甲。
	 */
	private static boolean beginQuickSwitch(ServerPlayer player, QuickSwitchState state) {
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		if (!data.beginQuickSwitch(state.persistent(player.getUUID(), 0L))) return false;
		if (QUICK_SWITCHES.putIfAbsent(player.getUUID(), state) == null) return true;
		data.removeQuickSwitch(player.getUUID(), state.transactionId, 0L);
		return false;
	}

	private static void discardQuickSwitch(ServerPlayer player, QuickSwitchState state) {
		ArmorCoreSavedData.get(player.level().getServer()).removeQuickSwitch(
				player.getUUID(), state.transactionId, state.revision);
		QUICK_SWITCHES.remove(player.getUUID(), state);
	}

	private static boolean persistQuickSwitch(MinecraftServer server, UUID ownerId,
			QuickSwitchState state) {
		long expected = state.revision;
		boolean updated = ArmorCoreSavedData.get(server).compareAndSetQuickSwitch(
				ownerId, state.transactionId, expected, state.persistent(ownerId, expected + 1L));
		if (updated) state.revision = expected + 1L;
		else QUICK_SWITCHES.remove(ownerId, state);
		return updated;
	}

	private static boolean removePersistedQuickSwitch(ServerPlayer player, QuickSwitchState state) {
		boolean removed = ArmorCoreSavedData.get(player.level().getServer()).removeQuickSwitch(
				player.getUUID(), state.transactionId, state.revision);
		if (removed) QUICK_SWITCHES.remove(player.getUUID(), state);
		return removed;
	}

	private static void hydrateQuickSwitches(MinecraftServer server) {
		ArmorCoreSavedData data = ArmorCoreSavedData.get(server);
		Map<UUID, ArmorCoreSavedData.QuickSwitchRecord> stored = new java.util.LinkedHashMap<>();
		for (ArmorCoreSavedData.QuickSwitchRecord record : data.quickSwitches()) {
			stored.put(record.ownerId(), record);
			QUICK_SWITCHES.compute(record.ownerId(), (ownerId, current) ->
					current != null && current.transactionId.equals(record.transactionId())
							&& current.revision == record.revision()
							? current : QuickSwitchState.restore(record));
		}
		QUICK_SWITCHES.entrySet().removeIf(entry -> !stored.containsKey(entry.getKey()));
	}

	private static void markQuickSwitchRecovery(MinecraftServer server, UUID ownerId,
			QuickSwitchState state, long now, RecoveryCause cause) {
		if (state.phase != QuickSwitchPhase.RECOVERING) {
			boolean targetWasInbound = state.phase == QuickSwitchPhase.INBOUND;
			state.phase = QuickSwitchPhase.RECOVERING;
			state.phaseStartGameTime = now;
			state.recoveryCause = cause;
			state.nextRecoveryAttemptTick = now;
			state.targetCancellationComplete = !targetWasInbound;
			persistQuickSwitch(server, ownerId, state);
		}
	}

	public static void tickServer(MinecraftServer server) {
		hydrateQuickSwitches(server);
		long now = server.overworld().getGameTime();
		processConfirmedDestroyedSuits(server);
		if (now % WORN_RECONCILE_INTERVAL_TICKS == 0L) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				boolean topologyUnlocked = !isCoreChangeLocked(player)
						&& !ArmorAssemblyController.isAnimating(player);
				boolean associationChanged = topologyUnlocked
						&& reconcileWornSuitAssociation(player);
				/*
				 * Outside the command menu this payload also feeds the compact core HUD. Do not
				 * inject receipt-less periodic snapshots into ArmorCoreMenu unless physical core
				 * topology changed. Primary failover is event-driven, never polled here.
				 */
				if (!(player.containerMenu instanceof ArmorCoreMenu)
						|| associationChanged) {
					sendPeriodicSnapshot(player);
				}
			}
		}
		for (Map.Entry<UUID, QuickSwitchState> entry : new ArrayList<>(QUICK_SWITCHES.entrySet())) {
			QuickSwitchState state = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			// Offline transactions remain persisted. Death/spectator transitions to recovery and
			// resumes only after a live player entity is available again.
			if (player == null) continue;
			if (!player.isAlive() || player.isSpectator()) {
				markQuickSwitchRecovery(server, entry.getKey(), state, now,
						player.isAlive() ? RecoveryCause.PLAYER_UNAVAILABLE : RecoveryCause.PLAYER_DIED);
				continue;
			}

			// 完成判定先于临时飞行清理，避免撤销着装完成路径刚建立的正式悬停。
			if (state.phase == QuickSwitchPhase.INBOUND
					&& isFullSuitWorn(player, state.targetSuitId)
					&& !ArmorAssemblyController.isAnimating(player)) {
				completeQuickSwitch(player, state);
				continue;
			}
			if (state.phase == QuickSwitchPhase.RECOVERING
					&& isFullSuitWorn(player, state.outgoingSuitId)
					&& !ArmorAssemblyController.isAnimating(player)) {
				completeQuickSwitchRecovery(player, state);
				continue;
			}

			applyQuickSwitchFallSafety(player);
			if (state.phase != QuickSwitchPhase.RECOVERING
					&& !(state.phase == QuickSwitchPhase.INBOUND
					&& isFullSuitWorn(player, state.targetSuitId))
					&& now - state.startGameTime > QUICK_SWITCH_TIMEOUT_TICKS) {
				beginQuickSwitchRecovery(player, state, now, RecoveryCause.TIMEOUT);
				continue;
			}

			if (state.phase == QuickSwitchPhase.SEPARATING) {
				if (ArmorAssemblyController.isAnimating(player)) continue;
				if (!state.outgoingDeployed && isFullSuitWorn(player, state.outgoingSuitId)) {
					beginQuickSwitchRecovery(player, state, now, RecoveryCause.SEPARATION_FAILED);
					continue;
				}

				if (!state.outgoingDeployed) {
					ArmorEscortController.SwitchDeploymentResult deployed =
							ArmorEscortController.deployCarriedSuitForSwitch(player,
									state.outgoingSuitId, state.outgoingBinding.platformId(),
									state.outgoingBinding.dimension(), state.outgoingBinding.pos());
					if (!deployed.success()) {
						state.lastRecoveryDetail = deployed.message();
						beginQuickSwitchRecovery(player, state, now, RecoveryCause.SEPARATION_FAILED);
						continue;
					}
					state.outgoingDeployed = true;
					if (!persistQuickSwitch(server, entry.getKey(), state)) continue;
				}
				if (state.targetSource == null) {
					state.targetSource = inboundSource(player, state.targetSuitId);
					if (state.targetSource == null) {
						beginQuickSwitchRecovery(player, state, now, RecoveryCause.TARGET_FAILED);
						continue;
					}
					if (!persistQuickSwitch(server, entry.getKey(), state)) continue;
				}
				// A restart can happen after the carrier accepted the recall but before the phase CAS.
				// Adopt that already-running recall instead of issuing a second physical transfer.
				if (!isInboundSwitchPending(player, state)
						&& !startInboundSwitchSuit(player, state.targetSuitId, state.targetSource)) {
					beginQuickSwitchRecovery(player, state, now, RecoveryCause.TARGET_FAILED);
					continue;
				}

				state.phase = QuickSwitchPhase.INBOUND;
				state.phaseStartGameTime = now;
				if (!persistQuickSwitch(server, entry.getKey(), state)) continue;
				player.sendOverlayMessage(Component.translatable(
						"message.flight_armor.quick_switch.inbound", state.targetName));
				sendSnapshot(player);
				continue;
			}
			if (state.phase == QuickSwitchPhase.INBOUND) {
				if (!isInboundSwitchPending(player, state)) {
					beginQuickSwitchRecovery(player, state, now, RecoveryCause.TARGET_FAILED);
				}
				continue;
			}

			if (now >= state.nextRecoveryAttemptTick) {
				retryOutgoingSuitRecovery(player, state, now);
			}
		}
	}
	private static void applyQuickSwitchFallSafety(ServerPlayer player) {
		// 不创建可接收 WASD/俯冲输入的飞行状态，只在服务器末 tick 抵消下坠和高速惯性。
		FlightController.suspendForQuickSwitch(player);
		FlightPhysics.applyPreBootFallArrest(player);
		if (!player.onGround()) FlightPhysics.prepareHoverEngagement(player);
		player.syncVelocity = true;
	}

	private static void completeQuickSwitch(ServerPlayer player, QuickSwitchState state) {
		if (!removePersistedQuickSwitch(player, state)) return;
		setQuickSwitchPrimary(player, state.targetSuitId);
		FlightController.ensureHoverAfterQuickSwitch(
				player, state.startGameTime, state.targetSuitId.getLeastSignificantBits());
		ArmorEscortController.EscortCommandResult returned =
				ArmorEscortController.returnToPlatform(player, state.outgoingSuitId,
						state.outgoingBinding.platformId(), state.outgoingBinding.dimension(),
						state.outgoingBinding.pos());
		player.sendOverlayMessage(Component.translatable(
				"message.flight_armor.quick_switch.complete", state.targetName));
		if (!returned.success()) {
			player.sendSystemMessage(Component.literal(
					"目标战甲已穿戴；旧甲返台失败，已保持无攻击待机：" + returned.message()));
		}
		sendSnapshot(player);
	}

	private static void completeQuickSwitchRecovery(ServerPlayer player, QuickSwitchState state) {
		if (!removePersistedQuickSwitch(player, state)) return;
		setQuickSwitchPrimary(player, state.outgoingSuitId);
		FlightController.ensureHoverAfterQuickSwitch(
				player, state.startGameTime, state.outgoingSuitId.getLeastSignificantBits());
		String cause = switch (state.recoveryCause) {
			case TIMEOUT -> "切换超过 60 秒";
			case TARGET_FAILED -> "目标战甲召回失败";
			case SEPARATION_FAILED -> "旧甲交接失败";
			case PLAYER_DIED -> "玩家死亡";
			case PLAYER_UNAVAILABLE -> "玩家暂不可用";
		};
		player.sendOverlayMessage(Component.literal(cause + "；旧甲已实际恢复穿戴"));
		sendSnapshot(player);
	}

	private static void setQuickSwitchPrimary(ServerPlayer player, UUID suitId) {
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		activeIdentity(player, record).ifPresent(identity ->
				data.setPrimary(player.getUUID(), identity, suitId));
	}

	private static void beginQuickSwitchRecovery(ServerPlayer player, QuickSwitchState state,
			long now, RecoveryCause cause) {
		if (state.phase == QuickSwitchPhase.RECOVERING) return;
		boolean targetWasInbound = state.phase == QuickSwitchPhase.INBOUND;
		state.phase = QuickSwitchPhase.RECOVERING;
		state.phaseStartGameTime = now;
		state.recoveryCause = cause;
		state.nextRecoveryAttemptTick = now;
		state.targetCancellationComplete = !targetWasInbound;
		if (!persistQuickSwitch(player.level().getServer(), player.getUUID(), state)) return;
		if (targetWasInbound) state.targetCancellationComplete = cancelInboundSwitchSuit(player, state);
		retryOutgoingSuitRecovery(player, state, now);
	}

	private static void retryOutgoingSuitRecovery(ServerPlayer player, QuickSwitchState state, long now) {
		try {
			state.nextRecoveryAttemptTick = now + QUICK_SWITCH_RECOVERY_RETRY_TICKS;
			if (!state.targetCancellationComplete) {
				state.targetCancellationComplete = cancelInboundSwitchSuit(player, state);
				if (!state.targetCancellationComplete) {
					announceRecoveryWaiting(player, state, "目标战甲仍在着装提交阶段，正在等待安全撤销");
					return;
				}
			}
	
			RecoveryAttempt attempt = attemptOutgoingSuitRecovery(player, state);
			state.lastRecoveryDetail = attempt.detail();
			if (attempt.status() == RecoveryStatus.RECOVERED) return;
			if (attempt.status() == RecoveryStatus.STARTED
					|| attempt.status() == RecoveryStatus.IN_PROGRESS) {
				if (!state.recoveryRequestAnnounced) {
					state.recoveryRequestAnnounced = true;
					if (state.recoveryCause == RecoveryCause.TIMEOUT) {
						switchMessage(player, "message.flight_armor.quick_switch.timeout");
					} else if (state.recoveryCause == RecoveryCause.TARGET_FAILED) {
						switchMessage(player, "message.flight_armor.quick_switch.target_failed");
					} else {
						player.sendOverlayMessage(Component.literal(
								"旧甲交接未完成，已实际发起旧甲恢复召回"));
					}
				}
				return;
			}
			announceRecoveryWaiting(player, state, attempt.detail());
		} finally {
			persistQuickSwitch(player.level().getServer(), player.getUUID(), state);
		}
	}
	private static void announceRecoveryWaiting(ServerPlayer player, QuickSwitchState state, String detail) {
		if (state.recoveryWaitingAnnounced) return;
		state.recoveryWaitingAnnounced = true;
		String cause = state.recoveryCause == RecoveryCause.TIMEOUT ? "切换已超时"
				: state.recoveryCause == RecoveryCause.TARGET_FAILED ? "目标召回失败" : "旧甲交接失败";
		player.sendOverlayMessage(Component.literal(
				cause + "；旧甲恢复尚未完成并会持续重试：" + detail));
	}

	private static RecoveryAttempt attemptOutgoingSuitRecovery(ServerPlayer player,
			QuickSwitchState state) {
		if (isFullSuitWorn(player, state.outgoingSuitId)) {
			return ArmorAssemblyController.isAnimating(player)
					? RecoveryAttempt.waiting("等待旧甲解体动画结束")
					: RecoveryAttempt.recovered();
		}
		if (ArmorAssemblyController.isAnimating(player)) {
			return RecoveryAttempt.waiting("等待当前装甲动画结束");
		}
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			if (!player.getItemBySlot(slot).isEmpty()) {
				return RecoveryAttempt.waiting("装甲槽仍被目标部件占用");
			}
		}

		if (ArmorEscortController.isActive(state.outgoingSuitId)) {
			if (ArmorEscortController.isRecallForWearInProgress(player, state.outgoingSuitId)) {
				return RecoveryAttempt.inProgress();
			}
			ArmorEscortController.EscortCommandResult recall =
					ArmorEscortController.recallToOwnerForWear(player, state.outgoingSuitId);
			return recall.success() ? RecoveryAttempt.started()
					: RecoveryAttempt.failed(recall.message());
		}

		ArmorEscortController.SwitchDeploymentResult deployed =
				ArmorEscortController.deployCarriedSuitForSwitch(player,
						state.outgoingSuitId, state.outgoingBinding.platformId(),
						state.outgoingBinding.dimension(), state.outgoingBinding.pos());
		if (!deployed.success()) return RecoveryAttempt.failed(deployed.message());
		state.outgoingDeployed = true;
		ArmorEscortController.EscortCommandResult recall =
				ArmorEscortController.recallToOwnerForWear(player, state.outgoingSuitId);
		return recall.success() ? RecoveryAttempt.started()
				: RecoveryAttempt.failed(recall.message());
	}

	private static InboundSource inboundSource(ServerPlayer player, UUID suitId) {
		if (player == null || suitId == null) return null;
		ArmorCoreSavedData.CoreRecord record =
				ArmorCoreSavedData.get(player.level().getServer()).record(player.getUUID());
		SwitchTarget target = resolveSwitchTarget(player, record, suitId);
		return target == null ? null : target.source();
	}

	private static boolean isInboundSwitchPending(ServerPlayer player, QuickSwitchState state) {
		if (isFullSuitWorn(player, state.targetSuitId)) return true;
		if (state.targetSource == null) return false;
		return switch (state.targetSource) {
			case ESCORT -> ArmorEscortController.isRecallForWearInProgress(
					player, state.targetSuitId);
			case CRUISE -> ArmorCruiseController.isRecallInProgressForSwitch(
					player, state.targetSuitId);
			case PLATFORM -> ArmorAssemblyController.isAnimating(player);
		};
	}

	private static boolean cancelInboundSwitchSuit(ServerPlayer player, QuickSwitchState state) {
		if (state.targetSource == null) return true;
		return switch (state.targetSource) {
			case ESCORT -> !ArmorEscortController.isActive(state.targetSuitId)
					|| ArmorEscortController.cancelRecallForSwitchFailure(
							player, state.targetSuitId).success();
			case CRUISE -> !ArmorCruiseController.hasActiveSuit(player, state.targetSuitId)
					|| ArmorCruiseController.cancelRecallForSwitchFailure(
							player, state.targetSuitId).success();
			case PLATFORM -> !ArmorAssemblyController.isAnimating(player)
						&& !isFullSuitWorn(player, state.targetSuitId);
		};
	}

	private static SwitchTarget nextAvailableSwitchTarget(
			ServerPlayer player, ArmorCoreSavedData.CoreRecord record, UUID currentSuitId) {
		List<UUID> suitIds = new ArrayList<>(record.linkedSuitIds);
		int currentIndex = suitIds.indexOf(currentSuitId);
		if (currentIndex < 0) return null;
		for (int offset = 1; offset < suitIds.size(); offset++) {
			UUID candidateSuitId = suitIds.get((currentIndex + offset) % suitIds.size());
			if (candidateSuitId.equals(currentSuitId)) continue;
			SwitchTarget candidate = resolveSwitchTarget(player, record, candidateSuitId);
			if (candidate != null) return candidate;
		}
		return null;
	}

	private static boolean isSwitchTargetAvailable(ServerPlayer player,
			ArmorCoreSavedData.CoreRecord record, UUID suitId) {
		return resolveSwitchTarget(player, record, suitId) != null;
	}

	private static SwitchTarget resolveSwitchTarget(ServerPlayer player,
			ArmorCoreSavedData.CoreRecord record, UUID suitId) {
		if (player == null || record == null || suitId == null
				|| !record.linkedSuitIds.contains(suitId)) return null;
		ArmorCoreSavedData.PlatformBinding binding = record.bindings.get(suitId);
		String displayName = binding == null
				? ArmorCoreSavedData.defaultName(
						wornVariant(player, suitId).orElse(ArmorVariant.DEFAULT), suitId)
				: binding.displayName();
		if (ArmorEscortController.isActive(suitId)) {
			return ArmorEscortController.canRecallForWear(player, suitId)
					? new SwitchTarget(suitId, displayName, InboundSource.ESCORT) : null;
		}
		if (ArmorCruiseController.isRecallableSuit(player, suitId)) {
			return new SwitchTarget(suitId, displayName, InboundSource.CRUISE);
		}
		// An exact but busy carrier must never fall through and be mistaken for a platform suit.
		if (ArmorCruiseController.hasActiveSuit(player, suitId)) return null;
		if (binding == null || validatePlatform(player, binding, true) != null) return null;
		return new SwitchTarget(suitId, displayName, InboundSource.PLATFORM);
	}

	private static boolean startInboundSwitchSuit(ServerPlayer player, UUID suitId,
			InboundSource source) {
		return switch (source) {
			case ESCORT -> ArmorEscortController.recallToOwnerForWear(player, suitId).success();
			case CRUISE -> ArmorCruiseController.recallActiveSuitForSwitch(player, suitId).success();
			case PLATFORM -> ArmorCruiseController.recallPlatformSuit(player, suitId);
		};
	}

	private static Optional<UUID> wornFullSuitId(ServerPlayer player) {
		UUID suitId = null;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(player.getItemBySlot(slot));
			if (identity.isEmpty() || !identity.get().ownerId().equals(player.getUUID())
					|| identity.get().slot() != slot) return Optional.empty();
			if (suitId != null && !suitId.equals(identity.get().suitId())) return Optional.empty();
			suitId = identity.get().suitId();
		}
		return Optional.ofNullable(suitId);
	}

	private static Optional<ArmorVariant> wornVariant(ServerPlayer player, UUID suitId) {
		if (player == null || suitId == null) return Optional.empty();
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(stack);
			if (identity.isPresent() && player.getUUID().equals(identity.get().ownerId())
					&& suitId.equals(identity.get().suitId()) && identity.get().slot() == slot) {
				return Optional.of(ArmorVariantData.get(stack));
			}
		}
		return Optional.empty();
	}

	private static boolean isFullSuitWorn(ServerPlayer player, UUID suitId) {
		return wornFullSuitId(player).filter(suitId::equals).isPresent();
	}

	private static void switchMessage(ServerPlayer player, String key) {
		player.sendOverlayMessage(Component.translatable(key));
	}

	/**
	 * Platform interaction for a held core. The core branch is consumed before display-mode toggling.
	 */
	public static boolean bindHeldCoreToPlatform(ServerPlayer player, InteractionHand hand,
			ChargingPlatformBlockEntity platform) {
		if (player == null || hand == null || platform == null
				|| !player.getItemInHand(hand).is(ModItems.ARMOR_NAVIGATION_CORE)) return false;
		if (isCoreChangeLocked(player)) {
			player.sendOverlayMessage(Component.literal(
					"战甲移动事务进行中，暂时不能更换核心或绑定平台"));
			return false;
		}
		if (!platform.isComplete()) {
			player.sendOverlayMessage(Component.literal(
					"平台需要先放齐头盔、胸甲、护腿和战靴"));
			return false;
		}
		if (platform.getOwnerUuid().filter(player.getUUID()::equals).isEmpty()) {
			player.sendOverlayMessage(Component.literal(
					"该平台战甲不属于你，无法接入核心"));
			return false;
		}
		if (platform.getSuitId().isEmpty()) {
			player.sendOverlayMessage(Component.literal(
					"平台战甲缺少唯一身份，无法绑定"));
			return false;
		}
		if (!(platform.getLevel() instanceof ServerLevel level)) return false;
		ItemStack held = player.getItemInHand(hand);
		ItemStack activated = held.copyWithCount(1);
		ArmorCoreIdentity.Identity nextIdentity =
				ArmorCoreIdentity.claim(activated, player.getUUID()).orElse(null);
		if (nextIdentity == null) {
			player.sendOverlayMessage(Component.literal("这枚装甲核心已认主给其他玩家"));
			return false;
		}
		UUID suitId = platform.getSuitId().orElseThrow();
		ArmorVariant variant = platform.getArmorItems().stream().filter(stack -> !stack.isEmpty())
				.findFirst().map(ArmorVariantData::get).orElse(ArmorVariant.DEFAULT);
		ArmorCoreSavedData.PlatformBinding binding = new ArmorCoreSavedData.PlatformBinding(
				suitId, platform.getPlatformId(), level.dimension(), platform.getBlockPos(), variant,
				ArmorCoreSavedData.defaultName(variant, suitId));
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		if (!data.canBind(player.getUUID(), nextIdentity, binding)) {
			player.sendOverlayMessage(Component.literal(
					"\u5f53\u524d\u6838\u5fc3\u65e0\u6cd5\u7ed1\u5b9a\u8be5\u5e73\u53f0\uff0c\u8bf7\u68c0\u67e5\u7ed1\u5b9a\u4e0a\u9650\u6216\u6838\u5fc3\u8eab\u4efd"));
			return false;
		}
		ItemStack previous = record.core.isEmpty() ? ItemStack.EMPTY : record.core.copyWithCount(1);
		boolean sameCore = ArmorCoreIdentity.read(previous)
				.map(identity -> identity.coreId().equals(nextIdentity.coreId())
						&& identity.instanceNonce().equals(nextIdentity.instanceNonce()))
				.orElse(false);
		int returnSlot = -1;
		if (!sameCore && !previous.isEmpty()) {
			returnSlot = player.getInventory().getFreeSlot();
			if (returnSlot < 0) {
				player.sendOverlayMessage(Component.literal(
						"背包已满，无法安全切换并绑定这枚装甲核心"));
				return false;
			}
		}
		if (!sameCore) {
			held.shrink(1);
			if (!previous.isEmpty()) player.getInventory().setItem(returnSlot, previous);
			data.setCore(player.getUUID(), activated);
		}
		// Preflight and commit run on the server thread, so no other core command can
		// interleave between them. If this ever fails, restore the exact physical stacks.
		if (!data.bind(player.getUUID(), nextIdentity, binding)
				&& !binding.equals(data.record(player.getUUID()).bindings.get(suitId))) {
			if (!sameCore) {
				data.setCore(player.getUUID(), previous);
				if (!previous.isEmpty() && returnSlot >= 0) {
					player.getInventory().setItem(returnSlot, ItemStack.EMPTY);
				}
				held.grow(1);
			}
			player.sendOverlayMessage(Component.literal(
					"平台绑定提交失败，核心与物品已恢复"));
			player.inventoryMenu.broadcastChanges();
			return false;
		}
		player.getInventory().setChanged();
		player.inventoryMenu.broadcastChanges();
		player.sendOverlayMessage(Component.literal("充能平台已绑定到装甲核心："
				+ binding.displayName()));
		openMenu(player);
		return true;
	}

	public static boolean bindPlatformIfCoreEquipped(ServerPlayer player,
			ChargingPlatformBlockEntity platform) {
		if (isCoreChangeLocked(player)) return false;
		return bindPlatformIfCoreEquipped(player, platform, true);
	}

	public static void refreshPlatformBindingIfCoreEquipped(ServerPlayer player,
			ChargingPlatformBlockEntity platform) {
		refreshExistingPlatformBinding(player, platform);
	}

	private static void refreshExistingPlatformBinding(ServerPlayer player,
			ChargingPlatformBlockEntity platform) {
		if (player == null || platform == null || !platform.isComplete()
				|| isCoreChangeLocked(player)
				|| platform.getOwnerUuid().filter(player.getUUID()::equals).isEmpty()) return;
		UUID suitId = platform.getSuitId().orElse(null);
		if (suitId == null || !(platform.getLevel() instanceof ServerLevel level)) return;
		ArmorVariant variant = platform.getArmorItems().stream().filter(stack -> !stack.isEmpty())
				.findFirst().map(ArmorVariantData::get).orElse(ArmorVariant.DEFAULT);
		ArmorCoreSavedData.PlatformBinding binding = new ArmorCoreSavedData.PlatformBinding(
				suitId, platform.getPlatformId(), level.dimension(), platform.getBlockPos(), variant,
				ArmorCoreSavedData.defaultName(variant, suitId));
		if (ArmorCoreSavedData.get(player.level().getServer()).refreshBoundPlatform(player.getUUID(), binding)) {
			sendSnapshot(player);
		}
	}
	private static boolean bindPlatformIfCoreEquipped(ServerPlayer player,
			ChargingPlatformBlockEntity platform, boolean notifyPlayer) {
		if (player == null || platform == null || !platform.isComplete()
				|| isCoreChangeLocked(player)
				|| platform.getOwnerUuid().filter(player.getUUID()::equals).isEmpty()) return false;
		UUID suitId = platform.getSuitId().orElse(null);
		if (suitId == null || !(platform.getLevel() instanceof ServerLevel level)) return false;
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		Optional<ArmorCoreIdentity.Identity> identity = activeIdentity(player, record);
		if (identity.isEmpty()) return false;
		ArmorVariant variant = platform.getArmorItems().stream().filter(stack -> !stack.isEmpty())
				.findFirst().map(ArmorVariantData::get).orElse(ArmorVariant.DEFAULT);
		ArmorCoreSavedData.PlatformBinding binding = new ArmorCoreSavedData.PlatformBinding(
				suitId, platform.getPlatformId(), level.dimension(), platform.getBlockPos(), variant,
				ArmorCoreSavedData.defaultName(variant, suitId));
		boolean changed = data.bind(player.getUUID(), identity.get(), binding);
		boolean bound = binding.equals(record.bindings.get(suitId));
		if (changed) {
			if (notifyPlayer) player.sendOverlayMessage(
					Component.literal("充能平台已绑定到装甲核心：" + binding.displayName()));
			sendSnapshot(player);
		}
		return bound;
	}

	public static void handleAction(ServerPlayer player, ArmorCoreActionPayload payload) {
		if (player == null || payload == null) return;
		long previous = LAST_CLIENT_SEQUENCE.getOrDefault(player.getUUID(), -1L);
		if (payload.clientSequence() <= previous) return;
		LAST_CLIENT_SEQUENCE.put(player.getUUID(), payload.clientSequence());
		if (isMovementAction(payload.action())
				&& (hasActiveQuickSwitch(player) || ArmorAssemblyController.isAnimating(player))) {
			String result = "已有战甲移动事务进行中；本次核心命令未执行";
			player.sendOverlayMessage(Component.literal(result));
			sendSnapshot(player, result, payload.clientSequence(), payload.action(), payload.suitId());
			return;
		}

		String result = switch (payload.action()) {
			case REFRESH -> "状态已刷新";
			case BIND_LOOKED_AT -> bindLookedAt(player);
			case SET_PRIMARY -> setPrimary(player, payload.suitId());
			case RECALL_SELECTED -> recallSelected(player, payload.suitId(), false);
			case RECALL_AS_ESCORT -> recallSelected(player, payload.suitId(), true);
			case RECALL_ALL -> reconcileConfiguredFormation(player, payload.suitId()).message();
			case RETURN_ESCORT -> returnEscort(player, payload.suitId());
			case CYCLE_ATTACK_MODE -> updateEscortProfile(player, payload.suitId(), payload.action());
			case CYCLE_ATTACK_RANGE -> updateEscortProfile(player, payload.suitId(), payload.action());
			case TOGGLE_ESCORT_STANCE, CYCLE_ESCORT_STANCE ->
					updateEscortProfile(player, payload.suitId(), payload.action());
			case FORGET_MISSING -> forgetMissingSuit(player, payload.suitId());
			case DEPLOY_SELECTED_WORN -> deploySelectedWorn(player, payload.suitId());
			case TOGGLE_REMOTE_COMMAND -> toggleRemoteCommand(player, payload.suitId());
		};
		if (!result.isBlank()) player.sendOverlayMessage(Component.literal(result));
		sendSnapshot(player, result, payload.clientSequence(), payload.action(), payload.suitId());
	}

	private static boolean isMovementAction(ArmorCoreAction action) {
		return switch (action) {
			case BIND_LOOKED_AT, SET_PRIMARY, RECALL_SELECTED, RECALL_AS_ESCORT, RECALL_ALL,
					RETURN_ESCORT, FORGET_MISSING, DEPLOY_SELECTED_WORN -> true;
			case REFRESH, CYCLE_ATTACK_MODE, CYCLE_ATTACK_RANGE,
					TOGGLE_ESCORT_STANCE, CYCLE_ESCORT_STANCE,
					TOGGLE_REMOTE_COMMAND -> false;
		};
	}

	private static String toggleRemoteCommand(ServerPlayer player, UUID requestedSuitId) {
		if (requestedSuitId == null) return "请先选择一套巡航或护航战甲";
		return ArmorRemoteCommandController.select(player, requestedSuitId).message();
	}

	private static String deploySelectedWorn(ServerPlayer player, UUID requestedSuitId) {
		if (requestedSuitId == null) return "请先选择当前穿戴的战甲";
		UUID wornSuitId = wornFullSuitId(player).orElse(null);
		if (!requestedSuitId.equals(wornSuitId)) {
			return "所选战甲已不是当前完整穿戴套装，状态已刷新";
		}
		ArmorCruiseController.WornDeploymentResult result =
				ArmorCruiseController.deployWornSuit(player, requestedSuitId);
		return result.success()
				? "当前战甲已释放为巡航/护航载体"
				: result.message();
	}

	private static String bindLookedAt(ServerPlayer player) {
		ArmorCoreSavedData.CoreRecord record = ArmorCoreSavedData.get(player.level().getServer()).record(player.getUUID());
		if (activeIdentity(player, record).isEmpty()) return "请先右键激活一枚装甲核心";
		HitResult hit = player.pick(6.0D, 0.0F, false);
		if (!(hit instanceof BlockHitResult blockHit)) return "请看向 6 格内的充能平台";
		if (!(player.level().getBlockEntity(blockHit.getBlockPos()) instanceof ChargingPlatformBlockEntity platform)) {
			return "目标不是充能平台";
		}
		if (!platform.isComplete()) return "平台需要先放齐头盔、胸甲、护腿和战靴";
		if (platform.getOwnerUuid().filter(player.getUUID()::equals).isEmpty()) return "该平台战甲不属于你";
		if (!bindPlatformIfCoreEquipped(player, platform)) {
			return "\u5f53\u524d\u6838\u5fc3\u7ed1\u5b9a\u5df2\u6ee1\u3001\u8eab\u4efd\u5df2\u53d8\u5316\u6216\u5e73\u53f0\u72b6\u6001\u5df2\u5931\u6548";
		}
		return "平台已接入装甲核心网络";
	}
	private static String primaryRoleChangeBlockReason(ServerPlayer player) {
		SuitRuntimeSnapshot snapshot = SuitStateResolver.resolve(player);
		boolean moving = snapshot.suits().stream().anyMatch(state ->
				state.phase() != SuitRuntimeSnapshot.Phase.IDLE
						&& state.phase() != SuitRuntimeSnapshot.Phase.GUARDING);
		if (moving) return "\u6218\u7532\u6b63\u5728\u79fb\u52a8\u6216\u7740\u88c5\uff0c\u5b8c\u6210\u540e\u624d\u80fd\u5207\u6362\u4e3b\u526f\u72b6\u6001";
		boolean conflict = snapshot.conflicts().stream()
				.anyMatch(ArmorCoreController::blocksPrimaryRoleChange);
		if (conflict) return "\u6218\u7532\u4f4d\u7f6e\u3001\u8eab\u4efd\u6216\u6240\u6709\u6743\u51b2\u7a81\uff0c\u5df2\u963b\u6b62\u4e3b\u526f\u5207\u6362";
		return null;
	}

	private static boolean blocksPrimaryRoleChange(SuitRuntimeSnapshot.Conflict conflict) {
		return switch (conflict) {
			case OWNER_MISMATCH, IDENTITY_MISMATCH, PARTIAL_WORN_SET,
					LOCATION_COLLISION, LEDGER_MISMATCH, PLATFORM_IDENTITY_MISMATCH,
					TRANSITION_CONFLICT -> true;
			default -> false;
		};
	}


	private static String setPrimary(ServerPlayer player, UUID suitId) {
		if (suitId == null) return "请先选择一套战甲";
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		Optional<ArmorCoreIdentity.Identity> identity = activeIdentity(player, record);
		String blocked = primaryRoleChangeBlockReason(player);
		if (blocked != null) return blocked;
		if (identity.isEmpty()) return "装甲核心未装备或身份无效";
		if (suitId.equals(record.primarySuitId)) {
			return "\u6240\u9009\u6218\u7532\u5df2\u662f\u4e3b\u6218\u7532";
		}
		SuitRuntimeSnapshot.SuitState runtime =
				SuitStateResolver.resolve(player).suitsById().get(suitId);
		if (runtime == null || !runtime.allowedActions().contains(
				SuitRuntimeSnapshot.AllowedAction.CAN_SET_PRIMARY)) {
			return "\u6240\u9009\u6218\u7532\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u4e0d\u80fd\u8bbe\u4e3a\u4e3b\u6218\u7532";
		}
		return data.setPrimary(player.getUUID(), identity.get(), suitId)
				? "已切换为主战甲；原主甲已自动降为副甲" : "所选战甲不在当前核心中";
	}
	private static String returnEscort(ServerPlayer player, UUID suitId) {
		if (suitId == null) return "请先选择一套护卫战甲";
		ArmorCoreSavedData.CoreRecord record =
				ArmorCoreSavedData.get(player.level().getServer()).record(player.getUUID());
		if (activeIdentity(player, record).isEmpty()) return "装甲核心未激活或身份无效";
		ArmorCoreSavedData.PlatformBinding binding = record.bindings.get(suitId);
		if (binding == null) return "所选护卫未绑定原始平台";
		SuitRuntimeSnapshot.SuitState runtimeState =
				SuitStateResolver.resolve(player).suitsById().get(suitId);
		if (runtimeState != null
				&& runtimeState.phase() == SuitRuntimeSnapshot.Phase.RETURNING_PLATFORM) {
			return "所选战甲已经在返回绑定平台";
		}
		if (runtimeState == null || !runtimeState.allowedActions().contains(
				SuitRuntimeSnapshot.AllowedAction.CAN_RETURN_HOME)) {
			return "\u6240\u9009\u6218\u7532\u5f53\u524d\u6ca1\u6709\u53ef\u5b89\u5168\u6267\u884c\u7684\u8fd4\u53f0\u6765\u6e90";
		}
		if (runtimeState != null
				&& runtimeState.location() == SuitRuntimeSnapshot.Location.CARRIER
				&& runtimeState.carrierKind() == SuitRuntimeSnapshot.CarrierKind.INDEPENDENT) {
			ArmorCruiseController.RecallCommandResult result =
					ArmorCruiseController.returnActiveSuitToPlatform(player, suitId,
							binding.platformId(), binding.dimension(), binding.pos());
			return result.success()
					? "独立巡航战甲正在直接返回绑定平台"
					: result.message();
		}

		ArmorEscortController.EscortCommandResult result =
				ArmorEscortController.returnToPlatform(player, suitId, binding.platformId(),
						binding.dimension(), binding.pos());
		return result.message();
	}

	private static String updateEscortProfile(ServerPlayer player, UUID suitId, ArmorCoreAction action) {
		if (suitId == null) {
			// 快捷键入口没有"选中哪一套"的概念，所以由服务端把全部已加载护航副甲一起循环；
			// 面板按钮始终带着明确的 suitId，走下面的单套路径。
			return action == ArmorCoreAction.CYCLE_ESCORT_STANCE
					? cycleEscortStanceForAllLoaded(player)
					: "请先选择一套副战甲";
		}
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		Optional<ArmorCoreIdentity.Identity> identity = activeIdentity(player, record);
		if (identity.isEmpty()) return "装甲核心未激活或身份无效";
		if (!record.linkedSuitIds.contains(suitId)) return "所选战甲不在当前核心中";
		SuitRuntimeSnapshot.SuitState runtime =
				SuitStateResolver.resolve(player).suitsById().get(suitId);
		if (runtime == null || !runtime.allowedActions().contains(
				SuitRuntimeSnapshot.AllowedAction.CAN_SET_ESCORT)) {
			return "\u6240\u9009\u6218\u7532\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u5f53\u524d\u4e0d\u80fd\u4fee\u6539\u62a4\u822a\u7b56\u7565";
		}
		ArmorEscortProfile current = data.escortProfile(player.getUUID(), suitId);
		ArmorEscortProfile next;
		String message;
		switch (action) {
			case CYCLE_ATTACK_MODE -> {
				next = current.cycleAttackMode();
				message = "副甲攻击策略：" + switch (next.attackMode()) {
					case OFF -> "关闭攻击";
					case HOSTILE -> "仅敌对目标";
					case ALL -> "所有合法目标";
				};
			}
			case CYCLE_ATTACK_RANGE -> {
				next = current.cycleRange();
				message = "副甲攻击范围：" + next.rangeBlocks() + " 格";
			}
			case TOGGLE_ESCORT_STANCE -> {
				ArmorEscortProfile.EscortAnchor nextStance = current.stance().next();
				if (nextStance == ArmorEscortProfile.EscortAnchor.HOLD) {
					ArmorEscortController.EscortWorldAnchor anchor =
							ArmorEscortController.currentAnchor(suitId).orElse(null);
					if (anchor == null) return "请先启动该副甲护航，再固定它的当前位置";
					next = current.withStance(nextStance, anchor.dimension(), anchor.position());
				} else {
					next = current.withStance(nextStance, null, null);
				}
				message = "副甲任务位置：" + escortStanceLabel(nextStance);
			}
			default -> {
				return "不支持的副甲配置命令";
			}
		}
		return data.setEscortProfile(player.getUUID(), identity.get(), suitId, next)
				? message : "副甲配置写入失败";
	}

	private static String escortStanceLabel(ArmorEscortProfile.EscortAnchor stance) {
		return switch (stance) {
			case FOLLOW -> "跟随自主";
			case CLOSE -> "贴身跟随";
			case HOLD -> "原地待命";
		};
	}

	/**
	 * 快捷键入口：把该玩家全部已加载护航副甲统一循环到下一个任务位置档位。
	 *
	 * <p>各套甲档位一致时整体前进一档；不一致时先归位到「跟随自主」，这样一次按键永远
	 * 得到可预期的结果，不会停在互不同步的组合上。原地待命的锚点始终取该套甲当前的真实
	 * 载体位置，客户端既不选目标也不提供坐标。</p>
	 */
	private static String cycleEscortStanceForAllLoaded(ServerPlayer player) {
		List<ArmorEscortController.LoadedEscort> escorts =
				ArmorEscortController.loadedEscorts(player);
		if (escorts.isEmpty()) return "附近没有已加载的护航副甲";
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		Optional<ArmorCoreIdentity.Identity> identity = activeIdentity(player, record);
		if (identity.isEmpty()) return "装甲核心未激活或身份无效";

		List<ArmorEscortController.LoadedEscort> configurable = new ArrayList<>();
		ArmorEscortProfile.EscortAnchor first = null;
		boolean uniform = true;
		for (ArmorEscortController.LoadedEscort escort : escorts) {
			if (!record.linkedSuitIds.contains(escort.suitId())) continue;
			ArmorEscortProfile.EscortAnchor stance =
					data.escortProfile(player.getUUID(), escort.suitId()).stance();
			if (first == null) first = stance;
			else if (first != stance) uniform = false;
			configurable.add(escort);
		}
		if (configurable.isEmpty()) return "没有可由当前核心配置的护航副甲";

		ArmorEscortProfile.EscortAnchor target = uniform && first != null
				? first.next() : ArmorEscortProfile.EscortAnchor.FOLLOW;
		int written = 0;
		for (ArmorEscortController.LoadedEscort escort : configurable) {
			ArmorEscortProfile current = data.escortProfile(player.getUUID(), escort.suitId());
			ArmorEscortProfile next = target == ArmorEscortProfile.EscortAnchor.HOLD
					? current.withStance(target, escort.dimension(),
							BlockPos.containing(escort.position()))
					: current.withStance(target, null, null);
			if (data.setEscortProfile(player.getUUID(), identity.get(), escort.suitId(), next)) {
				written++;
			}
		}
		if (written == 0) return "副甲配置写入失败";
		return "护航副甲任务位置：" + escortStanceLabel(target)
				+ (written > 1 ? "（" + written + " 套）" : "");
	}


	private static String forgetMissingSuit(ServerPlayer player, UUID suitId) {
		if (suitId == null) return "\u8bf7\u5148\u9009\u62e9\u4e00\u5957\u5931\u8054\u6218\u7532";
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		ArmorCoreIdentity.Identity coreIdentity = activeIdentity(player, record).orElse(null);
		if (coreIdentity == null) return "\u88c5\u7532\u6838\u5fc3\u672a\u88c5\u5907\u6216\u8eab\u4efd\u65e0\u6548";
		if (!record.linkedSuitIds.contains(suitId)) {
			return "\u6240\u9009\u6218\u7532\u5df2\u4e0d\u5728\u5f53\u524d\u6838\u5fc3\u4e2d";
		}

		SuitRuntimeSnapshot.SuitState runtime =
				SuitStateResolver.resolve(player).suitsById().get(suitId);
		if (runtime == null || !runtime.allowedActions().contains(
				SuitRuntimeSnapshot.AllowedAction.CAN_FORGET_MISSING)) {
			return "\u670d\u52a1\u7aef\u4ecd\u6709\u6218\u7532\u4f4d\u7f6e\u6216\u8fc7\u6e21\u8bc1\u636e\uff0c\u4e0d\u80fd\u79fb\u9664\u5173\u8054";
		}
		if (hasActiveQuickSwitch(player) || ArmorAssemblyController.isAnimating(player)
				|| runtime.transactionTargetSuitId() != null) {
			return "\u6218\u7532\u4e8b\u52a1\u5c1a\u672a\u7ed3\u675f\uff0c\u4e0d\u80fd\u79fb\u9664\u5173\u8054";
		}

		ArmorCruiseController.CruiseRuntimeView cruise =
				ArmorCruiseController.runtimeView(player).orElse(null);
		if (ArmorCruiseController.hasActiveCruise(player)
				&& (cruise == null || cruise.suitId() == null || suitId.equals(cruise.suitId()))) {
			return "\u72ec\u7acb\u5de1\u822a\u8f7d\u4f53\u7d22\u5f15\u5c1a\u5728\uff0c\u9700\u5148\u6062\u590d\u8f7d\u4f53\u518d\u5904\u7406";
		}
		if (ArmorEscortController.hasRecoverableSuit(player.level().getServer(), suitId)) {
			return "\u62a4\u822a\u8f7d\u4f53\u6216\u672a\u52a0\u8f7d\u7d22\u5f15\u5c1a\u5728\uff0c\u4e0d\u80fd\u79fb\u9664\u5173\u8054";
		}

		ChargingPlatformController.OwnershipRuntimeView ownership =
				ChargingPlatformController.ownershipRuntimeView(player, suitId).orElse(null);
		if (ownership != null
				&& ownership.location() == ChargingPlatformController.OwnershipLocation.LOOSE
				&& ownership.dimension() != null && ownership.pos() != null) {
			ServerLevel looseLevel = player.level().getServer().getLevel(ownership.dimension());
			if (looseLevel == null || !looseLevel.hasChunkAt(ownership.pos())) {
				return "\u6563\u843d\u6218\u7532\u6700\u540e\u8bb0\u5f55\u7684\u533a\u5757\u672a\u52a0\u8f7d\uff0c\u4e0d\u80fd\u5224\u5b9a\u5df2\u4e22\u5931";
			}
		}
		if (hasLoadedSuitPartEvidence(player.level().getServer(), player.getUUID(), suitId)) {
			return "\u4ecd\u5728\u5df2\u52a0\u8f7d\u4e16\u754c\u6216\u73a9\u5bb6\u7269\u54c1\u680f\u4e2d\u53d1\u73b0\u8be5\u6218\u7532\u90e8\u4ef6";
		}

		ArmorCoreSavedData.PlatformBinding binding = record.bindings.get(suitId);
		if (binding != null) {
			ChargingPlatformController.PlatformRuntimeView platform =
					ChargingPlatformController.platformRuntimeView(player, suitId,
							binding.platformId(), binding.dimension(), binding.pos());
			switch (platform.health()) {
				case VALID_OCCUPIED, INCOMPLETE ->
						{ return "\u7ed1\u5b9a\u5e73\u53f0\u4ecd\u4fdd\u5b58\u8be5\u6218\u7532\u90e8\u4ef6"; }
				case UNLOADED, DIMENSION_UNAVAILABLE ->
						{ return "\u7ed1\u5b9a\u5e73\u53f0\u5f53\u524d\u672a\u52a0\u8f7d\uff0c\u4e0d\u80fd\u5224\u5b9a\u6218\u7532\u5df2\u4e22\u5931"; }
				case IDENTITY_CONFLICT ->
						{ return "\u7ed1\u5b9a\u5e73\u53f0\u5b58\u5728\u8eab\u4efd\u51b2\u7a81\uff0c\u8bf7\u5148\u5904\u7406\u5e73\u53f0"; }
				case VALID_EMPTY_RESERVED, BROKEN, UNBOUND -> {
					// A loaded empty home is positive absence evidence; broken/unbound has no
					// physical platform evidence. Continue to the final identity CAS below.
				}
			}
		}

		ArmorCoreSavedData.CoreRecord current = data.record(player.getUUID());
		ArmorCoreIdentity.Identity currentIdentity = activeIdentity(player, current).orElse(null);
		if (currentIdentity == null || !currentIdentity.equals(coreIdentity)
				|| !current.linkedSuitIds.contains(suitId)) {
			return "\u6838\u5fc3\u7f51\u7edc\u5df2\u53d8\u5316\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5";
		}
		return data.removeSuitFromActiveCore(player.getUUID(), currentIdentity, suitId)
				? "\u5df2\u79fb\u9664\u5931\u8054\u6218\u7532\u7684\u6838\u5fc3\u5173\u8054\uff1b\u6240\u6709\u6743\u8bb0\u5f55\u5df2\u4fdd\u7559\uff0c\u771f\u5b9e\u6218\u7532\u91cd\u73b0\u540e\u53ef\u81ea\u52a8\u91cd\u65b0\u5173\u8054"
				: "\u6838\u5fc3\u7f51\u7edc\u5df2\u53d8\u5316\uff0c\u672a\u79fb\u9664\u4efb\u4f55\u8bb0\u5f55";
	}

	private static boolean hasLoadedSuitPartEvidence(
			MinecraftServer server, UUID ownerId, UUID suitId) {
		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			for (int slot = 0; slot < online.getInventory().getContainerSize(); slot++) {
				if (isSuitPart(online.getInventory().getItem(slot), ownerId, suitId)) return true;
			}
			if (isSuitPart(online.containerMenu.getCarried(), ownerId, suitId)) return true;
		}
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity.isRemoved()) continue;
				if (entity instanceof ItemEntity item
						&& isSuitPart(item.getItem(), ownerId, suitId)) return true;
				if (entity instanceof LivingEntity living) {
					for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
						if (isSuitPart(living.getItemBySlot(slot), ownerId, suitId)) return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean isSuitPart(ItemStack stack, UUID ownerId, UUID suitId) {
		return ArmorIdentity.read(stack).map(identity ->
				identity.ownerId().equals(ownerId) && identity.suitId().equals(suitId)).orElse(false);
	}

	private static String recallSelected(ServerPlayer player, UUID suitId, boolean escort) {
		if (suitId == null) return "请先选择一套战甲";
		ArmorCoreSavedData.CoreRecord record = ArmorCoreSavedData.get(player.level().getServer()).record(player.getUUID());
		if (activeIdentity(player, record).isEmpty()) return "装甲核心未装备或身份无效";
		if (!record.linkedSuitIds.contains(suitId)) return "所选战甲不在当前核心中";
		SuitRuntimeSnapshot.SuitState runtime =
				SuitStateResolver.resolve(player).suitsById().get(suitId);
		if (runtime == null) return "所选战甲状态已变化，请刷新面板";
		if (runtime.location() == SuitRuntimeSnapshot.Location.WORN) {
			return escort
					? "\u6240\u9009\u6218\u7532\u6b63\u5728\u8eab\u4e0a\uff0c\u4e0d\u80fd\u540c\u65f6\u4f5c\u4e3a\u62a4\u822a\u6218\u7532"
					: "\u6240\u9009\u6218\u7532\u5df2\u7ecf\u7a7f\u6234";
		}
		if (escort && runtime.location() == SuitRuntimeSnapshot.Location.CARRIER
				&& runtime.carrierKind() == SuitRuntimeSnapshot.CarrierKind.ESCORT) {
			return "\u6240\u9009\u6218\u7532\u5df2\u7ecf\u5904\u4e8e\u62a4\u822a\u72b6\u6001";
		}
		SuitRuntimeSnapshot.AllowedAction required = escort
				? SuitRuntimeSnapshot.AllowedAction.CAN_LAUNCH_ESCORT
				: SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR;
		if (!runtime.allowedActions().contains(required)) {
			return "\u6240\u9009\u6218\u7532\u4f4d\u7f6e\u3001\u8eab\u4efd\u6216\u8fc7\u6e21\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u547d\u4ee4\u672a\u6267\u884c";
		}
		ArmorCoreSavedData.PlatformBinding binding = record.bindings.get(suitId);
		player.closeContainer();
		return switch (runtime.location()) {
			case CARRIER -> {
				if (runtime.carrierKind() == SuitRuntimeSnapshot.CarrierKind.ESCORT) {
					if (escort) yield "所选战甲已经处于护航状态";
					ArmorEscortController.EscortCommandResult result =
							ArmorEscortController.recallToOwnerForWear(player, suitId);
					yield result.success() ? "护航战甲正在接近并着装" : result.message();
				}
				if (escort) {
					ArmorCruiseController.RecallCommandResult result =
							ArmorCruiseController.handoffActiveSuitToEscort(player, suitId,
									binding == null ? null : binding.platformId(),
									binding == null ? null : binding.dimension(),
									binding == null ? null : binding.pos());
					yield result.success() ? "独立巡航战甲已切换为护航状态" : result.message();
				}
				ArmorCruiseController.RecallCommandResult result =
						ArmorCruiseController.recallActiveSuitForFormation(player, suitId);
				yield result.success() ? "独立巡航战甲正在接近并着装" : result.message();
			}
			case PLATFORM -> {
				if (binding == null) yield "所选战甲缺少当前核心中的平台位置";
				String validation = validatePlatform(player, binding, true);
				if (validation != null) yield validation;
				if (escort) {
					ArmorEscortController.LaunchResult result =
							ArmorEscortController.launchFromPlatform(player, suitId, binding.platformId());
					yield result.success() ? "副战甲已离台，自动巡航与战斗模式已开启" : result.message();
				}
				yield ArmorCruiseController.recallPlatformSuit(player, suitId)
						? "主战甲正在高速接近并着装" : "主战甲召回未启动";
			}
			case WORN -> escort ? "所选战甲正在身上，不能同时作为护航战甲" : "所选战甲已经穿戴";
			case LOOSE, UNKNOWN -> "所选战甲当前没有可执行的召回来源";
		};
	}

	private static FormationExecution reconcileConfiguredFormation(ServerPlayer player, UUID selectedPrimary) {
		if (player == null) return FormationExecution.inactive();
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		ArmorCoreIdentity.Identity identity = activeIdentity(player, record).orElse(null);
		if (identity == null) return FormationExecution.inactive();
		if (!player.isAlive() || player.isSpectator()) {
			return FormationExecution.handled("当前状态无法执行核心编队方案");
		}
		if (selectedPrimary != null) {
			String blocked = primaryRoleChangeBlockReason(player);
			if (!selectedPrimary.equals(record.primarySuitId) && blocked != null)
				return FormationExecution.handled(blocked);
			if (!record.linkedSuitIds.contains(selectedPrimary)) {
				return FormationExecution.handled("所选战甲已不在当前核心绑定网络中，请刷新面板");
			}
			if (!selectedPrimary.equals(record.primarySuitId)
					&& !data.setPrimary(player.getUUID(), identity, selectedPrimary)) {
				return FormationExecution.handled("主战甲配置已变化，请刷新后重试");
			}
		}

		SuitRuntimeSnapshot snapshot = SuitStateResolver.resolve(player);
		ArmorFormationPlanner.Plan plan = ArmorFormationPlanner.plan(snapshot);
		return switch (plan.status()) {
			case INACTIVE -> FormationExecution.inactive();
			case SATISFIED -> FormationExecution.handled(
					"核心编队已对齐：主战甲已穿戴，现有副甲保持护航");
			case WAITING -> FormationExecution.handled(formationWaitMessage(plan.reason()));
			case REJECTED -> FormationExecution.handled(formationRejectMessage(plan.reason()));
			case EXECUTE -> executeFormationCommands(player, record, plan.commands());
		};
	}

	private static FormationExecution executeFormationCommands(ServerPlayer player,
			ArmorCoreSavedData.CoreRecord record, List<ArmorFormationPlanner.Command> commands) {
		player.closeContainer();
		int primaryStarted = 0;
		int escortsStarted = 0;
		int escortsAdopted = 0;
		List<String> failures = new ArrayList<>();
		for (ArmorFormationPlanner.Command command : commands) {
			if (!record.linkedSuitIds.contains(command.suitId())) {
				failures.add(command.suitId().toString().substring(0, 8) + "（已不在当前核心）");
				continue;
			}
			ArmorCoreSavedData.PlatformBinding binding = record.bindings.get(command.suitId());
			String displayName = binding == null
					? command.suitId().toString().substring(0, 8) : binding.displayName();
			if (ArmorFormationPlanner.requiresPlatformHome(command.type()) && binding == null) {
				failures.add(displayName + "（未绑定返航平台）");
				continue;
			}
			switch (command.type()) {
				case QUICK_SWITCH_PRIMARY -> {
					TargetedSwitchResult result = beginTargetedQuickSwitch(player, command.suitId());
					return FormationExecution.handled(result.message());
				}
				case RECALL_PRIMARY_PLATFORM -> {
					if (ArmorCruiseController.recallPlatformSuit(player, command.suitId())) primaryStarted++;
					else failures.add(binding.displayName());
				}
				case RECALL_PRIMARY_ESCORT -> {
					ArmorEscortController.EscortCommandResult result =
							ArmorEscortController.recallToOwnerForWear(player, command.suitId());
					if (result.success()) primaryStarted++;
					else failures.add(displayName + "（" + result.message() + "）");
				}
				case RECALL_PRIMARY_INDEPENDENT -> {
					ArmorCruiseController.RecallCommandResult result =
							ArmorCruiseController.recallActiveSuitForFormation(player, command.suitId());
					if (result.success()) primaryStarted++;
					else failures.add(displayName + "（" + result.message() + "）");
				}
				case LAUNCH_ESCORT_PLATFORM -> {
					ArmorEscortController.LaunchResult result = ArmorEscortController.launchFromPlatform(
							player, command.suitId(), binding.platformId());
					if (result.success()) escortsStarted++;
					else failures.add(binding.displayName() + "（" + result.message() + "）");
				}
				case ADOPT_INDEPENDENT_ESCORT -> {
					ArmorCruiseController.RecallCommandResult result =
							ArmorCruiseController.handoffActiveSuitToEscort(player, command.suitId(),
									binding == null ? null : binding.platformId(),
									binding == null ? null : binding.dimension(),
									binding == null ? null : binding.pos());
					if (result.success()) escortsAdopted++;
					else failures.add(displayName + "（" + result.message() + "）");
				}
			}
		}
		String message = "核心编队：主甲召回 " + primaryStarted + " 套，副甲离台 " + escortsStarted
				+ " 套，巡航转护航 " + escortsAdopted + " 套";
		if (!failures.isEmpty()) message += "；状态变化或未启动：" + String.join("、", failures);
		return FormationExecution.handled(message);
	}

	private static String formationWaitMessage(ArmorFormationPlanner.Reason reason) {
		return switch (reason) {
			case TRANSACTION_ACTIVE -> "核心编队正在快速换甲；B 不会重复启动第二个事务";
			case TRANSITION_ACTIVE -> "核心编队正在飞行、变形或着装过渡；本次 B 已安全等待";
			default -> "核心编队正在过渡；本次 B 未重复发送移动命令";
		};
	}

	private static String formationRejectMessage(ArmorFormationPlanner.Reason reason) {
		return switch (reason) {
			case NO_PRIMARY -> "当前核心尚未绑定并设置主战甲";
			case IDENTITY_OR_LOCATION_CONFLICT -> "检测到战甲身份、位置或所有权账本冲突，已锁定 B 以防止复制";
			case PRIMARY_UNAVAILABLE -> "主战甲当前不可召回或无法安全穿戴";
			case ESCORT_UNAVAILABLE -> "至少一套副甲当前无法进入护航编队";
			default -> "核心编队状态暂不允许执行方案";
		};
	}

	private record FormationExecution(boolean coreHandled, String message) {
		private static FormationExecution inactive() {
			return new FormationExecution(false, "装甲核心未激活或身份无效");
		}

		private static FormationExecution handled(String message) {
			return new FormationExecution(true, message == null ? "" : message);
		}
	}

	private static String validatePlatform(ServerPlayer player,
			ArmorCoreSavedData.PlatformBinding binding, boolean loadChunk) {
		ServerLevel level = player.level().getServer().getLevel(binding.dimension());
		if (level == null) return binding.displayName() + " 所在维度不可用";
		if (loadChunk) level.getChunkAt(binding.pos());
		else if (!level.hasChunkAt(binding.pos())) return binding.displayName() + " 平台区块未加载";
		if (!(level.getBlockEntity(binding.pos()) instanceof ChargingPlatformBlockEntity platform)) {
			return binding.displayName() + " 的平台已不存在";
		}
		if (!platform.getPlatformId().equals(binding.platformId())) return binding.displayName() + " 的平台实例已更换";
		if (platform.getOwnerUuid().filter(player.getUUID()::equals).isEmpty()
				|| platform.getSuitId().filter(binding.suitId()::equals).isEmpty()) {
			return binding.displayName() + " 的平台身份不一致";
		}
		return platform.isComplete() ? null : binding.displayName() + " 缺少部件";
	}

	/**
	 * Receives the shared Fabric entity-unload event. Only a definitive killed identified drop or
	 * vanilla-age despawn becomes a tombstone; pickup, merge, chunk unload and dimension changes do
	 * not alter core or ownership state.
	 */
	public static void handleEntityUnload(Entity entity, ServerLevel level) {
		if (!(entity instanceof ItemEntity item) || level == null) return;
		ItemStack stack = item.getItem();
		ArmorIdentity.Identity identity = ArmorIdentity.read(stack).orElse(null);
		if (identity == null) return;
		Entity.RemovalReason reason = item.getRemovalReason();
		if (!ArmorCoreLifecyclePolicy.confirmsDroppedPartDestruction(
				!stack.isEmpty(), reason == Entity.RemovalReason.KILLED,
				reason == Entity.RemovalReason.DISCARDED, item.getAge())) return;
		CONFIRMED_DESTROYED_SUITS.putIfAbsent(
				new SuitKey(identity.ownerId(), identity.suitId()), level.getGameTime());
	}

	private static void processConfirmedDestroyedSuits(MinecraftServer server) {
		for (Map.Entry<SuitKey, Long> entry :
				new ArrayList<>(CONFIRMED_DESTROYED_SUITS.entrySet())) {
			SuitKey key = entry.getKey();
			QuickSwitchState transaction = QUICK_SWITCHES.get(key.ownerId());
			if (transaction != null && (key.suitId().equals(transaction.outgoingSuitId)
					|| key.suitId().equals(transaction.targetSuitId))) {
				continue;
			}
			ServerPlayer owner = server.getPlayerList().getPlayer(key.ownerId());
			if (owner != null && ArmorAssemblyController.isAnimating(owner)) continue;

			boolean coreRemoved = ArmorCoreSavedData.get(server)
					.removeConfirmedDestroyedSuit(key.ownerId(), key.suitId());
			boolean ledgerRemoved = ChargingPlatformController.removeConfirmedDestroyedSuit(
					server, key.ownerId(), key.suitId());
			if (coreRemoved || ledgerRemoved) {
				FlightArmorMod.LOGGER.info(
						"Confirmed destroyed armor suit tombstoned owner={} suit={} core={} ownership={}",
						key.ownerId(), key.suitId(), coreRemoved, ledgerRemoved);
				if (owner != null) sendSnapshot(owner);
			}
			CONFIRMED_DESTROYED_SUITS.remove(key, entry.getValue());
		}
	}

	public static void sendSnapshot(ServerPlayer player) {
		sendSnapshot(player, "", -1L, null, null, true);
	}

	private static void sendSnapshot(ServerPlayer player, String resultMessage) {
		sendSnapshot(player, resultMessage, -1L, null, null, true);
	}

	private static void sendSnapshot(ServerPlayer player, String resultMessage,
			long resultClientSequence, ArmorCoreAction resultAction, UUID resultSuitId) {
		sendSnapshot(player, resultMessage, resultClientSequence, resultAction, resultSuitId, true);
	}

	private static void sendPeriodicSnapshot(ServerPlayer player) {
		sendSnapshot(player, "", -1L, null, null, false);
	}

	private static void sendSnapshot(ServerPlayer player, String resultMessage,
			long resultClientSequence, ArmorCoreAction resultAction, UUID resultSuitId,
			boolean processStateEvent) {
		if (!ServerPlayNetworking.canSend(player, ArmorCoreSnapshotPayload.TYPE)) return;
		if (processStateEvent && !isCoreChangeLocked(player)
				&& !ArmorAssemblyController.isAnimating(player)) {
			reconcileMissingPrimary(player);
		}
		SuitRuntimeSnapshot snapshot = SuitStateResolver.resolve(player);
		List<ArmorCoreSnapshotPayload.Entry> entries = new ArrayList<>();
		for (SuitRuntimeSnapshot.SuitState state : snapshot.suits()) {
			ArmorEscortProfile profile = state.escortProfile();
			boolean loose = state.location() == SuitRuntimeSnapshot.Location.LOOSE;
			ResourceKey<Level> displayDimension = loose && state.currentDimension() != null
					? state.currentDimension()
					: state.homeDimension() != null ? state.homeDimension()
					: state.currentDimension() != null ? state.currentDimension()
					: player.level().dimension();
			BlockPos displayPosition = loose && state.currentPosition() != null
					? state.currentPosition()
					: state.homePosition() != null ? state.homePosition()
					: state.currentPosition() != null ? state.currentPosition()
					: player.blockPosition();
			boolean hasHome = state.platformId() != null
					&& state.homeDimension() != null && state.homePosition() != null;
			entries.add(new ArmorCoreSnapshotPayload.Entry(state.suitId(), state.displayName(), state.variant(),
					displayDimension.identifier(), displayPosition.getX(),
					displayPosition.getY(), displayPosition.getZ(),
					legacyCoreStatus(state), profile.attackMode(), profile.rangeBlocks(), profile.stance(),
					hasHome, ArmorCoreSnapshotPayload.Entry.encodeAllowedActions(state.allowedActions())));
		}
		String safeMessage = resultMessage == null ? "" : resultMessage;
		if (safeMessage.length() > 240) safeMessage = safeMessage.substring(0, 239) + "…";
		ServerPlayNetworking.send(player, new ArmorCoreSnapshotPayload(snapshot.revision(),
				snapshot.coreActive(), snapshot.primarySuitId(), List.copyOf(entries),
				resultClientSequence, resultAction, resultSuitId, safeMessage));
	}

	/**
	 * Reconciles physical worn-suit evidence with the active navigation core. A platform is only
	 * an optional home, so equipping a core while already wearing a complete suit must immediately
	 * expose that suit in the panel instead of waiting for the B-key path to associate it.
	 */
	private static boolean reconcileWornSuitAssociation(ServerPlayer player) {
		if (player == null || !ModItems.hasFullSet(player)) return false;
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord record = data.record(player.getUUID());
		ArmorCoreIdentity.Identity active = activeIdentity(player, record).orElse(null);
		if (active == null) return false;
		ChargingPlatformController.OwnershipResult ownership =
				ChargingPlatformController.ensureOwnedEquippedSet(player);
		if (ownership.success() && ownership.suitId() != null) {
			boolean wasLinked = record.linkedSuitIds.contains(ownership.suitId());
			return data.associateUnlinkedSuit(player.getUUID(), active, ownership.suitId()) && !wasLinked;
		}
		return false;
	}

	/**
	 * B-key-only fallback. Unlike confirmed-loss failover, this may temporarily replace an
	 * unreachable primary, but only with a linked suit that can actually start recall now.
	 */
	private static boolean promoteRecallablePrimaryForB(ServerPlayer player,
			SuitRuntimeSnapshot snapshot) {
		UUID replacement = ArmorPrimaryFailoverPolicy.selectRecallablePrimaryForB(snapshot)
				.orElse(null);
		if (replacement == null) return false;
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord current = data.record(player.getUUID());
		ArmorCoreIdentity.Identity identity = activeIdentity(player, current).orElse(null);
		if (identity == null
				|| !Objects.equals(current.primarySuitId, snapshot.primarySuitId())
				|| !current.linkedSuitIds.contains(replacement)
				|| !data.setPrimary(player.getUUID(), identity, replacement)) {
			return false;
		}
		FlightArmorMod.LOGGER.info(
				"Armor core B recall fallback owner={} unavailable={} replacement={}",
				player.getUUID(), snapshot.primarySuitId(), replacement);
		return true;
	}
	/** Promotes a healthy linked suit only after the current primary has authoritative missing evidence. */
	private static boolean reconcileMissingPrimary(ServerPlayer player) {
		ArmorCoreSavedData data = ArmorCoreSavedData.get(player.level().getServer());
		ArmorCoreSavedData.CoreRecord before = data.record(player.getUUID());
		ArmorCoreIdentity.Identity identity = activeIdentity(player, before).orElse(null);
		if (identity == null) return false;

		SuitRuntimeSnapshot snapshot = SuitStateResolver.resolve(player);
		UUID replacement = ArmorPrimaryFailoverPolicy.selectReplacement(snapshot).orElse(null);
		if (replacement == null) return false;

		// Compare the identity and expected primary again immediately before the persistent write.
		ArmorCoreSavedData.CoreRecord current = data.record(player.getUUID());
		ArmorCoreIdentity.Identity currentIdentity = activeIdentity(player, current).orElse(null);
		if (!identity.equals(currentIdentity)
				|| !Objects.equals(current.primarySuitId, snapshot.primarySuitId())
				|| !current.linkedSuitIds.contains(replacement)
				|| !data.setPrimary(player.getUUID(), currentIdentity, replacement)) {
			return false;
		}

		String replacementName = snapshot.suits().stream()
				.filter(suit -> suit.suitId().equals(replacement))
				.map(SuitRuntimeSnapshot.SuitState::displayName)
				.findFirst().orElse(replacement.toString().substring(0, 8));
		player.sendOverlayMessage(Component.literal(
				"原主战甲已确认失联，已自动切换主战甲：" + replacementName));
		FlightArmorMod.LOGGER.info("Armor core primary failover owner={} missing={} replacement={}",
				player.getUUID(), snapshot.primarySuitId(), replacement);
		return true;
	}

	private static ArmorCoreSnapshotPayload.Status legacyCoreStatus(
			SuitRuntimeSnapshot.SuitState state) {
		if (state.phase() == SuitRuntimeSnapshot.Phase.RETURNING_PLATFORM) {
			return ArmorCoreSnapshotPayload.Status.RETURNING;
		}
		if (state.conflicts().stream().anyMatch(
				conflict -> switch (conflict) {
					case CORE_INACTIVE, PLATFORM_BROKEN -> false;
					default -> true;
				})) {
			return ArmorCoreSnapshotPayload.Status.CONFLICT;
		}
		if (state.phase() != SuitRuntimeSnapshot.Phase.IDLE
				&& state.phase() != SuitRuntimeSnapshot.Phase.GUARDING
				&& state.phase() != SuitRuntimeSnapshot.Phase.OWNER_DOWN) {
			return ArmorCoreSnapshotPayload.Status.TRANSITION;
		}
		if (state.location() == SuitRuntimeSnapshot.Location.CARRIER) {
			if (state.carrierKind() == SuitRuntimeSnapshot.CarrierKind.INDEPENDENT) {
				return ArmorCoreSnapshotPayload.Status.CRUISING;
			}
			return ArmorCoreSnapshotPayload.Status.ESCORTING;
		}
		if (state.location() == SuitRuntimeSnapshot.Location.WORN) {
			return ArmorCoreSnapshotPayload.Status.WORN;
		}
		if (state.location() == SuitRuntimeSnapshot.Location.LOOSE) {
			return ArmorCoreSnapshotPayload.Status.LOOSE;
		}
		if (state.location() == SuitRuntimeSnapshot.Location.UNKNOWN
				&& state.currentDimension() != null && state.currentPosition() != null) {
			return ArmorCoreSnapshotPayload.Status.OFFLINE;
		}
		if (state.location() == SuitRuntimeSnapshot.Location.UNKNOWN
				&& state.homeHealth() != SuitRuntimeSnapshot.HomeHealth.UNLOADED) {
			return ArmorCoreSnapshotPayload.Status.MISSING;
		}
		return switch (state.homeHealth()) {
			case VALID_OCCUPIED -> ArmorCoreSnapshotPayload.Status.READY;
			case INCOMPLETE -> ArmorCoreSnapshotPayload.Status.INCOMPLETE;
			case UNLOADED -> ArmorCoreSnapshotPayload.Status.OFFLINE;
			default -> ArmorCoreSnapshotPayload.Status.INVALID;
		};
	}

	private static Optional<ArmorCoreIdentity.Identity> activeIdentity(ServerPlayer player,
			ArmorCoreSavedData.CoreRecord record) {
		if (record.core.isEmpty() || !record.core.is(ModItems.ARMOR_NAVIGATION_CORE)) return Optional.empty();
		return ArmorCoreIdentity.read(record.core).filter(identity -> identity.ownerId().equals(player.getUUID())
				&& record.matches(identity));
	}


	private enum QuickSwitchPhase { SEPARATING, INBOUND, RECOVERING }
	private enum InboundSource { PLATFORM, ESCORT, CRUISE }
	private record SwitchTarget(UUID suitId, String displayName, InboundSource source) {
		private SwitchTarget {
			if (suitId == null || source == null) {
				throw new IllegalArgumentException("Incomplete quick-switch target");
			}
			displayName = displayName == null || displayName.isBlank()
					? suitId.toString().substring(0, 8) : displayName;
		}
	}
	private enum RecoveryCause { SEPARATION_FAILED, TARGET_FAILED, TIMEOUT, PLAYER_DIED, PLAYER_UNAVAILABLE }
	private enum RecoveryStatus { RECOVERED, STARTED, IN_PROGRESS, WAITING, FAILED }

	private record RecoveryAttempt(RecoveryStatus status, String detail) {
		static RecoveryAttempt recovered() { return new RecoveryAttempt(RecoveryStatus.RECOVERED, ""); }
		static RecoveryAttempt started() { return new RecoveryAttempt(RecoveryStatus.STARTED, ""); }
		static RecoveryAttempt inProgress() { return new RecoveryAttempt(RecoveryStatus.IN_PROGRESS, ""); }
		static RecoveryAttempt waiting(String detail) {
			return new RecoveryAttempt(RecoveryStatus.WAITING, detail);
		}
		static RecoveryAttempt failed(String detail) {
			return new RecoveryAttempt(RecoveryStatus.FAILED, detail);
		}
	}

	private static final class QuickSwitchState {
		final UUID transactionId;
		final UUID coreId;
		final UUID coreNonce;
		final UUID outgoingSuitId;
		final ArmorCoreSavedData.PlatformBinding outgoingBinding;
		final UUID targetSuitId;
		final String targetName;
		final long startGameTime;
		QuickSwitchPhase phase = QuickSwitchPhase.SEPARATING;
		InboundSource targetSource;
		RecoveryCause recoveryCause = RecoveryCause.SEPARATION_FAILED;
		long phaseStartGameTime;
		long nextRecoveryAttemptTick;
		long revision;
		boolean outgoingDeployed;
		boolean targetCancellationComplete;
		boolean recoveryRequestAnnounced;
		boolean recoveryWaitingAnnounced;
		String lastRecoveryDetail = "";

		QuickSwitchState(ArmorCoreIdentity.Identity coreIdentity, UUID outgoingSuitId,
				ArmorCoreSavedData.PlatformBinding outgoingBinding, UUID targetSuitId,
				String targetName, long startGameTime) {
			this(UUID.randomUUID(), coreIdentity.coreId(), coreIdentity.instanceNonce(),
					outgoingSuitId, outgoingBinding, targetSuitId, targetName, startGameTime);
		}

		private QuickSwitchState(UUID transactionId, UUID coreId, UUID coreNonce,
				UUID outgoingSuitId, ArmorCoreSavedData.PlatformBinding outgoingBinding,
				UUID targetSuitId, String targetName, long startGameTime) {
			this.transactionId = transactionId;
			this.coreId = coreId;
			this.coreNonce = coreNonce;
			this.outgoingSuitId = outgoingSuitId;
			this.outgoingBinding = outgoingBinding;
			this.targetSuitId = targetSuitId;
			this.targetName = targetName;
			this.startGameTime = startGameTime;
			this.phaseStartGameTime = startGameTime;
		}

		static QuickSwitchState restore(ArmorCoreSavedData.QuickSwitchRecord stored) {
			QuickSwitchState state = new QuickSwitchState(stored.transactionId(), stored.coreId(),
					stored.coreNonce(), stored.outgoingSuitId(), stored.outgoingBinding(),
					stored.targetSuitId(), stored.targetName(), stored.startGameTime());
			try {
				state.phase = QuickSwitchPhase.valueOf(stored.phase());
			} catch (IllegalArgumentException ignored) {
				state.phase = QuickSwitchPhase.RECOVERING;
			}
			if (!stored.targetSource().isBlank()) {
				try {
					state.targetSource = InboundSource.valueOf(stored.targetSource());
				} catch (IllegalArgumentException ignored) {
					state.phase = QuickSwitchPhase.RECOVERING;
				}
			}
			try {
				state.recoveryCause = RecoveryCause.valueOf(stored.recoveryCause());
			} catch (IllegalArgumentException ignored) {
				state.recoveryCause = RecoveryCause.SEPARATION_FAILED;
				state.phase = QuickSwitchPhase.RECOVERING;
			}
			state.phaseStartGameTime = stored.phaseStartGameTime();
			state.nextRecoveryAttemptTick = stored.nextRecoveryAttemptTick();
			state.revision = stored.revision();
			state.outgoingDeployed = stored.outgoingDeployed();
			state.targetCancellationComplete = stored.targetCancellationComplete();
			state.recoveryRequestAnnounced = stored.recoveryRequestAnnounced();
			state.recoveryWaitingAnnounced = stored.recoveryWaitingAnnounced();
			state.lastRecoveryDetail = stored.lastRecoveryDetail();
			return state;
		}

		ArmorCoreSavedData.QuickSwitchRecord persistent(UUID ownerId, long nextRevision) {
			return new ArmorCoreSavedData.QuickSwitchRecord(transactionId, ownerId, coreId, coreNonce,
					outgoingSuitId, outgoingBinding, targetSuitId, targetName,
					targetSource == null ? "" : targetSource.name(), phase.name(), startGameTime,
					phaseStartGameTime, recoveryCause.name(), nextRecoveryAttemptTick,
					outgoingDeployed, targetCancellationComplete, recoveryRequestAnnounced,
					recoveryWaitingAnnounced, lastRecoveryDetail, nextRevision);
		}
	}
	public static void handleJoin(ServerPlayer player) {
		if (player != null) {
			hydrateQuickSwitches(player.level().getServer());
			reconcileWornSuitAssociation(player);
		}
	}

	public static void handleDeath(ServerPlayer player) {
		if (player == null) return;
		hydrateQuickSwitches(player.level().getServer());
		QuickSwitchState state = QUICK_SWITCHES.get(player.getUUID());
		if (state == null) return;
		UUID rescuedSuit = ArmorCruiseController.runtimeView(player)
				.map(ArmorCruiseController.CruiseRuntimeView::suitId).orElse(null);
		if (state.targetSuitId.equals(rescuedSuit)) {
			if (removePersistedQuickSwitch(player, state)) {
				setQuickSwitchPrimary(player, state.targetSuitId);
				FlightArmorMod.LOGGER.info("Quick switch death rescue committed target suit owner={} suit={}",
						player.getUUID(), state.targetSuitId);
				return;
			}
		}
		if (state.outgoingSuitId.equals(rescuedSuit)) {
			boolean targetCancelled = state.targetSource == null || cancelInboundSwitchSuit(player, state);
			if (targetCancelled && removePersistedQuickSwitch(player, state)) {
				setQuickSwitchPrimary(player, state.outgoingSuitId);
				FlightArmorMod.LOGGER.info("Quick switch death rescue restored outgoing suit owner={} suit={}",
						player.getUUID(), state.outgoingSuitId);
				return;
			}
		}
		markQuickSwitchRecovery(player.level().getServer(), player.getUUID(), state,
				player.level().getGameTime(), RecoveryCause.PLAYER_DIED);
	}

	public static void handleRespawn(ServerPlayer player) {
		if (player == null) return;
		hydrateQuickSwitches(player.level().getServer());
		QuickSwitchState state = QUICK_SWITCHES.get(player.getUUID());
		if (state != null && state.phase == QuickSwitchPhase.RECOVERING) {
			state.nextRecoveryAttemptTick = player.level().getGameTime();
			persistQuickSwitch(player.level().getServer(), player.getUUID(), state);
		}
	}

	public static void handleDisconnect(UUID playerId) {
		// The physical transaction remains in SavedData and the cache; reconnect/restart resumes it.
		LAST_QUICK_SWITCH_SEQUENCE.remove(playerId);
		LAST_CLIENT_SEQUENCE.remove(playerId);
	}

	public static void handleServerStopping(MinecraftServer server) {
		LAST_QUICK_SWITCH_SEQUENCE.clear();
		LAST_CLIENT_SEQUENCE.clear();
		QUICK_SWITCHES.clear();
		CONFIRMED_DESTROYED_SUITS.clear();
	}
}

