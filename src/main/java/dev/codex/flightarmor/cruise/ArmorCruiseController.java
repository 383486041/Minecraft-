package dev.codex.flightarmor.cruise;

import com.mojang.datafixers.util.Pair;
import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.combat.ArmorCombatController;
import dev.codex.flightarmor.combat.ArmorCombatDenyReason;
import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.flight.FlightController;
import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.network.AutomaticHoverSource;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.integrity.ArmorIntegrityController;
import dev.codex.flightarmor.network.ArmorCruiseStatePayload;
import dev.codex.flightarmor.platform.ArmorIdentity;
import dev.codex.flightarmor.network.ArmorPlatformRecallPayload;
import dev.codex.flightarmor.platform.ChargingPlatformBlockEntity;
import dev.codex.flightarmor.platform.ChargingPlatformController;
import dev.codex.flightarmor.platform.ChargingPlatformDisplayMode;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.runtime.SuitRuntimeSnapshot;
import dev.codex.flightarmor.runtime.SuitStateResolver;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Rotations;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
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
 * 战甲独立巡航的服务端权威状态机。
 *
 * <p>任何时刻四件物品只属于玩家或巡航 ArmorStand 之一。网络包只同步状态，
 * 从不携带或创建 ItemStack。</p>
 */
public final class ArmorCruiseController {
	private static final long ANY_ATTEMPT_ID = Long.MIN_VALUE;
	public static final String CARRIER_TAG = "flight_armor_cruise_carrier";
	public static final String OWNER_TAG_PREFIX = "flight_armor_cruise_owner_";
	private static final String OWNER_DOWN_TAG = "flight_armor_cruise_owner_down";

	private static final double PATROL_RADIUS = 2.45D;
	private static final double PATROL_VERTICAL_RANGE = 0.14D;
	private static final double PATROL_ANGULAR_SPEED = 0.025D;
	private static final double DOCKING_ARC_HEIGHT = 0.08D;
	private static final double OWNER_DOWN_PATROL_RADIUS = 0.62D;
	private static final double PHASING_REENTRY_DISTANCE = 24.0D;
	/** radius=2 会生成 level 31 的区域票据，保证载体所在区块持续进行实体 tick。 */
	private static final int CARRIER_TICKET_RADIUS = 2;
	private static final TicketType<UUID> CARRIER_TICKET =
			TicketType.create("flight_armor_cruise", UUID::compareTo);
	private static final AtomicLong NEXT_SEQUENCE_ID = new AtomicLong();
	private static final AtomicLong NEXT_EMERGENCY_RECALL_ATTEMPT_ID = new AtomicLong();
	private static final double EMERGENCY_DESCENT_SPEED = -0.35D;
	private static final float EMERGENCY_FALL_DISTANCE = 3.0F;
	private static final double EMERGENCY_RECALL_INITIAL_SPEED = RecallApproachProfile.MAX_SPEED;
	private static final long B_REQUEST_COOLDOWN_TICKS = 5L;
	private static final Map<UUID, CruiseState> STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_B_REQUEST_TICKS = new ConcurrentHashMap<>();
	private static final Map<UUID, PlatformRecallSnapshot> PLATFORM_RECALL_SNAPSHOTS = new ConcurrentHashMap<>();
	private static final Set<UUID> SPAWNING_CARRIERS = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> MIGRATING_OWNERS = ConcurrentHashMap.newKeySet();
	private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
			EquipmentSlot.FEET,
			EquipmentSlot.LEGS,
			EquipmentSlot.CHEST,
			EquipmentSlot.HEAD
	);

	private ArmorCruiseController() {
	}

	/** 注册载体交互保护。服务端仍会再次校验实体标签。 */
	public static void initialize() {
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
				isCruiseCarrier(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
				InteractionResult.PASS);
	}

	/** B 键请求的唯一服务端入口。 */
	public static void handleRequest(ServerPlayer player) {
		if (!player.isAlive() || player.isSpectator()) {
			deny(player, "当前状态无法切换战甲巡航");
			return;
		}
		long requestTick = player.getServer().overworld().getGameTime();
		Long previousRequestTick = LAST_B_REQUEST_TICKS.get(player.getUUID());
		if (previousRequestTick != null && requestTick >= previousRequestTick
				&& requestTick - previousRequestTick < B_REQUEST_COOLDOWN_TICKS) {
			return;
		}
		LAST_B_REQUEST_TICKS.put(player.getUUID(), requestTick);
		if (ArmorAssemblyController.isAnimating(player)) {
			deny(player, "战甲部件正在移动，请稍候");
			return;
		}
		if (ArmorCoreController.hasActiveQuickSwitch(player)) {
			deny(player, "战甲快速切换事务进行中，B 不会启动第二个移动流程");
			return;
		}

		/*
		 * A complete physical suit always wins B-key arbitration. Resolve its exact suitId before
		 * any abstract core formation or owner-keyed carrier fallback can accidentally operate on
		 * a different suit.
		 */
		if (ModItems.hasFullSet(player)) {
			ChargingPlatformController.OwnershipResult ownership =
					ChargingPlatformController.ensureOwnedEquippedSet(player);
			if (!ownership.success()) {
				deny(player, ownership.message());
				return;
			}
			WornDeploymentResult deployed = deployWornSuit(player, ownership.suitId());
			if (!deployed.success()) deny(player, deployed.message());
			return;
		}

		// A partial/local loadout is an assembly request, not a remote formation request. This
		// lets boots-first and step-equip flows complete the exact same suit without producing the
		// misleading "clear all four armor slots" response from a platform recall.
		if (!allArmorSlotsEmpty(player)) {
			ArmorAssemblyController.handleRequest(player);
			return;
		}

		/*
		 * Empty slots no longer mean "recall the existing carrier immediately". If another
		 * complete physical suit is already local, wear it first and leave every carrier in
		 * place. The core panel remains the explicit way to execute a configured formation.
		 */
		Set<UUID> carrierSuitIds = activeCarrierSuitIds(player);
		if (!carrierSuitIds.isEmpty()
				&& ArmorAssemblyController.tryHandleCompleteInventorySet(
						player, carrierSuitIds)
				== ArmorAssemblyController.LocalAssemblyResult.HANDLED) {
			return;
		}

		// Only four genuinely empty armor slots may execute the configured recall/escort plan.
		ArmorCoreController.BKeyPlanResult earlyCorePlan =
				ArmorCoreController.executeConfiguredRecallPlanForB(player);
		if (earlyCorePlan == ArmorCoreController.BKeyPlanResult.HANDLED) return;

		CruiseState state = STATES.get(player.getUUID());
		if (state == null) {
			ArmorCruiseSavedData.Locator locator = ArmorCruiseSavedData.find(player.getServer(), player.getUUID());
			if (locator != null) {
				restoreIndexedCarrier(player.getServer(), locator, player);
				state = STATES.get(player.getUUID());
				if (state == null) {
					deny(player, "检测到尚未完成恢复的战甲载体；为防重复创建，本次操作已拒绝");
					return;
				}
			}
		}
		if (state != null) {
			switch (state.phase) {
				case CRUISING -> beginRecallOrPhasing(player, state);
				case DEPLOYING -> {
					if (isDangerousFall(player)) {
						beginRecallOrPhasing(player, state);
					} else {
						deny(player, "战甲正在整体展开并脱离，请稍候");
					}
				}
				case PHASING, RECALLING, DOCKING -> {
					if (isDangerousFall(player)) {
						promoteEmergencyRecall(player, state);
					} else {
						deny(player, "战甲已经在召回或着装途中");
					}
				}
				case OWNER_DOWN -> {
					if (!allArmorSlotsEmpty(player)) {
						deny(player, "战甲正在待机；清空四个装甲槽后会自动召回");
					} else {
						beginRecallOrPhasing(player, state);
					}
				}
				case IDLE -> deny(player, "战甲状态正在清理，请稍候");
			}
			return;
		}
		SuitRuntimeSnapshot runtimeSnapshot = SuitStateResolver.resolve(player);

		SuitRuntimeSnapshot.SuitState blockedState = runtimeSnapshot.suits().stream()
				.filter(candidate -> candidate.transactionTargetSuitId() != null
						|| hasBlockingRuntimeConflict(candidate))
				.findFirst().orElse(null);
		if (blockedState != null) {
			deny(player, blockedState.transactionTargetSuitId() != null
					? "战甲快速切换事务尚未完成，B 键不会重复启动另一笔移动"
					: "检测到战甲位置或身份冲突，已锁定 B 键以防止复制或遗留部件");
			return;
		}

		if (ChargingPlatformController.hasRecallableSuit(player)) {
			deny(player, "平台无法定位主人：请先激活装甲核心、绑定平台并设置主甲");
			return;
		}


		// 未完整穿戴时从背包原子收集完整套装并进入 36 tick 展示型高速着甲。
		ArmorAssemblyController.handleRequest(player);
	}

	private static Set<UUID> activeCarrierSuitIds(ServerPlayer player) {
		Set<UUID> suitIds = new HashSet<>();
		runtimeView(player).map(CruiseRuntimeView::suitId)
				.ifPresent(suitId -> {
					if (suitId != null) suitIds.add(suitId);
				});
		for (ArmorCoreController.CoreBindingRuntimeView binding :
				ArmorCoreController.runtimeView(player).bindings()) {
			if (binding.suitId() != null
					&& ArmorEscortController.hasRecoverableSuit(
							player.getServer(), binding.suitId())) {
				suitIds.add(binding.suitId());
			}
		}
		return Set.copyOf(suitIds);
	}

	/** 核心菜单与普通 B 共用的唯一主战甲平台召回入口。 */
	public static boolean recallPlatformSuit(ServerPlayer player, UUID suitId) {
		if (suitId == null) {
			deny(player, "缺少有效的战甲导航核心与主战甲绑定");
			return false;
		}
		if (ArmorAssemblyController.isAnimating(player)) {
			deny(player, "战甲组装状态尚未结束");
			return false;
		}
		if (ArmorCoreController.hasActiveQuickSwitch(player)) {
			deny(player, "战甲快速切换事务进行中，不能同时从平台召回主甲");
			return false;
		}
		ChargingPlatformController.RecallResult result = ChargingPlatformController.tryRecallToPlayer(player, suitId);
		if (!result.success()) {
			deny(player, result.message());
			return false;
		}
		int bootArrivalTick = result.displayMode() == ChargingPlatformDisplayMode.ASSEMBLED
				? ArmorPlatformRecallPayload.ASSEMBLED_ARRIVAL_TICK
				: ArmorPlatformRecallPayload.PARTS_ARRIVAL_TICK;
		if (!ArmorAssemblyController.beginAssemblyForEquippedSet(player, bootArrivalTick)) {
			boolean rolledBack = ChargingPlatformController.rollbackRecallToPlatform(player, result);
			deny(player, rolledBack
					? "着装状态冲突，战甲已安全返回充能平台"
					: "战甲已召回，但着装动画启动失败");
			return false;
		}
		FlightPhysics.applyPreBootFallArrest(player);
		player.hurtMarked = true;
		broadcastPlatformRecall(player, result);
		player.displayClientMessage(Component.literal("主战甲已从充能平台高速召回"), true);
		return true;
	}

	/** 查询该玩家是否仍有 owner 级独立巡航载体；调用方还必须按 suitId 判断关联性。 */
	public static boolean hasActiveCruise(ServerPlayer player) {
		return STATES.containsKey(player.getUUID())
				|| ArmorCruiseSavedData.find(player.getServer(), player.getUUID()) != null;
	}

	/**
	 * Exact server-authoritative deployment entry for B and future suit-panel actions.
	 *
	 * <p>The requested suit must be the complete suit physically worn by {@code player}. With no
	 * existing owner carrier it becomes the primary independent cruise suit. If a different stable
	 * independent suit already exists, this suit is atomically released into the suit-keyed escort
	 * controller and the existing carrier is left untouched.</p>
	 */
	public static WornDeploymentResult deployWornSuit(ServerPlayer player, UUID requestedSuitId) {
		if (player == null || requestedSuitId == null
				|| !player.isAlive() || player.isSpectator()) {
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.CONTINUE_CORE_PLAN,
					"当前玩家无法释放战甲");
		}
		if (ArmorAssemblyController.isAnimating(player)
				|| ArmorCoreController.hasActiveQuickSwitch(player)) {
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.REJECT_UNVERIFIED_INDEPENDENT_STATE,
					"玩家装甲槽已有移动事务");
		}
		if (!ModItems.hasFullSet(player)) {
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.CONTINUE_CORE_PLAN,
					"需要完整穿戴同一套战甲");
		}

		ChargingPlatformController.OwnershipResult ownership =
				ChargingPlatformController.ensureOwnedEquippedSet(player);
		if (!ownership.success()) {
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.REJECT_DUPLICATE_PHYSICAL_STATE,
					ownership.message());
		}
		if (!requestedSuitId.equals(ownership.suitId())) {
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.REJECT_DUPLICATE_PHYSICAL_STATE,
					"请求的战甲与当前实际穿戴 suitId 不一致");
		}

		// Membership is captured while all four physical identities are still available.
		ArmorCoreController.associateWornSuitForDeployment(player, requestedSuitId);
		CruiseRuntimeView independent = runtimeView(player).orElse(null);
		WornSuitDeploymentPolicy.Route route = WornSuitDeploymentPolicy.decide(
				new WornSuitDeploymentPolicy.Evidence(
						true,
						requestedSuitId,
						independent != null,
						independent == null ? null : independent.suitId(),
						independent != null));
		switch (route) {
			case DEPLOY_AS_INDEPENDENT -> {
				return deployFromPlayer(player, requestedSuitId);
			}
			case DEPLOY_AS_ESCORT -> {
				ArmorCoreController.CoreBindingRuntimeView home =
						coreHomeBinding(player, requestedSuitId);
				ArmorEscortController.SwitchDeploymentResult result =
						ArmorEscortController.deployWornSuit(
								player, requestedSuitId,
								home == null ? null : home.platformId(),
								home == null ? null : home.dimension(),
								home == null ? null : home.pos());
				if (!result.success()) {
					return WornDeploymentResult.failed(route, result.message());
				}
				player.displayClientMessage(Component.literal(
						"当前战甲已脱离穿戴并加入护航巡航；原独立巡航甲保持不变"), true);
				return WornDeploymentResult.started(route);
			}
			case REJECT_DUPLICATE_PHYSICAL_STATE -> {
				return WornDeploymentResult.failed(route,
						"同一 suitId 同时出现在玩家和巡航索引中，已拒绝移动以防复制");
			}
			case REJECT_UNVERIFIED_INDEPENDENT_STATE -> {
				return WornDeploymentResult.failed(route,
						"已有独立巡航记录无法完成物理隔离校验，已拒绝移动当前战甲");
			}
			case CONTINUE_CORE_PLAN -> {
				return WornDeploymentResult.failed(route,
						"当前穿戴战甲身份不完整，不能执行实体释放");
			}
		}
		throw new IllegalStateException("Unhandled worn-suit deployment route " + route);
	}

	private static ArmorCoreController.CoreBindingRuntimeView coreHomeBinding(
			ServerPlayer player, UUID suitId) {
		return ArmorCoreController.runtimeView(player).bindings().stream()
				.filter(binding -> suitId.equals(binding.suitId()))
				.filter(binding -> binding.platformId() != null
						&& binding.dimension() != null && binding.pos() != null)
				.findFirst().orElse(null);
	}

	/** 独立巡航状态机及其持久定位器的不可变只读投影。 */
	public static Optional<CruiseRuntimeView> runtimeView(ServerPlayer player) {
		if (player == null) return Optional.empty();
		CruiseState state = STATES.get(player.getUUID());
		if (state != null && carrierIsUsable(state.carrier)) {
			ResourceKey<Level> dimension = state.carrier.level().dimension();
			return Optional.of(new CruiseRuntimeView(carrierSuitId(state.carrier).orElse(null),
					state.phase, dimension, state.carrier.position(), state.carrier.getUUID(),
					state.sequenceId, state.phaseStartGameTime, true));
		}
		ArmorCruiseSavedData.Locator locator =
				ArmorCruiseSavedData.find(player.getServer(), player.getUUID());
		if (locator == null) return Optional.empty();
		return Optional.of(new CruiseRuntimeView(locator.suitId(), locator.phase(), locator.dimension(),
				locator.anchor(), locator.carrierId(), locator.sequenceId(), 0L, false));
	}

	public record CruiseRuntimeView(UUID suitId, ArmorCruisePhase phase,
			ResourceKey<Level> dimension, Vec3 position, UUID carrierId, long sequenceId,
			long phaseStartGameTime, boolean loaded) {
	}

	public static boolean isRecallableSuit(ServerPlayer player, UUID suitId) {
		if (player == null || suitId == null) return false;
		CruiseState state = findOrRestoreFormationState(player);
		return state != null && (state.phase == ArmorCruisePhase.CRUISING
				|| state.phase == ArmorCruisePhase.OWNER_DOWN)
				&& carrierIsUsable(state.carrier)
				&& carrierSuitId(state.carrier).filter(suitId::equals).isPresent();
	}

	/** Starts a requested independent-carrier recall without falling back to any other suit. */
	public static RecallCommandResult recallActiveSuitForSwitch(ServerPlayer player, UUID suitId) {
		return recallIndependentSuit(player, suitId);
	}

	/** Core formation entry; restores a persisted carrier locator before issuing one idempotent recall. */
	public static RecallCommandResult recallActiveSuitForFormation(ServerPlayer player, UUID suitId) {
		return recallIndependentSuit(player, suitId);
	}

	private static RecallCommandResult recallIndependentSuit(ServerPlayer player, UUID suitId) {
		CruiseState state = findOrRestoreFormationState(player);
		if (state == null || suitId == null
				|| carrierSuitId(state.carrier).filter(suitId::equals).isEmpty()) {
			return RecallCommandResult.failed("Requested suit is not the active independent carrier");
		}
		if (state.phase != ArmorCruisePhase.CRUISING && state.phase != ArmorCruisePhase.OWNER_DOWN) {
			return RecallCommandResult.failed("Independent armor is already changing state");
		}
		if (!allArmorSlotsEmpty(player)) {
			return RecallCommandResult.failed("Armor slots must be empty before carrier recall");
		}
		beginRecallOrPhasing(player, state);
		return RecallCommandResult.started();
	}

	/**
	 * Converts a stable independent carrier into an escort state without touching its ItemStacks.
	 * On any adoption failure the exact CruiseState is restored before returning.
	 */
	public static RecallCommandResult handoffActiveSuitToEscort(ServerPlayer player, UUID suitId,
			UUID homePlatformId, ResourceKey<Level> homeDimension, BlockPos homePos) {
		CruiseState state = findOrRestoreFormationState(player);
		if (state == null || suitId == null
				|| carrierSuitId(state.carrier).filter(suitId::equals).isEmpty()) {
			return RecallCommandResult.failed("目标战甲不是活动独立巡航载体");
		}
		if (state.phase != ArmorCruisePhase.CRUISING || !carrierIsUsable(state.carrier)) {
			return RecallCommandResult.failed("独立巡航战甲仍在过渡，暂不能接入护航");
		}
		if (!STATES.remove(player.getUUID(), state)) {
			return RecallCommandResult.failed("独立巡航状态已变化，请稍后重试");
		}
		ArmorEscortController.EscortCommandResult adopted =
				ArmorEscortController.adoptIndependentCarrier(player, suitId, state.carrier,
						homePlatformId, homeDimension, homePos);
		if (!adopted.success()) {
			if (STATES.putIfAbsent(player.getUUID(), state) != null) {
				FlightArmorMod.LOGGER.error("独立巡航转护航回滚状态冲突 owner={} suit={} carrier={}",
						player.getUUID(), suitId, state.carrier.getUUID());
			}
			return RecallCommandResult.failed(adopted.message());
		}
		releaseCarrierTicket(state);
		ArmorCruiseCombatController.cancelByOwnerId(player.getUUID());
		ArmorCruiseSavedData.removeIfMatches(player.getServer(), player.getUUID(),
				state.carrier.getUUID(), state.sequenceId);
		broadcast(player.getServer(), idlePayload(player.getUUID(), state));
		return RecallCommandResult.started();
	}

	/**
	 * Sends the active independent carrier directly to its bound platform without equipping it on
	 * the owner first. Controller ownership changes atomically while the physical carrier and every
	 * ItemStack remain unchanged.
	 */
	public static RecallCommandResult returnActiveSuitToPlatform(ServerPlayer player, UUID suitId,
			UUID platformId, ResourceKey<Level> dimension, BlockPos pos) {
		if (player == null || suitId == null || platformId == null || dimension == null || pos == null) {
			return RecallCommandResult.failed("独立巡航返台命令数据不完整");
		}
		if (ArmorEscortController.isReturningTo(player, suitId, platformId, dimension, pos)) {
			return RecallCommandResult.started();
		}

		CruiseState state = findOrRestoreFormationState(player);
		if (state == null) {
			return RecallCommandResult.failed("目标战甲当前不是活动独立巡航载体");
		}
		ServerLevel home = player.getServer().getLevel(dimension);
		ChargingPlatformBlockEntity platform = null;
		if (home != null) {
			home.getChunkAt(pos);
			if (home.getBlockEntity(pos) instanceof ChargingPlatformBlockEntity observed) {
				platform = observed;
			}
		}
		boolean platformMatches = platform != null && platform.getPlatformId().equals(platformId);
		boolean platformEmpty = platformMatches && platform.isEmpty()
				&& platform.getOwnerUuid().isEmpty() && platform.getSuitId().isEmpty();
		IndependentCruiseReturnHandoff.Admission admission =
				IndependentCruiseReturnHandoff.decide(new IndependentCruiseReturnHandoff.Evidence(
						false,
						state.ownerId.equals(player.getUUID()),
						carrierSuitId(state.carrier).filter(suitId::equals).isPresent(),
						carrierIsUsable(state.carrier) && state.carrier.isAlive()
								&& carrierHasFullSet(state.carrier),
						state.phase == ArmorCruisePhase.CRUISING
								|| state.phase == ArmorCruisePhase.OWNER_DOWN,
						ArmorEscortController.isActive(suitId),
						home != null,
						platformMatches,
						platformEmpty));
		if (admission != IndependentCruiseReturnHandoff.Admission.START) {
			return RecallCommandResult.failed(returnHandoffFailure(admission));
		}

		ChargingPlatformDisplayMode displayMode = platform.getDisplayMode();
		if (!STATES.remove(player.getUUID(), state)) {
			return RecallCommandResult.failed("独立巡航状态已变化，请稍后重试");
		}
		ArmorEscortController.EscortCommandResult adopted =
				ArmorEscortController.adoptIndependentCarrierForReturn(
						player, suitId, state.carrier, platformId, dimension, pos, displayMode);
		if (!adopted.success()) {
			if (STATES.putIfAbsent(player.getUUID(), state) == null) {
				updateCarrierTicket(state);
				syncLocator(state);
				broadcast(player.getServer(), state.toPayload(player.getUUID()));
			} else {
				FlightArmorMod.LOGGER.error(
						"独立巡航返台回滚状态冲突 owner={} suit={} carrier={}",
						player.getUUID(), suitId, state.carrier.getUUID());
			}
			return RecallCommandResult.failed(adopted.message());
		}

		// The return controller acquired its ticket before this independent ticket is released.
		releaseCarrierTicket(state);
		ArmorCruiseCombatController.cancel(player, "return_platform_handoff");
		ArmorCruiseCombatController.cancelByOwnerId(player.getUUID());
		ArmorCruiseSavedData.removeIfMatches(player.getServer(), player.getUUID(),
				state.carrier.getUUID(), state.sequenceId);
		broadcast(player.getServer(), idlePayload(player.getUUID(), state));
		return RecallCommandResult.started();
	}

	private static String returnHandoffFailure(IndependentCruiseReturnHandoff.Admission admission) {
		return switch (admission) {
			case ALREADY_RETURNING -> "目标战甲已在返回同一平台";
			case OWNER_MISMATCH -> "独立巡航载体不属于当前玩家";
			case SUIT_MISMATCH -> "独立巡航载体与所选战甲不一致";
			case CARRIER_UNAVAILABLE -> "独立巡航载体缺失或部件不完整";
			case PHASE_BUSY -> "独立巡航战甲正在过渡，暂不能返台";
			case ESCORT_CONFLICT -> "该战甲已有护航或返航状态";
			case HOME_DIMENSION_UNAVAILABLE -> "绑定平台所在维度不可用";
			case PLATFORM_REPLACED -> "原绑定平台已不存在或实例已更换";
			case PLATFORM_OCCUPIED -> "原绑定平台已被占用";
			case START -> "独立巡航返台状态已变化";
		};
	}

	private static CruiseState findOrRestoreFormationState(ServerPlayer player) {
		if (player == null) return null;
		CruiseState state = STATES.get(player.getUUID());
		if (state != null) return state;
		ArmorCruiseSavedData.Locator locator =
				ArmorCruiseSavedData.find(player.getServer(), player.getUUID());
		if (locator == null) return null;
		restoreIndexedCarrier(player.getServer(), locator, player);
		return STATES.get(player.getUUID());
	}
	/** True while this owner's independent carrier still uniquely holds the requested suit. */
	public static boolean hasActiveSuit(ServerPlayer player, UUID suitId) {
		CruiseState state = player == null ? null : STATES.get(player.getUUID());
		return state != null && carrierSuitId(state.carrier).filter(suitId::equals).isPresent();
	}

	/** Quick-switch watchdog query for asynchronous visible recall, phasing, and docking. */
	public static boolean isRecallInProgressForSwitch(ServerPlayer player, UUID suitId) {
		CruiseState state = player == null ? null : STATES.get(player.getUUID());
		return state != null && carrierSuitId(state.carrier).filter(suitId::equals).isPresent()
				&& (state.phase == ArmorCruisePhase.RECALLING
				|| state.phase == ArmorCruisePhase.PHASING
				|| state.phase == ArmorCruisePhase.DOCKING);
	}

	/**
	 * Rolls a non-committed switch recall back to carrier cruise. The carrier retains every armor
	 * ItemStack, preventing a late docking commit from racing the outgoing-suit recovery.
	 */
	public static RecallCommandResult cancelRecallForSwitchFailure(ServerPlayer player, UUID suitId) {
		CruiseState state = player == null ? null : STATES.get(player.getUUID());
		if (state == null || carrierSuitId(state.carrier).filter(suitId::equals).isEmpty()) {
			return RecallCommandResult.failed("目标独立巡航战甲已不在活动状态");
		}
		if (state.phase == ArmorCruisePhase.CRUISING
				|| state.phase == ArmorCruisePhase.OWNER_DOWN) {
			return RecallCommandResult.started();
		}
		if (state.phase != ArmorCruisePhase.RECALLING
				&& state.phase != ArmorCruisePhase.PHASING
				&& state.phase != ArmorCruisePhase.DOCKING) {
			return RecallCommandResult.failed("目标独立战甲已进入不可撤销的状态");
		}
		resumeCruising(player, state, "目标战甲已撤销切换召回并恢复独立巡航");
		return RecallCommandResult.started();
	}


	/** 每个服务端 tick 更新独立巡航、远程召回和近身停靠。 */
	public static void tickServer(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		PLATFORM_RECALL_SNAPSHOTS.entrySet().removeIf(entry -> {
			long elapsed = now - entry.getValue().payload().startGameTime();
			return elapsed < 0L || elapsed > ArmorPlatformRecallPayload.EXPIRY_TICKS;
		});

		for (Map.Entry<UUID, CruiseState> entry : STATES.entrySet()) {
			CruiseState state = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				// 连接事件可能被其他模组/异常路径跳过；索引落盘后立即释放长期区块票据。
				suspendForOfflineOwner(state);
				continue;
			}

			if (state.needsOwnerSync) {
				state.needsOwnerSync = false;
				if (state.phase == ArmorCruisePhase.OWNER_DOWN && player.isAlive()) {
					state.respawnResumeGameTime = player.serverLevel().getGameTime() + 1L;
				}
				broadcast(server, state.toPayload(player.getUUID()));
			}
			if (!carrierIsUsable(state.carrier)) {
				if (isPersistedUnloaded(state.carrier)) {
					suspendUnloadedCarrier(player, state);
				} else {
					abortToOwner(player, state, "巡航载体异常，战甲已安全归还");
				}
				continue;
			}
			if (!player.isAlive()) {
				if (state.phase != ArmorCruisePhase.OWNER_DOWN) {
					handleDeath(player);
				}
				if (ArmorIntegrityController.isDisabled(state.carrier)) {
					ArmorCruiseCombatController.cancel(player, "integrity_disabled");
					ArmorIntegrityController.tickDisabledCarrier(state.carrier);
					updateCarrierTicket(state);
				} else {
					tickOwnerDown(state, player.serverLevel().getGameTime());
				}
				continue;
			}
			if (ArmorIntegrityController.isDisabled(state.carrier)
					&& (state.phase == ArmorCruisePhase.CRUISING
					|| state.phase == ArmorCruisePhase.OWNER_DOWN)) {
				ArmorCruiseCombatController.cancel(player, "integrity_disabled");
				ArmorIntegrityController.tickDisabledCarrier(state.carrier);
				updateCarrierTicket(state);
				continue;
			}
			if (ArmorIntegrityController.isDisabled(state.carrier)
					&& state.phase == ArmorCruisePhase.DEPLOYING) {
				ArmorIntegrityController.tickDisabledCarrier(state.carrier);
				tickDeploying(player, state, player.serverLevel().getGameTime());
				continue;
			}
			switch (state.phase) {
				case DEPLOYING -> tickDeploying(player, state, player.serverLevel().getGameTime());
				case CRUISING -> tickCruising(player, state, player.serverLevel().getGameTime());
				case RECALLING -> tickRecalling(player, state);
				case PHASING -> tickPhasing(player, state);
				case OWNER_DOWN -> tickOwnerDown(player, state);
				case DOCKING -> tickDocking(player, state);
				case IDLE -> abortToOwner(player, state, "巡航状态异常，战甲已安全归还");
			}
		}
	}

	/** 新追踪者进入玩家视野时补发巡航快照。 */
	public static void syncStateTo(ServerPlayer trackedPlayer, ServerPlayer recipient) {
		CruiseState state = STATES.get(trackedPlayer.getUUID());
		if (state != null) {
			send(recipient, state.toPayload(trackedPlayer.getUUID()));
		}
	}
	public static void syncPlatformRecallTo(ServerPlayer trackedPlayer, ServerPlayer recipient) {
		PlatformRecallSnapshot snapshot = PLATFORM_RECALL_SNAPSHOTS.get(trackedPlayer.getUUID());
		if (snapshot == null) return;
		ArmorPlatformRecallPayload base = snapshot.payload();
		long elapsed = Math.max(0L, trackedPlayer.serverLevel().getGameTime() - base.startGameTime());
		boolean wrongDimension = !trackedPlayer.level().dimension().location()
				.equals(snapshot.targetDimension());
		if (wrongDimension || elapsed > ArmorPlatformRecallPayload.EXPIRY_TICKS) {
			PLATFORM_RECALL_SNAPSHOTS.remove(trackedPlayer.getUUID(), snapshot);
			return;
		}
		int animationOffset = (int) Math.min(
				ArmorPlatformRecallPayload.DURATION_TICKS, elapsed);
		sendPlatformRecall(recipient, new ArmorPlatformRecallPayload(
				base.playerId(), base.recallSequence(), base.originDimension(), base.origin(),
				base.displayMode(), base.startGameTime(), animationOffset));
	}



	/** 观察者开始追踪远离所有者的载体时，按载体身份补发当前阶段。 */
	public static void syncCarrierStateTo(Entity carrier, ServerPlayer recipient) {
		if (!isCruiseCarrier(carrier)) {
			return;
		}
		ownerIdOf((ArmorStand) carrier).ifPresent(ownerId -> {
			CruiseState state = STATES.get(ownerId);
			if (state != null && state.carrier.getUUID().equals(carrier.getUUID())) {
				send(recipient, state.toPayload(ownerId));
			}
		});
	}

	/** 玩家加入服务器时低频补发所有活动巡航快照，避免远距载体阶段缺失。 */
	public static void syncAllTo(ServerPlayer recipient) {
		for (CruiseState state : STATES.values()) {
			send(recipient, state.toPayload(state.ownerId));
		}
	}

	/** 玩家正常断线前把物品放回可保存的玩家数据；死亡中的载体继续留在世界中等待重生。 */
	public static void handleDisconnect(ServerPlayer player) {
		LAST_B_REQUEST_TICKS.remove(player.getUUID());
		CruiseState state = STATES.get(player.getUUID());
		if (state == null) {
			return;
		}
		if (state.phase == ArmorCruisePhase.OWNER_DOWN || !player.isAlive()) {
			suspendForOfflineOwner(state);
		} else {
			abortToOwner(player, state, null);
			if (STATES.get(player.getUUID()) == state) {
				suspendForOfflineOwner(state);
			}
		}
	}

	/** JOIN 时按持久索引精准加载载体所在区块，并把死亡待机召回排到下一服务端 tick。 */
	public static void handleJoin(ServerPlayer player) {
		CruiseState present = STATES.get(player.getUUID());
		if (present != null) {
			present.needsOwnerSync = true;
			if (present.phase == ArmorCruisePhase.OWNER_DOWN) {
				present.respawnResumeGameTime = player.serverLevel().getGameTime() + 1L;
			}
			return;
		}
		ArmorCruiseSavedData.Locator locator = ArmorCruiseSavedData.find(player.getServer(), player.getUUID());
		if (locator != null) {
			restoreIndexedCarrier(player.getServer(), locator, player);
		}
	}

	/** 玩家换维度时保留原载体与四件物品，改走折跃接入。 */
	public static void handleDimensionChange(ServerPlayer player) {
		CruiseState state = STATES.get(player.getUUID());
		if (state == null || !player.isAlive()) {
			return;
		}
		if (state.phase == ArmorCruisePhase.OWNER_DOWN) {
			state.respawnResumeGameTime = Math.max(state.respawnResumeGameTime,
					player.serverLevel().getGameTime() + 1L);
			return;
		}
		beginPhasing(player, state, "玩家维度变化");
		if (STATES.get(player.getUUID()) == state && state.emergencyRecallAttemptId != 0L) {
			FlightController.tryEngageEmergencyRecallHover(
					player, state.sequenceId, state.emergencyRecallAttemptId);
		}
	}

	/** 玩家死亡时绝不把战甲塞入死亡中的物品栏，也不生成掉落物。 */
	public static void handleDeath(ServerPlayer player) {
		CruiseState state = STATES.get(player.getUUID());
		if (state == null || state.phase == ArmorCruisePhase.OWNER_DOWN) {
			return;
		}
		if (!carrierIsUsable(state.carrier)) {
			FlightArmorMod.LOGGER.error("玩家 {} 死亡时巡航载体已移除（{}）；为防复制未从旧实体引用提取物品",
					player.getUUID(), state.carrier == null ? null : state.carrier.getRemovalReason());
			return;
		}
		enterOwnerDown(player, state, "玩家死亡");
	}

	/**
	 * 在 ServerPlayer#die 的最前端接管死亡时仍穿在身上的完整战甲。
	 * 只有新载体已经成功加入世界后才清空玩家槽位；任何失败都保持原装备交给原版死亡流程。
	 */
	public static void captureWornSetForDeath(ServerPlayer player) {
		CruiseState active = STATES.get(player.getUUID());
		ArmorCruiseSavedData.Locator locator =
				ArmorCruiseSavedData.find(player.getServer(), player.getUUID());
		if (active == null) {
			if (locator != null) {
				restoreIndexedCarrier(player.getServer(), locator, player);
				active = STATES.get(player.getUUID());
				if (active == null) {
					FlightArmorMod.LOGGER.error(
							"死亡时 owner={} 尚有未验证的持久载体 {}；保留索引并仅尝试接管另一套穿戴甲",
							player.getUUID(), locator.carrierId());
				}
			}
		}

		/*
		 * The owner-keyed carrier may already hold suit A while the player physically wears suit B.
		 * Preserve B first on its own suit-keyed passive carrier; then mark A owner-down. The two
		 * transfers never share controller state or ItemStacks.
		 */
		if ((active != null || locator != null) && ModItems.hasFullSet(player)) {
			ChargingPlatformController.OwnershipResult worn =
					ChargingPlatformController.ensureOwnedEquippedSet(player);
			if (!worn.success()) {
				FlightArmorMod.LOGGER.error(
						"Secondary death rescue identity admission failed owner={} reason={}",
						player.getUUID(), worn.message());
			} else {
				UUID independentSuitId = active != null
						? carrierSuitId(active.carrier).orElse(null)
						: locator.suitId();
				if (independentSuitId != null && independentSuitId.equals(worn.suitId())) {
					FlightArmorMod.LOGGER.error(
							"Death rescue found duplicate physical suit owner={} suit={}; no item mutation performed",
							player.getUUID(), worn.suitId());
				} else {
					ArmorCoreController.associateWornSuitForDeployment(player, worn.suitId());
					ArmorCoreController.CoreBindingRuntimeView home =
							coreHomeBinding(player, worn.suitId());
					ArmorEscortController.SwitchDeploymentResult rescued =
							ArmorEscortController.captureWornSuitForDeath(
									player, worn.suitId(),
									home == null ? null : home.platformId(),
									home == null ? null : home.dimension(),
									home == null ? null : home.pos());
					if (rescued.success()) {
						FlightArmorMod.LOGGER.info(
								"Secondary worn suit captured before death owner={} suit={}",
								player.getUUID(), worn.suitId());
					} else {
						FlightArmorMod.LOGGER.error(
								"Secondary death rescue failed owner={} suit={} reason={}",
								player.getUUID(), worn.suitId(), rescued.message());
					}
				}
			}
		}
		if (active != null) {
			handleDeath(player);
			return;
		}
		if (locator != null) {
			// The indexed carrier still owns its original items. Never create a replacement primary
			// while it is unavailable; any secondary worn suit was handled above.
			return;
		}
		if (!ModItems.hasFullSet(player)) {
			return;
		}

		ChargingPlatformController.OwnershipResult ownership =
				ChargingPlatformController.ensureOwnedEquippedSet(player);
		if (!ownership.success()) {
			FlightArmorMod.LOGGER.error("Death rescue ownership admission failed owner={} reason={}",
					player.getUUID(), ownership.message());
			return;
		}
		UUID ownerId = player.getUUID();
		UUID suitId = ownership.suitId();
		if (!ChargingPlatformController.ensureSuitCruising(player.getServer(), ownerId, suitId)) {
			FlightArmorMod.LOGGER.error("Death rescue ledger transition failed owner={} suit={}", ownerId, suitId);
			return;
		}
		double safeX = Mth.clamp(player.getX(), player.serverLevel().getWorldBorder().getMinX() + 1.0D,
				player.serverLevel().getWorldBorder().getMaxX() - 1.0D);
		double safeY = Mth.clamp(player.getY() + 0.28D,
				player.serverLevel().getMinBuildHeight() + 2.0D,
				player.serverLevel().getMaxBuildHeight() - 3.0D);
		double safeZ = Mth.clamp(player.getZ(), player.serverLevel().getWorldBorder().getMinZ() + 1.0D,
				player.serverLevel().getWorldBorder().getMaxZ() - 1.0D);
		Vec3 position = new Vec3(safeX, safeY, safeZ);
		ArmorStand carrier = new ArmorStand(player.level(), position.x, position.y, position.z);
		configureCarrier(carrier, ownerId);
		carrier.addTag(OWNER_DOWN_TAG);
		carrier.setYRot(player.getYRot());
		carrier.setYBodyRot(player.getYRot());
		SPAWNING_CARRIERS.add(carrier.getUUID());
		boolean spawned;
		try {
			spawned = player.serverLevel().addFreshEntity(carrier);
		} finally {
			SPAWNING_CARRIERS.remove(carrier.getUUID());
		}
		if (!spawned) {
			rollbackNewCruiseLedger(player, ownerId, suitId, "death_spawn_failed");
			FlightArmorMod.LOGGER.error("死亡战甲接管失败：载体无法生成 owner={}", ownerId);
			return;
		}

		Map<EquipmentSlot, ItemStack> originals = snapshotPlayerArmor(player);
		try {
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				ItemStack moved = player.getItemBySlot(slot);
				if (!ModItems.isExactFlightArmor(moved, slot)) {
					throw new IllegalStateException("死亡接管期间装备变化: " + slot);
				}
				player.setItemSlot(slot, ItemStack.EMPTY);
				carrier.setItemSlot(slot, moved);
			}
		} catch (RuntimeException exception) {
			rollbackDeployment(player, carrier, originals);
			rollbackNewCruiseLedger(player, ownerId, suitId, "death_transfer_failed");
			FlightArmorMod.LOGGER.error("死亡战甲接管事务已回滚 owner={}", ownerId, exception);
			return;
		}

		long now = player.serverLevel().getGameTime();
		CruiseState state = new CruiseState(ownerId, NEXT_SEQUENCE_ID.incrementAndGet(),
				ArmorCruisePhase.OWNER_DOWN, carrier, now, false, position);
		state.ownerDownAnchor = position;
		state.recoveringFromDeath = true;
		state.respawnResumeGameTime = Long.MAX_VALUE;
		CruiseState raced = STATES.putIfAbsent(ownerId, state);
		if (raced != null) {
			rollbackDeployment(player, carrier, originals);
			rollbackNewCruiseLedger(player, ownerId, suitId, "death_state_conflict");
			handleDeath(player);
			return;
		}
		FlightController.remove(player);
		updateCarrierTicket(state);
		player.getInventory().setChanged();
		player.containerMenu.broadcastChanges();
		broadcast(player.getServer(), state.toPayload(ownerId));
		FlightArmorMod.LOGGER.info("死亡前完整战甲已事务性接管 owner={} carrier={} position={}",
				ownerId, carrier.getUUID(), position);
	}

	/** 重生后自动判断使用真实飞行还是折跃接入；槽位被占用时继续原地待机。 */
	public static void handleRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
		CruiseState state = STATES.get(newPlayer.getUUID());
		if (state == null || state.phase != ArmorCruisePhase.OWNER_DOWN || !newPlayer.isAlive()) {
			return;
		}
		state.needsOwnerSync = false;
		state.respawnResumeGameTime = newPlayer.serverLevel().getGameTime() + 1L;
		broadcast(newPlayer.getServer(), state.toPayload(newPlayer.getUUID()));
		if (!allArmorSlotsEmpty(newPlayer)) {
			newPlayer.displayClientMessage(Component.literal("战甲已锁定重生信标；清空装甲槽后自动召回"), true);
		}
	}

	/** 正常服务停止前归还在线玩家；离线恢复载体仍保存在世界实体数据中。 */
	public static void handleServerStopping(MinecraftServer server) {
		for (Map.Entry<UUID, CruiseState> entry : new ArrayList<>(STATES.entrySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null && player.isAlive()
					&& entry.getValue().phase != ArmorCruisePhase.OWNER_DOWN) {
				abortToOwner(player, entry.getValue(), null);
				if (STATES.get(entry.getKey()) == entry.getValue()) {
					suspendForOfflineOwner(entry.getValue());
				}
			} else {
				suspendForOfflineOwner(entry.getValue());
			}
		}
		LAST_B_REQUEST_TICKS.clear();
	}

	/**
	 * 启动时扫描已加载区块中的持久载体。用于接管上次异常退出后由世界保存的唯一物品。
	 */
	public static void recoverLoadedCarriers(MinecraftServer server) {
		int recovered = 0;
		// 持久索引是唯一的载体身份来源，必须先于普通已加载实体扫描恢复。
		for (ArmorCruiseSavedData.Locator locator : ArmorCruiseSavedData.snapshot(server)) {
			if (restoreIndexedCarrier(server, locator,
					server.getPlayerList().getPlayer(locator.ownerId()))) {
				recovered++;
			}
		}
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof ArmorStand stand && recoverCarrier(stand, level)) {
					recovered++;
				}
			}
		}
		// 服务刚启动时通常没有在线所有者；验证实体存在后释放临时票据，由 JOIN 精准恢复。
		for (CruiseState state : new ArrayList<>(STATES.values())) {
			if (server.getPlayerList().getPlayer(state.ownerId) == null) {
				suspendForOfflineOwner(state);
			}
		}
		if (recovered > 0) {
			FlightArmorMod.LOGGER.info("已恢复 {} 个持久巡航载体", recovered);
		}
	}

	private static boolean restoreIndexedCarrier(MinecraftServer server,
			ArmorCruiseSavedData.Locator locator, ServerPlayer onlineOwner) {
		ServerLevel level = server.getLevel(locator.dimension());
		if (level == null) {
			FlightArmorMod.LOGGER.error("持久索引指向不存在的维度 owner={} dimension={}",
					locator.ownerId(), locator.dimension().location());
			return false;
		}
		ChunkPos chunk = new ChunkPos(locator.chunkX(), locator.chunkZ());
		CruiseState stateBeforeLoad = STATES.get(locator.ownerId());
		boolean hadSameTicket = stateBeforeLoad != null && stateBeforeLoad.ticketLevel == level
				&& chunk.equals(stateBeforeLoad.ticketChunk);
		if (!hadSameTicket) {
			level.getChunkSource().addRegionTicket(
					CARRIER_TICKET, chunk, CARRIER_TICKET_RADIUS, locator.ownerId());
		}
		level.getChunk(chunk.x, chunk.z);
		Entity entity = level.getEntity(locator.carrierId());
		if (entity instanceof ArmorStand transferred && ArmorEscortController.isEscortCarrier(transferred)) {
			UUID suitId = carrierSuitId(transferred).orElse(null);
			ArmorEscortSavedData.Locator escort = ArmorEscortSavedData.find(server, suitId);
			if (escort != null && escort.ownerId().equals(locator.ownerId())
					&& escort.carrierId().equals(locator.carrierId())) {
				ArmorCruiseSavedData.removeIfMatches(server, locator.ownerId(),
						locator.carrierId(), locator.sequenceId());
				removeRestoreTicketIfAdded(level, chunk, locator.ownerId(), hadSameTicket);
				FlightArmorMod.LOGGER.warn(
						"Removed stale independent locator after durable escort handoff owner={} carrier={} suit={}",
						locator.ownerId(), locator.carrierId(), suitId);
				return false;
			}
		}
		if (!(entity instanceof ArmorStand stand) || !isCruiseCarrier(stand)
				|| !carrierHasFullSet(stand) || !ownerIdOf(stand).filter(locator.ownerId()::equals).isPresent()) {
			removeRestoreTicketIfAdded(level, chunk, locator.ownerId(), hadSameTicket);
			FlightArmorMod.LOGGER.error("持久索引未在目标区块找到完整载体 owner={} carrier={} chunk={}",
					locator.ownerId(), locator.carrierId(), chunk);
			return false;
		}

		CruiseState state = STATES.get(locator.ownerId());
		if (state != null && (!state.carrier.getUUID().equals(locator.carrierId())
				|| !state.carrier.getUUID().equals(stand.getUUID()))) {
			removeRestoreTicketIfAdded(level, chunk, locator.ownerId(), hadSameTicket);
			FlightArmorMod.LOGGER.error(
					"恢复索引与活动状态载体不一致，已隔离且未改写状态 owner={} active={} indexed={}",
					locator.ownerId(), state.carrier.getUUID(), locator.carrierId());
			return false;
		}
		if (state == null && onlineOwner != null) {
			recoverCarrier(stand, level);
			state = STATES.get(locator.ownerId());
		}
		if (state == null) {
			removeRestoreTicketIfAdded(level, chunk, locator.ownerId(), hadSameTicket);
			return true;
		}
		ServerLevel oldTicketLevel = state.ticketLevel;
		ChunkPos oldTicketChunk = state.ticketChunk;
		state.ticketLevel = level;
		state.ticketChunk = chunk;
		if (oldTicketLevel != null && oldTicketChunk != null
				&& (oldTicketLevel != level || !oldTicketChunk.equals(chunk))) {
			oldTicketLevel.getChunkSource().removeRegionTicket(
					CARRIER_TICKET, oldTicketChunk, CARRIER_TICKET_RADIUS, state.ownerId);
		}
		state.patrolAnchor = locator.anchor();
		state.patrolLevel = level;
		if (locator.phase() == ArmorCruisePhase.OWNER_DOWN || stand.getTags().contains(OWNER_DOWN_TAG)) {
			state.phase = ArmorCruisePhase.OWNER_DOWN;
			state.ownerDownAnchor = locator.anchor();
			state.recoveringFromDeath = true;
			state.respawnResumeGameTime = onlineOwner == null ? Long.MAX_VALUE
					: onlineOwner.serverLevel().getGameTime() + 1L;
			stand.addTag(OWNER_DOWN_TAG);
		}
		state.needsOwnerSync = onlineOwner != null;
		syncLocator(state);
		return true;
	}

	private static void removeRestoreTicketIfAdded(ServerLevel level, ChunkPos chunk, UUID ownerId,
			boolean hadSameTicket) {
		if (!hadSameTicket) {
			level.getChunkSource().removeRegionTicket(
					CARRIER_TICKET, chunk, CARRIER_TICKET_RADIUS, ownerId);
		}
	}

	private static void suspendForOfflineOwner(CruiseState state) {
		if (!STATES.remove(state.ownerId, state)) {
			return;
		}
		ArmorCruiseCombatController.cancelByOwnerId(state.ownerId);
		syncLocator(state);
		releaseCarrierTicket(state);
		FlightArmorMod.LOGGER.info("离线所有者的战甲载体已持久化并释放区块票据 owner={} carrier={}",
				state.ownerId, state.carrier.getUUID());
	}

	/** 区块稍后加载时接管持久载体，补足启动阶段未加载区块的恢复路径。 */
	public static void handleEntityLoad(Entity entity, ServerLevel level) {
		if (entity instanceof ArmorStand stand && !SPAWNING_CARRIERS.contains(stand.getUUID())
				&& ownerIdOf(stand).map(owner -> !MIGRATING_OWNERS.contains(owner)).orElse(true)) {
			recoverCarrier(stand, level);
		}
	}

	public static boolean isCruiseCarrier(Entity entity) {
		return entity instanceof ArmorStand && entity.getTags().contains(CARRIER_TAG);
	}

	private static WornDeploymentResult deployFromPlayer(ServerPlayer player, UUID suitId) {
		UUID ownerId = player.getUUID();
		if (STATES.containsKey(ownerId)) {
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.REJECT_UNVERIFIED_INDEPENDENT_STATE,
					"巡航状态正在切换，请稍候");
		}
		if (ArmorCruiseSavedData.find(player.getServer(), ownerId) != null) {
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.REJECT_UNVERIFIED_INDEPENDENT_STATE,
					"检测到待恢复的旧战甲载体；为防重复部署，本次操作已拒绝");
		}

		ArmorCombatController.cancel(player, ArmorCombatDenyReason.CRUISE_ACTIVE);
		// 物品离开玩家装备槽前先撤销服务端飞行授权，避免残留一个 tick 的动力状态。
		FlightController.remove(player);
		Vec3 spawnPosition = deploymentPosition(player);
		ArmorStand carrier = new ArmorStand(player.level(), spawnPosition.x, spawnPosition.y, spawnPosition.z);
		configureCarrier(carrier, ownerId);
		carrier.setYRot(player.getYRot());
		carrier.setYBodyRot(player.getYRot());
		SPAWNING_CARRIERS.add(carrier.getUUID());
		boolean spawned;
		if (!ChargingPlatformController.ensureSuitCruising(player.getServer(), ownerId, suitId)) {
			carrier.discard();
			SPAWNING_CARRIERS.remove(carrier.getUUID());
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.DEPLOY_AS_INDEPENDENT,
					"\u6218\u7532\u4f4d\u7f6e\u8d26\u672c\u4e0d\u662f\u73a9\u5bb6\u643a\u5e26\u72b6\u6001\uff0c\u5df2\u62d2\u7edd\u90e8\u7f72");
		}

		try {
			spawned = player.serverLevel().addFreshEntity(carrier);
		} finally {
			SPAWNING_CARRIERS.remove(carrier.getUUID());
		}
		if (!spawned) {
			rollbackNewCruiseLedger(player, ownerId, suitId, "deploy_spawn_failed");
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.DEPLOY_AS_INDEPENDENT,
					"巡航载体生成失败，战甲未被移动");
		}

		Map<EquipmentSlot, ItemStack> originals = snapshotPlayerArmor(player);
		try {
			// 先清空来源槽，再把同一个 ItemStack 引用交给载体；全程不 copy、不创建副本。
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				ItemStack moved = player.getItemBySlot(slot);
				if (!ModItems.isExactFlightArmor(moved, slot)) {
					throw new IllegalStateException("部署期间玩家装备已变化: " + slot);
				}
				player.setItemSlot(slot, ItemStack.EMPTY);
				carrier.setItemSlot(slot, moved);
			}
		} catch (RuntimeException exception) {
			rollbackDeployment(player, carrier, originals);
			rollbackNewCruiseLedger(player, ownerId, suitId, "deploy_transfer_failed");
			FlightArmorMod.LOGGER.error("玩家 {} 的巡航部署事务已回滚", ownerId, exception);
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.DEPLOY_AS_INDEPENDENT,
					"巡航部署失败，战甲已回滚");
		}

		long startGameTime = player.serverLevel().getGameTime();
		CruiseState state = new CruiseState(
				ownerId,
				NEXT_SEQUENCE_ID.incrementAndGet(),
				ArmorCruisePhase.DEPLOYING,
				carrier,
				startGameTime,
				false,
				spawnPosition
		);
		CruiseState existing = STATES.putIfAbsent(ownerId, state);
		if (existing != null) {
			rollbackDeployment(player, carrier, originals);
			rollbackNewCruiseLedger(player, ownerId, suitId, "deploy_state_conflict");
			return WornDeploymentResult.failed(
					WornSuitDeploymentPolicy.Route.DEPLOY_AS_INDEPENDENT,
					"巡航状态冲突，战甲已回滚");
		}
		updateCarrierTicket(state);

		player.getInventory().setChanged();
		player.containerMenu.broadcastChanges();
		broadcast(player.getServer(), state.toPayload(ownerId));
		player.displayClientMessage(Component.literal("战甲已进入独立巡航模式，再按 B 召回"), true);
		return WornDeploymentResult.started(
				WornSuitDeploymentPolicy.Route.DEPLOY_AS_INDEPENDENT);
	}

	private static void beginRecallOrPhasing(ServerPlayer player, CruiseState state) {
		boolean emergency = isDangerousFall(player);
		if (!allArmorSlotsEmpty(player)) {
			deny(player, "召回前请清空四个装甲槽");
			return;
		}
		if (!carrierIsUsable(state.carrier)) {
			abortToOwner(player, state, "巡航载体异常，战甲已安全归还");
			return;
		}
		if (state.carrier.level() != player.level()
				|| state.carrier.position().distanceToSqr(player.position())
				> ArmorCruisePhase.DIRECT_RECALL_DISTANCE * ArmorCruisePhase.DIRECT_RECALL_DISTANCE) {
			beginPhasing(player, state, state.carrier.level() != player.level() ? "跨维度召回" : "远距离召回");
			if (emergency) promoteEmergencyRecall(player, state);
			return;
		}
		beginVisibleRecall(player, state, false);
		if (emergency) promoteEmergencyRecall(player, state);
	}

	private static boolean isDangerousFall(ServerPlayer player) {
		return player.isAlive() && !player.onGround() && !player.isPassenger()
				&& !player.isSleeping() && !player.isInWaterOrBubble() && !player.isInLava()
				&& (player.fallDistance >= EMERGENCY_FALL_DISTANCE
				|| player.getDeltaMovement().y <= EMERGENCY_DESCENT_SPEED);
	}

	/**
	 * 把当前召回尝试原地升级为救援模式。不会修改 phaseStart、载体位置或当前动画进度；
	 * attemptId 跨 RECALLING/PHASING/DOCKING 保持不变，用于拒绝旧自动悬停包。
	 */
	private static void promoteEmergencyRecall(ServerPlayer player, CruiseState state) {
		if (state.phase != ArmorCruisePhase.RECALLING
				&& state.phase != ArmorCruisePhase.PHASING
				&& state.phase != ArmorCruisePhase.DOCKING) {
			return;
		}
		if (state.phase == ArmorCruisePhase.DOCKING && state.bootHoverTriggered) {
			return;
		}
		if (!allArmorSlotsEmpty(player) || !carrierIsUsable(state.carrier)
				|| !carrierHasFullSet(state.carrier)) {
			return;
		}
		boolean newlyPromoted = state.emergencyRecallAttemptId == 0L;
		if (newlyPromoted) {
			long attemptId = NEXT_EMERGENCY_RECALL_ATTEMPT_ID.incrementAndGet();
			state.emergencyRecallAttemptId = attemptId == 0L
					? NEXT_EMERGENCY_RECALL_ATTEMPT_ID.incrementAndGet() : attemptId;
			state.recallSpeed = Math.max(state.recallSpeed, EMERGENCY_RECALL_INITIAL_SPEED);
			broadcast(player.getServer(), state.toPayload(player.getUUID()));
		}
		if (FlightController.tryEngageEmergencyRecallHover(
				player, state.sequenceId, state.emergencyRecallAttemptId)) {
			if (newlyPromoted) {
				player.displayClientMessage(Component.literal("紧急召回：反重力制动已接管，战甲高速返回"), true);
			}
		} else if (newlyPromoted) {
			state.emergencyRecallAttemptId = 0L;
			broadcast(player.getServer(), state.toPayload(player.getUUID()));
		}
	}

	private static void beginVisibleRecall(ServerPlayer player, CruiseState state, boolean afterPhasing) {
		ArmorCruiseCombatController.cancel(player, "recall_visible");
		ArmorCruisePhase previousPhase = state.phase;
		if (!state.recoveringFromDeath) {
			state.carrier.removeTag(OWNER_DOWN_TAG);
		}
		state.phase = ArmorCruisePhase.RECALLING;
		state.phaseStartGameTime = player.serverLevel().getGameTime();
		state.recallSpeed = state.emergencyRecallAttemptId != 0L
			? EMERGENCY_RECALL_INITIAL_SPEED
			: afterPhasing
				? RecallApproachProfile.PHASING_REENTRY_SPEED
				: RecallApproachProfile.INITIAL_SPEED;
		state.recallTicks = 0;
		state.dockingStartPosition = state.carrier.position();
		state.dockingStartYaw = state.carrier.getYRot();
		syncLocator(state);
		broadcast(player.getServer(), state.toPayload(player.getUUID()));
		FlightArmorMod.LOGGER.info("巡航召回切换 owner={} route={} distance={} carrier={}",
				player.getUUID(), previousPhase + "->RECALLING",
				String.format(java.util.Locale.ROOT, "%.2f", state.carrier.distanceTo(player)),
				state.carrier.getUUID());
		player.displayClientMessage(Component.literal("战甲正在飞回"), true);
	}

	/** 真实物品已经原子交给唯一载体；这 28 tick 只冻结巡航和武器，供客户端播放整甲脱离。 */
	private static void tickDeploying(ServerPlayer player, CruiseState state, long gameTime) {
		state.carrier.setDeltaMovement(Vec3.ZERO);
		updateCarrierTicket(state);
		long elapsed = Math.max(0L, gameTime - state.phaseStartGameTime);
		if (elapsed <= ArmorCruisePhase.DEPLOYMENT_HANDOFF_TICKS) {
			state.carrier.setYRot(player.getYRot());
			state.carrier.setYBodyRot(player.getYRot());
		}
		if (elapsed < ArmorCruisePhase.DEPLOYMENT_DURATION_TICKS) {
			return;
		}
		state.phase = ArmorCruisePhase.CRUISING;
		state.phaseStartGameTime = gameTime;
		state.patrolAnchor = state.carrier.position();
		state.ownerDownAnchor = state.patrolAnchor;
		state.patrolLevel = (ServerLevel) state.carrier.level();
		setCruisePose(state.carrier);
		syncLocator(state);
		broadcast(player.getServer(), state.toPayload(player.getUUID()));
	}

	private static void tickCruising(ServerPlayer player, CruiseState state, long gameTime) {
		CombatHandle handle = new CombatHandle(
				state.sequenceId, state.phase, state.carrier, state.patrolAnchor);
		boolean combatOwnsMovement =
				ArmorRemoteCommandController.tickIndependent(player, handle, gameTime);
		if (!combatOwnsMovement) {
			combatOwnsMovement = ArmorCruiseCombatController.tick(player, handle, gameTime);
		} else {
			ArmorCombatController.tickCruiseResources(player, handle.carrier());
		}
		if (!combatOwnsMovement) {
			Vec3 target = patrolTarget(state, gameTime);
			moveCarrier(state.carrier, target, patrolYaw(state, gameTime), false);
		}
		updateCarrierTicket(state);
	}

	private static void tickOwnerDown(ServerPlayer player, CruiseState state) {
		long now = player.serverLevel().getGameTime();
		tickOwnerDown(state, now);
		if (player.isAlive() && now >= state.respawnResumeGameTime && allArmorSlotsEmpty(player)) {
			beginRecallOrPhasing(player, state);
		}
	}

	private static void tickOwnerDown(CruiseState state, long gameTime) {
		double elapsed = Math.max(0L, gameTime - state.phaseStartGameTime);
		double angle = (state.sequenceId & 127L) * (Math.PI * 2.0D / 128.0D) + elapsed * 0.018D;
		Vec3 target = state.ownerDownAnchor.add(
				Math.cos(angle) * OWNER_DOWN_PATROL_RADIUS,
				Math.sin(angle * 1.4D) * 0.08D,
				Math.sin(angle) * OWNER_DOWN_PATROL_RADIUS);
		moveCarrier(state.carrier, target, (float) Math.toDegrees(angle), false);
		updateCarrierTicket(state);
	}

	private static void enterOwnerDown(ServerPlayer player, CruiseState state, String reason) {
		ArmorCruiseCombatController.cancel(player, "owner_down");
		FlightController.remove(player);
		if (state.phase == ArmorCruisePhase.OWNER_DOWN) {
			state.recoveringFromDeath = true;
			state.carrier.addTag(OWNER_DOWN_TAG);
			syncLocator(state);
			return;
		}
		state.phase = ArmorCruisePhase.OWNER_DOWN;
		state.phaseStartGameTime = player.serverLevel().getGameTime();
		state.ownerDownAnchor = state.carrier.position();
		state.patrolAnchor = state.ownerDownAnchor;
		state.recoveringFromDeath = true;
		state.respawnResumeGameTime = Long.MAX_VALUE;
		state.phasingMigrated = false;
		state.carrier.addTag(OWNER_DOWN_TAG);
		setCruisePose(state.carrier);
		updateCarrierTicket(state);
		syncLocator(state);
		broadcast(player.getServer(), state.toPayload(player.getUUID()));
		FlightArmorMod.LOGGER.info("巡航进入死亡待机 owner={} reason={} carrier={} dimension={} position={}",
				player.getUUID(), reason, state.carrier.getUUID(),
				state.carrier.level().dimension().location(), state.carrier.position());
	}

	private static void tickRecalling(ServerPlayer player, CruiseState state) {
		if (!allArmorSlotsEmpty(player)) {
			handleSlotConflict(player, state, "装甲槽在召回途中被占用，战甲已恢复独立巡航");
			return;
		}
		if (!carrierHasFullSet(state.carrier)) {
			abortToOwner(player, state, "巡航载体装备不完整，现有部件已安全归还");
			return;
		}

		if (state.carrier.level() != player.level()
				|| state.carrier.position().distanceToSqr(player.position())
				> ArmorCruisePhase.DIRECT_RECALL_DISTANCE * ArmorCruisePhase.DIRECT_RECALL_DISTANCE
				|| state.recallTicks >= ArmorCruisePhase.RECALL_TIMEOUT_TICKS) {
			beginPhasing(player, state, state.carrier.level() != player.level()
					? "召回途中跨维度" : state.recallTicks >= ArmorCruisePhase.RECALL_TIMEOUT_TICKS
					? "实体召回超时" : "召回距离超过上限");
			return;
		}

		// RECALLING 直接瞄准 DOCKING 的首个捕获位，避免先冲到玩家脚下再倒飞到身后。
		Vec3 target = dockingCaptureTarget(player);
		Vec3 delta = target.subtract(state.carrier.position());
		double distance = delta.length();
		state.recallSpeed = RecallApproachProfile.advanceSpeed(state.recallSpeed, distance);
		if (distance > 1.0E-5D) {
			double step = RecallApproachProfile.safeStep(distance, state.recallSpeed);
			state.carrier.setPos(state.carrier.position().add(delta.scale(step / distance)));
			// recallSpeed 必须表示真实权威位移，不能在近景仍保留被闭合限制截掉的高速虚假动势。
			state.recallSpeed = step;
			float flightYaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
			float hoverProgress = (float) RecallApproachProfile.hoverProgress(distance);
			float approachYaw = Mth.rotLerp(hoverProgress, flightYaw, player.getYRot());
			state.carrier.setYRot(Mth.rotLerp(0.32F, state.carrier.getYRot(), approachYaw));
			state.carrier.setYBodyRot(state.carrier.getYRot());
		}
		state.carrier.setDeltaMovement(Vec3.ZERO);
		state.recallTicks++;
		updateCarrierTicket(state);
		if (state.carrier.position().distanceToSqr(target)
				<= RecallApproachProfile.CAPTURE_TOLERANCE * RecallApproachProfile.CAPTURE_TOLERANCE) {
			beginDocking(player, state);
		}
	}

	/** 玩家背后上方的整甲捕获位；RECALLING 终点与 DOCKING 起点必须共用同一个定义。 */
	private static Vec3 dockingCaptureTarget(ServerPlayer player) {
		double yawRadians = Math.toRadians(player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
		return player.position()
				.subtract(forward.scale(RecallApproachProfile.CAPTURE_REAR_OFFSET))
				.add(0.0D, RecallApproachProfile.CAPTURE_HEIGHT_OFFSET, 0.0D);
	}

	private static void beginPhasing(ServerPlayer player, CruiseState state, String reason) {
		ArmorCruiseCombatController.cancel(player, "phasing");
		if (!carrierIsUsable(state.carrier)) {
			abortToOwner(player, state, "巡航载体异常，战甲已安全归还");
			return;
		}
		state.phaseBeforePhasing = state.phase;
		if (!state.recoveringFromDeath) {
			state.carrier.removeTag(OWNER_DOWN_TAG);
		}
		state.phase = ArmorCruisePhase.PHASING;
		state.phaseStartGameTime = player.serverLevel().getGameTime();
		state.phasingOrigin = state.carrier.position();
		state.phasingMigrated = false;
		state.phasingReentryPosition = Vec3.ZERO;
		state.recallSpeed = state.emergencyRecallAttemptId != 0L
				? EMERGENCY_RECALL_INITIAL_SPEED
				: RecallApproachProfile.INITIAL_SPEED;
		state.recallTicks = 0;
		syncLocator(state);
		broadcast(player.getServer(), state.toPayload(player.getUUID()));
		FlightArmorMod.LOGGER.info("巡航召回切换 owner={} route={}->PHASING reason={} carrier={} from={} to={}",
				player.getUUID(), state.phaseBeforePhasing, reason, state.carrier.getUUID(),
				state.carrier.level().dimension().location(), player.level().dimension().location());
		player.displayClientMessage(Component.literal("距离过远，战甲正在折跃接入"), true);
	}

	private static void tickPhasing(ServerPlayer player, CruiseState state) {
		if (!allArmorSlotsEmpty(player)) {
			handleSlotConflict(player, state, "装甲槽被占用，折跃已取消并进入独立巡航");
			return;
		}
		if (!carrierHasFullSet(state.carrier)) {
			abortToOwner(player, state, "折跃载体装备不完整，现有部件已安全归还");
			return;
		}

		long now = player.serverLevel().getGameTime();
		long elapsed = Math.max(0L, now - state.phaseStartGameTime);
		if (!state.phasingMigrated) {
			double p = Mth.clamp((double) elapsed / ArmorCruisePhase.PHASING_MIDPOINT_TICKS, 0.0D, 1.0D);
			double radius = (1.0D - p) * 0.34D;
			double angle = p * Math.PI * 4.0D;
			state.carrier.setPos(state.phasingOrigin.add(Math.cos(angle) * radius,
					Math.sin(Math.PI * p) * 0.18D, Math.sin(angle) * radius));
			state.carrier.setDeltaMovement(Vec3.ZERO);
			updateCarrierTicket(state);
			if (elapsed >= ArmorCruisePhase.PHASING_MIDPOINT_TICKS && migrateCarrier(player, state)) {
				state.phasingMigrated = true;
				// 保证迁移成功后一定展示完整的后半段展开，而不会因区块加载延迟直接跳过。
				state.phaseStartGameTime = now - ArmorCruisePhase.PHASING_MIDPOINT_TICKS;
				broadcast(player.getServer(), state.toPayload(player.getUUID()));
			}
			return;
		}

		if (state.carrier.level() != player.level()) {
			beginPhasing(player, state, "折跃接入期间目标维度再次变化");
			return;
		}
		double secondHalf = Mth.clamp((double) (elapsed - ArmorCruisePhase.PHASING_MIDPOINT_TICKS)
				/ ArmorCruisePhase.PHASING_MIDPOINT_TICKS, 0.0D, 1.0D);
		Vec3 visibleTarget = state.phasingReentryPosition.lerp(player.position(), secondHalf * 0.10D);
		state.carrier.setPos(visibleTarget.add(0.0D, Math.sin(secondHalf * Math.PI) * 0.22D, 0.0D));
		state.carrier.setDeltaMovement(Vec3.ZERO);
		state.carrier.setYRot(Mth.rotLerp(0.24F, state.carrier.getYRot(), player.getYRot()));
		state.carrier.setYBodyRot(state.carrier.getYRot());
		setPhasingPose(state.carrier, (float) secondHalf);
		updateCarrierTicket(state);
		if (elapsed >= ArmorCruisePhase.PHASING_DURATION_TICKS) {
			beginVisibleRecall(player, state, true);
		}
	}

	/**
	 * 在折跃中点事务性替换载体。目标区块票据和区块加载均先于新载体生成；
	 * 跨维度使用原版 changeDimension 原子重建，返回对象接管 state 后才移除旧票据。
	 */
	private static boolean migrateCarrier(ServerPlayer player, CruiseState state) {
		ArmorStand oldCarrier = state.carrier;
		if (!carrierIsUsable(oldCarrier) || !carrierHasFullSet(oldCarrier)) {
			return false;
		}
		ReentrySite site = prepareReentrySite(player, state);
		if (site == null) {
			FlightArmorMod.LOGGER.warn("折跃目的地暂不可用 owner={}，将在下一 tick 重试", player.getUUID());
			return false;
		}

		ServerLevel oldTicketLevel = state.ticketLevel;
		ChunkPos oldTicketChunk = state.ticketChunk;
		UUID oldCarrierId = oldCarrier.getUUID();
		ArmorStand newCarrier;
		if (oldCarrier.level() == site.level) {
			// 同维度远距召回无需复制实体，保留同一个载体和同一批物品，仅在已持票区块重定位。
			oldCarrier.setPos(site.position);
			oldCarrier.setDeltaMovement(Vec3.ZERO);
			newCarrier = oldCarrier;
		} else {
			// 原版维度迁移会序列化并重建完整实体，四个 ItemStack 不经过手工逐槽复制窗口。
			MIGRATING_OWNERS.add(state.ownerId);
			Entity changed = null;
			RuntimeException migrationFailure = null;
			try {
				changed = oldCarrier.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(
						site.level, site.position, Vec3.ZERO, player.getYRot(), 0.0F,
						net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
			} catch (RuntimeException exception) {
				migrationFailure = exception;
			} finally {
				MIGRATING_OWNERS.remove(state.ownerId);
			}
			if (!(changed instanceof ArmorStand)) {
				changed = findMigratedCarrier(site.level, state.ownerId, oldCarrierId);
			}
			if (!(changed instanceof ArmorStand changedStand)) {
				releasePreparedTicket(state, site);
				FlightArmorMod.LOGGER.error("原版折跃迁移未返回可接管载体 owner={} returned={}",
						player.getUUID(), changed, migrationFailure);
				return false;
			}
			newCarrier = changedStand;
			configureCarrier(newCarrier, state.ownerId);
			if (state.recoveringFromDeath) {
				// 原版迁移通常会保留 scoreboard tag；这里再次写入，封死死亡自返的崩溃恢复窗口。
				newCarrier.addTag(OWNER_DOWN_TAG);
			}
			if (migrationFailure != null) {
				FlightArmorMod.LOGGER.error("折跃抛出异常，但已接管目标维度中的唯一载体 owner={} carrier={}",
						player.getUUID(), newCarrier.getUUID(), migrationFailure);
			}
		}
		newCarrier.setYRot(player.getYRot());
		newCarrier.setYBodyRot(player.getYRot());
		state.carrier = newCarrier;
		state.ticketLevel = site.level;
		state.ticketChunk = site.chunk;
		state.phasingReentryPosition = site.position;
		state.patrolLevel = site.level;
		syncLocator(state);
		broadcast(player.getServer(), state.toPayload(player.getUUID()));

		if (oldTicketLevel != null && oldTicketChunk != null
				&& (oldTicketLevel != site.level || !oldTicketChunk.equals(site.chunk))) {
			oldTicketLevel.getChunkSource().removeRegionTicket(
					CARRIER_TICKET, oldTicketChunk, CARRIER_TICKET_RADIUS, state.ownerId);
		}
		if (!carrierHasFullSet(newCarrier)) {
			FlightArmorMod.LOGGER.error("折跃后的载体装备不完整，已保留新载体及索引等待安全恢复 owner={} carrier={}",
					player.getUUID(), newCarrier.getUUID());
			return false;
		}
		FlightArmorMod.LOGGER.info(
				"折跃载体迁移完成 owner={} oldCarrier={} newCarrier={} dimension={} reentryDistance={}",
				player.getUUID(), oldCarrierId, newCarrier.getUUID(), site.level.dimension().location(),
				String.format(java.util.Locale.ROOT, "%.2f", site.position.distanceTo(player.position())));
		return true;
	}

	private static ReentrySite prepareReentrySite(ServerPlayer player, CruiseState state) {
		ServerLevel level = player.serverLevel();
		double baseYaw = Math.toRadians(player.getYRot());
		double[] distances = {PHASING_REENTRY_DISTANCE, 20.0D, 28.0D};
		double[] offsets = {Math.PI, Math.PI * 0.75D, -Math.PI * 0.75D, Math.PI * 0.5D,
				-Math.PI * 0.5D, Math.PI * 0.25D, -Math.PI * 0.25D, 0.0D};
		double[] heightOffsets = {2.5D, 6.0D, 10.0D, -2.0D};
		for (double distance : distances) {
			for (double offset : offsets) {
				for (double heightOffset : heightOffsets) {
			double angle = baseYaw + offset;
			double y = Mth.clamp(player.getY() + heightOffset,
					level.getMinBuildHeight() + 2.0D, level.getMaxBuildHeight() - 3.0D);
			Vec3 position = new Vec3(player.getX() - Math.sin(angle) * distance, y,
					player.getZ() + Math.cos(angle) * distance);
			if (!level.getWorldBorder().isWithinBounds(position)) {
				continue;
			}
			ChunkPos chunk = new ChunkPos(BlockPos.containing(position));
			boolean sharesExisting = level == state.ticketLevel && chunk.equals(state.ticketChunk);
			if (!sharesExisting) {
				level.getChunkSource().addRegionTicket(
						CARRIER_TICKET, chunk, CARRIER_TICKET_RADIUS, state.ownerId);
			}
			// 同步取得区块，确保不会在尚未加载的目标区块中销毁源载体。
			level.getChunk(chunk.x, chunk.z);
			AABB bounds = new AABB(position.x - 0.35D, position.y, position.z - 0.35D,
					position.x + 0.35D, position.y + 2.05D, position.z + 0.35D);
			if (level.noCollision(bounds)) {
				return new ReentrySite(level, position, chunk, !sharesExisting);
			}
			if (!sharesExisting) {
				level.getChunkSource().removeRegionTicket(
						CARRIER_TICKET, chunk, CARRIER_TICKET_RADIUS, state.ownerId);
			}
				}
			}
		}
		return null;
	}

	private static void releasePreparedTicket(CruiseState state, ReentrySite site) {
		if (site.ticketAdded) {
			site.level.getChunkSource().removeRegionTicket(
					CARRIER_TICKET, site.chunk, CARRIER_TICKET_RADIUS, state.ownerId);
		}
	}

	private static ArmorStand findMigratedCarrier(ServerLevel level, UUID ownerId, UUID oldCarrierId) {
		ArmorStand match = null;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ArmorStand stand) || stand.getUUID().equals(oldCarrierId)
					|| !isCruiseCarrier(stand)
					|| !ownerIdOf(stand).filter(ownerId::equals).isPresent()) {
				continue;
			}
			if (match != null) {
				FlightArmorMod.LOGGER.error("折跃目标维度检测到多个候选载体 owner={} first={} second={}",
						ownerId, match.getUUID(), stand.getUUID());
				return null;
			}
			match = stand;
		}
		return match;
	}

	private static void beginDocking(ServerPlayer player, CruiseState state) {
		if (!allArmorSlotsEmpty(player)) {
			handleSlotConflict(player, state, "装甲槽在召回途中被占用，战甲已恢复独立巡航");
			return;
		}
		state.phase = ArmorCruisePhase.DOCKING;
		if (state.recoveringFromDeath) {
			state.carrier.addTag(OWNER_DOWN_TAG);
		}
		state.phaseStartGameTime = player.serverLevel().getGameTime();
		state.dockingStartPosition = state.carrier.position();
		state.dockingStartYaw = state.carrier.getYRot();
		state.bootHoverTriggered = false;
		syncLocator(state);
		broadcast(player.getServer(), state.toPayload(player.getUUID()));
		FlightArmorMod.LOGGER.info("整甲着装开始 owner={} carrier={} durationTicks={}",
				player.getUUID(), state.carrier.getUUID(), ArmorCruisePhase.DOCKING_DURATION_TICKS);
		player.displayClientMessage(Component.literal("整甲已抵达，正在贴合着装"), true);
	}

	private static void tickDocking(ServerPlayer player, CruiseState state) {
		if (!allArmorSlotsEmpty(player)) {
			handleSlotConflict(player, state, "装甲槽在贴合途中被占用，战甲已恢复独立巡航");
			return;
		}
		if (!carrierHasFullSet(state.carrier)) {
			abortToOwner(player, state, "巡航载体装备不完整，现有部件已安全归还");
			return;
		}
		if (state.carrier.level() != player.level()) {
			beginPhasing(player, state, "着装阶段目标跨维度");
			return;
		}
		if (state.carrier.position().distanceToSqr(player.position()) > 36.0D) {
			if (state.carrier.position().distanceToSqr(player.position())
					> ArmorCruisePhase.DIRECT_RECALL_DISTANCE * ArmorCruisePhase.DIRECT_RECALL_DISTANCE) {
				beginPhasing(player, state, "着装阶段玩家远距移动");
			} else {
				beginVisibleRecall(player, state, false);
			}
			return;
		}

		long elapsed = Math.max(0L, player.serverLevel().getGameTime() - state.phaseStartGameTime);
		if (!state.bootHoverTriggered && elapsed >= ArmorCruisePhase.DOCKING_BOOTS_LOCK_TICKS
				&& FlightController.tryEngageHoverAfterBootLock(
						player, AutomaticHoverSource.DOCKING_BOOTS,
						state.sequenceId, state.phaseStartGameTime)) {
			state.bootHoverTriggered = true;
			if (state.emergencyRecallAttemptId != 0L) {
				state.emergencyRecallAttemptId = 0L;
				broadcast(player.getServer(), state.toPayload(player.getUUID()));
			}
			player.displayClientMessage(Component.literal("战靴锁定完成，反重力悬停已接管"), true);
		}
		Vec3 bodyTarget = player.position();
		double yawRadians = Math.toRadians(player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
		Vec3 rearUpperTarget = dockingCaptureTarget(player);
		Vec3 rearAlignedTarget = bodyTarget.subtract(forward.scale(1.05D)).add(0.0D, 0.32D, 0.0D);
		Vec3 position;
		float yaw;
		float closureProgress;
		if (elapsed < ArmorCruisePhase.DOCKING_FRAME_START_TICKS) {
			// [0,4) 延续 RECALLING 的最后动势，同时展开并进入玩家后上方捕获位。
			double local = smoothStep(elapsed / (double) ArmorCruisePhase.DOCKING_FRAME_START_TICKS);
			position = state.dockingStartPosition.lerp(rearUpperTarget, local)
					.add(0.0D, Math.sin(Math.PI * local) * DOCKING_ARC_HEIGHT, 0.0D);
			yaw = state.dockingStartYaw
					+ Mth.wrapDegrees(player.getYRot() - state.dockingStartYaw) * (float) local;
			closureProgress = 0.0F;
		} else if (elapsed < ArmorCruisePhase.DOCKING_WRAP_START_TICKS) {
			// [4,8) 在玩家背后完成朝向和骨架轴线对正。
			double local = smoothStep((elapsed - ArmorCruisePhase.DOCKING_FRAME_START_TICKS)
					/ (double) (ArmorCruisePhase.DOCKING_WRAP_START_TICKS
					- ArmorCruisePhase.DOCKING_FRAME_START_TICKS));
			position = rearUpperTarget.lerp(rearAlignedTarget, local);
			yaw = player.getYRot();
			closureProgress = 0.0F;
		} else if (elapsed < ArmorCruisePhase.DOCKING_ZONE_LOCK_START_TICKS) {
			// [8,22) 整套战甲从背后整体推进到玩家身体，不进行分件飞行。
			double local = smoothStep((elapsed - ArmorCruisePhase.DOCKING_WRAP_START_TICKS)
					/ (double) (ArmorCruisePhase.DOCKING_ZONE_LOCK_START_TICKS
					- ArmorCruisePhase.DOCKING_WRAP_START_TICKS));
			position = rearAlignedTarget.lerp(bodyTarget, local);
			yaw = player.getYRot();
			closureProgress = (float) (local * 0.25D);
		} else if (elapsed < ArmorCruisePhase.DOCKING_CORE_START_TICKS) {
			// [22,28) 与玩家逐 tick 重合并完成框架闭合。
			double local = smoothStep((elapsed - ArmorCruisePhase.DOCKING_ZONE_LOCK_START_TICKS)
					/ (double) (ArmorCruisePhase.DOCKING_CORE_START_TICKS
					- ArmorCruisePhase.DOCKING_ZONE_LOCK_START_TICKS));
			position = bodyTarget;
			yaw = player.getYRot();
			closureProgress = (float) (0.25D + local * 0.75D);
		} else {
			// [28,32) 核心封锁；保持逐 tick 跟随，最终提交不会再发生位置跳变。
			position = bodyTarget;
			yaw = player.getYRot();
			closureProgress = 1.0F;
		}
		state.carrier.setPos(position);
		state.carrier.setDeltaMovement(Vec3.ZERO);
		state.carrier.setYRot(yaw);
		state.carrier.setYBodyRot(yaw);
		setDockingPose(state.carrier, closureProgress);
		updateCarrierTicket(state);

		if (elapsed >= ArmorCruisePhase.DOCKING_DURATION_TICKS) {
			completeDocking(player, state);
		}
	}

	private static void completeDocking(ServerPlayer player, CruiseState state) {
		// 最后一 tick 再做一次槽位和载体完整性校验，失败时绝不移动任何 ItemStack。
		if (!player.isAlive()) {
			enterOwnerDown(player, state, "着装提交前玩家死亡");
			return;
		}
		if (state.carrier.level() != player.level()
				|| state.carrier.position().distanceToSqr(player.position()) > 36.0D) {
			beginPhasing(player, state, "着装提交前位置失配");
			return;
		}
		if (!allArmorSlotsEmpty(player)) {
			handleSlotConflict(player, state, "装甲槽在贴合完成前被占用，战甲已恢复独立巡航");
			return;
		}
		if (!carrierHasFullSet(state.carrier)) {
			abortToOwner(player, state, "巡航载体装备不完整，现有部件已安全归还");
			return;
		}

		Map<EquipmentSlot, ItemStack> armor = snapshotCarrierArmor(state.carrier);
		UUID suitId = carrierSuitId(state.carrier).orElse(null);
		if (suitId == null || !ChargingPlatformController.markCruisingSuitCarried(
				player.getServer(), player.getUUID(), suitId)) {
			resumeCruising(player, state, "\u4f4d\u7f6e\u8d26\u672c\u63d0\u4ea4\u5931\u8d25\uff0c\u6218\u7532\u5df2\u4fdd\u6301\u72ec\u7acb\u5de1\u822a");
			return;
		}

		try {
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				state.carrier.setItemSlot(slot, ItemStack.EMPTY);
			}
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				player.setItemSlot(slot, armor.getOrDefault(slot, ItemStack.EMPTY));
			}
		} catch (RuntimeException exception) {
			// 任一槽位提交异常时恢复到“载体持有完整四件、玩家槽位为空”的提交前状态。
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				player.setItemSlot(slot, ItemStack.EMPTY);
				state.carrier.setItemSlot(slot, armor.getOrDefault(slot, ItemStack.EMPTY));
			}
			ChargingPlatformController.rollbackSuitToCruising(player.getServer(), player.getUUID(), suitId);
			player.getInventory().setChanged();
			player.containerMenu.broadcastChanges();
			FlightArmorMod.LOGGER.error("整甲贴合提交异常，物品事务已回滚 owner={} carrier={}",
					player.getUUID(), state.carrier.getUUID(), exception);
			resumeCruising(player, state, "整甲贴合提交异常，战甲已安全恢复独立巡航");
			return;
		}
		player.getInventory().setChanged();
		player.containerMenu.broadcastChanges();
		List<Pair<EquipmentSlot, ItemStack>> equipmentUpdate = new ArrayList<>();
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			equipmentUpdate.add(Pair.of(slot, player.getItemBySlot(slot)));
		}
		// 显式保证玩家新装备包排在旧载体移除/IDLE 墓碑之前，避免客户端出现空甲一帧。
		player.serverLevel().getChunkSource().broadcastAndSend(player,
				new ClientboundSetEquipmentPacket(player.getId(), equipmentUpdate));
		releaseCarrierTicket(state);
		state.carrier.discard();
		if (!STATES.remove(player.getUUID(), state)) {
			// 服务端主线程不应发生该分支；物品已唯一落在玩家装备槽，因此仍不会复制或丢失。
			FlightArmorMod.LOGGER.error("玩家 {} 的巡航抵达状态提交失败", player.getUUID());
		}
		ArmorCruiseCombatController.cancelByOwnerId(player.getUUID());
		ArmorCruiseSavedData.removeIfMatches(player.getServer(), player.getUUID(),
				state.carrier.getUUID(), state.sequenceId);
		broadcast(player.getServer(), idlePayload(player.getUUID(), state));
		FlightArmorMod.LOGGER.info("整甲着装提交完成 owner={} sequence={} 四件物品已唯一转移到装备槽",
				player.getUUID(), state.sequenceId);
		player.displayClientMessage(Component.literal("整甲贴合完成，动力核心已上线"), true);
		FlightController.tryEngageHoverAfterArmorReady(
				player, AutomaticHoverSource.DOCKING_COMPLETE,
				state.sequenceId, state.phaseStartGameTime);
	}

	private static void handleSlotConflict(ServerPlayer player, CruiseState state, String normalMessage) {
		if (state.recoveringFromDeath) {
			enterOwnerDown(player, state, "死亡自返途中装甲槽被占用");
			player.displayClientMessage(Component.literal("装甲槽被占用，战甲继续待机并会在清空后自动召回"), true);
		} else {
			resumeCruising(player, state, normalMessage);
		}
	}

	/** 整甲贴合时间线已越过双靴闭合点，服务端可在不提前移动物品的前提下授权悬停。 */
	public static boolean isBootHoverReady(ServerPlayer player) {
		return isBootHoverReady(player, 0L, ANY_ATTEMPT_ID);
	}

	/** 精确绑定同一次贴合尝试；同一巡航序列重新贴合时不会沿用旧授权。 */
	public static boolean isBootHoverReady(ServerPlayer player, long sequenceId, long attemptId) {
		CruiseState state = STATES.get(player.getUUID());
		return state != null && state.phase == ArmorCruisePhase.DOCKING
				&& (sequenceId == 0L || state.sequenceId == sequenceId)
				&& (attemptId == ANY_ATTEMPT_ID || state.phaseStartGameTime == attemptId)
				&& player.serverLevel().getGameTime() - state.phaseStartGameTime
				>= ArmorCruisePhase.DOCKING_BOOTS_LOCK_TICKS;
	}

	/** 只有本次整甲贴合已经成功触发过战靴悬停，后续客户端输入才能重建该临时状态。 */
	public static boolean isBootHoverAuthorized(ServerPlayer player) {
		return isBootHoverAuthorized(player, 0L, ANY_ATTEMPT_ID);
	}

	/** 自动悬停会话只能由创建它的同一次贴合尝试维持。 */
	public static boolean isBootHoverAuthorized(ServerPlayer player, long sequenceId, long attemptId) {
		CruiseState state = STATES.get(player.getUUID());
		return state != null && state.phase == ArmorCruisePhase.DOCKING && state.bootHoverTriggered
				&& (sequenceId == 0L || state.sequenceId == sequenceId)
				&& (attemptId == ANY_ATTEMPT_ID || state.phaseStartGameTime == attemptId)
				&& player.serverLevel().getGameTime() - state.phaseStartGameTime
				>= ArmorCruisePhase.DOCKING_BOOTS_LOCK_TICKS;
	}

	/** 临时悬停意外结束但贴合尚未提交时，允许本次贴合重新建立战靴接管。 */
	public static void revokeBootHoverAuthorization(ServerPlayer player, long sequenceId, long attemptId) {
		CruiseState state = STATES.get(player.getUUID());
		if (state != null && state.phase == ArmorCruisePhase.DOCKING && state.bootHoverTriggered
				&& state.sequenceId == sequenceId && state.phaseStartGameTime == attemptId) {
			state.bootHoverTriggered = false;
		}
	}

	/** 应急制动只在同一巡航序列、同一次不可变救援尝试及召回相关阶段内有效。 */
	public static boolean isEmergencyRecallHoverAuthorized(ServerPlayer player,
			long sequenceId, long attemptId) {
		CruiseState state = STATES.get(player.getUUID());
		if (state == null || attemptId == 0L || state.sequenceId != sequenceId
				|| state.emergencyRecallAttemptId != attemptId) {
			return false;
		}
		return state.phase == ArmorCruisePhase.RECALLING
				|| state.phase == ArmorCruisePhase.PHASING
				|| state.phase == ArmorCruisePhase.DOCKING;
	}

	/** 飞行会话结束时同步撤销救援标识，迟到输入包无法重新取得权限。 */
	public static void revokeEmergencyRecallHoverAuthorization(ServerPlayer player,
			long sequenceId, long attemptId) {
		CruiseState state = STATES.get(player.getUUID());
		if (state == null || state.sequenceId != sequenceId
				|| state.emergencyRecallAttemptId != attemptId) {
			return;
		}
		state.emergencyRecallAttemptId = 0L;
		broadcast(player.getServer(), state.toPayload(player.getUUID()));
	}

	private static void resumeCruising(ServerPlayer player, CruiseState state, String message) {
		FlightController.remove(player);
		if (state.recoveringFromDeath) {
			enterOwnerDown(player, state, "死亡自返流程拒绝降级为普通巡航");
			return;
		}
		state.phase = ArmorCruisePhase.CRUISING;
		state.phaseStartGameTime = player.serverLevel().getGameTime();
		if (state.phasingMigrated || state.patrolLevel != state.carrier.level()) {
			state.patrolAnchor = state.carrier.position();
			state.ownerDownAnchor = state.patrolAnchor;
			state.patrolLevel = (ServerLevel) state.carrier.level();
		}
		state.recoveringFromDeath = false;
		state.phasingMigrated = false;
		state.carrier.removeTag(OWNER_DOWN_TAG);
		state.dockingStartPosition = state.carrier.position();
		state.dockingStartYaw = state.carrier.getYRot();
		setCruisePose(state.carrier);
		updateCarrierTicket(state);
		syncLocator(state);
		broadcast(player.getServer(), state.toPayload(player.getUUID()));
		deny(player, message);
	}

	private static void abortToOwner(ServerPlayer player, CruiseState state, String message) {
		if (STATES.get(player.getUUID()) != state) {
			return;
		}
		FlightController.remove(player);
		ArmorCruiseCombatController.cancel(player, "abort_to_owner");
		ArmorCruiseCombatController.cancelByOwnerId(player.getUUID());
		if (!carrierIsUsable(state.carrier)) {
			STATES.remove(player.getUUID(), state);
			releaseCarrierTicket(state);
			broadcast(player.getServer(), idlePayload(player.getUUID(), state));
			FlightArmorMod.LOGGER.error("玩家 {} 的巡航载体已移除（{}）；为防复制未从旧实体引用提取物品",
					player.getUUID(), state.carrier == null ? null : state.carrier.getRemovalReason());
			if (message != null) {
				player.displayClientMessage(Component.literal("巡航载体已卸载，物品保留在世界存档中等待恢复"), true);
			}
			return;
		}
		UUID suitId = carrierClaimedSuitId(state.carrier).orElse(null);
		if (suitId != null && !ChargingPlatformController.markCruisingSuitCarried(
				player.getServer(), player.getUUID(), suitId)) {
			syncLocator(state);
			player.displayClientMessage(Component.literal(
					"\u4f4d\u7f6e\u8d26\u672c\u51b2\u7a81\uff0c\u6218\u7532\u4ecd\u7531\u5de1\u822a\u8f7d\u4f53\u4fdd\u7ba1"), true);
			return;
		}

		if (!deliverCarrierArmorTransaction(player, state.carrier)) {
			syncLocator(state);
			if (suitId != null) ChargingPlatformController.rollbackSuitToCruising(
					player.getServer(), player.getUUID(), suitId);
			FlightArmorMod.LOGGER.error("战甲安全归还预提交失败，载体与索引均已保留 owner={} carrier={}",
					player.getUUID(), state.carrier.getUUID());
			player.displayClientMessage(Component.literal("物品栏与掉落实体均无法接收战甲，载体已原地保留"), true);
			return;
		}
		STATES.remove(player.getUUID(), state);
		releaseCarrierTicket(state);
		state.carrier.discard();
		ArmorCruiseSavedData.removeIfMatches(player.getServer(), player.getUUID(),
				state.carrier.getUUID(), state.sequenceId);
		broadcast(player.getServer(), idlePayload(player.getUUID(), state));
		if (message != null) {
			player.displayClientMessage(Component.literal(message), true);
		}
	}

	private static void suspendUnloadedCarrier(ServerPlayer player, CruiseState state) {
		FlightController.remove(player);
		if (!STATES.remove(player.getUUID(), state)) {
			return;
		}
		ArmorCruiseCombatController.cancel(player, "carrier_unloaded");
		ArmorCruiseCombatController.cancelByOwnerId(player.getUUID());
		releaseCarrierTicket(state);
		broadcast(player.getServer(), idlePayload(player.getUUID(), state));
		FlightArmorMod.LOGGER.error("玩家 {} 的巡航载体异常卸载；未从已保存的旧实体引用提取物品，等待区块恢复",
				player.getUUID());
		player.displayClientMessage(Component.literal("巡航载体区块异常卸载，战甲保留在世界存档中"), true);
	}

	/** Removes only independent-cruise ownership tags after another controller accepted the carrier. */
	static void clearTransferredCarrierTags(ArmorStand carrier, UUID ownerId) {
		if (carrier == null || ownerId == null) return;
		carrier.removeTag(CARRIER_TAG);
		carrier.removeTag(OWNER_TAG_PREFIX + ownerId);
		carrier.removeTag(OWNER_DOWN_TAG);
	}

	private static void configureCarrier(ArmorStand carrier, UUID ownerId) {
		carrier.addTag(CARRIER_TAG);
		carrier.addTag(OWNER_TAG_PREFIX + ownerId);
		carrier.setInvulnerable(true);
		carrier.setSilent(true);
		carrier.setNoGravity(true);
		carrier.noPhysics = true;
		carrier.setShowArms(true);
		carrier.setNoBasePlate(true);
		setCruisePose(carrier);
	}

	static void setCruisePose(ArmorStand carrier) {
		carrier.setHeadPose(new Rotations(-4.0F, 7.0F, -3.0F));
		carrier.setBodyPose(new Rotations(4.0F, -5.0F, 2.0F));
		carrier.setLeftArmPose(new Rotations(-18.0F, 0.0F, -16.0F));
		carrier.setRightArmPose(new Rotations(12.0F, 0.0F, 22.0F));
		carrier.setLeftLegPose(new Rotations(8.0F, 0.0F, -5.0F));
		carrier.setRightLegPose(new Rotations(-11.0F, 0.0F, 7.0F));
	}

	private static void setDockingPose(ArmorStand carrier, float progress) {
		float remaining = 1.0F - Mth.clamp(progress, 0.0F, 1.0F);
		carrier.setHeadPose(new Rotations(-4.0F * remaining, 7.0F * remaining, -3.0F * remaining));
		carrier.setBodyPose(new Rotations(4.0F * remaining, -5.0F * remaining, 2.0F * remaining));
		carrier.setLeftArmPose(new Rotations(-18.0F * remaining, 0.0F, -16.0F * remaining));
		carrier.setRightArmPose(new Rotations(12.0F * remaining, 0.0F, 22.0F * remaining));
		carrier.setLeftLegPose(new Rotations(8.0F * remaining, 0.0F, -5.0F * remaining));
		carrier.setRightLegPose(new Rotations(-11.0F * remaining, 0.0F, 7.0F * remaining));
	}

	private static void setPhasingPose(ArmorStand carrier, float unfoldProgress) {
		float p = Mth.clamp(unfoldProgress, 0.0F, 1.0F);
		float folded = 1.0F - p;
		carrier.setHeadPose(new Rotations(-10.0F * folded - 4.0F * p, 7.0F * p, 0.0F));
		carrier.setBodyPose(new Rotations(12.0F * folded + 4.0F * p, -5.0F * p, 0.0F));
		carrier.setLeftArmPose(new Rotations(-42.0F * folded - 18.0F * p, 0.0F, -28.0F * folded - 16.0F * p));
		carrier.setRightArmPose(new Rotations(-42.0F * folded + 12.0F * p, 0.0F, 28.0F * folded + 22.0F * p));
		carrier.setLeftLegPose(new Rotations(28.0F * folded + 8.0F * p, 0.0F, -7.0F));
		carrier.setRightLegPose(new Rotations(28.0F * folded - 11.0F * p, 0.0F, 7.0F));
	}

	private static double smoothStep(double value) {
		double p = Mth.clamp(value, 0.0D, 1.0D);
		return p * p * (3.0D - 2.0D * p);
	}

	private static Vec3 deploymentPosition(ServerPlayer player) {
		double yaw = Math.toRadians(player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
		Vec3 right = new Vec3(forward.z, 0.0D, -forward.x);
		return player.position()
				.subtract(forward.scale(1.55D))
				.add(right.scale(1.35D))
				.add(0.0D, 0.22D, 0.0D);
	}

	/** 巡航目标只依赖固定锚点、服务端时间和状态序号，不读取玩家当前位置或朝向。 */
	private static Vec3 patrolTarget(CruiseState state, long gameTime) {
		double elapsed = Math.max(0L, gameTime - state.phaseStartGameTime);
		double phaseOffset = (state.sequenceId & 255L) * (Math.PI * 2.0D / 256.0D);
		double angle = phaseOffset + elapsed * PATROL_ANGULAR_SPEED;
		double vertical = Math.sin(angle * 1.7D) * PATROL_VERTICAL_RANGE;
		return state.patrolAnchor.add(
				Math.cos(angle) * PATROL_RADIUS,
				vertical,
				Math.sin(angle) * PATROL_RADIUS
		);
	}

	private static float patrolYaw(CruiseState state, long gameTime) {
		double elapsed = Math.max(0L, gameTime - state.phaseStartGameTime);
		double phaseOffset = (state.sequenceId & 255L) * (Math.PI * 2.0D / 256.0D);
		double angle = phaseOffset + elapsed * PATROL_ANGULAR_SPEED;
		// 圆轨道切线为 (-sin(angle), 0, cos(angle))，正好对应 Minecraft 的 yaw 前向量。
		return (float) Math.toDegrees(angle);
	}

	private static void moveCarrier(ArmorStand carrier, Vec3 target, float targetYaw, boolean recalling) {
		ArmorIntegrityController.prepareControlledMovement(carrier);
		Vec3 delta = target.subtract(carrier.position());
		double distance = delta.length();
		if (distance > 1.0E-5D) {
			double maxStep = (recalling
					? Math.min(1.45D, 0.30D + distance * 0.22D)
					: Math.min(0.105D, Math.max(0.035D, distance * 0.10D)))
					* ArmorIntegrityController.movementMultiplier(carrier);
			carrier.setPos(carrier.position().add(delta.scale(Math.min(1.0D, maxStep / distance))));
		}
		carrier.setDeltaMovement(Vec3.ZERO);
		float yaw = Mth.rotLerp(recalling ? 0.30F : 0.12F, carrier.getYRot(), targetYaw);
		carrier.setYRot(yaw);
		carrier.setYBodyRot(yaw);
	}

	private static void updateCarrierTicket(CruiseState state) {
		if (!(state.carrier.level() instanceof ServerLevel level) || state.carrier.isRemoved()) {
			return;
		}
		ChunkPos nextChunk = state.carrier.chunkPosition();
		if (level == state.ticketLevel && nextChunk.equals(state.ticketChunk)) {
			return;
		}

		// 先添加新票据再移除旧票据，保证跨区块召回时不存在一个 tick 的卸载窗口。
		level.getChunkSource().addRegionTicket(
				CARRIER_TICKET, nextChunk, CARRIER_TICKET_RADIUS, state.ownerId);
		if (state.ticketLevel != null && state.ticketChunk != null) {
			state.ticketLevel.getChunkSource().removeRegionTicket(
					CARRIER_TICKET, state.ticketChunk, CARRIER_TICKET_RADIUS, state.ownerId);
		}
		state.ticketLevel = level;
		state.ticketChunk = nextChunk;
		syncLocator(state);
	}

	private static void releaseCarrierTicket(CruiseState state) {
		if (state.ticketLevel != null && state.ticketChunk != null) {
			state.ticketLevel.getChunkSource().removeRegionTicket(
					CARRIER_TICKET, state.ticketChunk, CARRIER_TICKET_RADIUS, state.ownerId);
			state.ticketLevel = null;
			state.ticketChunk = null;
		}
	}

	private static void syncLocator(CruiseState state) {
		if (!carrierIsUsable(state.carrier) || !(state.carrier.level() instanceof ServerLevel level)) {
			return;
		}
		ChunkPos chunk = state.carrier.chunkPosition();
		Vec3 anchor = state.phase == ArmorCruisePhase.OWNER_DOWN ? state.ownerDownAnchor : state.patrolAnchor;
		ArmorCruiseSavedData.put(level.getServer(), new ArmorCruiseSavedData.Locator(
				state.ownerId, state.carrier.getUUID(), carrierSuitId(state.carrier).orElse(null),
				level.dimension(), chunk.x, chunk.z,
				state.phase, anchor, state.sequenceId));
	}

	private static boolean carrierIsUsable(ArmorStand carrier) {
		return carrier != null && !carrier.isRemoved() && isCruiseCarrier(carrier);
	}

	private static boolean isPersistedUnloaded(ArmorStand carrier) {
		if (carrier == null || !carrier.isRemoved()) {
			return false;
		}
		Entity.RemovalReason reason = carrier.getRemovalReason();
		return reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
				|| reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER;
	}

	private static boolean carrierHasFullSet(ArmorStand carrier) {
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			if (!ModItems.isExactFlightArmor(carrier.getItemBySlot(slot), slot)) {
				return false;
			}
		}
		return true;
	}
	private static Optional<UUID> carrierSuitId(ArmorStand carrier) {
		if (!carrierHasFullSet(carrier)) return Optional.empty();
		return carrierClaimedSuitId(carrier);
	}

	private static Optional<UUID> carrierClaimedSuitId(ArmorStand carrier) {
		UUID suitId = null;
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack stack = carrier.getItemBySlot(slot);
			if (stack.isEmpty()) continue;
			Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(stack);
			if (identity.isEmpty() || identity.get().slot() != slot) return Optional.empty();
			if (suitId != null && !suitId.equals(identity.get().suitId())) return Optional.empty();
			suitId = identity.get().suitId();
		}
		return Optional.ofNullable(suitId);
	}


	private static boolean allArmorSlotsEmpty(ServerPlayer player) {
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			if (!player.getItemBySlot(slot).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private static Map<EquipmentSlot, ItemStack> snapshotPlayerArmor(ServerPlayer player) {
		Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class);
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			armor.put(slot, player.getItemBySlot(slot));
		}
		return armor;
	}

	private static Map<EquipmentSlot, ItemStack> snapshotCarrierArmor(ArmorStand carrier) {
		Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class);
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack stack = carrier.getItemBySlot(slot);
			if (!stack.isEmpty()) {
				armor.put(slot, stack);
			}
		}
		return armor;
	}

	private static void rollbackDeployment(ServerPlayer player, ArmorStand carrier,
			Map<EquipmentSlot, ItemStack> originals) {
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			carrier.setItemSlot(slot, ItemStack.EMPTY);
			player.setItemSlot(slot, originals.getOrDefault(slot, ItemStack.EMPTY));
		}
		carrier.discard();
		player.getInventory().setChanged();
		player.containerMenu.broadcastChanges();
	}

	/** Reverts the CARRIED -> CRUISE admission performed before a new carrier was committed. */
	private static void rollbackNewCruiseLedger(ServerPlayer player, UUID ownerId,
			UUID suitId, String context) {
		if (!ChargingPlatformController.markCruisingSuitCarried(
				player.getServer(), ownerId, suitId)) {
			FlightArmorMod.LOGGER.error(
					"New carrier ledger rollback failed context={} owner={} suit={}",
					context, ownerId, suitId);
		}
	}

	/**
	 * 先为全部部件预留装备槽/背包槽/已成功生成的掉落实体，再一次性清空载体。
	 * 任一掉落实体生成失败都会撤销预提交，旧载体和持久索引保持原样。
	 */
	private static boolean deliverCarrierArmorTransaction(ServerPlayer player, ArmorStand carrier) {
		Map<EquipmentSlot, ItemStack> originals = new EnumMap<>(EquipmentSlot.class);
		Map<EquipmentSlot, DeliveryTarget> plan = new EnumMap<>(EquipmentSlot.class);
		List<Integer> freeInventorySlots = new ArrayList<>();
		for (int index = 0; index < player.getInventory().items.size(); index++) {
			if (player.getInventory().items.get(index).isEmpty()) {
				freeInventorySlots.add(index);
			}
		}
		int nextInventorySlot = 0;
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack stack = carrier.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			originals.put(slot, stack);
			if (player.getItemBySlot(slot).isEmpty()) {
				plan.put(slot, DeliveryTarget.equipment(slot));
			} else if (nextInventorySlot < freeInventorySlots.size()) {
				plan.put(slot, DeliveryTarget.inventory(freeInventorySlots.get(nextInventorySlot++)));
			} else {
				plan.put(slot, DeliveryTarget.drop());
			}
		}

		Map<EquipmentSlot, ItemEntity> spawnedDrops = new EnumMap<>(EquipmentSlot.class);
		for (Map.Entry<EquipmentSlot, DeliveryTarget> entry : plan.entrySet()) {
			if (!entry.getValue().isDrop()) {
				continue;
			}
			ItemStack stack = originals.get(entry.getKey());
			ItemEntity drop = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5D,
					player.getZ(), stack);
			drop.setDefaultPickUpDelay();
			if (!player.serverLevel().addFreshEntity(drop)) {
				drop.discard();
				for (ItemEntity committedDrop : spawnedDrops.values()) {
					committedDrop.discard();
				}
				return false;
			}
			spawnedDrops.put(entry.getKey(), drop);
		}

		try {
			for (Map.Entry<EquipmentSlot, DeliveryTarget> entry : plan.entrySet()) {
				ItemStack stack = originals.get(entry.getKey());
				DeliveryTarget target = entry.getValue();
				if (target.equipmentSlot != null) {
					player.setItemSlot(target.equipmentSlot, stack);
				} else if (target.inventoryIndex >= 0) {
					player.getInventory().setItem(target.inventoryIndex, stack);
				}
			}
			for (EquipmentSlot slot : originals.keySet()) {
				carrier.setItemSlot(slot, ItemStack.EMPTY);
			}
		} catch (RuntimeException exception) {
			for (Map.Entry<EquipmentSlot, DeliveryTarget> entry : plan.entrySet()) {
				DeliveryTarget target = entry.getValue();
				if (target.equipmentSlot != null) {
					player.setItemSlot(target.equipmentSlot, ItemStack.EMPTY);
				} else if (target.inventoryIndex >= 0) {
					player.getInventory().setItem(target.inventoryIndex, ItemStack.EMPTY);
				}
			}
			for (ItemEntity drop : spawnedDrops.values()) {
				drop.discard();
			}
			for (Map.Entry<EquipmentSlot, ItemStack> entry : originals.entrySet()) {
				carrier.setItemSlot(entry.getKey(), entry.getValue());
			}
			FlightArmorMod.LOGGER.error("战甲安全归还提交异常，事务已回滚 owner={}", player.getUUID(), exception);
			return false;
		}
		player.getInventory().setChanged();
		player.containerMenu.broadcastChanges();
		return true;
	}

	private static void spawnAtPlayer(ServerPlayer player, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		ItemEntity itemEntity = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5D,
				player.getZ(), stack);
		itemEntity.setDefaultPickUpDelay();
		player.serverLevel().addFreshEntity(itemEntity);
	}

	static Optional<UUID> ownerIdOf(ArmorStand carrier) {
		for (String tag : carrier.getTags()) {
			if (!tag.startsWith(OWNER_TAG_PREFIX)) {
				continue;
			}
			try {
				return Optional.of(UUID.fromString(tag.substring(OWNER_TAG_PREFIX.length())));
			} catch (IllegalArgumentException ignored) {
				// 继续查找可能存在的下一个合法所有者标签。
			}
		}
		return Optional.empty();
	}

	static CombatHandle combatHandle(ServerPlayer player) {
		CruiseState state = STATES.get(player.getUUID());
		if (state == null || !carrierIsUsable(state.carrier)
				|| ArmorIntegrityController.isDisabled(state.carrier)) {
			return null;
		}
		return new CombatHandle(state.sequenceId, state.phase, state.carrier, state.patrolAnchor);
	}

	static record CombatHandle(long sequenceId, ArmorCruisePhase phase,
			ArmorStand carrier, Vec3 patrolAnchor) {
	}

	private static boolean recoverCarrier(ArmorStand stand, ServerLevel level) {
		if (!isCruiseCarrier(stand)) {
			return false;
		}
		Optional<UUID> ownerId = ownerIdOf(stand);
		if (ownerId.isEmpty() || !carrierHasFullSet(stand)) {
			FlightArmorMod.LOGGER.warn("忽略无有效所有者或装备不完整的巡航载体 {}", stand.getUUID());
			return false;
		}
		UUID suitId = carrierSuitId(stand).orElse(null);
		if (suitId == null || !ChargingPlatformController.ensureSuitCruising(
				level.getServer(), ownerId.get(), suitId)) {
			FlightArmorMod.LOGGER.error("Cruise carrier ownership ledger conflict owner={} carrier={} suit={}",
					ownerId.get(), stand.getUUID(), suitId);
			return false;
		}
		ArmorCruiseSavedData.Locator indexed = ArmorCruiseSavedData.find(level.getServer(), ownerId.get());
		if (indexed != null && !indexed.carrierId().equals(stand.getUUID())) {
			FlightArmorMod.LOGGER.error("载体 {} 与持久索引 {} 冲突，已隔离且不改写索引 owner={}",
					stand.getUUID(), indexed.carrierId(), ownerId.get());
			return false;
		}

		CruiseState existing = STATES.get(ownerId.get());
		if (existing != null) {
			if (!existing.carrier.getUUID().equals(stand.getUUID())) {
				FlightArmorMod.LOGGER.error("检测到玩家 {} 的重复巡航载体 {}，为避免误删物品已隔离保留",
						ownerId.get(), stand.getUUID());
			}
			return false;
		}

		configureCarrier(stand, ownerId.get());
		boolean ownerDown = stand.getTags().contains(OWNER_DOWN_TAG);
		CruiseState recoveredState = new CruiseState(
				ownerId.get(),
				NEXT_SEQUENCE_ID.incrementAndGet(),
				ownerDown ? ArmorCruisePhase.OWNER_DOWN : ArmorCruisePhase.CRUISING,
				stand,
				level.getGameTime(),
				true,
				stand.position()
		);
		recoveredState.recoveringFromDeath = ownerDown;
		recoveredState.respawnResumeGameTime = ownerDown ? Long.MAX_VALUE : 0L;
		if (STATES.putIfAbsent(ownerId.get(), recoveredState) != null) {
			return false;
		}
		updateCarrierTicket(recoveredState);
		if (level.getServer().getPlayerList().getPlayer(ownerId.get()) == null) {
			suspendForOfflineOwner(recoveredState);
		}
		return true;
	}

	private static ArmorCruiseStatePayload idlePayload(UUID playerId, CruiseState state) {
		return new ArmorCruiseStatePayload(
				playerId,
				state.sequenceId,
				ArmorCruisePhase.IDLE,
				ArmorCruiseStatePayload.NO_CARRIER_ID,
				ArmorCruiseStatePayload.NO_CARRIER_ENTITY_ID,
				state.phaseStartGameTime,
				0L
		);
	}

	private static void broadcast(MinecraftServer server, ArmorCruiseStatePayload payload) {
		if (server == null) {
			return;
		}
		// 巡航切换是低频事件；全服清理快照可覆盖死亡、断线和跨维度后的旧追踪者。
		for (ServerPlayer recipient : PlayerLookup.all(server)) {
			send(recipient, payload);
		}
	}

	private static void send(ServerPlayer recipient, ArmorCruiseStatePayload payload) {
		if (ServerPlayNetworking.canSend(recipient, ArmorCruiseStatePayload.TYPE)) {
			ServerPlayNetworking.send(recipient, payload);
		}
	}


	private static void broadcastPlatformRecall(ServerPlayer player,
			ChargingPlatformController.RecallResult result) {
		ArmorPlatformRecallPayload payload = new ArmorPlatformRecallPayload(
				player.getUUID(), result.sequenceId(), result.originDimension().location(),
				result.origin(), result.displayMode(), result.startGameTime(), 0);
		PLATFORM_RECALL_SNAPSHOTS.put(player.getUUID(), new PlatformRecallSnapshot(
				payload, player.level().dimension().location()));
		Set<ServerPlayer> recipients = new java.util.HashSet<>(PlayerLookup.tracking(player));
		recipients.add(player);
		for (ServerPlayer recipient : recipients) {
			sendPlatformRecall(recipient, payload);
		}
	}

	private static void sendPlatformRecall(ServerPlayer recipient, ArmorPlatformRecallPayload payload) {
		if (ServerPlayNetworking.canSend(recipient, ArmorPlatformRecallPayload.TYPE)) {
			ServerPlayNetworking.send(recipient, payload);
		}
	}

	private static boolean hasBlockingRuntimeConflict(SuitRuntimeSnapshot.SuitState state) {
		return state.conflicts().stream().anyMatch(conflict -> switch (conflict) {
			case OWNER_MISMATCH, IDENTITY_MISMATCH, PARTIAL_WORN_SET,
					LOCATION_COLLISION, LEDGER_MISMATCH, PLATFORM_IDENTITY_MISMATCH,
					TRANSITION_CONFLICT -> true;
			case CORE_INACTIVE, PLATFORM_BROKEN -> false;
		});
	}

	private static void deny(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message), true);
	}

	public record WornDeploymentResult(boolean success,
			WornSuitDeploymentPolicy.Route route, String message) {
		static WornDeploymentResult started(WornSuitDeploymentPolicy.Route route) {
			return new WornDeploymentResult(true, route, "");
		}

		static WornDeploymentResult failed(
				WornSuitDeploymentPolicy.Route route, String message) {
			return new WornDeploymentResult(false, route, message);
		}
	}

	public record RecallCommandResult(boolean success, String message) {
		static RecallCommandResult started() { return new RecallCommandResult(true, ""); }
		static RecallCommandResult failed(String message) { return new RecallCommandResult(false, message); }
	}

	private record PlatformRecallSnapshot(ArmorPlatformRecallPayload payload,
			net.minecraft.resources.ResourceLocation targetDimension) {
	}

	private record ReentrySite(ServerLevel level, Vec3 position, ChunkPos chunk, boolean ticketAdded) {
	}

	private record DeliveryTarget(EquipmentSlot equipmentSlot, int inventoryIndex) {
		private static DeliveryTarget equipment(EquipmentSlot slot) {
			return new DeliveryTarget(slot, -1);
		}

		private static DeliveryTarget inventory(int index) {
			return new DeliveryTarget(null, index);
		}

		private static DeliveryTarget drop() {
			return new DeliveryTarget(null, -1);
		}

		private boolean isDrop() {
			return equipmentSlot == null && inventoryIndex < 0;
		}
	}

	private static final class CruiseState {
		private final UUID ownerId;
		private final long sequenceId;
		private ArmorStand carrier;
		private ArmorCruisePhase phase;
		private ArmorCruisePhase phaseBeforePhasing = ArmorCruisePhase.CRUISING;
		private long phaseStartGameTime;
		private boolean needsOwnerSync;
		private Vec3 patrolAnchor;
		private ServerLevel patrolLevel;
		private Vec3 ownerDownAnchor;
		private boolean recoveringFromDeath;
		private long respawnResumeGameTime;
		private double recallSpeed = RecallApproachProfile.INITIAL_SPEED;
		private int recallTicks;
		private Vec3 phasingOrigin;
		private Vec3 phasingReentryPosition;
		private boolean phasingMigrated;
		private Vec3 dockingStartPosition;
		private float dockingStartYaw;
		private boolean bootHoverTriggered;
		private long emergencyRecallAttemptId;
		private ServerLevel ticketLevel;
		private ChunkPos ticketChunk;

		private CruiseState(UUID ownerId, long sequenceId, ArmorCruisePhase phase, ArmorStand carrier,
				long phaseStartGameTime, boolean needsOwnerSync, Vec3 patrolAnchor) {
			this.ownerId = ownerId;
			this.sequenceId = sequenceId;
			this.phase = phase;
			this.carrier = carrier;
			this.phaseStartGameTime = phaseStartGameTime;
			this.needsOwnerSync = needsOwnerSync;
			this.patrolAnchor = patrolAnchor;
			this.patrolLevel = (ServerLevel) carrier.level();
			this.ownerDownAnchor = patrolAnchor;
			this.respawnResumeGameTime = 0L;
			this.phasingOrigin = carrier.position();
			this.phasingReentryPosition = carrier.position();
			this.dockingStartPosition = carrier.position();
			this.dockingStartYaw = carrier.getYRot();
		}

		private ArmorCruiseStatePayload toPayload(UUID playerId) {
			return new ArmorCruiseStatePayload(
					playerId,
					sequenceId,
					phase,
					carrier.getUUID(),
					carrier.getId(),
					phaseStartGameTime,
					emergencyRecallAttemptId
			);
		}
	}
}
