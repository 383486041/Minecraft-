package dev.codex.flightarmor.suitpanel;

import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.configuration.ArmorConfigurationData;
import dev.codex.flightarmor.configuration.SentinelArmorMode;
import dev.codex.flightarmor.cruise.ArmorCruiseController;
import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import dev.codex.flightarmor.cruise.ArmorEscortController;
import dev.codex.flightarmor.cruise.ArmorRemoteCommandController;
import dev.codex.flightarmor.integrity.ArmorIntegrityController;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorSuitPanelActionPayload;
import dev.codex.flightarmor.network.ArmorSuitPanelSnapshotPayload;
import dev.codex.flightarmor.network.ArmorSuitPanelSnapshotPayload.Capability;
import dev.codex.flightarmor.network.ArmorSuitPanelSnapshotPayload.PrimaryAction;
import dev.codex.flightarmor.network.ArmorSuitPanelSnapshotPayload.SubjectKind;
import dev.codex.flightarmor.platform.ArmorIdentity;
import dev.codex.flightarmor.platform.ChargingPlatformBlockEntity;
import dev.codex.flightarmor.platform.ChargingPlatformDisplayMode;
import dev.codex.flightarmor.runtime.SuitRuntimeSnapshot;
import dev.codex.flightarmor.runtime.SuitStateResolver;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Server boundary for the right-click status panel.
 *
 * <p>A session remembers only the identity of the object that was clicked. Every button resolves
 * that object again and rechecks its owner, suit id, dimension, distance and current phase before
 * asking a movement controller to do anything.</p>
 */
public final class ArmorSuitPanelController {
	private static final double PANEL_OPEN_DISTANCE_SQR = 14.0D * 14.0D;
	private static final double CARRIER_SESSION_DISTANCE_SQR = 48.0D * 48.0D;
	private static final long SESSION_TTL_TICKS = 20L * 30L;
	private static final AtomicLong NEXT_SESSION = new AtomicLong();
	private static final Map<UUID, PanelSession> SESSIONS = new ConcurrentHashMap<>();
	private static boolean initialized;

	private ArmorSuitPanelController() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		PayloadTypeRegistry.playC2S().register(
				ArmorSuitPanelActionPayload.TYPE, ArmorSuitPanelActionPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(
				ArmorSuitPanelSnapshotPayload.TYPE, ArmorSuitPanelSnapshotPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(
				ArmorSuitPanelActionPayload.TYPE,
				(payload, context) -> handleAction(context.player(), payload));
		ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> SESSIONS.remove(handler.player.getUUID()));

		// This is registered before the carrier protection callback. Use is consumed, while attack
		// protection remains owned by the cruise/escort controllers.
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			SubjectKind kind = subjectKind(entity);
			if (kind == null) return InteractionResult.PASS;
			if (!world.isClientSide
					&& player instanceof ServerPlayer serverPlayer
					&& entity instanceof ArmorStand stand) {
				openCarrier(serverPlayer, stand, kind);
			}
			return InteractionResult.SUCCESS;
		});
	}

	public static void openPlatform(ServerPlayer viewer, ChargingPlatformBlockEntity platform) {
		if (viewer == null || platform == null || !viewer.isAlive() || viewer.isSpectator()) return;
		if (!(platform.getLevel() instanceof ServerLevel level)
				|| viewer.level() != level
				|| viewer.distanceToSqr(platform.getBlockPos().getCenter())
				> PANEL_OPEN_DISTANCE_SQR) return;
		UUID ownerId = platform.getOwnerUuid().orElse(null);
		UUID suitId = platform.getSuitId().orElse(null);
		if (ownerId == null || suitId == null || !platform.isComplete()) {
			viewer.displayClientMessage(Component.literal("平台中没有可识别的完整战甲"), true);
			return;
		}
		ArmorVariant variant = platform.getArmorItems().stream()
				.filter(stack -> !stack.isEmpty())
				.findFirst()
				.map(ArmorVariantData::get)
				.orElse(ArmorVariant.DEFAULT);
		PanelSession session = PanelSession.platform(
				NEXT_SESSION.incrementAndGet(), ownerId, suitId, variant,
				level.dimension(), platform.getBlockPos(), platform.getPlatformId(), expiresAt(viewer));
		SESSIONS.put(viewer.getUUID(), session);
		sendSnapshot(viewer, session, new ResolvedTarget(platform, null), "",
				true, false);
	}

	private static void openCarrier(ServerPlayer viewer, ArmorStand carrier, SubjectKind kind) {
		if (!viewer.isAlive() || viewer.isSpectator() || viewer.level() != carrier.level()
				|| viewer.distanceToSqr(carrier) > PANEL_OPEN_DISTANCE_SQR) return;
		SuitIdentity identity = readSuitIdentity(carrier).orElse(null);
		if (identity == null) {
			viewer.displayClientMessage(Component.literal("无法读取这套战甲的完整身份"), true);
			return;
		}
		PanelSession session = PanelSession.carrier(
				NEXT_SESSION.incrementAndGet(), identity.ownerId(), identity.suitId(),
				identity.variant(), kind, carrier.level().dimension(), carrier.getUUID(),
				expiresAt(viewer));
		SESSIONS.put(viewer.getUUID(), session);
		sendSnapshot(viewer, session, new ResolvedTarget(null, carrier), "",
				true, false);
	}

	private static void handleAction(ServerPlayer viewer, ArmorSuitPanelActionPayload payload) {
		PanelSession session = SESSIONS.get(viewer.getUUID());
		if (session == null || session.token() != payload.sessionId()
				|| viewer.serverLevel().getGameTime() > session.expiresAt()) {
			SESSIONS.remove(viewer.getUUID());
			viewer.displayClientMessage(
					Component.literal("战甲状态面板已过期，请重新右键目标"), true);
			return;
		}

		ResolvedTarget target = resolve(viewer, session);
		if (target == null) {
			SESSIONS.remove(viewer.getUUID());
			viewer.displayClientMessage(
					Component.literal("目标已离开控制距离，或战甲身份已经变化"), true);
			return;
		}

		session = session.refresh(expiresAt(viewer));
		SESSIONS.put(viewer.getUUID(), session);
		if (!session.ownerId().equals(viewer.getUUID())) {
			sendSnapshot(viewer, session, target,
					payload.action() == ArmorSuitPanelActionPayload.Action.REFRESH
							? "只读状态已刷新"
							: "这套战甲不属于你；当前面板仅可查看",
					false, false);
			return;
		}

		switch (payload.action()) {
			case REFRESH -> sendSnapshot(viewer, session, target, "状态已刷新", false, false);
			case TOGGLE_DISPLAY -> handleToggleDisplay(viewer, session, target);
			case SELECT_REMOTE -> handleSelectRemote(viewer, session, target);
			case TOGGLE_FOLLOW -> handleToggleFollow(viewer, session, target);
			case PRIMARY -> handlePrimary(viewer, session, target);
			case OPEN_COMMAND_CENTER -> {
				SESSIONS.remove(viewer.getUUID(), session);
				ArmorCoreController.openMenu(viewer);
			}
		}
	}

	private static void handlePrimary(
			ServerPlayer viewer, PanelSession session, ResolvedTarget target) {
		SuitRuntimeSnapshot.SuitState runtimeState =
				runtimeState(viewer, session.suitId()).orElse(null);
		PrimaryActionState primary = primaryAction(viewer, session, runtimeState);
		if (!primary.enabled()) {
			sendSnapshot(viewer, session, target, primary.reason(), false, false);
			return;
		}

		ActionOutcome outcome;
		if (primary.action() == PrimaryAction.WEAR) {
			outcome = switch (session.kind()) {
				case PLATFORM -> ArmorCruiseController.recallPlatformSuit(
						viewer, session.suitId())
						? ActionOutcome.started("战甲已离开平台，正在高速召回并穿戴")
						: ActionOutcome.failed("平台战甲召回未能启动；状态已刷新");
				case CRUISE -> {
					ArmorCruiseController.RecallCommandResult result =
							ArmorCruiseController.recallActiveSuitForSwitch(
									viewer, session.suitId());
					yield result.success()
							? ActionOutcome.started("独立巡航战甲正在召回并穿戴")
							: ActionOutcome.failed(result.message());
				}
				case ESCORT -> {
					ArmorEscortController.EscortCommandResult result =
							ArmorEscortController.recallToOwnerForWear(
									viewer, session.suitId());
					yield result.success()
							? ActionOutcome.started("护航战甲已脱离编队，正在近身穿戴")
							: ActionOutcome.failed(result.message());
				}
			};
		} else if (primary.action() == PrimaryAction.SWITCH) {
			ArmorCoreController.TargetedSwitchResult result =
					ArmorCoreController.beginTargetedQuickSwitch(viewer, session.suitId());
			outcome = result.success()
					? ActionOutcome.started(result.message())
					: ActionOutcome.failed(result.message());
		} else {
			outcome = ActionOutcome.failed("当前没有可执行的穿戴或切换操作");
		}

		if (outcome.closePanel()) {
			viewer.displayClientMessage(Component.literal(outcome.message()), true);
			sendSnapshot(viewer, session, target, outcome.message(), false, true);
			SESSIONS.remove(viewer.getUUID(), session);
		} else {
			sendSnapshot(viewer, session, target, outcome.message(), false, false);
		}
	}

	private static void handleToggleDisplay(
			ServerPlayer viewer, PanelSession session, ResolvedTarget target) {
		if (session.kind() != SubjectKind.PLATFORM || target.platform() == null) {
			sendSnapshot(viewer, session, target, "当前目标不支持切换陈列", false, false);
			return;
		}
		ChargingPlatformBlockEntity.ToggleResult result =
				target.platform().toggleDisplayMode(viewer);
		String message = result.success()
				? result.mode() == ChargingPlatformDisplayMode.PARTS
						? "已切换为分件悬浮陈列"
						: "已切换为整甲待机陈列"
				: result.message();
		sendSnapshot(viewer, session, target, message, false, false);
	}

	private static void handleSelectRemote(
			ServerPlayer viewer, PanelSession session, ResolvedTarget target) {
		if (!isPhysicalCarrier(session, target)) {
			sendSnapshot(viewer, session, target,
					"平台陈列不能设为近距指令战甲", false, false);
			return;
		}
		ArmorRemoteCommandController.Result result =
				ArmorRemoteCommandController.select(viewer, session.suitId());
		sendSnapshot(viewer, session, target, result.message(), false, false);
	}

	private static void handleToggleFollow(
			ServerPlayer viewer, PanelSession session, ResolvedTarget target) {
		if (!isPhysicalCarrier(session, target)) {
			sendSnapshot(viewer, session, target,
					"平台陈列没有跟随或待命状态", false, false);
			return;
		}
		ArmorRemoteCommandController.Result result =
				ArmorRemoteCommandController.toggleFollow(viewer, session.suitId());
		sendSnapshot(viewer, session, target, result.message(), false, false);
	}

	private static void sendSnapshot(
			ServerPlayer viewer,
			PanelSession session,
			ResolvedTarget target,
			String notice,
			boolean openRequested,
			boolean closeRequested
	) {
		if (!ServerPlayNetworking.canSend(viewer, ArmorSuitPanelSnapshotPayload.TYPE)) return;
		boolean ownerView = session.ownerId().equals(viewer.getUUID());
		ServerPlayer stateOwner = ownerView
				? viewer
				: viewer.getServer().getPlayerList().getPlayer(session.ownerId());
		SuitRuntimeSnapshot.SuitState runtimeState = stateOwner == null
				? null
				: runtimeState(stateOwner, session.suitId()).orElse(null);
		boolean transitioning = isTransitioning(stateOwner, session, runtimeState);
		PrimaryActionState primary = ownerView
				? primaryAction(viewer, session, runtimeState)
				: PrimaryActionState.none("只有战甲主人可以执行控制");

		EnumSet<Capability> capabilities = EnumSet.of(Capability.REFRESH);
		if (ownerView) {
			if (primary.enabled()) capabilities.add(Capability.PRIMARY);
			if (session.kind() == SubjectKind.PLATFORM) {
				capabilities.add(Capability.TOGGLE_DISPLAY);
			} else if (isPhysicalCarrier(session, target) && !transitioning
					&& !isOwnerDown(stateOwner, session, runtimeState)) {
				// Physical carrier identity + owner + current interaction distance is sufficient.
				// This deliberately does not depend on an equipped/active navigation core.
			}
		}

		String effectiveNotice = notice == null ? "" : notice;
		if (effectiveNotice.isBlank() && !ownerView) {
			effectiveNotice = "只读查看：只有战甲主人可以召回、切换或下达近距指令";
		} else if (effectiveNotice.isBlank() && transitioning) {
			effectiveNotice = "战甲正在执行状态迁移，移动类按钮已暂时锁定";
		} else if (effectiveNotice.isBlank() && ownerView && !primary.reason().isBlank()) {
			effectiveNotice = primary.reason();
		}

		boolean selected = session.kind() != SubjectKind.PLATFORM
				&& ArmorRemoteCommandController.isSelected(session.ownerId(), session.suitId());
		boolean following = session.kind() != SubjectKind.PLATFORM
				&& ArmorRemoteCommandController.isFollowing(session.ownerId(), session.suitId());
		String integrity = ArmorIntegrityController.statusText(
				viewer.getServer(), session.ownerId(), session.suitId());
		if (target.platform() != null && target.platform().isIntegrityRepairing()) {
			integrity += " · 平台维护中";
		}

		ArmorSuitPanelSnapshotPayload payload = new ArmorSuitPanelSnapshotPayload(
				session.token(),
				session.suitId(),
				displayName(session.variant(), session.suitId()),
				session.variant(),
				ownerName(viewer, session.ownerId()),
				session.kind(),
				lifecycleText(session, target.platform(), runtimeState),
				roleText(stateOwner, session.suitId(), session.variant(), target),
				positionText(session, target),
				distanceText(viewer, target),
				integrity,
				conflictText(session, runtimeState),
				ownerView,
				primary.action(),
				ArmorSuitPanelSnapshotPayload.capabilityMask(capabilities),
				selected,
				following,
				transitioning,
				openRequested,
				closeRequested,
				effectiveNotice);
		ServerPlayNetworking.send(viewer, payload);
	}

	private static PrimaryActionState primaryAction(
			ServerPlayer viewer,
			PanelSession session,
			SuitRuntimeSnapshot.SuitState runtimeState
	) {
		if (allArmorSlotsEmpty(viewer)) {
			if (session.kind() != SubjectKind.PLATFORM) {
				if (isTransitioning(viewer, session, runtimeState)) {
					return PrimaryActionState.disabled(
							PrimaryAction.WEAR, "载体正在变形、返航或召回，暂时不能重复穿戴");
				}
				// Exact nearby physical carriers remain directly recallable without a core binding.
				return PrimaryActionState.enabled(PrimaryAction.WEAR);
			}
			boolean allowed = runtimeState != null
					&& runtimeState.allowedActions().contains(
							SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR)
					&& !isTransitioning(viewer, session, runtimeState);
			return allowed
					? PrimaryActionState.enabled(PrimaryAction.WEAR)
					: PrimaryActionState.disabled(
							PrimaryAction.WEAR,
							"平台召回需要当前核心关联此战甲，且战甲不处于过渡或冲突状态");
		}

		Optional<UUID> wornSuit = wornFullSuitId(viewer);
		if (wornSuit.isPresent() && wornSuit.get().equals(session.suitId())) {
			return PrimaryActionState.none("你已经穿着这套战甲");
		}
		if (wornSuit.isPresent()) {
			boolean allowed = runtimeState != null
					&& runtimeState.conflicts().isEmpty()
					&& (runtimeState.phase() == SuitRuntimeSnapshot.Phase.IDLE
					|| runtimeState.phase() == SuitRuntimeSnapshot.Phase.GUARDING)
					&& ArmorCoreController.canTargetedQuickSwitch(viewer, session.suitId());
			return allowed
					? PrimaryActionState.enabled(PrimaryAction.SWITCH)
					: PrimaryActionState.disabled(
							PrimaryAction.SWITCH,
							"换甲需要当前核心同时关联身上战甲和目标战甲，且两套战甲均无事务冲突");
		}
		return PrimaryActionState.none("装甲槽不是全空或同套穿齐状态，不能安全移动战甲");
	}

	private static boolean isTransitioning(
			ServerPlayer owner,
			PanelSession session,
			SuitRuntimeSnapshot.SuitState state
	) {
		if (session.kind() == SubjectKind.ESCORT && owner != null) {
			if (ArmorEscortController.isReturning(session.suitId())
					|| ArmorEscortController.isRecallForWearInProgress(
							owner, session.suitId())) return true;
		}
		if (session.kind() == SubjectKind.CRUISE && owner != null) {
			ArmorCruiseController.CruiseRuntimeView cruise =
					ArmorCruiseController.runtimeView(owner).orElse(null);
			if (cruise != null && session.suitId().equals(cruise.suitId())) {
				return cruise.phase() != ArmorCruisePhase.CRUISING
						&& cruise.phase() != ArmorCruisePhase.OWNER_DOWN;
			}
		}
		return state != null
				&& state.phase() != SuitRuntimeSnapshot.Phase.IDLE
				&& state.phase() != SuitRuntimeSnapshot.Phase.GUARDING
				&& state.phase() != SuitRuntimeSnapshot.Phase.OWNER_DOWN;
	}

	private static boolean isOwnerDown(ServerPlayer owner, PanelSession session,
			SuitRuntimeSnapshot.SuitState state) {
		if (state != null && state.phase() == SuitRuntimeSnapshot.Phase.OWNER_DOWN) return true;
		if (owner == null || session.kind() != SubjectKind.CRUISE) return false;
		ArmorCruiseController.CruiseRuntimeView cruise =
				ArmorCruiseController.runtimeView(owner).orElse(null);
		return cruise != null && session.suitId().equals(cruise.suitId())
				&& cruise.phase() == ArmorCruisePhase.OWNER_DOWN;
	}

	private static String lifecycleText(
			PanelSession session,
			ChargingPlatformBlockEntity platform,
			SuitRuntimeSnapshot.SuitState state
	) {
		if (state == null) {
			return switch (session.kind()) {
				case PLATFORM -> platform != null
						&& platform.getDisplayMode() == ChargingPlatformDisplayMode.PARTS
						? "平台待机 · 分件陈列"
						: "平台待机 · 整甲陈列";
				case CRUISE -> "独立巡航 · 近距载体在线";
				case ESCORT -> "护航待命 · 近距载体在线";
			};
		}
		return switch (state.phase()) {
			case RETURNING_PLATFORM -> "返航平台 · 正在接近泊位";
			case RECALLING_OWNER -> "高速召回 · 正在接近主人";
			case ASSEMBLING -> "机械着装 · 部件移动已锁定";
			case RETRACTING -> "装甲解体 · 切换事务进行中";
			case DEPLOYING -> "整甲脱离 · 正在展开";
			case DOCKING -> "近身停靠 · 即将着装";
			case PHASING -> "远距召回 · 相位接近中";
			case OWNER_DOWN -> "主人失能 · 战甲原地待命";
			case LAUNCHING -> "平台升空 · 正在建立航线";
			case TRANSIT -> "高速航渡 · 正在接近目标";
			case GUARDING -> state.carrierKind()
					== SuitRuntimeSnapshot.CarrierKind.INDEPENDENT
					? "独立巡航 · 载体稳定"
					: "护航作战 · 载体稳定";
			case IDLE -> switch (state.location()) {
				case PLATFORM -> platform != null
						&& platform.getDisplayMode() == ChargingPlatformDisplayMode.PARTS
						? "平台待机 · 分件陈列"
						: "平台待机 · 整甲陈列";
				case WORN -> "已由主人穿戴";
				case CARRIER -> state.carrierKind()
						== SuitRuntimeSnapshot.CarrierKind.INDEPENDENT
						? "独立巡航 · 待命"
						: "护航战甲 · 待命";
				case LOOSE -> "离台散落 · 等待找回";
				case UNKNOWN -> "位置待确认";
			};
		};
	}

	private static String roleText(ServerPlayer owner, UUID suitId,
			ArmorVariant variant, ResolvedTarget target) {
		String form = configurationSuffix(variant, target);
		if (owner == null) return "核心角色未知" + form;
		ArmorCoreController.CoreRuntimeView core = ArmorCoreController.runtimeView(owner);
		boolean linked = core.bindings().stream()
				.anyMatch(binding -> suitId.equals(binding.suitId()));
		if (!linked) return "未关联核心" + form;
		return (suitId.equals(core.primarySuitId()) ? "主战甲" : "副战甲") + form;
	}

	private static String configurationSuffix(ArmorVariant variant, ResolvedTarget target) {
		if (variant != ArmorVariant.SENTINEL || target == null) return "";
		ItemStack chest = ItemStack.EMPTY;
		if (target.platform() != null) {
			int chestIndex = ChargingPlatformBlockEntity.ARMOR_SLOTS.indexOf(EquipmentSlot.CHEST);
			if (chestIndex >= 0) chest = target.platform().getItem(chestIndex);
		} else if (target.carrier() != null) {
			chest = target.carrier().getItemBySlot(EquipmentSlot.CHEST);
		}
		SentinelArmorMode mode = ArmorConfigurationData.read(chest).mode();
		return mode == SentinelArmorMode.BREACH_MELEE
				? " · 重装攻坚" : " · 超驰飞行";
	}

	private static String positionText(PanelSession session, ResolvedTarget target) {
		BlockPos pos = target.platform() != null
				? target.platform().getBlockPos()
				: target.carrier().blockPosition();
		return session.dimension().location() + " · "
				+ pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	private static String distanceText(ServerPlayer viewer, ResolvedTarget target) {
		double blocks = target.platform() != null
				? Math.sqrt(viewer.distanceToSqr(target.platform().getBlockPos().getCenter()))
				: Math.sqrt(viewer.distanceToSqr(target.carrier()));
		return String.format(Locale.ROOT, "%.1f 格 / %.1f 米", blocks, blocks * 2.0D);
	}

	private static String conflictText(
			PanelSession session, SuitRuntimeSnapshot.SuitState state) {
		if (state == null) {
			return session.kind() == SubjectKind.PLATFORM
					? "核心运行状态未同步"
					: "无活动核心记录；近距物理控制仍可用";
		}
		if (state.conflicts().isEmpty()) return "无冲突";
		return state.conflicts().stream()
				.map(ArmorSuitPanelController::conflictLabel)
				.collect(Collectors.joining("、"));
	}

	private static String conflictLabel(SuitRuntimeSnapshot.Conflict conflict) {
		return switch (conflict) {
			case CORE_INACTIVE -> "核心未激活（近距载体仍可控制）";
			case OWNER_MISMATCH -> "主人身份不一致";
			case IDENTITY_MISMATCH -> "部件身份不一致";
			case PARTIAL_WORN_SET -> "穿戴部件不完整";
			case LOCATION_COLLISION -> "同套战甲出现位置冲突";
			case LEDGER_MISMATCH -> "物理位置与登记状态不一致";
			case PLATFORM_IDENTITY_MISMATCH -> "平台身份不一致";
			case PLATFORM_BROKEN -> "返回平台不可用";
			case TRANSITION_CONFLICT -> "其他移动事务占用";
		};
	}

	private static Optional<SuitRuntimeSnapshot.SuitState> runtimeState(
			ServerPlayer owner, UUID suitId) {
		return SuitStateResolver.resolve(owner).suits().stream()
				.filter(state -> state.suitId().equals(suitId))
				.findFirst();
	}

	private static ResolvedTarget resolve(ServerPlayer viewer, PanelSession session) {
		ServerLevel level = viewer.getServer().getLevel(session.dimension());
		if (level == null || viewer.level() != level) return null;
		if (session.kind() == SubjectKind.PLATFORM) {
			if (viewer.distanceToSqr(session.platformPos().getCenter())
					> PANEL_OPEN_DISTANCE_SQR
					|| !(level.getBlockEntity(session.platformPos())
					instanceof ChargingPlatformBlockEntity platform)
					|| !platform.getPlatformId().equals(session.platformId())
					|| !platform.isComplete()
					|| platform.getOwnerUuid().filter(session.ownerId()::equals).isEmpty()
					|| platform.getSuitId().filter(session.suitId()::equals).isEmpty()) {
				return null;
			}
			return new ResolvedTarget(platform, null);
		}
		Entity entity = level.getEntity(session.carrierId());
		if (!(entity instanceof ArmorStand carrier)
				|| viewer.distanceToSqr(carrier) > CARRIER_SESSION_DISTANCE_SQR
				|| subjectKind(carrier) != session.kind()) return null;
		SuitIdentity identity = readSuitIdentity(carrier).orElse(null);
		return identity != null
				&& identity.ownerId().equals(session.ownerId())
				&& identity.suitId().equals(session.suitId())
				? new ResolvedTarget(null, carrier)
				: null;
	}

	private static boolean isPhysicalCarrier(PanelSession session, ResolvedTarget target) {
		return session.kind() != SubjectKind.PLATFORM
				&& target.carrier() != null
				&& !target.carrier().isRemoved()
				&& target.carrier().isAlive();
	}

	private static Optional<SuitIdentity> readSuitIdentity(ArmorStand carrier) {
		UUID ownerId = null;
		UUID suitId = null;
		ArmorVariant variant = null;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = carrier.getItemBySlot(slot);
			if (!ModItems.isExactFlightArmor(stack, slot)) return Optional.empty();
			ArmorIdentity.Identity identity = ArmorIdentity.read(stack).orElse(null);
			if (identity == null || identity.slot() != slot) return Optional.empty();
			if (ownerId != null && !ownerId.equals(identity.ownerId())) return Optional.empty();
			if (suitId != null && !suitId.equals(identity.suitId())) return Optional.empty();
			ArmorVariant partVariant = ArmorVariantData.get(stack);
			if (variant != null && variant != partVariant) return Optional.empty();
			ownerId = identity.ownerId();
			suitId = identity.suitId();
			variant = partVariant;
		}
		return ownerId == null || suitId == null || variant == null
				? Optional.empty()
				: Optional.of(new SuitIdentity(ownerId, suitId, variant));
	}

	private static SubjectKind subjectKind(Entity entity) {
		if (ArmorCruiseController.isCruiseCarrier(entity)) return SubjectKind.CRUISE;
		if (ArmorEscortController.isEscortCarrier(entity)) return SubjectKind.ESCORT;
		return null;
	}

	private static boolean allArmorSlotsEmpty(ServerPlayer player) {
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			if (!player.getItemBySlot(slot).isEmpty()) return false;
		}
		return true;
	}

	private static Optional<UUID> wornFullSuitId(ServerPlayer player) {
		UUID suitId = null;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ArmorIdentity.Identity identity =
					ArmorIdentity.read(player.getItemBySlot(slot)).orElse(null);
			if (identity == null
					|| identity.slot() != slot
					|| !identity.ownerId().equals(player.getUUID())) {
				return Optional.empty();
			}
			if (suitId != null && !suitId.equals(identity.suitId())) return Optional.empty();
			suitId = identity.suitId();
		}
		return Optional.ofNullable(suitId);
	}

	private static String displayName(ArmorVariant variant, UUID suitId) {
		String compact = suitId.toString().replace("-", "");
		return variant.chineseName() + "-" + compact
				.substring(compact.length() - 4).toUpperCase(Locale.ROOT);
	}

	private static String ownerName(ServerPlayer viewer, UUID ownerId) {
		ServerPlayer online = viewer.getServer().getPlayerList().getPlayer(ownerId);
		return online != null
				? online.getGameProfile().getName()
				: ownerId.toString().substring(0, 8);
	}

	private static long expiresAt(ServerPlayer viewer) {
		return viewer.serverLevel().getGameTime() + SESSION_TTL_TICKS;
	}

	private record SuitIdentity(UUID ownerId, UUID suitId, ArmorVariant variant) {
	}

	private record ResolvedTarget(
			ChargingPlatformBlockEntity platform, ArmorStand carrier) {
	}

	private record PrimaryActionState(
			PrimaryAction action, boolean enabled, String reason) {
		private static PrimaryActionState enabled(PrimaryAction action) {
			return new PrimaryActionState(action, true, "");
		}

		private static PrimaryActionState disabled(PrimaryAction action, String reason) {
			return new PrimaryActionState(action, false, reason);
		}

		private static PrimaryActionState none(String reason) {
			return new PrimaryActionState(PrimaryAction.NONE, false, reason);
		}
	}

	private record ActionOutcome(boolean closePanel, String message) {
		private static ActionOutcome started(String message) {
			return new ActionOutcome(true, message);
		}

		private static ActionOutcome failed(String message) {
			return new ActionOutcome(false,
					message == null || message.isBlank() ? "操作未能启动；状态已刷新" : message);
		}
	}

	private record PanelSession(
			long token,
			UUID ownerId,
			UUID suitId,
			ArmorVariant variant,
			SubjectKind kind,
			ResourceKey<Level> dimension,
			BlockPos platformPos,
			UUID platformId,
			UUID carrierId,
			long expiresAt
	) {
		private static PanelSession platform(
				long token,
				UUID ownerId,
				UUID suitId,
				ArmorVariant variant,
				ResourceKey<Level> dimension,
				BlockPos pos,
				UUID platformId,
				long expiresAt
		) {
			return new PanelSession(token, ownerId, suitId, variant, SubjectKind.PLATFORM,
					dimension, pos.immutable(), platformId, null, expiresAt);
		}

		private static PanelSession carrier(
				long token,
				UUID ownerId,
				UUID suitId,
				ArmorVariant variant,
				SubjectKind kind,
				ResourceKey<Level> dimension,
				UUID carrierId,
				long expiresAt
		) {
			return new PanelSession(token, ownerId, suitId, variant, kind,
					dimension, null, null, carrierId, expiresAt);
		}

		private PanelSession refresh(long nextExpiry) {
			return new PanelSession(token, ownerId, suitId, variant, kind, dimension,
					platformPos, platformId, carrierId, nextExpiry);
		}
	}
}
