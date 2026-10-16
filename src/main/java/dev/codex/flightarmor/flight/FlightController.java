package dev.codex.flightarmor.flight;

import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.combat.ArmorCombatController;
import dev.codex.flightarmor.combat.ImpactBlastController;
import dev.codex.flightarmor.cruise.ArmorCruiseController;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.AutomaticHoverAckPayload;
import dev.codex.flightarmor.network.AutomaticHoverEndPayload;
import dev.codex.flightarmor.network.FlightInputPayload;
import dev.codex.flightarmor.network.FlightVisualStatePayload;
import dev.codex.flightarmor.network.AutomaticHoverEngagePayload;
import dev.codex.flightarmor.network.AutomaticHoverSource;
import dev.codex.flightarmor.platform.ChargingPlatformController;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class FlightController {
	private static final int INPUT_TIMEOUT_TICKS = 20;
	private static final int AUTOMATIC_ACK_TIMEOUT_TICKS = 20 * 5;
	private static final int AUTOMATIC_ENGAGE_RETRY_TICKS = 10;
	private static final int BRAKE_TO_HOVER_CONFIRM_TICKS = 5;
	private static final AtomicLong NEXT_AUTOMATIC_ENGAGEMENT_ID = new AtomicLong();
	private static final Map<UUID, FlightState> STATES = new ConcurrentHashMap<>();

	private FlightController() {
	}

	public static void handleInput(ServerPlayer player, FlightInputPayload payload) {
		FlightState previous = STATES.get(player.getUUID());
		if (previous != null && !previous.dimension().equals(player.level().dimension())) {
			stopFlight(player, previous);
			return;
		}
		// 非零 token 只能更新服务端已经创建的同一自动飞行会话；旧维度或已取消会话的迟到包会被拒绝。
		if (payload.flightSessionToken() != 0L
				&& (previous == null || previous.automaticEngagementId() != payload.flightSessionToken())) {
			sendAutomaticHoverEnd(player, payload.flightSessionToken());
			return;
		}
		if (previous != null && previous.automaticEngagementId() != 0L
				&& previous.automaticEngagementId() != payload.flightSessionToken()) {
			return;
		}

		if (!payload.active()) {
			// 自动悬停命令与确认包共用同一条有序 C2S 连接；确认前忽略事件创建前已经在路上的旧 OFF。
			if (previous != null && previous.automaticEngagementId() != 0L
					&& !previous.automaticAcknowledged()) {
				return;
			}
			stopFlight(player, previous);
			return;
		}
		if (!canAcceptFlightInput(player, previous)) {
			stopFlight(player, previous);
			return;
		}

		boolean bootAssemblyOverride = previous != null && previous.bootAssemblyOverride()
				&& !canUseRegularFlightGear(player) && isBootHoverAuthorized(player, previous);
		// 战靴先行阶段只负责稳定悬空。核心完成前，服务端不信任客户端的移动、俯冲或加速输入。
		boolean controlsUnlocked = !bootAssemblyOverride;
		boolean ascending = controlsUnlocked && payload.ascending() && !payload.descending();
		boolean descending = controlsUnlocked && payload.descending() && !payload.ascending();
		boolean originalMayfly = previous == null ? player.getAbilities().mayfly : previous.originalMayfly();
		boolean originalFlying = previous == null ? player.getAbilities().flying : previous.originalFlying();
		long engagementId = payload.flightSessionToken();
		boolean automaticAcknowledged = previous == null || previous.automaticAcknowledged();
		player.getAbilities().mayfly = true;
		player.getAbilities().flying = false;
		// Keep the server-forced hover latched while S remains held. Otherwise the same key that
		// finished braking would immediately become "hover backwards" on the next input packet.
		boolean brakeToHoverLatch = previous != null && previous.brakeToHoverLatch()
				&& payload.movingBackward() && !payload.movingForward();
		boolean nextDiving = controlsUnlocked && payload.diving() && !brakeToHoverLatch;
		boolean nextBoosting = controlsUnlocked && payload.boosting() && !brakeToHoverLatch;
		boolean wasDiving = previous != null && previous.diving();
		if (!wasDiving && nextDiving) {
			ArmorCombatController.beginDiveImpactAction(player);
		} else if (wasDiving && !nextDiving) {
			ArmorCombatController.tryDiveStopImpact(
					player, previous.lastDiveVelocity(), player.position());
			ArmorCombatController.endDiveImpactAction(player);
		}
		boolean sustainedBrake = nextDiving && payload.movingBackward() && !payload.movingForward();
		float steeringYaw = Mth.wrapDegrees(Float.isFinite(payload.steeringYaw())
				? payload.steeringYaw() : player.getYRot());
		float steeringPitch = Mth.clamp(Float.isFinite(payload.steeringPitch())
				? payload.steeringPitch() : player.getXRot(), -90.0F, 90.0F);
		FlightState state = new FlightState(
				nextDiving, nextBoosting,
				controlsUnlocked && payload.movingForward(),
				controlsUnlocked && payload.movingBackward() && !brakeToHoverLatch,
				controlsUnlocked && payload.movingLeft(), controlsUnlocked && payload.movingRight(),
				ascending, descending, bootAssemblyOverride, engagementId, automaticAcknowledged,
				previous == null ? null : previous.automaticSource(),
				previous == null ? 0L : previous.sourceSequenceId(),
				previous == null ? 0L : previous.sourceAttemptId(),
				previous == null ? player.tickCount : previous.engagementStartTick(),
				previous == null ? player.tickCount : previous.lastEngageSendTick(),
				player.tickCount, originalMayfly, originalFlying, player.level().dimension(),
				steeringYaw, steeringPitch,
				previous != null && previous.diving() && nextDiving
						? previous.lastDiveVelocity() : Vec3.ZERO,
				sustainedBrake && previous != null ? previous.brakeToHoverReadyTicks() : 0,
				brakeToHoverLatch
		);
		STATES.put(player.getUUID(), state);
		setDivePose(player, state.diving());
		if (previous == null || visualStateChanged(previous, state)) {
			broadcastVisualState(player, true, state.diving(), state.boosting(), isMoving(state));
		}
	}

	/** 客户端已经接管自动悬停；从这一刻起可以正常处理玩家主动发送的关闭输入。 */
	public static void handleAutomaticHoverAck(ServerPlayer player, AutomaticHoverAckPayload payload) {
		FlightState state = STATES.get(player.getUUID());
		if (state == null || state.automaticEngagementId() != payload.engagementId()) return;
		STATES.replace(player.getUUID(), state, state.withAutomaticAcknowledged(player.tickCount));
	}

	/**
	 * 只由两条权威着装完成路径调用。玩家仍在空中下落时，服务端先建立悬停状态并刹停，
	 * 再通知所属客户端接续常规输入；不会扫描背包、召回战甲或在后续普通下落时重复触发。
	 */
	public static boolean tryEngageHoverAfterArmorReady(ServerPlayer player,
			AutomaticHoverSource source, long sourceSequenceId, long sourceAttemptId) {
		FlightState previous = STATES.get(player.getUUID());
		boolean continuingEmergencyRecall = previous != null
				&& previous.automaticSource() == AutomaticHoverSource.EMERGENCY_RECALL;
		boolean upgradingBootHover = previous != null && previous.bootAssemblyOverride()
				&& previous.sourceSequenceId() == sourceSequenceId
				&& previous.sourceAttemptId() == sourceAttemptId
				&& previous.dimension().equals(player.level().dimension())
				&& ((source == AutomaticHoverSource.ASSEMBLY_COMPLETE
						&& previous.automaticSource() == AutomaticHoverSource.ASSEMBLY_BOOTS)
					|| (source == AutomaticHoverSource.DOCKING_COMPLETE
						&& previous.automaticSource() == AutomaticHoverSource.DOCKING_BOOTS));
		if (!canUseRegularFlightGear(player) || player.onGround()
				|| (!continuingEmergencyRecall && !upgradingBootHover && !player.isInWaterOrBubble()
				&& player.getDeltaMovement().y >= -0.03D)
				|| !ServerPlayNetworking.canSend(player, AutomaticHoverEngagePayload.TYPE)) {
			return false;
		}
		return beginAutomaticHover(player, source, sourceSequenceId, sourceAttemptId, false);
	}
	/**
	 * 快速换甲事务完成后的唯一补偿入口。若着装完成路径已建立有效悬停则不重复建会话；
	 * 只有事务期间被刻意清除的会话确实缺失时，才在完整战甲上恢复正式输入。
	 */
	public static boolean ensureHoverAfterQuickSwitch(ServerPlayer player,
			long sourceSequenceId, long sourceAttemptId) {
		FlightState existing = STATES.get(player.getUUID());
		if (existing != null && canContinueFlight(player, existing)) return true;
		if (existing != null) stopFlight(player, existing);
		if (!canUseRegularFlightGear(player) || player.onGround()
				|| !ServerPlayNetworking.canSend(player, AutomaticHoverEngagePayload.TYPE)) {
			return false;
		}
		return beginAutomaticHover(player, AutomaticHoverSource.QUICK_SWITCH_COMPLETE,
				sourceSequenceId, sourceAttemptId, false);
	}
	/**
	 * Authoritative entry for an ordinary equipment-slot false-to-true edge. Unlike assembly and
	 * docking, no client request can create this event: the server slot sampler supplies the edge id.
	 */
	public static OrdinaryEquipHoverResult tryEngageHoverAfterOrdinaryEquip(ServerPlayer player,
			long edgeId, long attemptId, boolean permitGroundLift) {
		FlightState existing = STATES.get(player.getUUID());
		if (existing != null && existing.dimension().equals(player.level().dimension())
				&& canContinueFlight(player, existing)) {
			return OrdinaryEquipHoverResult.ENGAGED;
		}
		if (existing != null) stopFlight(player, existing);
		if (!canUseRegularFlightGear(player)
				|| !ServerPlayNetworking.canSend(player, AutomaticHoverEngagePayload.TYPE)) {
			return OrdinaryEquipHoverResult.DENIED;
		}
		if (player.onGround()) {
			if (!permitGroundLift) return OrdinaryEquipHoverResult.WAITING_FOR_LIFTOFF;
			FlightPhysics.prepareBootLaunch(player);
			player.hurtMarked = true;
			return OrdinaryEquipHoverResult.GROUND_LIFT_PREPARED;
		}
		return beginAutomaticHover(player, AutomaticHoverSource.ORDINARY_EQUIP,
				edgeId, attemptId, false)
				? OrdinaryEquipHoverResult.ENGAGED : OrdinaryEquipHoverResult.DENIED;
	}

	/** Physical gear plus ownership evidence; situational and transaction gates are applied later. */
	public static boolean hasAuthorizedOrdinaryFlightGear(ServerPlayer player) {
		return player != null && ModItems.hasFlightGear(player)
				&& ChargingPlatformController.canUseEquippedFlightArmor(player);
	}

	/** Used only to consume an equipment edge that an existing server flight session already owns. */
	public static boolean hasActiveFlightSession(ServerPlayer player) {
		FlightState state = player == null ? null : STATES.get(player.getUUID());
		return state != null && state.dimension().equals(player.level().dimension())
				&& canContinueFlight(player, state);
	}

	public enum OrdinaryEquipHoverResult {
		ENGAGED,
		GROUND_LIFT_PREPARED,
		WAITING_FOR_LIFTOFF,
		DENIED
	}

	/** 双靴已经在动画中完成锁定时提前接管下落，其余装甲继续组装。 */
	public static boolean tryEngageHoverAfterBootLock(ServerPlayer player,
			AutomaticHoverSource source, long sourceSequenceId, long sourceAttemptId) {
		if (!hasBaseFlightEligibility(player)
				|| !ChargingPlatformController.canUseEquippedFlightArmor(player)
				|| !isBootHoverReady(player, source, sourceSequenceId, sourceAttemptId)
				|| !ServerPlayNetworking.canSend(player, AutomaticHoverEngagePayload.TYPE)) {
			return false;
		}
		if (player.onGround()) {
			// 地面着装先由战靴轻抬离地；下一服务端 tick 再建立不会被 onGround 门禁立即关闭的悬停会话。
			FlightPhysics.prepareBootLaunch(player);
			player.hurtMarked = true;
			return false;
		}
		return beginAutomaticHover(player, source, sourceSequenceId, sourceAttemptId, true);
	}

	/** 巡航战甲尚未抵达时建立只读反重力制动；生存判定完全由服务端完成。 */
	public static boolean tryEngageEmergencyRecallHover(ServerPlayer player,
			long sourceSequenceId, long sourceAttemptId) {
		if (!ChargingPlatformController.canUseEquippedFlightArmor(player)) return false;
		FlightState existing = STATES.get(player.getUUID());
		if (existing != null && existing.bootAssemblyOverride()
				&& existing.automaticSource() == AutomaticHoverSource.EMERGENCY_RECALL
				&& existing.sourceSequenceId() == sourceSequenceId
				&& existing.sourceAttemptId() == sourceAttemptId
				&& existing.dimension().equals(player.level().dimension())
				&& ArmorCruiseController.isEmergencyRecallHoverAuthorized(
				player, sourceSequenceId, sourceAttemptId)) {
			FlightPhysics.prepareHoverEngagement(player);
			player.hurtMarked = true;
			return true;
		}
		if (!hasBaseFlightEligibility(player) || player.onGround()
				|| !ArmorCruiseController.isEmergencyRecallHoverAuthorized(
				player, sourceSequenceId, sourceAttemptId)) {
			return false;
		}
		return beginAutomaticHover(player, AutomaticHoverSource.EMERGENCY_RECALL,
				sourceSequenceId, sourceAttemptId, true);
	}

	private static boolean beginAutomaticHover(ServerPlayer player, AutomaticHoverSource source,
			long sourceSequenceId, long sourceAttemptId, boolean bootAssemblyOverride) {
		FlightState previous = STATES.get(player.getUUID());
		boolean originalMayfly = previous == null ? player.getAbilities().mayfly : previous.originalMayfly();
		boolean originalFlying = previous == null ? player.getAbilities().flying : previous.originalFlying();
		long engagementId = NEXT_AUTOMATIC_ENGAGEMENT_ID.incrementAndGet();
		if (previous != null && previous.automaticEngagementId() != 0L) {
			sendAutomaticHoverEnd(player, previous.automaticEngagementId());
		}
		player.getAbilities().mayfly = true;
		player.getAbilities().flying = false;
		FlightState hover = new FlightState(
				false, false, false, false, false, false,
				false, false, bootAssemblyOverride, engagementId, false,
				source, sourceSequenceId, sourceAttemptId,
				player.tickCount, player.tickCount,
				player.tickCount, originalMayfly, originalFlying, player.level().dimension(),
				player.getYRot(), player.getXRot(), Vec3.ZERO,
				0, false);
		STATES.put(player.getUUID(), hover);
		setDivePose(player, false);
		FlightPhysics.prepareHoverEngagement(player);
		player.hurtMarked = true;
		if (previous == null || visualStateChanged(previous, hover)) {
			broadcastVisualState(player, true, false, false, false);
		}
		player.onUpdateAbilities();
		sendAutomaticHoverEngage(player, hover);
		return true;
	}

	public static void tickServer(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			FlightState state = STATES.get(player.getUUID());
			if (state == null) {
				continue;
			}
			boolean waitingForAck = state.automaticEngagementId() != 0L && !state.automaticAcknowledged();
			boolean timedOut = waitingForAck
					? player.tickCount - state.engagementStartTick() > AUTOMATIC_ACK_TIMEOUT_TICKS
					: player.tickCount - state.lastInputTick() > INPUT_TIMEOUT_TICKS;
			if (!canContinueFlight(player, state)
					|| !state.dimension().equals(player.level().dimension()) || timedOut) {
				stopFlight(player, state);
				continue;
			}
			if (player.onGround()) {
				if (state.diving()) {
					ArmorCombatController.tryDiveImpact(player, state.lastDiveVelocity(), player.position());
					ArmorCombatController.endDiveImpactAction(player);
				}
				stopFlight(player, state);
				continue;
			}
			if (waitingForAck
					&& player.tickCount - state.lastEngageSendTick() >= AUTOMATIC_ENGAGE_RETRY_TICKS) {
				FlightState resent = state.withLastEngageSendTick(player.tickCount);
				if (STATES.replace(player.getUUID(), state, resent)) {
					state = resent;
					sendAutomaticHoverEngage(player, state);
				}
			}

			setDivePose(player, state.diving());
			if (state.diving()) {
				FlightPhysics.ImpactFrame impactFrame = FlightPhysics.impactFrame(player);
				if (impactFrame != null && ArmorCombatController.tryDivePathImpact(
						player, impactFrame.previousPosition(), impactFrame.currentPosition(),
						impactFrame.preContactVelocity(), player.horizontalCollision)) {
					FlightPhysics.resetMotionSample(player);
					FlightState impacted = state.withPhysicsSample(player.getDeltaMovement(), 0);
					STATES.replace(player.getUUID(), state, impacted);
					player.hurtMarked = true;
					continue;
				}
			}
			if (player.getAbilities().flying) {
				player.getAbilities().flying = false;
				player.onUpdateAbilities();
			}
			FlightPhysics.FlightStep flightStep = FlightPhysics.apply(
					player, FlightPhysics.lookDirection(
							state.steeringYaw(), state.steeringPitch()),
					state.diving(), state.boosting(), state.movingForward(),
					state.movingBackward(), state.movingLeft(), state.movingRight(),
					state.ascending(), state.descending());
		SonicBoomController.sample(player, flightStep.totalSpeed());
		// 撞击爆破：撞到方块就在撞击点炸开一个球，而不是沿运动方向钻一条隧道。
		// 速度门槛、爆坑半径曲线、冷却与全部护栏都在 ImpactBlastController 内部。
		if (player.horizontalCollision || player.verticalCollision) {
			// 碰撞这一 tick 的 getDeltaMovement() 已经被 FlightPhysics 按碰撞保留率削过
			// （疾影 0.16、守望 0.18），拿它当撞击力，巡航撞山连门槛都够不着。
			// impactFrame 里的 preContactVelocity 才是撞击前真正飞出来的速度。
			FlightPhysics.ImpactFrame frame = FlightPhysics.impactFrame(player);
			Vec3 impact = frame != null ? frame.preContactVelocity() : player.getDeltaMovement();
			if (impact.lengthSqr() <= 1.0E-6D) impact = player.getDeltaMovement();
			if (impact.lengthSqr() > 1.0E-6D) {
				int blasted = ImpactBlastController.detonate(player,
						player.getBoundingBox().getCenter(), impact, impact.length());
				if (blasted > 0) {
					// 炸开了就保住撞击前的动量；否则每撞一下都被撞停，穿山会变成一格一格地蹭。
					player.setDeltaMovement(impact);
					player.hurtMarked = true;
				}
			}
		}
		boolean sustainedBrake = state.diving() && state.movingBackward() && !state.movingForward();
			int readyTicks = sustainedBrake && flightStep.settledForHover()
					? Math.min(BRAKE_TO_HOVER_CONFIRM_TICKS, state.brakeToHoverReadyTicks() + 1) : 0;
			FlightState sampled = state.withPhysicsSample(
					state.diving() ? player.getDeltaMovement() : Vec3.ZERO, readyTicks);
			if (readyTicks >= BRAKE_TO_HOVER_CONFIRM_TICKS) {
				FlightState hover = sampled.afterBrakeToHover();
				if (STATES.replace(player.getUUID(), state, hover)) {
					ArmorCombatController.tryDiveStopImpact(
							player, sampled.lastDiveVelocity(), player.position());
					ArmorCombatController.endDiveImpactAction(player);
					FlightPhysics.settleBrakeToHover(player);
					setDivePose(player, false);
					broadcastVisualState(player, true, false, false, isMoving(hover));
				}
			} else {
				STATES.replace(player.getUUID(), state, sampled);
			}
			// Continuous high-speed client prediction already consumes the exact same quantized
			// steering snapshot. Forcing a stale velocity packet every tick made fast turns fight
			// the local result; retain forced sync for hover, contact and the final brake handoff.
			if (!state.diving() || player.horizontalCollision || player.verticalCollision
					|| readyTicks >= BRAKE_TO_HOVER_CONFIRM_TICKS) {
				player.hurtMarked = true;
			}
		}
	}

	public static boolean isDiveActive(ServerPlayer player) {
		FlightState state = STATES.get(player.getUUID());
		return state != null && state.diving();
	}

	public static boolean isBoostActive(ServerPlayer player) {
		FlightState state = STATES.get(player.getUUID());
		return state != null && state.boosting();
	}

	/**
	 * 快速换甲事务的服务器悬停闸门。安全的战靴先行自动会话只清空方向输入并保留
	 * engagement/授权；普通飞行状态则完整停止，避免每 tick 发送 end/engage 抖动。
	 */
	public static boolean suspendForQuickSwitch(ServerPlayer player) {
		FlightState state = STATES.get(player.getUUID());
		if (state == null) return false;
		boolean safeBootHover = state.bootAssemblyOverride()
				&& state.automaticEngagementId() != 0L
				&& state.dimension().equals(player.level().dimension())
				&& isBootHoverAuthorized(player, state);
		if (!safeBootHover) {
			stopFlight(player, state);
			return false;
		}
		FlightState suspended = state.suspendedForQuickSwitch(player.tickCount);
		if (!STATES.replace(player.getUUID(), state, suspended)) return false;
		setDivePose(player, false);
		if (visualStateChanged(state, suspended)) {
			broadcastVisualState(player, true, false, false, false);
		}
		return true;
	}
	public static void remove(ServerPlayer player) {
		stopFlight(player, STATES.get(player.getUUID()));
	}

	/** 新玩家开始追踪目标时，补发目标当前状态，避免中途进入视野看不到展开效果。 */
	public static void syncVisualStateTo(ServerPlayer trackedPlayer, ServerPlayer recipient) {
		FlightState state = STATES.get(trackedPlayer.getUUID());
		if (state != null) {
			sendVisualState(recipient, new FlightVisualStatePayload(trackedPlayer.getUUID(), true, state.diving(),
					state.boosting(), isMoving(state)));
		}
	}

	private static void setDivePose(ServerPlayer player, boolean diving) {
		if (diving) {
			player.startFallFlying();
		} else {
			player.stopFallFlying();
		}
	}

	private static void stopFlight(ServerPlayer player, FlightState state) {
		ArmorCombatController.endDiveImpactAction(player);
		SonicBoomController.remove(player);
		ImpactBlastController.remove(player);
		if (state == null) {
			STATES.remove(player.getUUID());
			FlightPhysics.resetMotionSample(player);
			player.stopFallFlying();
			return;
		}
		if (!STATES.remove(player.getUUID(), state)) {
			return;
		}
		FlightPhysics.resetMotionSample(player);
		player.stopFallFlying();
		broadcastVisualState(player, false, false, false, false);
		if (state.bootAssemblyOverride()) {
			revokeBootHoverAuthorization(player, state);
		}
		if (state.automaticEngagementId() != 0L) {
			sendAutomaticHoverEnd(player, state.automaticEngagementId());
		}
		player.getAbilities().mayfly = state.originalMayfly();
		player.getAbilities().flying = state.originalFlying();
		player.onUpdateAbilities();
	}

	private static void sendAutomaticHoverEngage(ServerPlayer player, FlightState state) {
		if (state.automaticSource() == null
				|| !ServerPlayNetworking.canSend(player, AutomaticHoverEngagePayload.TYPE)) {
			return;
		}
		ServerPlayNetworking.send(player, new AutomaticHoverEngagePayload(
				state.automaticEngagementId(), state.automaticSource(),
				state.sourceSequenceId(), state.sourceAttemptId(), state.dimension().location()));
	}

	private static void sendAutomaticHoverEnd(ServerPlayer player, long engagementId) {
		if (engagementId != 0L && ServerPlayNetworking.canSend(player, AutomaticHoverEndPayload.TYPE)) {
			ServerPlayNetworking.send(player, new AutomaticHoverEndPayload(engagementId));
		}
	}

	private static boolean visualStateChanged(FlightState previous, FlightState current) {
		return previous.diving() != current.diving()
				|| previous.boosting() != current.boosting()
				|| isMoving(previous) != isMoving(current);
	}

	/** 相反方向同时按下会抵消，不应被广播成巡航输入。 */
	private static boolean isMoving(FlightState state) {
		return state.movingForward() != state.movingBackward()
				|| state.movingLeft() != state.movingRight()
				|| state.ascending() != state.descending();
	}

	private static void broadcastVisualState(ServerPlayer player, boolean active, boolean diving, boolean boosting,
			boolean moving) {
		FlightVisualStatePayload payload = new FlightVisualStatePayload(player.getUUID(), active, diving, boosting, moving);
		sendVisualState(player, payload);
		for (ServerPlayer trackingPlayer : PlayerLookup.tracking(player)) {
			if (trackingPlayer != player) {
				sendVisualState(trackingPlayer, payload);
			}
		}
	}

	private static void sendVisualState(ServerPlayer recipient, FlightVisualStatePayload payload) {
		if (ServerPlayNetworking.canSend(recipient, FlightVisualStatePayload.TYPE)) {
			ServerPlayNetworking.send(recipient, payload);
		}
	}

	private static boolean hasBaseFlightEligibility(ServerPlayer player) {
		return player.isAlive() && !player.isSpectator() && !player.isPassenger()
				&& !player.isSleeping() && !player.isInLava();
	}

	private static boolean canUseRegularFlightGear(ServerPlayer player) {
		return hasBaseFlightEligibility(player)
				&& !ArmorAssemblyController.isAssembling(player)
				&& ChargingPlatformController.canUseEquippedFlightArmor(player)
				&& ModItems.hasFlightGear(player);
	}

	private static boolean canAcceptFlightInput(ServerPlayer player, FlightState previous) {
		return canUseRegularFlightGear(player)
				|| (previous != null && previous.bootAssemblyOverride()
				&& hasBaseFlightEligibility(player)
				&& ChargingPlatformController.canUseEquippedFlightArmor(player)
				&& isBootHoverAuthorized(player, previous));
	}

	private static boolean canContinueFlight(ServerPlayer player, FlightState state) {
		return canUseRegularFlightGear(player)
				|| (state.bootAssemblyOverride() && hasBaseFlightEligibility(player)
				&& ChargingPlatformController.canUseEquippedFlightArmor(player)
				&& isBootHoverAuthorized(player, state));
	}

	private static boolean isBootHoverReady(ServerPlayer player, AutomaticHoverSource source,
			long sequenceId, long attemptId) {
		return switch (source) {
			case ASSEMBLY_BOOTS -> ArmorAssemblyController.isBootHoverReady(player, sequenceId, attemptId);
			case DOCKING_BOOTS -> ArmorCruiseController.isBootHoverReady(player, sequenceId, attemptId);
			case EMERGENCY_RECALL -> ArmorCruiseController.isEmergencyRecallHoverAuthorized(
					player, sequenceId, attemptId);
			default -> false;
		};
	}

	private static boolean isBootHoverAuthorized(ServerPlayer player, FlightState state) {
		if (state.automaticSource() == null) return false;
		return switch (state.automaticSource()) {
			case ASSEMBLY_BOOTS -> ArmorAssemblyController.isBootHoverAuthorized(
					player, state.sourceSequenceId(), state.sourceAttemptId());
			case DOCKING_BOOTS -> ArmorCruiseController.isBootHoverAuthorized(
					player, state.sourceSequenceId(), state.sourceAttemptId());
			case EMERGENCY_RECALL -> ArmorCruiseController.isEmergencyRecallHoverAuthorized(
					player, state.sourceSequenceId(), state.sourceAttemptId());
			default -> false;
		};
	}

	private static void revokeBootHoverAuthorization(ServerPlayer player, FlightState state) {
		if (state.automaticSource() == null) return;
		switch (state.automaticSource()) {
			case ASSEMBLY_BOOTS -> ArmorAssemblyController.revokeBootHoverAuthorization(
					player, state.sourceSequenceId(), state.sourceAttemptId());
			case DOCKING_BOOTS -> ArmorCruiseController.revokeBootHoverAuthorization(
					player, state.sourceSequenceId(), state.sourceAttemptId());
			case EMERGENCY_RECALL -> ArmorCruiseController.revokeEmergencyRecallHoverAuthorization(
					player, state.sourceSequenceId(), state.sourceAttemptId());
			default -> {
			}
		}
	}

	private record FlightState(
			boolean diving, boolean boosting, boolean movingForward, boolean movingBackward, boolean movingLeft, boolean movingRight,
			boolean ascending, boolean descending, boolean bootAssemblyOverride,
			long automaticEngagementId, boolean automaticAcknowledged,
			AutomaticHoverSource automaticSource, long sourceSequenceId, long sourceAttemptId,
			int engagementStartTick, int lastEngageSendTick,
			int lastInputTick, boolean originalMayfly, boolean originalFlying,
			ResourceKey<Level> dimension, float steeringYaw, float steeringPitch,
			Vec3 lastDiveVelocity,
			int brakeToHoverReadyTicks, boolean brakeToHoverLatch
	) {
		private FlightState suspendedForQuickSwitch(int serverTick) {
			return new FlightState(false, false, false, false, false, false,
					false, false, bootAssemblyOverride, automaticEngagementId, automaticAcknowledged,
					automaticSource, sourceSequenceId, sourceAttemptId,
					engagementStartTick, lastEngageSendTick,
					serverTick, originalMayfly, originalFlying, dimension,
					steeringYaw, steeringPitch, Vec3.ZERO,
					0, false);
		}
		private FlightState withAutomaticAcknowledged(int acknowledgedTick) {
			return new FlightState(diving, boosting, movingForward, movingBackward, movingLeft, movingRight,
					ascending, descending, bootAssemblyOverride, automaticEngagementId, true,
					automaticSource, sourceSequenceId, sourceAttemptId,
					engagementStartTick, lastEngageSendTick,
					acknowledgedTick, originalMayfly, originalFlying, dimension,
					steeringYaw, steeringPitch, lastDiveVelocity,
					brakeToHoverReadyTicks, brakeToHoverLatch);
		}

		private FlightState withPhysicsSample(Vec3 velocity, int readyTicks) {
			return new FlightState(diving, boosting, movingForward, movingBackward, movingLeft, movingRight,
					ascending, descending, bootAssemblyOverride, automaticEngagementId, automaticAcknowledged,
					automaticSource, sourceSequenceId, sourceAttemptId,
					engagementStartTick, lastEngageSendTick,
					lastInputTick, originalMayfly, originalFlying, dimension,
					steeringYaw, steeringPitch,
					velocity == null ? Vec3.ZERO : velocity,
					Math.max(0, readyTicks), brakeToHoverLatch);
		}

		private FlightState afterBrakeToHover() {
			return new FlightState(false, false, movingForward, false, movingLeft, movingRight,
					ascending, descending, bootAssemblyOverride, automaticEngagementId, automaticAcknowledged,
					automaticSource, sourceSequenceId, sourceAttemptId,
					engagementStartTick, lastEngageSendTick,
					lastInputTick, originalMayfly, originalFlying, dimension,
					steeringYaw, steeringPitch, Vec3.ZERO,
					0, true);
		}

		private FlightState withLastEngageSendTick(int sendTick) {
			return new FlightState(diving, boosting, movingForward, movingBackward, movingLeft, movingRight,
					ascending, descending, bootAssemblyOverride, automaticEngagementId, automaticAcknowledged,
					automaticSource, sourceSequenceId, sourceAttemptId,
					engagementStartTick, sendTick,
					lastInputTick, originalMayfly, originalFlying, dimension,
					steeringYaw, steeringPitch, lastDiveVelocity,
					brakeToHoverReadyTicks, brakeToHoverLatch);
		}
	}
}
