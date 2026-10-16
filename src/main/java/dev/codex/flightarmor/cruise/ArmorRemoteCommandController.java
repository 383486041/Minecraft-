package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.combat.ArmorCombatController;
import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import dev.codex.flightarmor.configuration.ArmorConfigurationController;
import dev.codex.flightarmor.combat.CombatTargetPolicy;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.core.ArmorEscortProfile;
import dev.codex.flightarmor.integrity.ArmorIntegrityController;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorBladeStatePayload;
import dev.codex.flightarmor.network.ArmorRemoteCommandAckPayload;
import dev.codex.flightarmor.network.ArmorRemoteCommandPayload;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.core.Rotations;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-authoritative remote control for one explicitly selected nearby physical suit.
 *
 * <p>The owner selection is only a routing hint. Selection, every key command and every controlled
 * tick independently resolve owner + suitId to the current real carrier, require the same
 * dimension and enforce the 48-block command radius. No client packet can select a target,
 * direction, damage value or movement endpoint.</p>
 */
public final class ArmorRemoteCommandController {
	private static final double MAX_DISTANCE_SQR =
			RemoteArmorCommandPolicy.MAX_COMMAND_DISTANCE
					* RemoteArmorCommandPolicy.MAX_COMMAND_DISTANCE;
	private static final double LOOK_RANGE = 64.0D;
	private static final double MELEE_SCAN_RANGE = 24.0D;
	private static final long COMMAND_EXPIRE_TICKS = 50L;
	private static final int BASIC_MELEE_COOLDOWN_TICKS = 18;
	private static final Map<UUID, RemoteState> STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> NEARBY_RECALL_SEQUENCES = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> NEAREST_REMOTE_SEQUENCES = new ConcurrentHashMap<>();
	private static final AtomicLong NEXT_SELECTION_GENERATION = new AtomicLong();

	private ArmorRemoteCommandController() {
	}

	/** Stable suit-panel API: select one exact, currently loaded nearby carrier. */
	public static Result select(ServerPlayer owner, UUID suitId) {
		ResolvedCarrier resolved = resolve(owner, suitId);
		if (resolved == null) {
			return Result.failed("只能选择同维度 48 格内、由你拥有且载体有效的巡航战甲");
		}
		// 遥控指令是核心网络能力。已经接管时仍允许取消，但核心离线不允许新接管。
		RemoteState existing = STATES.get(owner.getUUID());
		boolean cancellingSelection = existing != null && existing.matches(resolved);
		if (!cancellingSelection && !ArmorCoreController.coreNetworkActive(owner)) {
			return Result.failed("核心离线 · 遥控指令需要装甲核心在位");
		}
		RemoteState state = STATES.computeIfAbsent(owner.getUUID(), ignored -> new RemoteState());
		if (state.matches(resolved)) {
			syncCarrierBlade(owner, resolved.carrier(), false, state.nextBladeRevision());
			STATES.remove(owner.getUUID(), state);
			sendAck(owner, 0L, null, false,
					ArmorRemoteCommandAckPayload.Outcome.CLEARED,
					"已取消指令战甲，M/R/G/X/J 恢复控制身上战甲");
			return Result.success("已取消指令战甲；穿甲武器按键已恢复");
		}
		state.resetForSelection(resolved, nextSelectionGeneration());
		sendAck(owner, 0L, suitId, true,
				ArmorRemoteCommandAckPayload.Outcome.SELECTED,
				"指令战甲已锁定：M/R/G/X/J 将优先控制它");
		return Result.success("已选择附近战甲，可用 M 切换形态、R/G 指令攻击、X 切换近战、J 释放近战大招");
	}

	/** Stable suit-panel API: toggle continuous formation following for an exact nearby suit. */
	public static Result toggleFollow(ServerPlayer owner, UUID suitId) {
		ResolvedCarrier resolved = resolve(owner, suitId);
		if (resolved == null) {
			return Result.failed("战甲不在同维度 48 格指令范围内，无法切换任务位置");
		}
		RemoteState state = STATES.computeIfAbsent(owner.getUUID(), ignored -> new RemoteState());
		if (!state.matches(resolved)) {
			state.resetForSelection(resolved, nextSelectionGeneration());
			sendAck(owner, 0L, suitId, true,
					ArmorRemoteCommandAckPayload.Outcome.SELECTED,
					"指令战甲已锁定并接管任务位置");
		}
		if (state.following) {
			state.following = false;
			state.holding = true;
			state.holdAnchor = resolved.carrier.position();
			return Result.success("战甲已进入原地待命");
		}
		state.following = true;
		state.holding = false;
		state.holdAnchor = null;
		return Result.success("战甲已恢复护航编队");
	}

	public static boolean isSelected(UUID ownerId, UUID suitId) {
		RemoteState state = ownerId == null ? null : STATES.get(ownerId);
		return state != null && suitId != null && suitId.equals(state.suitId);
	}

	public static boolean isFollowing(UUID ownerId, UUID suitId) {
		RemoteState state = ownerId == null ? null : STATES.get(ownerId);
		return state != null && state.following && suitId != null && suitId.equals(state.suitId);
	}

	/** Handles a C2S key intent for the explicit, server-authoritative remote selection. */
	public static Result handleCommand(ServerPlayer owner, ArmorRemoteCommandPayload payload) {
		if (owner == null || payload == null || !owner.isAlive() || owner.isSpectator()) {
			return respond(owner, payload, null, false,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"当前状态无法远程控制战甲");
		}
		if (payload.command() == ArmorRemoteCommandPayload.Command.RECALL_NEAREST) {
			return handleNearestRecall(owner, payload);
		}
		if (payload.command() == ArmorRemoteCommandPayload.Command.TOGGLE_NEAREST_REMOTE) {
			return handleNearestRemoteToggle(owner, payload);
		}
		RemoteState state = STATES.get(owner.getUUID());
		if (state == null || state.suitId == null) {
			return respond(owner, payload, null, false,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"没有已选择的指令战甲；按键仍由身上战甲处理");
		}
		if (payload.selectionGeneration() != state.selectionGeneration) {
			return respond(owner, payload, state.suitId, true,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"指令属于旧的战甲选择会话，已拒绝执行");
		}
		if (payload.clientSequence() <= state.lastClientSequence) {
			return respond(owner, payload, state.suitId, true,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"重复或过期的远程指令");
		}
		state.lastClientSequence = payload.clientSequence();
		ResolvedCarrier resolved = resolve(owner, state.suitId);
		if (resolved == null || !state.matches(resolved)) {
			STATES.remove(owner.getUUID(), state);
			return respond(owner, payload, state.suitId, false,
					ArmorRemoteCommandAckPayload.Outcome.OUT_OF_RANGE,
					"所选战甲载体已更换或离开 48 格范围，遥控已取消");
		}
		long now = owner.serverLevel().getGameTime();
		if (state.pending != null && now > state.pending.expiresAt) {
			sendAck(owner, state.pending.clientSequence, state.suitId, true,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"上一条指令未能在响应窗口内执行：请检查冷却、能量、遮挡或目标距离");
			state.pending = null;
		}
		if (state.pending != null) {
			return respond(owner, payload, state.suitId, true,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"战甲仍在执行上一条指令，请等待完成后再操作");
		}
		CommandResponse response = switch (payload.command()) {
			case WRIST, CORE -> {
				if (!ArmorConfigurationAccess.allowsRangedCombat(resolved.carrier, now)) {
					yield CommandResponse.rejected(
							"当前形态未展开远程武器；守望型请切换至超驰飞行形态");
				}
				LivingEntity target = lookTarget(owner);
				if (target == null) yield CommandResponse.noTarget("准星方向没有可攻击目标");
				if (!insideControlSphere(owner, target.getBoundingBox().getCenter(), 0.0D)) {
					yield CommandResponse.outOfRange(
							"准星目标超出主人 48 格控制球，指令未接受");
				}
				int responseTicks = RemoteArmorCommandPolicy.responseTicks(
						owner.distanceTo(resolved.carrier));
				state.pending = new PendingCommand(
						payload.clientSequence(), payload.command(), target.getUUID(),
						now + responseTicks, now + COMMAND_EXPIRE_TICKS);
				yield CommandResponse.accepted("战甲已接收攻击指令，正在响应");
			}
			case TOGGLE_MELEE -> {
				if (!state.meleeMode
						&& !ArmorConfigurationAccess.allowsHeavyMelee(resolved.carrier, now)) {
					yield CommandResponse.rejected(
							"当前形态未锁定重型近战结构；守望型请切换至重装攻坚形态");
				}
				state.meleeMode = !state.meleeMode;
				syncCarrierBlade(owner, resolved.carrier, state.meleeMode,
						state.nextBladeRevision());
				LivingEntity lookedAt = state.meleeMode ? lookTarget(owner) : null;
				state.meleeTargetId = lookedAt != null
						&& insideControlSphere(
								owner, lookedAt.getBoundingBox().getCenter(), 2.0D)
						? lookedAt.getUUID() : null;
				yield CommandResponse.accepted(state.meleeMode
						? "战甲已切换近战贴地模式" : "战甲已切换远程悬空模式");
			}
			case MELEE_ULTIMATE -> {
				if (!state.meleeMode) yield CommandResponse.rejected("请先按 X 切换近战模式");
				if (!ArmorConfigurationAccess.allowsHeavyMelee(resolved.carrier, now)) {
					yield CommandResponse.rejected(
							"当前形态无法执行近战大招；守望型需要重装攻坚形态");
				}
				if (now < state.ultimateCooldownUntil) {
					yield CommandResponse.rejected("近战大招仍在冷却");
				}
				LivingEntity target = lookTarget(owner);
				if (target == null) {
					target = nearestMeleeTarget(owner, resolved.carrier, state.suitId);
				}
				if (target == null) yield CommandResponse.noTarget(
						"附近没有可执行近战大招的目标");
				if (!insideControlSphere(owner, target.getBoundingBox().getCenter(), 2.0D)) {
					yield CommandResponse.outOfRange(
							"近战目标超出主人 48 格控制球，指令未接受");
				}
				int responseTicks = RemoteArmorCommandPolicy.responseTicks(
						owner.distanceTo(resolved.carrier));
				state.meleeTargetId = target.getUUID();
				state.pending = new PendingCommand(
						payload.clientSequence(), payload.command(), target.getUUID(),
						now + responseTicks, now + COMMAND_EXPIRE_TICKS);
				yield CommandResponse.accepted("近战大招指令已发送，战甲正在接敌");
			}
			case TOGGLE_CONFIGURATION -> {
				ArmorConfigurationController.RemoteToggleResult result =
						ArmorConfigurationController.toggleRemote(owner, resolved.carrier);
				yield result.success()
						? CommandResponse.accepted(result.message())
						: CommandResponse.rejected(result.message());
			}
			case INVALID, RECALL_NEAREST, TOGGLE_NEAREST_REMOTE ->
					CommandResponse.rejected("不支持的远程战甲指令");
		};
		return respond(owner, payload, state.suitId, true,
				response.outcome(), response.message());
	}

	/**
	 * 遥控模式的一键开关，交给战甲组合键使用。
	 *
	 * <p>已有指令甲时直接交还自主控制；没有指令甲时，用与最近召回完全相同的确定性规则选出
	 * 最近的一套己方稳定载体并接管。两条路径都复用 {@link #select(ServerPlayer, UUID)}，
	 * 因此主人、suitId、载体版本、维度与 48 格距离仍然逐次重新校验，客户端不参与选目标。</p>
	 */
	private static Result handleNearestRemoteToggle(
			ServerPlayer owner, ArmorRemoteCommandPayload payload) {
		long lastSequence = NEAREST_REMOTE_SEQUENCES.getOrDefault(owner.getUUID(), -1L);
		if (payload.clientSequence() <= lastSequence) {
			return respond(owner, payload, null, false,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"重复或过期的遥控开关请求");
		}
		NEAREST_REMOTE_SEQUENCES.put(owner.getUUID(), payload.clientSequence());
		RemoteState existing = STATES.get(owner.getUUID());
		if (existing != null && existing.suitId != null) {
			return toggleOrReport(owner, payload, existing.suitId);
		}
		// 已经有指令甲时上面的分支负责交还控制；只有「新接管」才需要核心在位。
		if (!ArmorCoreController.coreNetworkActive(owner)) {
			return respond(owner, payload, null, false,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"核心离线 · 遥控指令需要装甲核心在位");
		}
		ResolvedCarrier candidate = nearestRecallCandidate(owner);
		if (candidate == null) {
			return respond(owner, payload, null, false,
					ArmorRemoteCommandAckPayload.Outcome.NO_TARGET,
					"附近 48 格内没有可接管的己方巡航或护航战甲");
		}
		return toggleOrReport(owner, payload, candidate.suitId);
	}

	/**
	 * select() 成功时已经发出 SELECTED/CLEARED 回执，失败时不发任何回执；
	 * 这里只在失败路径补一条拒绝回执，保证每次按键恰好一条反馈。
	 */
	private static Result toggleOrReport(
			ServerPlayer owner, ArmorRemoteCommandPayload payload, UUID suitId) {
		Result result = select(owner, suitId);
		if (result.success()) return result;
		return respond(owner, payload, null, false,
				ArmorRemoteCommandAckPayload.Outcome.REJECTED, result.message());
	}

	/**
	 * Unambiguous nearby recall entry used only by the suit-modifier chord.
	 *
	 * <p>A selected remote carrier keeps ownership of R for its wrist weapon, so this command is
	 * rejected whenever a selection exists. The server chooses one nearest stable carrier and
	 * revalidates empty armor slots before starting its normal recall transaction.</p>
	 */
	private static Result handleNearestRecall(
			ServerPlayer owner, ArmorRemoteCommandPayload payload) {
		if (payload.selectionGeneration() != 0L || STATES.containsKey(owner.getUUID())) {
			return respond(owner, payload, null, false,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"已有指令战甲时 R 只控制手炮；请先用遥控开关交还控制，再按组合键 + R 召回");
		}
		long lastSequence = NEARBY_RECALL_SEQUENCES.getOrDefault(owner.getUUID(), -1L);
		if (payload.clientSequence() <= lastSequence) {
			return respond(owner, payload, null, false,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"重复或过期的附近召回请求");
		}
		NEARBY_RECALL_SEQUENCES.put(owner.getUUID(), payload.clientSequence());
		if (!allArmorSlotsEmpty(owner)) {
			return respond(owner, payload, null, false,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"组合键 + R 的附近召回只在四个装甲槽为空时生效");
		}
		ResolvedCarrier candidate = nearestRecallCandidate(owner);
		if (candidate == null) {
			return respond(owner, payload, null, false,
					ArmorRemoteCommandAckPayload.Outcome.NO_TARGET,
					"附近 48 格内没有可穿戴的己方巡航战甲");
		}
		Result result;
		if (candidate.kind == CarrierKind.INDEPENDENT) {
			ArmorCruiseController.RecallCommandResult recalled =
					ArmorCruiseController.recallActiveSuitForSwitch(owner, candidate.suitId);
			result = recalled.success()
					? Result.success("最近的独立巡航战甲正在高速接近并穿戴")
					: Result.failed(recalled.message());
		} else {
			ArmorEscortController.EscortCommandResult recalled =
					ArmorEscortController.recallToOwnerForWear(owner, candidate.suitId);
			result = recalled.success()
					? Result.success("最近的护航战甲正在脱离编队并穿戴")
					: Result.failed(recalled.message());
		}
		return respond(owner, payload, candidate.suitId, false,
				result.success()
						? ArmorRemoteCommandAckPayload.Outcome.ACCEPTED
						: ArmorRemoteCommandAckPayload.Outcome.REJECTED,
				result.message());
	}

	private static ResolvedCarrier nearestRecallCandidate(ServerPlayer owner) {
		List<ResolvedCarrier> candidates = new ArrayList<>();
		ArmorCruiseController.CruiseRuntimeView independent =
				ArmorCruiseController.runtimeView(owner).orElse(null);
		if (independent != null && independent.suitId() != null) {
			ResolvedCarrier resolved = resolve(owner, independent.suitId());
			if (resolved != null && resolved.kind == CarrierKind.INDEPENDENT) {
				candidates.add(resolved);
			}
		}
		for (ArmorEscortController.RemoteHandle escort :
				ArmorEscortController.remoteHandles(owner)) {
			if (owner.distanceToSqr(escort.carrier()) > MAX_DISTANCE_SQR) continue;
			ResolvedCarrier resolved = resolve(owner, escort.suitId());
			if (resolved != null && resolved.kind == CarrierKind.ESCORT) {
				candidates.add(resolved);
			}
		}
		return candidates.stream()
				.min(Comparator.comparingDouble(
								(ResolvedCarrier candidate) ->
										owner.distanceToSqr(candidate.carrier))
						.thenComparing(candidate -> candidate.suitId.toString()))
				.orElse(null);
	}

	private static boolean allArmorSlotsEmpty(ServerPlayer owner) {
		return owner.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
				&& owner.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
				&& owner.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
				&& owner.getItemBySlot(EquipmentSlot.FEET).isEmpty();
	}

	/**
	 * Called before independent autonomous combat. True means this controller wrote movement/pose,
	 * so patrol and autonomous AI must not write the carrier again this tick.
	 */
	static boolean tickIndependent(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle, long now) {
		if (owner == null || handle == null || handle.phase() != ArmorCruisePhase.CRUISING) {
			return false;
		}
		ArmorCruiseController.CruiseRuntimeView view =
				ArmorCruiseController.runtimeView(owner).orElse(null);
		if (view == null || view.suitId() == null || !view.loaded()
				|| !view.carrierId().equals(handle.carrier().getUUID())) return false;
		return tickSelected(owner, view.suitId(), handle.carrier(), now);
	}

	/** Called by the suit-keyed escort loop before autonomous formation/combat writes movement. */
	static boolean tickEscort(ServerPlayer owner, UUID suitId, ArmorStand carrier, long now) {
		return tickSelected(owner, suitId, carrier, now);
	}

	public static void remove(ServerPlayer owner) {
		if (owner != null) {
			NEARBY_RECALL_SEQUENCES.remove(owner.getUUID());
			NEAREST_REMOTE_SEQUENCES.remove(owner.getUUID());
		}
		if (owner != null && STATES.remove(owner.getUUID()) != null) {
			sendAck(owner, 0L, null, false,
					ArmorRemoteCommandAckPayload.Outcome.CLEARED,
					"指令战甲控制已结束");
		}
	}

	public static void clear(UUID ownerId) {
		if (ownerId != null) {
			STATES.remove(ownerId);
			NEARBY_RECALL_SEQUENCES.remove(ownerId);
			NEAREST_REMOTE_SEQUENCES.remove(ownerId);
		}
	}

	public static void clearAll() {
		STATES.clear();
		NEARBY_RECALL_SEQUENCES.clear();
		NEAREST_REMOTE_SEQUENCES.clear();
	}

	/** Clears stale selections even when a transitioning carrier no longer enters a cruise tick. */
	public static void tickServer(MinecraftServer server) {
		if (server == null) return;
		for (Map.Entry<UUID, RemoteState> entry : STATES.entrySet()) {
			ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
			RemoteState state = entry.getValue();
			ResolvedCarrier resolved =
					owner == null || state.suitId == null ? null : resolve(owner, state.suitId);
			if (owner == null || resolved == null || !state.matches(resolved)) {
				if (STATES.remove(entry.getKey(), state) && owner != null) {
					sendAck(owner, 0L, state.suitId, false,
							ArmorRemoteCommandAckPayload.Outcome.OUT_OF_RANGE,
							"指令战甲载体已更换或离开 48 格范围，遥控自动取消");
				}
			}
		}
	}

	private static boolean tickSelected(ServerPlayer owner, UUID suitId,
			ArmorStand carrier, long now) {
		RemoteState state = STATES.get(owner.getUUID());
		if (state == null || !suitId.equals(state.suitId)) return false;
		ResolvedCarrier resolved = resolve(owner, suitId);
		if (resolved == null || resolved.carrier != carrier || !state.matches(resolved)) {
			if (STATES.remove(owner.getUUID(), state)) {
				sendAck(owner, 0L, suitId, false,
						ArmorRemoteCommandAckPayload.Outcome.OUT_OF_RANGE,
						"指令战甲载体失效或离开控制范围，遥控自动取消");
			}
			return false;
		}
		if (state.pending != null && now > state.pending.expiresAt) {
			sendAck(owner, state.pending.clientSequence, state.suitId, true,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"指令未能在响应窗口内执行：请检查冷却、能量、遮挡或目标距离");
			state.pending = null;
		}
		if (state.meleeMode
				&& !ArmorConfigurationAccess.allowsHeavyMelee(carrier, now)) {
			state.meleeMode = false;
			syncCarrierBlade(owner, carrier, false, state.nextBladeRevision());
			state.meleeTargetId = null;
			state.pending = null;
			sendAck(owner, 0L, state.suitId, true,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"战甲形态已变化，近战指令已安全退出");
		}
		if (state.pending != null
				&& (state.pending.command == ArmorRemoteCommandPayload.Command.WRIST
						|| state.pending.command == ArmorRemoteCommandPayload.Command.CORE)
				&& !ArmorConfigurationAccess.allowsRangedCombat(carrier, now)) {
			sendAck(owner, state.pending.clientSequence, state.suitId, true,
					ArmorRemoteCommandAckPayload.Outcome.REJECTED,
					"战甲形态已变化，远程攻击指令已取消");
			state.pending = null;
		}

		LivingEntity target = resolveTarget(owner.serverLevel(),
				state.pending == null ? state.meleeTargetId : state.pending.targetId);
		if (target != null && !isValidTarget(owner, target)) target = null;
		if (target != null && !insideControlSphere(
				owner, target.getBoundingBox().getCenter(),
				state.meleeMode ? 2.0D : 0.0D)) {
			if (state.pending != null) {
				sendAck(owner, state.pending.clientSequence, state.suitId, true,
						ArmorRemoteCommandAckPayload.Outcome.OUT_OF_RANGE,
						"指令目标已离开主人 48 格控制球，本次攻击已取消");
			}
			target = null;
			state.meleeTargetId = null;
			state.pending = null;
		}
		if (target == null && state.pending != null) {
			sendAck(owner, state.pending.clientSequence, state.suitId, true,
					ArmorRemoteCommandAckPayload.Outcome.NO_TARGET,
					"指令目标已失效，战甲已取消本次攻击");
			state.pending = null;
		}
		if (state.meleeMode) {
			if (target == null) {
				target = nearestMeleeTarget(owner, carrier, suitId);
				state.meleeTargetId = targetId(target);
			}
			if (target != null) {
				tickMelee(owner, carrier, state, target, now);
				return true;
			}
			if (!state.following && !state.holding) {
				state.holding = true;
				state.holdAnchor = carrier.position();
			}
			return tickIdleControl(owner, carrier, state, true);
		}

		if (state.pending != null) {
			if (target == null) {
				state.pending = null;
				return tickIdleControl(owner, carrier, state, false);
			}
			tickRangedCommand(owner, carrier, state, target, now);
			return true;
		}
		return tickIdleControl(owner, carrier, state, false);
	}

	private static boolean tickIdleControl(ServerPlayer owner, ArmorStand carrier,
			RemoteState state, boolean meleePose) {
		if (state.holding) {
			if (state.holdAnchor == null) state.holdAnchor = carrier.position();
			moveCarrier(carrier, state.holdAnchor, 0.85D);
			if (meleePose) setMeleeReadyPose(carrier);
			else setHoverPose(carrier);
			return true;
		}
		if (!state.following) return false;

		Vec3 baseFormation = predictedFormationPoint(owner, state.suitId, 0.0D, 1.15D);
		boolean lineOfSight = carrierHasRouteLineOfSight(carrier, baseFormation);
		Vec3 ownerVelocity = owner.getDeltaMovement();
		double ownerHorizontalSpeed =
				Math.sqrt(ownerVelocity.x * ownerVelocity.x + ownerVelocity.z * ownerVelocity.z);
		double distance = carrier.position().distanceTo(owner.position());
		RemoteFollowPolicy.Plan plan = RemoteFollowPolicy.plan(
				distance, ownerHorizontalSpeed, false, lineOfSight,
				carrier.level() == owner.level());
		if (!plan.controlsMovement()) {
			if (STATES.remove(owner.getUUID(), state)) {
				sendAck(owner, 0L, state.suitId, false,
						ArmorRemoteCommandAckPayload.Outcome.OUT_OF_RANGE,
						"战甲无法安全维持任务编队，已在边界前解除遥控");
			}
			return false;
		}
		Vec3 target = predictedFormationPoint(owner, state.suitId,
				plan.predictionTicks(), plan.verticalClearance());
		target = groundAwareFollowPoint(owner.serverLevel(), owner.position().y, target);
		target = clampToControlSphere(owner, target, 0.75D);
		moveCarrier(carrier, target, plan.maxSpeed());
		if (meleePose) setMeleeReadyPose(carrier);
		else setHoverPose(carrier);
		return true;
	}

	private static void tickRangedCommand(ServerPlayer owner, ArmorStand carrier,
			RemoteState state, LivingEntity target, long now) {
		Vec3 targetCenter = target.getBoundingBox().getCenter();
		Vec3 away = carrier.position().subtract(targetCenter);
		if (away.lengthSqr() < 1.0E-6D) away = owner.position().subtract(targetCenter);
		if (away.lengthSqr() < 1.0E-6D) away = new Vec3(0.0D, 0.0D, 1.0D);
		away = new Vec3(away.x, 0.0D, away.z).normalize();
		Vec3 firingPoint = clampToControlSphere(owner,
				targetCenter.add(away.scale(5.2D)).add(0.0D, 1.5D, 0.0D), 0.75D);
		moveCarrier(carrier, firingPoint, 1.65D);
		aimCarrier(carrier, target);
		if (now < state.pending.executeAt) return;
		boolean fired = switch (state.pending.command) {
			case WRIST -> ArmorCombatController.tryFireCruiseWrist(
					owner, carrier, target, carrier.isEyeInFluid(FluidTags.WATER)).fired();
			case CORE -> ArmorCombatController.tryFireRemoteCore(owner, carrier, target).fired();
			default -> false;
		};
		if (fired) state.pending = null;
	}

	private static void tickMelee(ServerPlayer owner, ArmorStand carrier,
			RemoteState state, LivingEntity target, long now) {
		Vec3 targetCenter = target.getBoundingBox().getCenter();
		Vec3 lowTarget = lowHoverPoint(owner.serverLevel(), target.position());
		Vec3 offset = carrier.position().subtract(targetCenter);
		if (offset.lengthSqr() < 1.0E-6D) offset = new Vec3(0.0D, 0.0D, 1.0D);
		offset = new Vec3(offset.x, 0.0D, offset.z).normalize().scale(1.45D);
		Vec3 approach = clampToControlSphere(owner,
				new Vec3(targetCenter.x + offset.x, lowTarget.y,
						targetCenter.z + offset.z), 0.75D);
		moveCarrier(carrier, approach, 1.85D);
		aimCarrier(carrier, target);
		setMeleeReadyPose(carrier);
		double distanceSqr = carrier.getBoundingBox().getCenter()
				.distanceToSqr(target.getBoundingBox().getCenter());
		if (state.pending != null
				&& state.pending.command == ArmorRemoteCommandPayload.Command.MELEE_ULTIMATE
				&& now >= state.pending.executeAt && distanceSqr <= 6.5D * 6.5D) {
			ArmorVariant variant = ModItems.fullSetVariant(carrier).orElse(ArmorVariant.DEFAULT);
			RemoteArmorCommandPolicy.UltimateProfile profile =
					RemoteArmorCommandPolicy.ultimateProfile(variant);
			if (ArmorCombatController.tryRemoteMeleeUltimate(
					owner, carrier, target, profile).fired()) {
				state.ultimateCooldownUntil = now + profile.cooldownTicks();
				state.pending = null;
				state.nextBasicMeleeTick = now + 20L;
			}
			return;
		}
		if (state.pending == null && now >= state.nextBasicMeleeTick
				&& distanceSqr <= 3.1D * 3.1D) {
			ArmorCombatController.tryRemoteMeleeStrike(owner, carrier, target);
			state.nextBasicMeleeTick = now + BASIC_MELEE_COOLDOWN_TICKS;
		}
	}

	private static ResolvedCarrier resolve(ServerPlayer owner, UUID suitId) {
		if (owner == null || suitId == null || !owner.isAlive() || owner.isSpectator()) return null;
		ArmorCruiseController.CruiseRuntimeView view =
				ArmorCruiseController.runtimeView(owner).orElse(null);
		if (view != null && view.loaded() && suitId.equals(view.suitId())
				&& view.phase() == ArmorCruisePhase.CRUISING
				&& view.dimension().equals(owner.level().dimension())) {
			ArmorCruiseController.CombatHandle handle = ArmorCruiseController.combatHandle(owner);
			if (handle != null && view.carrierId().equals(handle.carrier().getUUID())
					&& carrierValid(owner, suitId, handle.carrier())
					&& owner.distanceToSqr(handle.carrier()) <= MAX_DISTANCE_SQR) {
				return new ResolvedCarrier(
						suitId, handle.carrier(), CarrierKind.INDEPENDENT, view.sequenceId());
			}
		}
		ArmorEscortController.RemoteHandle escort =
				ArmorEscortController.remoteHandle(owner, suitId);
		if (escort != null && carrierValid(owner, suitId, escort.carrier())
				&& owner.distanceToSqr(escort.carrier()) <= MAX_DISTANCE_SQR) {
			return new ResolvedCarrier(
					suitId, escort.carrier(), CarrierKind.ESCORT, escort.carrierRevision());
		}
		return null;
	}

	private static boolean carrierValid(ServerPlayer owner, UUID suitId, ArmorStand carrier) {
		return carrier != null && carrier.isAlive() && !carrier.isRemoved()
				&& carrier.level() == owner.level()
				&& !ArmorIntegrityController.isDisabled(carrier)
				&& ModItems.fullSetVariant(carrier).isPresent()
				&& (ArmorCruiseController.isCruiseCarrier(carrier)
				|| ArmorEscortController.isEscortCarrier(carrier));
	}

	private static LivingEntity lookTarget(ServerPlayer owner) {
		Vec3 origin = owner.getEyePosition();
		Vec3 direction = owner.getViewVector(1.0F).normalize();
		Vec3 requestedEnd = origin.add(direction.scale(LOOK_RANGE));
		HitResult blockHit = owner.level().clip(new ClipContext(origin, requestedEnd,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
		Vec3 end = blockHit.getType() == HitResult.Type.MISS
				? requestedEnd : blockHit.getLocation();
		double maxAlong = origin.distanceTo(end);
		AABB search = owner.getBoundingBox().expandTowards(direction.scale(maxAlong)).inflate(2.0D);
		return owner.level().getEntitiesOfClass(LivingEntity.class, search,
						target -> isValidTarget(owner, target))
				.stream()
				.map(target -> new TargetScore(target,
						projection(origin, direction, target.getBoundingBox().getCenter()),
						perpendicularDistanceSqr(origin, direction,
								target.getBoundingBox().getCenter())))
				.filter(score -> score.along >= 0.0D && score.along <= maxAlong
						&& score.perpendicularSqr <= aimRadiusSqr(score.target))
				.min(Comparator.comparingDouble(TargetScore::perpendicularSqr)
						.thenComparingDouble(TargetScore::along))
				.map(TargetScore::target)
				.orElse(null);
	}

	private static LivingEntity nearestMeleeTarget(ServerPlayer owner, ArmorStand carrier,
			UUID suitId) {
		ArmorEscortProfile.CombatPolicy policy =
				ArmorCoreController.escortProfile(owner, suitId).attackMode();
		if (policy == ArmorEscortProfile.CombatPolicy.OFF) return null;
		AABB area = carrier.getBoundingBox().inflate(MELEE_SCAN_RANGE);
		return carrier.level().getEntitiesOfClass(LivingEntity.class, area,
						target -> isValidTarget(owner, target)
								&& (policy == ArmorEscortProfile.CombatPolicy.ALL
								|| target instanceof Enemy))
				.stream()
				.filter(target -> carrierHasLineOfSight(carrier, target))
				.filter(target -> insideControlSphere(
						owner, target.getBoundingBox().getCenter(), 2.0D))
				.min(Comparator.comparingDouble(carrier::distanceToSqr))
				.orElse(null);
	}

	private static boolean carrierHasLineOfSight(ArmorStand carrier, LivingEntity target) {
		Vec3 origin = carrier.position().add(0.0D, 1.35D, 0.0D);
		Vec3 destination = target.getBoundingBox().getCenter();
		HitResult hit = carrier.level().clip(new ClipContext(origin, destination,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, carrier));
		return hit.getType() == HitResult.Type.MISS
				|| hit.getLocation().distanceToSqr(destination) <= 0.30D * 0.30D;
	}

	private static boolean isValidTarget(ServerPlayer owner, LivingEntity target) {
		return target != null && target != owner && target.isAlive() && !target.isRemoved()
				&& target.level() == owner.level() && CombatTargetPolicy.canDamage(owner, target);
	}

	private static LivingEntity resolveTarget(ServerLevel level, UUID targetId) {
		if (level == null || targetId == null) return null;
		Entity entity = level.getEntity(targetId);
		return entity instanceof LivingEntity living ? living : null;
	}

	private static UUID targetId(LivingEntity target) {
		return target == null ? null : target.getUUID();
	}

	private static Vec3 predictedFormationPoint(ServerPlayer owner, UUID suitId,
			double predictionTicks, double verticalClearance) {
		Vec3 velocity = owner.getDeltaMovement();
		Vec3 horizontal = new Vec3(velocity.x, 0.0D, velocity.z);
		double horizontalSpeed = horizontal.length();
		if (horizontalSpeed > 4.0D) horizontal = horizontal.scale(4.0D / horizontalSpeed);
		Vec3 predictedOwner = owner.position().add(
				horizontal.scale(Math.max(0.0D, predictionTicks))).add(
				0.0D,
				Mth.clamp(velocity.y, -0.45D, 0.65D)
						* Math.min(6.0D, Math.max(0.0D, predictionTicks)),
				0.0D);
		double yaw = owner.getYRot() * Mth.DEG_TO_RAD;
		double side = (suitId.hashCode() & 1) == 0 ? 1.0D : -1.0D;
		double catchUp = predictionTicks > 8.0D ? 0.62D : 1.0D;
		Vec3 behind = new Vec3(Math.sin(yaw) * 2.6D * catchUp, verticalClearance,
				-Math.cos(yaw) * 2.6D * catchUp);
		Vec3 lateral = new Vec3(Math.cos(yaw) * 1.35D * side * catchUp, 0.0D,
				Math.sin(yaw) * 1.35D * side * catchUp);
		return predictedOwner.add(behind).add(lateral);
	}

	private static Vec3 groundAwareFollowPoint(
			ServerLevel level, double ownerY, Vec3 desired) {
		int x = Mth.floor(desired.x);
		int z = Mth.floor(desired.z);
		double surface = level.getHeight(
				Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		double y = desired.y;
		// Heightmaps point to the overworld surface even while the owner is underground. Only use
		// that sample when it is locally relevant, otherwise LOS steering remains conservative.
		if (surface <= ownerY + 7.0D) y = Math.max(y, surface + 0.25D);
		y = Mth.clamp(y, level.getMinBuildHeight() + 1.0D,
				level.getMaxBuildHeight() - 2.0D);
		return new Vec3(desired.x, y, desired.z);
	}

	private static boolean carrierHasRouteLineOfSight(
			ArmorStand carrier, Vec3 destination) {
		Vec3 origin = carrier.position().add(0.0D, 1.15D, 0.0D);
		Vec3 target = destination.add(0.0D, 0.65D, 0.0D);
		HitResult hit = carrier.level().clip(new ClipContext(origin, target,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, carrier));
		return hit.getType() == HitResult.Type.MISS
				|| hit.getLocation().distanceToSqr(target) <= 0.45D * 0.45D;
	}

	private static Vec3 lowHoverPoint(ServerLevel level, Vec3 desired) {
		int x = Mth.floor(desired.x);
		int z = Mth.floor(desired.z);
		double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		double y = Mth.clamp(ground + 0.15D, level.getMinBuildHeight() + 1.0D,
				level.getMaxBuildHeight() - 2.0D);
		return new Vec3(desired.x, y, desired.z);
	}

	private static boolean insideControlSphere(ServerPlayer owner, Vec3 point, double margin) {
		double radius = Math.max(1.0D,
				RemoteArmorCommandPolicy.MAX_COMMAND_DISTANCE - Math.max(0.0D, margin));
		return owner.position().distanceToSqr(point) <= radius * radius;
	}

	private static Vec3 clampToControlSphere(ServerPlayer owner, Vec3 desired, double margin) {
		Vec3 origin = owner.position();
		Vec3 delta = desired.subtract(origin);
		double radius = Math.max(1.0D,
				RemoteArmorCommandPolicy.MAX_COMMAND_DISTANCE - Math.max(0.0D, margin));
		double distanceSqr = delta.lengthSqr();
		if (distanceSqr <= radius * radius || distanceSqr < 1.0E-8D) return desired;
		return origin.add(delta.scale(radius / Math.sqrt(distanceSqr)));
	}

	private static void moveCarrier(ArmorStand carrier, Vec3 target, double maxSpeed) {
		ArmorIntegrityController.prepareControlledMovement(carrier);
		Vec3 delta = target.subtract(carrier.position());
		double distance = delta.length();
		if (distance < 1.0E-5D) {
			carrier.setDeltaMovement(Vec3.ZERO);
			return;
		}
		double speed = Math.min(distance, Math.min(maxSpeed, 0.24D + distance * 0.22D))
				* ArmorIntegrityController.movementMultiplier(carrier);
		Vec3 movement = delta.scale(speed / distance);
		carrier.setPos(carrier.position().add(movement));
		carrier.setDeltaMovement(movement);
		carrier.hasImpulse = true;
		float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
		carrier.setYRot(Mth.rotLerp(0.38F, carrier.getYRot(), yaw));
		carrier.setYBodyRot(carrier.getYRot());
	}

	private static void aimCarrier(ArmorStand carrier, Entity target) {
		Vec3 delta = target.getBoundingBox().getCenter()
				.subtract(carrier.position().add(0.0D, 1.25D, 0.0D));
		float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
		carrier.setYRot(Mth.rotLerp(0.48F, carrier.getYRot(), yaw));
		carrier.setYBodyRot(carrier.getYRot());
		carrier.setLeftArmPose(new Rotations(-35.0F, 0.0F, -8.0F));
		carrier.setRightArmPose(new Rotations(-31.0F, 0.0F, 9.0F));
	}

	private static void syncCarrierBlade(ServerPlayer owner, ArmorStand carrier,
			boolean extended, long revision) {
		if (owner == null || carrier == null) return;
		ArmorVariant variant = ModItems.fullSetVariant(carrier).orElse(ArmorVariant.VANGUARD);
		ArmorBladeStatePayload payload = new ArmorBladeStatePayload(
				carrier.getUUID(), revision, extended, variant);
		java.util.Set<ServerPlayer> recipients = new java.util.HashSet<>(PlayerLookup.tracking(carrier));
		recipients.add(owner);
		for (ServerPlayer recipient : recipients) {
			if (ServerPlayNetworking.canSend(recipient, ArmorBladeStatePayload.TYPE)) {
				ServerPlayNetworking.send(recipient, payload);
			}
		}
	}
	private static void setHoverPose(ArmorStand carrier) {
		carrier.setBodyPose(new Rotations(4.0F, 0.0F, 0.0F));
		carrier.setLeftArmPose(new Rotations(-17.0F, 0.0F, -13.0F));
		carrier.setRightArmPose(new Rotations(-12.0F, 0.0F, 15.0F));
		carrier.setLeftLegPose(new Rotations(8.0F, 0.0F, -5.0F));
		carrier.setRightLegPose(new Rotations(-6.0F, 0.0F, 5.0F));
	}

	private static void setMeleeReadyPose(ArmorStand carrier) {
		carrier.setBodyPose(new Rotations(7.0F, 0.0F, 0.0F));
		carrier.setLeftArmPose(new Rotations(-26.0F, 0.0F, -20.0F));
		carrier.setRightArmPose(new Rotations(-43.0F, 0.0F, 17.0F));
		carrier.setLeftLegPose(new Rotations(-8.0F, 0.0F, -4.0F));
		carrier.setRightLegPose(new Rotations(13.0F, 0.0F, 5.0F));
	}

	private static double projection(Vec3 origin, Vec3 direction, Vec3 point) {
		return point.subtract(origin).dot(direction);
	}

	private static double perpendicularDistanceSqr(Vec3 origin, Vec3 direction, Vec3 point) {
		Vec3 delta = point.subtract(origin);
		double along = delta.dot(direction);
		return delta.subtract(direction.scale(along)).lengthSqr();
	}

	private static double aimRadiusSqr(LivingEntity target) {
		double radius = Math.max(1.0D,
				Math.max(target.getBbWidth(), target.getBbHeight() * 0.35D) + 0.55D);
		return radius * radius;
	}

	private static Result respond(ServerPlayer owner, ArmorRemoteCommandPayload payload,
			UUID suitId, boolean active, ArmorRemoteCommandAckPayload.Outcome outcome,
			String message) {
		long sequence = payload == null ? 0L : payload.clientSequence();
		sendAck(owner, sequence, suitId, active, outcome, message);
		return outcome == ArmorRemoteCommandAckPayload.Outcome.ACCEPTED
				? Result.success(message)
				: Result.failed(message);
	}

	private static void sendAck(ServerPlayer owner, long sequence, UUID suitId,
			boolean active, ArmorRemoteCommandAckPayload.Outcome outcome, String message) {
		if (owner == null
				|| !ServerPlayNetworking.canSend(owner, ArmorRemoteCommandAckPayload.TYPE)) return;
		RemoteState state = active ? STATES.get(owner.getUUID()) : null;
		long selectionGeneration = state != null && suitId != null
				&& suitId.equals(state.suitId) ? state.selectionGeneration : 0L;
		ServerPlayNetworking.send(owner, new ArmorRemoteCommandAckPayload(
				sequence, selectionGeneration, suitId, active, outcome, message));
	}

	private static long nextSelectionGeneration() {
		long generation = NEXT_SELECTION_GENERATION.incrementAndGet();
		if (generation > 0L) return generation;
		NEXT_SELECTION_GENERATION.compareAndSet(generation, 1L);
		return NEXT_SELECTION_GENERATION.get();
	}

	public record Result(boolean success, String message) {
		public static Result success(String message) {
			return new Result(true, message);
		}

		public static Result failed(String message) {
			return new Result(false, message);
		}
	}

	private enum CarrierKind { INDEPENDENT, ESCORT }

	private record ResolvedCarrier(
			UUID suitId,
			ArmorStand carrier,
			CarrierKind kind,
			long carrierRevision) {
	}

	private record TargetScore(LivingEntity target, double along, double perpendicularSqr) {
	}

	private record PendingCommand(long clientSequence,
			ArmorRemoteCommandPayload.Command command, UUID targetId,
			long executeAt, long expiresAt) {
	}

	private record CommandResponse(
			ArmorRemoteCommandAckPayload.Outcome outcome, String message) {
		private static CommandResponse accepted(String message) {
			return new CommandResponse(ArmorRemoteCommandAckPayload.Outcome.ACCEPTED, message);
		}

		private static CommandResponse rejected(String message) {
			return new CommandResponse(ArmorRemoteCommandAckPayload.Outcome.REJECTED, message);
		}

		private static CommandResponse outOfRange(String message) {
			return new CommandResponse(ArmorRemoteCommandAckPayload.Outcome.OUT_OF_RANGE, message);
		}

		private static CommandResponse noTarget(String message) {
			return new CommandResponse(ArmorRemoteCommandAckPayload.Outcome.NO_TARGET, message);
		}
	}

	private static final class RemoteState {
		UUID suitId;
		UUID carrierId;
		CarrierKind carrierKind;
		long carrierRevision;
		long selectionGeneration;
		boolean following;
		boolean holding;
		Vec3 holdAnchor;
		boolean meleeMode;
		long lastClientSequence = -1L;
		UUID meleeTargetId;
		PendingCommand pending;
		long nextBasicMeleeTick;
		long ultimateCooldownUntil;
		long bladeRevision;

		long nextBladeRevision() {
			return ++bladeRevision;
		}

		boolean matches(ResolvedCarrier resolved) {
			return resolved != null
					&& resolved.suitId.equals(suitId)
					&& resolved.carrier.getUUID().equals(carrierId)
					&& resolved.kind == carrierKind
					&& resolved.carrierRevision == carrierRevision;
		}

		void resetForSelection(ResolvedCarrier resolved, long nextGeneration) {
			suitId = resolved.suitId;
			carrierId = resolved.carrier.getUUID();
			carrierKind = resolved.kind;
			carrierRevision = resolved.carrierRevision;
			selectionGeneration = nextGeneration;
			following = false;
			holding = false;
			holdAnchor = null;
			meleeMode = false;
			meleeTargetId = null;
			pending = null;
			nextBasicMeleeTick = 0L;
			ultimateCooldownUntil = 0L;
			bladeRevision = 0L;
		}
	}
}
