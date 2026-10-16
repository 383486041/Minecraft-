package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.cruise.ArmorCruiseController;
import dev.codex.flightarmor.cruise.ArmorEscortController;
import dev.codex.flightarmor.cruise.RemoteArmorCommandPolicy;
import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import dev.codex.flightarmor.flight.FlightController;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorAttackFxPayload;
import dev.codex.flightarmor.network.ArmorAttackIntentPayload;
import dev.codex.flightarmor.network.ArmorCombatOwnerStatePayload;
import dev.codex.flightarmor.network.ArmorCombatVisualStatePayload;
import dev.codex.flightarmor.network.ArmorBladeStatePayload;
import dev.codex.flightarmor.platform.ArmorIdentity;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.VariantCombatProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 战甲武器的服务端权威入口。
 *
 * <p>客户端只提交单调递增的按键意图。本类决定发射手、射线、伤害、资源消耗、
 * 冷却和所有下行表现。主入口需要注册四种 payload，并把服务端 tick/连接生命周期
 * 转发到本类公开方法。</p>
 */
public final class ArmorCombatController {
	private static final long PERSISTENCE_DIRTY_INTERVAL_TICKS = 20L;
	private static final double INTENT_BUCKET_CAPACITY = 4.0D;
	private static final double INTENT_REFILL_PER_TICK = 0.25D;
	private static final long DENIED_SYNC_INTERVAL_TICKS = 4L;
	private static final AtomicLong NEXT_SERVER_ATTACK_ID = new AtomicLong();
	private static final AtomicLong NEXT_DIVE_ACTION_SEQUENCE = new AtomicLong();
	private static final Map<UUID, ArmorCombatState> STATES = new ConcurrentHashMap<>();
	/**
	 * Runtime-only weapon/resource lanes for physical autonomous carriers. A player's worn suit
	 * intentionally remains in {@link #STATES}; autonomous suits must never share its phase,
	 * cooldowns, heat or energy merely because they have the same owner.
	 */
	private static final Map<CruiseLaneKey, ArmorCombatState> CRUISE_STATES =
			new ConcurrentHashMap<>();
	private static final Map<UUID, DiveImpactAction> DIVE_ACTIONS = new ConcurrentHashMap<>();

	private ArmorCombatController() {
	}

	/** 注册停服前快照，随后仍由世界 SavedData 的标准保存流程负责写盘。 */
	public static void initialize() {
		ServerLifecycleEvents.SERVER_STOPPING.register(ArmorCombatController::handleServerStopping);
		AttackEntityCallback.EVENT.register((player, world, hand, target, hitResult) -> {
			if (world.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			ArmorCombatState state = stateFor(serverPlayer);
			if (!state.bladeExtended) return InteractionResult.PASS;
			long now = combatTime(serverPlayer.getServer());
			ArmorCombatGate.Result gate = ArmorCombatGate.evaluate(serverPlayer,
					ArmorAttackType.ENERGY_BLADE_SLASH, ArmorAttackAction.PRESS, state, now,
					ArmorCombatTuning.BLADE_SLASH_ENERGY_COST);
			if (gate.allowed()) fireBladeSlash(serverPlayer, state, now);
			return InteractionResult.SUCCESS;
		});
	}

	/** C2S ArmorAttackIntentPayload 的唯一接收入口。 */
	public static void handleIntent(ServerPlayer player, ArmorAttackIntentPayload payload) {
		ArmorCombatState state = stateFor(player);
		long now = combatTime(player.getServer());
		if (payload.clientSequence() <= state.lastClientSequence) {
			// 同一可靠连接上的重复包无需确认；此前权威回包已经在同一有序通道中。
			return;
		}
		if (payload.attackType() == ArmorAttackType.INVALID) {
			if (!consumeIntentToken(state, now)) return;
			state.lastClientSequence = payload.clientSequence();
			sendDeniedState(player, state, ArmorCombatDenyReason.UNSUPPORTED_ATTACK, now);
			return;
		}
		if (payload.action() == ArmorAttackAction.INVALID) {
			if (!consumeIntentToken(state, now)) return;
			state.lastClientSequence = payload.clientSequence();
			sendDeniedState(player, state, ArmorCombatDenyReason.INVALID_ACTION, now);
			return;
		}

		// 活动攻击的 CANCEL 优先于普通令牌桶，避免关界面/开始脱甲后因丢包残留蓄力。
		if (payload.action() == ArmorAttackAction.CANCEL) {
			state.lastClientSequence = payload.clientSequence();
			if (state.phase != ArmorCombatPhase.IDLE
					&& state.attackType == payload.attackType()
					&& (payload.attackType() == ArmorAttackType.WRIST_GRAVITY_PULSE
					|| payload.attackType() == ArmorAttackType.CORE_BEAM
					|| payload.attackType() == ArmorAttackType.HAND_BEAM_PULSE
					|| payload.attackType() == ArmorAttackType.HAND_BEAM_LANCE)) {
				resetPhase(player, state, now, true);
				sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
			}
			return;
		}
		// 非取消意图继续使用限流；序列只在实际消费后推进。
		if (!consumeIntentToken(state, now)) return;
		state.lastClientSequence = payload.clientSequence();

		switch (payload.attackType()) {
			case WRIST_GRAVITY_PULSE -> handleWristIntent(player, state, payload.action(), now);
			case CORE_BEAM -> handleCoreIntent(player, state, payload.action(), now);
			case ENERGY_BLADE_TOGGLE -> handleBladeToggle(player, state, payload.action(), now);
			case ENERGY_BLADE_SLASH -> handleBladeSlash(player, state, payload.action(), now);
			case ENERGY_BLADE_COMBO -> handleBladeCombo(player, state, payload.action(), now);
			case HAND_BEAM_PULSE -> handleHandBeamPulse(player, state, payload.action(), now);
			case HAND_BEAM_LANCE -> handleHandBeamLance(player, state, payload.action(), now);
			case GRAVITY_DIVE_IMPACT, CRUISE_RAM, CRUISE_CORE_BEAM,
					CRUISE_MISSILE_VOLLEY, CRUISE_GROUND_SLAM, INVALID ->
					sendDeniedState(player, state, ArmorCombatDenyReason.UNSUPPORTED_ATTACK, now);
		}
	}

	/** 每个 END_SERVER_TICK 调用一次。 */
	public static void tickServer(MinecraftServer server) {
		long now = combatTime(server);
		for (Map.Entry<UUID, ArmorCombatState> entry : STATES.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) continue;
			ArmorCombatState state = entry.getValue();

			if (!state.dimension.equals(player.level().dimension())) {
				resetPhase(player, state, now, true);
				state.dimension = player.level().dimension();
				sendOwnerState(player, state, ArmorCombatDenyReason.DIMENSION_CHANGED, now);
				continue;
			}
			ArmorCombatDenyReason generalLifecycle =
					ArmorCombatGate.evaluateLifecycle(player);
			if (state.bladeExtended && generalLifecycle != ArmorCombatDenyReason.NONE) {
				setBladeExtended(player, state, false);
			}
			ArmorCombatDenyReason lifecycle = state.phase == ArmorCombatPhase.IDLE
					? generalLifecycle
					: ArmorCombatGate.evaluateLifecycle(
							player, state.attackType, now);
			if (lifecycle == ArmorCombatDenyReason.NONE
					&& state.phase == ArmorCombatPhase.CHARGING
					&& state.attackType == ArmorAttackType.CORE_BEAM
					&& FlightController.isBoostActive(player)) {
				lifecycle = ArmorCombatDenyReason.BOOSTING;
			}
			if (state.phase != ArmorCombatPhase.IDLE && lifecycle != ArmorCombatDenyReason.NONE) {
				cancel(player, lifecycle);
				continue;
			}

			advancePhase(player, state, now);
			float oldEnergy = state.energy;
			float oldHeat = state.heat;
			boolean oldOverheated = state.overheated;
			tickResources(player, state, now);
			boolean resourcesChanged = Math.abs(state.energy - oldEnergy) >= 0.001F
					|| Math.abs(state.heat - oldHeat) >= 0.001F
					|| oldOverheated != state.overheated;
			// 冷却剩余量只按在线 tick 递减；到 0 的那一 tick 也必须写入内存快照。
			boolean cooldownAdvancing = state.wristCooldownUntilTick > 0L
					&& now <= state.wristCooldownUntilTick
					|| state.coreCooldownUntilTick > 0L && now <= state.coreCooldownUntilTick
					|| state.diveImpactCooldownUntilTick > 0L && now <= state.diveImpactCooldownUntilTick;
			boolean recoveryDelayAdvancing = state.lastFireTick != Long.MIN_VALUE
					&& now <= state.lastFireTick + ArmorCombatTuning.PASSIVE_RECOVERY_DELAY_TICKS;
			if (resourcesChanged || cooldownAdvancing || recoveryDelayAdvancing) {
				state.persistenceDirty = true;
			}
			checkpointPersistentState(player, state, now, false);
			if (resourcesChanged && now - state.lastOwnerSyncGameTime >= 5L) {
				sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
			}
		}
		// Carrier lanes are deliberately non-persistent. Prune only the exact physical carrier;
		// another suit owned by the same player remains untouched.
		for (CruiseLaneKey key : List.copyOf(CRUISE_STATES.keySet())) {
			ServerPlayer owner = server.getPlayerList().getPlayer(key.ownerId());
			Entity carrier = null;
			for (ServerLevel level : server.getAllLevels()) {
				carrier = level.getEntity(key.carrierId());
				if (carrier != null) break;
			}
			if (owner == null || carrier == null || carrier.isRemoved() || !carrier.isAlive()) {
				removeCruiseLane(key.ownerId(), key.suitId(), key.carrierId(), 0L);
			}
		}
	}

	/**
	 * 玩家加入时只恢复持久资源；连接序列、攻击号和动作阶段始终从全新运行态开始。
	 * 当前 JOIN 路径即使只调用 syncStateTo，也会通过相同的 stateFor 逻辑按需加载。
	 */
	public static void handleJoin(ServerPlayer player) {
		ArmorCombatState stale = STATES.remove(player.getUUID());
		long now = combatTime(player.getServer());
		if (stale != null) checkpointPersistentState(player, stale, now, true);
		ArmorCombatState state = stateFor(player);
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
		sendBladeState(player, player, state);
	}

	/** 重生后保留能量与冷却，但清除旧实体上的瞬时攻击阶段并同步新维度。 */
	public static void handleRespawn(ServerPlayer player) {
		ArmorCombatState state = stateFor(player);
		long now = combatTime(player.getServer());
		DIVE_ACTIONS.remove(player.getUUID());
		resetPhase(player, state, now, true);
		state.dimension = player.level().dimension();
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	/** 换维完成后原子更新战斗维度，避免新维度中的第一次攻击被误判为跨维请求。 */
	public static void handleDimensionChange(ServerPlayer player) {
		ArmorCombatState state = stateFor(player);
		long now = combatTime(player.getServer());
		DIVE_ACTIONS.remove(player.getUUID());
		resetPhase(player, state, now, true);
		state.dimension = player.level().dimension();
		sendOwnerState(player, state, ArmorCombatDenyReason.DIMENSION_CHANGED, now);
	}

	/** 连接关闭时强制保存资源，然后仅清理连接内运行态。 */
	public static void remove(ServerPlayer player) {
		ArmorCombatState state = STATES.remove(player.getUUID());
		if (state != null) {
			checkpointPersistentState(player, state, combatTime(player.getServer()), true);
		}
		cancelDelayedCruiseAttacksByOwner(player.getUUID());
		CRUISE_STATES.keySet().removeIf(key -> key.ownerId().equals(player.getUUID()));
		DIVE_ACTIONS.remove(player.getUUID());
	}

	/**
	 * Invalidates every delayed hit belonging to one independently identified escort suit.
	 * The escort controller also advances its action sequence, preventing an old task from
	 * becoming valid again after an unload/reload or a later lifecycle transition.
	 */
	public static void cancelDelayedCruiseAttacks(UUID suitId, UUID carrierId,
			long carrierRevision) {
		ArmorHomingMissileEntity.discardByCarrier(
				suitId, carrierId, carrierRevision);
	}

	private static void cancelDelayedCruiseAttacksByOwner(UUID ownerId) {
		ArmorHomingMissileEntity.discardByOwner(ownerId);
	}

	/** 停服前把节流窗口内的最后状态并入 SavedData，不直接操作存档文件。 */
	private static void handleServerStopping(MinecraftServer server) {
		long now = combatTime(server);
		for (Map.Entry<UUID, ArmorCombatState> entry : STATES.entrySet()) {
			ArmorCombatSavedData.flush(server, entry.getKey(), persistentEntry(entry.getValue(), now));
		}
		STATES.clear();
		CRUISE_STATES.clear();
		ArmorHomingMissileEntity.clearLiveIndex();
	}

	/** 着甲、脱甲、巡航、死亡或维度切换开始前调用，幂等清除活动攻击阶段。 */
	public static void cancel(ServerPlayer player, ArmorCombatDenyReason reason) {
		// This is the worn-player lane only. Autonomous carriers owned by the same player have
		// independent lifecycle and must not lose their in-flight missiles here.
		DIVE_ACTIONS.remove(player.getUUID());

		ArmorCombatState state = STATES.get(player.getUUID());
		if (state == null) return;
		long now = combatTime(player.getServer());
		if (state.bladeExtended) setBladeExtended(player, state, false);
		resetPhase(player, state, now, true);
		sendOwnerState(player, state, reason == null ? ArmorCombatDenyReason.NONE : reason, now);
	}

	/** 新追踪者补发仍在进行的视觉阶段；私有资源状态只会发给所属玩家本人。 */
	public static void syncStateTo(ServerPlayer tracked, ServerPlayer recipient) {
		ArmorCombatState state = tracked.getUUID().equals(recipient.getUUID())
				? stateFor(tracked)
				: STATES.get(tracked.getUUID());
		if (state == null) return;
		long now = combatTime(tracked.getServer());
		if (tracked.getUUID().equals(recipient.getUUID())) {
			sendOwnerState(recipient, state, ArmorCombatDenyReason.NONE, now);
		}
		sendBladeState(recipient, tracked, state);
		if (state.phase != ArmorCombatPhase.IDLE) {
			sendVisualState(recipient, visualPayload(tracked, state, now));
		}
	}

	private static void handleWristIntent(ServerPlayer player, ArmorCombatState state,
			ArmorAttackAction action, long now) {
		boolean underwater = player.isEyeInFluid(FluidTags.WATER);
		float energyCost = underwater
				? ArmorCombatTuning.UNDERWATER_WRIST_ENERGY_COST
				: ArmorCombatTuning.WRIST_ENERGY_COST;
		ArmorCombatGate.Result gate = ArmorCombatGate.evaluate(
				player, ArmorAttackType.WRIST_GRAVITY_PULSE, action, state, now, energyCost);
		if (!acceptGate(player, state, gate, now)) return;
		if (action == ArmorAttackAction.PRESS) {
			startWristCharge(player, state, now);
		} else if (action == ArmorAttackAction.RELEASE) {
			releaseWristPulse(player, state, underwater, now, energyCost);
		}
	}

	private static void startWristCharge(ServerPlayer player, ArmorCombatState state, long now) {
		state.serverAttackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.visualRevision = 0;
		state.attackType = ArmorAttackType.WRIST_GRAVITY_PULSE;
		state.activeLeftHand = state.nextLeftHand;
		state.phase = ArmorCombatPhase.CHARGING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + ArmorCombatTuning.WRIST_MAX_CHARGE_TICKS;
		broadcastVisual(player, visualPayload(player, state, now));
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	private static void releaseWristPulse(ServerPlayer player, ArmorCombatState state,
			boolean underwater, long now, float minimumEnergyCost) {
		long heldTicks = Math.max(0L, now - state.phaseStartGameTime);
		if (heldTicks < ArmorCombatTuning.WRIST_MIN_CHARGE_TICKS) {
			resetPhase(player, state, now, true);
			sendDeniedState(player, state, ArmorCombatDenyReason.CHARGE_TOO_SHORT, now);
			return;
		}
		float charge = Mth.clamp(
				(float) (heldTicks - ArmorCombatTuning.WRIST_MIN_CHARGE_TICKS)
						/ Math.max(1, ArmorCombatTuning.WRIST_MAX_CHARGE_TICKS
						- ArmorCombatTuning.WRIST_MIN_CHARGE_TICKS),
				0.0F, 1.0F);
		float energyCost = Mth.lerp(charge, minimumEnergyCost,
				underwater ? ArmorCombatTuning.WRIST_MAX_ENERGY_COST * 1.15F
						: ArmorCombatTuning.WRIST_MAX_ENERGY_COST);
		float energyCharge = Mth.clamp((state.energy - minimumEnergyCost)
				/ Math.max(0.001F, energyCost - minimumEnergyCost), 0.0F, 1.0F);
		charge = Math.min(charge, energyCharge);
		energyCost = Mth.lerp(charge, minimumEnergyCost,
				underwater ? ArmorCombatTuning.WRIST_MAX_ENERGY_COST * 1.15F
						: ArmorCombatTuning.WRIST_MAX_ENERGY_COST);
		fireWristPulse(player, state, underwater, now, energyCost, charge);
	}

	private static void handleCoreIntent(ServerPlayer player, ArmorCombatState state,
			ArmorAttackAction action, long now) {
		float minimumEnergyCost = combatVariant(player) == ArmorVariant.SENTINEL
				? SentinelOverdriveSalvo.MINIMUM_ENERGY_COST
				: ArmorCombatTuning.CORE_MIN_ENERGY_COST;
		ArmorCombatGate.Result gate = ArmorCombatGate.evaluate(
				player, ArmorAttackType.CORE_BEAM, action, state, now,
				minimumEnergyCost);
		if (!acceptGate(player, state, gate, now)) return;
		if (action == ArmorAttackAction.PRESS) {
			startCoreCharge(player, state, now);
		} else if (action == ArmorAttackAction.RELEASE) {
			releaseCoreBeam(player, state, now);
		}
	}

	private static void handleBladeToggle(ServerPlayer player, ArmorCombatState state,
			ArmorAttackAction action, long now) {
		ArmorCombatGate.Result gate = ArmorCombatGate.evaluate(player,
				ArmorAttackType.ENERGY_BLADE_TOGGLE, action, state, now, 0.0F);
		if (!acceptGate(player, state, gate, now)) return;
		setBladeExtended(player, state, !state.bladeExtended);
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	private static void handleBladeSlash(ServerPlayer player, ArmorCombatState state,
			ArmorAttackAction action, long now) {
		ArmorCombatGate.Result gate = ArmorCombatGate.evaluate(player,
				ArmorAttackType.ENERGY_BLADE_SLASH, action, state, now,
				ArmorCombatTuning.BLADE_SLASH_ENERGY_COST);
		if (!acceptGate(player, state, gate, now)) return;
		fireBladeSlash(player, state, now);
	}

	private static void handleBladeCombo(ServerPlayer player, ArmorCombatState state,
			ArmorAttackAction action, long now) {
		ArmorCombatGate.Result gate = ArmorCombatGate.evaluate(player,
				ArmorAttackType.ENERGY_BLADE_COMBO, action, state, now,
				ArmorCombatTuning.BLADE_COMBO_ENERGY_COST);
		if (!acceptGate(player, state, gate, now)) return;
		startBladeCombo(player, state, now);
	}

	private static void startBladeCombo(ServerPlayer player, ArmorCombatState state, long now) {
		boolean expired = state.lastBladeComboTick == Long.MIN_VALUE
				|| now - state.lastBladeComboTick > ArmorCombatTuning.BLADE_COMBO_RESET_TICKS;
		state.bladeComboStep = expired ? 0 : Math.floorMod(state.bladeComboStep + 1, 3);
		state.lastBladeComboTick = now;
		state.energy = Mth.clamp(state.energy - ArmorCombatTuning.BLADE_COMBO_ENERGY_COST,
				0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat + ArmorCombatTuning.BLADE_COMBO_HEAT,
				0.0F, ArmorCombatTuning.MAX_HEAT);
		state.wristCooldownUntilTick = now + ArmorCombatTuning.BLADE_COMBO_COOLDOWN_TICKS;
		state.lastFireTick = now;
		state.serverAttackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.visualRevision = 0;
		state.attackType = ArmorAttackType.ENERGY_BLADE_COMBO;
		state.activeLeftHand = combatVariant(player) == ArmorVariant.SWIFT
				&& state.bladeComboStep == 1;
		state.phase = ArmorCombatPhase.WINDUP;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + ArmorCombatTuning.BLADE_COMBO_WINDUP_TICKS;
		state.persistenceDirty = true;
		broadcastVisual(player, visualPayload(player, state, now));
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}
	private static void fireBladeSlash(ServerPlayer player, ArmorCombatState state, long now) {
		state.energy = Mth.clamp(state.energy - ArmorCombatTuning.BLADE_SLASH_ENERGY_COST,
				0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat + ArmorCombatTuning.BLADE_SLASH_HEAT,
				0.0F, ArmorCombatTuning.MAX_HEAT);
		state.overheated = state.overheated || state.heat >= ArmorCombatTuning.MAX_HEAT;
		state.wristCooldownUntilTick = now + ArmorCombatTuning.BLADE_SLASH_COOLDOWN_TICKS;
		state.lastFireTick = now;
		state.serverAttackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.visualRevision = 0;
		state.attackType = ArmorAttackType.ENERGY_BLADE_SLASH;
		if (combatVariant(player) == ArmorVariant.SWIFT) {
			state.activeLeftHand = state.nextLeftHand;
			state.nextLeftHand = !state.nextLeftHand;
		} else {
			state.activeLeftHand = false;
		}
		state.phase = ArmorCombatPhase.WINDUP;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + ArmorCombatTuning.BLADE_SLASH_WINDUP_TICKS;
		state.persistenceDirty = true;
		checkpointPersistentState(player, state, now, true);
		broadcastVisual(player, visualPayload(player, state, now));
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	/**
	 * Resolves the ranged corridor exactly once on the authored swing frame. The
	 * central ray and every candidate ray respect block collision, while the
	 * candidate count remains server-capped.
	 */
	private static void commitBladeSlash(ServerPlayer player, ArmorCombatState state, long now) {
		ArmorVariant variant = combatVariant(player);
		ArmorWeaponRules.BladeProfile profile = ArmorWeaponRules.bladeProfile(variant);
		Vec3 forward = player.getLookAngle();
		if (forward.lengthSqr() < 1.0E-8D) forward = new Vec3(0.0D, 0.0D, 1.0D);
		forward = forward.normalize();
		boolean leftHand = state.activeLeftHand;
		Vec3 origin = ArmorAnchorResolver.resolve(player, variant, combatAnchorPose(player),
				ArmorSemanticAnchor.palm(leftHand), forward);
		Vec3 maximumEnd = origin.add(forward.scale(profile.range()));
		HitResult centralBlock = player.level().clip(new ClipContext(
				origin, maximumEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 visualEnd = centralBlock.getType() == HitResult.Type.BLOCK
				? centralBlock.getLocation() : maximumEnd;
		double corridorLength = origin.distanceTo(visualEnd);
		AABB area = new AABB(origin, visualEnd).inflate(profile.halfWidth(), 1.4D, profile.halfWidth());
		List<LivingEntity> candidates = player.serverLevel().getEntitiesOfClass(
				LivingEntity.class, area, candidate -> CombatTargetPolicy.canDamage(player, candidate));
		candidates.sort((left, right) -> Double.compare(
				origin.distanceToSqr(left.getBoundingBox().getCenter()),
				origin.distanceToSqr(right.getBoundingBox().getCenter())));
		int hitCount = 0;
		Entity firstHit = null;
		for (LivingEntity target : candidates) {
			if (hitCount >= profile.maxTargets()) break;
			Vec3 targetCenter = target.getBoundingBox().getCenter();
			Vec3 relative = targetCenter.subtract(origin);
			double projection = relative.dot(forward);
			if (projection < -0.35D || projection > corridorLength + 0.05D) continue;
			double lateral = relative.subtract(forward.scale(projection)).length();
			if (lateral > profile.halfWidth()) continue;
			if (bladeTargetOccluded(player, origin, targetCenter)) continue;
			float falloff = (float) Mth.clamp(1.0D - projection / (profile.range() * 1.4D),
					0.62D, 1.0D);
			if (!target.hurt(ModDamageTypes.gravityPulse(player), profile.damage() * falloff)) continue;
			target.knockback(profile.knockback(),
					player.getX() - target.getX(), player.getZ() - target.getZ());
			if (variant != ArmorVariant.SENTINEL) {
				target.setDeltaMovement(target.getDeltaMovement().add(0.0D,
						variant == ArmorVariant.SWIFT ? 0.12D : 0.24D, 0.0D));
			}
			target.hurtMarked = true;
			if (firstHit == null) firstHit = target;
			hitCount++;
		}

		state.phase = ArmorCombatPhase.FIRING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + ArmorCombatTuning.BLADE_SLASH_FIRING_TICKS;
		state.visualRevision++;
		broadcastVisual(player, visualPayload(player, state, now));
		broadcastFx(player, new ArmorAttackFxPayload(
				player.getUUID(), player.level().dimension().location(), state.serverAttackId, 0,
				ArmorAttackType.ENERGY_BLADE_SLASH, variant, leftHand, origin,
				firstHit == null ? visualEnd : firstHit.getBoundingBox().getCenter(),
				visualEnd, new Vec3(0.0D, 1.0D, 0.0D),
				firstHit == null ? ArmorAttackHitKind.MISS : ArmorAttackHitKind.ENTITY,
				firstHit == null ? ArmorAttackFxPayload.NO_ENTITY_ID : firstHit.getId(),
				1.0F, player.isInWaterOrBubble()));
		if (variant == ArmorVariant.SWIFT) {
			boolean supportLeft = !leftHand;
			Vec3 supportOrigin = ArmorAnchorResolver.resolve(player, variant, combatAnchorPose(player),
					ArmorSemanticAnchor.palm(supportLeft), forward);
			broadcastFx(player, new ArmorAttackFxPayload(
					player.getUUID(), player.level().dimension().location(), state.serverAttackId, 1,
					ArmorAttackType.ENERGY_BLADE_SLASH, variant, supportLeft, supportOrigin,
					visualEnd, visualEnd, new Vec3(0.0D, 1.0D, 0.0D),
					ArmorAttackHitKind.MISS, ArmorAttackFxPayload.NO_ENTITY_ID,
					1.0F, player.isInWaterOrBubble()));
		}
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	/** Resolves a short forward arc. Unlike ENERGY_BLADE_SLASH this never creates a ranged corridor. */
	private static void commitBladeCombo(ServerPlayer player, ArmorCombatState state, long now) {
		ArmorVariant variant = combatVariant(player);
		int step = Math.floorMod(state.bladeComboStep, 3);
		double reach = variant == ArmorVariant.SWIFT ? 4.15D
				: variant == ArmorVariant.SENTINEL ? 3.65D : 3.85D;
		double halfAngleCos = step == 2 ? 0.18D : 0.42D;
		int maxTargets = step == 2 ? (variant == ArmorVariant.SENTINEL ? 7 : 5) : 2;
		float baseDamage = variant == ArmorVariant.SWIFT ? 9.0F
				: variant == ArmorVariant.SENTINEL ? 15.0F : 11.0F;
		float damage = baseDamage * (step == 0 ? 0.82F : step == 1 ? 1.0F : 1.55F);
		double knockback = step == 2 ? (variant == ArmorVariant.SENTINEL ? 1.65D : 1.05D) : 0.28D;
		Vec3 forward = player.getLookAngle();
		if (forward.lengthSqr() < 1.0E-8D) forward = new Vec3(0.0D, 0.0D, 1.0D);
		forward = forward.normalize();
		Vec3 origin = ArmorAnchorResolver.resolve(player, variant, combatAnchorPose(player),
				ArmorSemanticAnchor.palm(state.activeLeftHand), forward);
		Vec3 visualEnd = origin.add(forward.scale(reach));
		List<LivingEntity> candidates = player.serverLevel().getEntitiesOfClass(
				LivingEntity.class, player.getBoundingBox().inflate(reach, 2.0D, reach),
				candidate -> CombatTargetPolicy.canDamage(player, candidate));
		candidates.sort((left, right) -> Double.compare(
				player.distanceToSqr(left), player.distanceToSqr(right)));
		LivingEntity firstHit = null;
		int hitCount = 0;
		for (LivingEntity target : candidates) {
			if (hitCount >= maxTargets) break;
			Vec3 targetCenter = target.getBoundingBox().getCenter();
			Vec3 relative = targetCenter.subtract(player.getEyePosition());
			double distance = relative.length();
			if (distance > reach + target.getBbWidth() * 0.5D || distance < 1.0E-5D) continue;
			if (relative.scale(1.0D / distance).dot(forward) < halfAngleCos) continue;
			if (bladeTargetOccluded(player, player.getEyePosition(), targetCenter)) continue;
			if (!target.hurt(ModDamageTypes.gravityPulse(player), damage)) continue;
			target.knockback(knockback, player.getX() - target.getX(), player.getZ() - target.getZ());
			if (step == 2) target.setDeltaMovement(target.getDeltaMovement().add(0.0D, 0.18D, 0.0D));
			target.hurtMarked = true;
			if (firstHit == null) firstHit = target;
			hitCount++;
		}
		state.phase = ArmorCombatPhase.FIRING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + ArmorCombatTuning.BLADE_COMBO_FIRING_TICKS;
		state.visualRevision++;
		broadcastVisual(player, visualPayload(player, state, now));
		broadcastFx(player, new ArmorAttackFxPayload(
				player.getUUID(), player.level().dimension().location(), state.serverAttackId, 0,
				ArmorAttackType.ENERGY_BLADE_COMBO, variant, state.activeLeftHand, origin,
				firstHit == null ? visualEnd : firstHit.getBoundingBox().getCenter(), visualEnd,
				new Vec3(0.0D, 1.0D, 0.0D),
				firstHit == null ? ArmorAttackHitKind.MISS : ArmorAttackHitKind.ENTITY,
				firstHit == null ? ArmorAttackFxPayload.NO_ENTITY_ID : firstHit.getId(),
				(step + 1) / 3.0F, player.isInWaterOrBubble()));
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}
	private static boolean bladeTargetOccluded(ServerPlayer player, Vec3 origin,
			Vec3 targetCenter) {
		HitResult hit = player.level().clip(new ClipContext(
				origin, targetCenter, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK
				&& origin.distanceToSqr(hit.getLocation()) + 0.04D
						< origin.distanceToSqr(targetCenter);
	}

	private static void setBladeExtended(ServerPlayer player, ArmorCombatState state, boolean extended) {
		if (state.bladeExtended == extended) return;
		state.bladeExtended = extended;
		state.bladeRevision++;
		state.bladeReadyAtTick = extended ? combatTime(player.getServer()) + 8L : Long.MAX_VALUE;
		ArmorBladeStatePayload payload = bladePayload(player, state);
		Set<ServerPlayer> recipients = new HashSet<>(PlayerLookup.tracking(player));
		recipients.add(player);
		for (ServerPlayer recipient : recipients) sendBladeState(recipient, payload);
	}

	private static ArmorBladeStatePayload bladePayload(ServerPlayer player, ArmorCombatState state) {
		return new ArmorBladeStatePayload(player.getUUID(), state.bladeRevision,
				state.bladeExtended, combatVariant(player));
	}

	private static void sendBladeState(ServerPlayer recipient, ServerPlayer player,
			ArmorCombatState state) {
		sendBladeState(recipient, bladePayload(player, state));
	}

	private static void sendBladeState(ServerPlayer recipient, ArmorBladeStatePayload payload) {
		if (ServerPlayNetworking.canSend(recipient, ArmorBladeStatePayload.TYPE)) {
			ServerPlayNetworking.send(recipient, payload);
		}
	}

	private static boolean acceptGate(ServerPlayer player, ArmorCombatState state,
			ArmorCombatGate.Result gate, long now) {
		if (gate.allowed()) return true;
		if (gate.reason() == ArmorCombatDenyReason.DIMENSION_CHANGED) {
			resetPhase(player, state, now, true);
			state.dimension = player.level().dimension();
		}
		sendDeniedState(player, state, gate.reason(), now);
		return false;
	}

	private static void startCoreCharge(ServerPlayer player, ArmorCombatState state, long now) {
		state.serverAttackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.visualRevision = 0;
		state.attackType = ArmorAttackType.CORE_BEAM;
		state.activeLeftHand = false;
		state.phase = ArmorCombatPhase.CHARGING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + ArmorCombatTuning.CORE_MAX_CHARGE_TICKS;
		broadcastVisual(player, visualPayload(player, state, now));
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	private static void releaseCoreBeam(ServerPlayer player, ArmorCombatState state, long now) {
		long heldTicks = Math.max(0L, now - state.phaseStartGameTime);
		if (heldTicks < ArmorCombatTuning.CORE_MIN_CHARGE_TICKS) {
			resetPhase(player, state, now, true);
			sendDeniedState(player, state, ArmorCombatDenyReason.CHARGE_TOO_SHORT, now);
			return;
		}
		float rawCharge = Mth.clamp(
				(float) (heldTicks - ArmorCombatTuning.CORE_MIN_CHARGE_TICKS)
						/ (ArmorCombatTuning.CORE_MAX_CHARGE_TICKS - ArmorCombatTuning.CORE_MIN_CHARGE_TICKS),
				0.0F, 1.0F);
		ArmorVariant variant = combatVariant(player);
		if (variant == ArmorVariant.SENTINEL) {
			Optional<SentinelOverdriveSalvo.FireLayout> authorizedLayout =
					SentinelOverdriveSalvo.authorizedLayout(player, now);
			if (authorizedLayout.isEmpty()) {
				resetPhase(player, state, now, true);
				sendDeniedState(player, state, ArmorCombatDenyReason.UNSUPPORTED_ATTACK, now);
				return;
			}
			float affordableCharge =
					SentinelOverdriveSalvo.affordableCharge(rawCharge, state.energy);
			if (affordableCharge < 0.0F) {
				resetPhase(player, state, now, true);
				sendDeniedState(player, state, ArmorCombatDenyReason.NOT_ENOUGH_ENERGY, now);
				return;
			}
			Optional<SentinelOverdriveSalvo.Result> salvo =
					SentinelOverdriveSalvo.fire(player, combatAnchorPose(player),
							affordableCharge, ArmorCombatTuning.CORE_MAX_RANGE,
							ArmorCombatTuning.CORE_MAX_DAMAGE,
							ArmorCombatTuning.CORE_MAX_KNOCKBACK,
							authorizedLayout.get());
			if (salvo.isEmpty()) {
				resetPhase(player, state, now, true);
				sendDeniedState(player, state, ArmorCombatDenyReason.UNSUPPORTED_ATTACK, now);
				return;
			}
			commitSentinelOverdriveSalvo(player, state, salvo.get(), now);
			return;
		}
		float energyLimitedCharge = Mth.clamp(
				(state.energy - ArmorCombatTuning.CORE_MIN_ENERGY_COST)
						/ (ArmorCombatTuning.CORE_MAX_ENERGY_COST - ArmorCombatTuning.CORE_MIN_ENERGY_COST),
				0.0F, 1.0F);
		float charge = Math.min(rawCharge, energyLimitedCharge);
		float energyCost = Mth.lerp(charge,
				ArmorCombatTuning.CORE_MIN_ENERGY_COST, ArmorCombatTuning.CORE_MAX_ENERGY_COST);
		float heat = Mth.lerp(charge,
				ArmorCombatTuning.CORE_MIN_HEAT, ArmorCombatTuning.CORE_MAX_HEAT);
		VariantCombatProfile variantProfile = variant.combatProfile();
		double range = variantProfile.scaleCoreRange(Mth.lerp(charge,
				ArmorCombatTuning.CORE_MIN_RANGE, ArmorCombatTuning.CORE_MAX_RANGE));
		float damage = variantProfile.scaleCoreDamage(Mth.lerp(charge,
				ArmorCombatTuning.CORE_MIN_DAMAGE, ArmorCombatTuning.CORE_MAX_DAMAGE));
		double knockback = Mth.lerp(charge,
				ArmorCombatTuning.CORE_MIN_KNOCKBACK, ArmorCombatTuning.CORE_MAX_KNOCKBACK);
		boolean underwater = player.isEyeInFluid(FluidTags.WATER);
		CombatRaycast.Result hit = CombatRaycast.castCoreBeam(
				player, variant, combatAnchorPose(player), range, charge);
		Entity target = hit.entity();
		boolean nativeExplosion =
				ArmorWeaponRules.coreExplosion(variant, charge).enabled();
		if (!nativeExplosion && target != null
				&& CombatTargetPolicy.canDamage(player, target)) {
			boolean hurt = target.hurt(ModDamageTypes.coreBeam(player), damage);
			if (hurt && target instanceof LivingEntity living) {
				living.knockback(knockback, player.getX() - living.getX(), player.getZ() - living.getZ());
			}
		}
		Vec3 visualEndpoint = ArmorVariantAttackResolver.applyCore(player, variant, hit, target,
				damage, range, knockback, charge);

		state.energy = Mth.clamp(state.energy - energyCost, 0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat + heat, 0.0F, ArmorCombatTuning.MAX_HEAT);
		state.overheated = state.overheated || state.heat >= ArmorCombatTuning.MAX_HEAT;
		state.coreCooldownUntilTick = now + ArmorCombatTuning.CORE_COOLDOWN_TICKS;
		state.lastFireTick = now;
		state.phase = ArmorCombatPhase.FIRING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + ArmorCombatTuning.CORE_FIRING_TICKS;
		state.visualRevision++;
		state.persistenceDirty = true;
		checkpointPersistentState(player, state, now, true);

		broadcastVisual(player, visualPayload(player, state, now));
		broadcastFx(player, new ArmorAttackFxPayload(
				player.getUUID(), player.level().dimension().location(), state.serverAttackId, 0,
				ArmorAttackType.CORE_BEAM, variant, false,
				hit.origin(), hit.endpoint(), visualEndpoint, hit.impactNormal(), hit.hitKind(),
				target == null ? ArmorAttackFxPayload.NO_ENTITY_ID : target.getId(), charge, underwater));
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	private static void commitSentinelOverdriveSalvo(ServerPlayer player,
			ArmorCombatState state, SentinelOverdriveSalvo.Result salvo, long now) {
		SentinelOverdriveSalvo.Profile profile = salvo.profile();
		state.energy = Mth.clamp(state.energy - profile.energyCost(),
				0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat + profile.heat(),
				0.0F, ArmorCombatTuning.MAX_HEAT);
		state.overheated = state.overheated || state.heat >= ArmorCombatTuning.MAX_HEAT;
		state.coreCooldownUntilTick = now + profile.cooldownTicks();
		state.lastFireTick = now;
		state.phase = ArmorCombatPhase.FIRING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + profile.firingTicks();
		state.visualRevision++;
		state.persistenceDirty = true;
		checkpointPersistentState(player, state, now, true);

		broadcastVisual(player, visualPayload(player, state, now));
		boolean underwater = player.isInWaterOrBubble();
		for (SentinelOverdriveSalvo.Shot shot : salvo.shots()) {
			broadcastFx(player, new ArmorAttackFxPayload(
					player.getUUID(), player.level().dimension().location(),
					state.serverAttackId, shot.eventIndex(),
					ArmorAttackType.CORE_BEAM, ArmorVariant.SENTINEL,
					shot.leftWing(), shot.origin(), shot.endpoint(), shot.endpoint(),
					shot.impactNormal(), shot.hitKind(), shot.hitEntityId(),
					profile.charge(), underwater));
		}
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	// ------------------------------------------------------------------
	// 增强能量光束（手部发射器）：切换后用鼠标左键开火
	// 按下=蓄力开始，松手=开火；松手时按住不足 LANCE_CLASSIFY_TICKS 走点射，否则走灭世光束。
	// ------------------------------------------------------------------

	/**
	 * 短按点射。**当帧立即结算**——点射档的节奏由玩家点击速度决定，服务端不做任何排队，
	 * 唯一的节流是 {@link EnhancedBeamMath#PULSE_COOLDOWN_TICKS}（2 tick）。
	 *
	 * <p>两个入口都落在这里：{@code HAND_BEAM_PULSE} 的直接 PRESS（线协议上的对应值），
	 * 以及 {@code HAND_BEAM_LANCE} 松手时按住长度不足门槛的分流。</p>
	 *
	 * <p>它刻意不破坏地形：轻点一枪只打人与出命中特效，削山是长按蓄力档的事。</p>
	 */
	private static void fireHandBeamPulse(ServerPlayer player, ArmorCombatState state, long now) {
		ArmorVariant variant = combatVariant(player);
		EnhancedBeamMath.PulseProfile profile = EnhancedBeamMath.pulseProfile(variant);
		boolean leftHand = state.nextLeftHand;
		state.nextLeftHand = !state.nextLeftHand;

		CombatRaycast.Result hit = CombatRaycast.castWristPulse(
				player, variant, combatAnchorPose(player), leftHand, profile.range());
		Entity target = hit.entity();
		if (target != null && CombatTargetPolicy.canDamage(player, target)) {
			boolean hurt = target.hurt(ModDamageTypes.coreBeam(player), profile.damage());
			if (hurt && target instanceof LivingEntity living) {
				living.knockback(profile.knockback(),
						player.getX() - living.getX(), player.getZ() - living.getZ());
			}
		}

		state.energy = Mth.clamp(state.energy - profile.energyCost(),
				0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat + profile.heat(), 0.0F, ArmorCombatTuning.MAX_HEAT);
		state.overheated = state.overheated || state.heat >= ArmorCombatTuning.MAX_HEAT;
		state.handBeamCooldownUntilTick = now + profile.cooldownTicks();
		state.lastFireTick = now;
		state.serverAttackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.visualRevision = 0;
		state.attackType = ArmorAttackType.HAND_BEAM_PULSE;
		state.activeLeftHand = leftHand;
		state.phase = ArmorCombatPhase.FIRING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + profile.firingTicks();
		state.persistenceDirty = true;
		checkpointPersistentState(player, state, now, true);

		broadcastVisual(player, visualPayload(player, state, now));
		broadcastFx(player, new ArmorAttackFxPayload(
				player.getUUID(), player.level().dimension().location(), state.serverAttackId, 0,
				ArmorAttackType.HAND_BEAM_PULSE, variant, leftHand,
				hit.origin(), hit.endpoint(), hit.impactNormal(), hit.hitKind(),
				target == null ? ArmorAttackFxPayload.NO_ENTITY_ID : target.getId(),
				EnhancedBeamMath.PULSE_FX_INTENSITY, player.isInWaterOrBubble()));
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	/** 灭世档的蓄力开始；真正打出去在 {@link #releaseHandBeamLance}。 */
	private static void startHandBeamCharge(ServerPlayer player, ArmorCombatState state, long now) {
		state.serverAttackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.visualRevision = 0;
		state.attackType = ArmorAttackType.HAND_BEAM_LANCE;
		// 灭世光束是双手托举的开火姿势，不参与左右手交替。
		state.activeLeftHand = false;
		state.phase = ArmorCombatPhase.CHARGING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + EnhancedBeamMath.LANCE_MAX_CHARGE_TICKS;
		broadcastVisual(player, visualPayload(player, state, now));
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	/**
	 * 释放灭世光束：沿视线打出一条光束，在命中点开一个球形爆坑。
	 *
	 * <p>地形破坏**只**走 {@link ImpactBlastController#crater}——光束不自己复制一份护栏判定，
	 * 那正是「两份实现不一致就等于穿墙漏洞」的经典写法。</p>
	 */
	private static void releaseHandBeamLance(ServerPlayer player, ArmorCombatState state, long now) {
		long heldTicks = Math.max(0L, now - state.phaseStartGameTime);
		ArmorVariant variant = combatVariant(player);
		float charge = energyLimitedCharge(variant,
				EnhancedBeamMath.chargeFor(heldTicks), state.energy);
		EnhancedBeamMath.LanceProfile profile = EnhancedBeamMath.lanceProfile(variant, charge);

		Vec3 direction = player.getLookAngle();
		if (direction.lengthSqr() < 1.0E-8D) direction = new Vec3(0.0D, 0.0D, 1.0D);
		direction = direction.normalize();
		CombatRaycast.Result hit = CombatRaycast.castWristPulse(
				player, variant, combatAnchorPose(player), false, profile.range());
		Entity target = hit.entity();
		boolean struckSomething = hit.hitKind() != ArmorAttackHitKind.MISS;
		// 球心沿光束前移一段：贴着地表炸会把半个球浪费在空气里，前移之后坑是「咬」进山体。
		Vec3 craterCenter = hit.endpoint().add(
				direction.scale(profile.craterRadius() * EnhancedBeamMath.CRATER_SINK_RATIO));

		if (target != null && CombatTargetPolicy.canDamage(player, target)) {
			boolean hurt = target.hurt(ModDamageTypes.coreBeam(player), profile.damage());
			if (hurt && target instanceof LivingEntity living) {
				living.knockback(profile.knockback(),
						player.getX() - living.getX(), player.getZ() - living.getZ());
			}
		}
		if (struckSomething) {
			ImpactBlastController.crater(player, craterCenter, profile.craterRadius(),
					profile.hardnessLimit(), profile.carveBudget(), profile.carveMaximumBlocks());
			ArmorVariantAttackResolver.applyHandBeamBlast(
					player, craterCenter, profile.explosionPower());
		}

		state.energy = Mth.clamp(state.energy - profile.energyCost(),
				0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat + profile.heat(), 0.0F, ArmorCombatTuning.MAX_HEAT);
		state.overheated = state.overheated || state.heat >= ArmorCombatTuning.MAX_HEAT;
		state.handBeamCooldownUntilTick = now + profile.cooldownTicks();
		state.lastFireTick = now;
		state.phase = ArmorCombatPhase.FIRING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + profile.firingTicks();
		state.visualRevision++;
		state.persistenceDirty = true;
		checkpointPersistentState(player, state, now, true);

		broadcastVisual(player, visualPayload(player, state, now));
		broadcastFx(player, new ArmorAttackFxPayload(
				player.getUUID(), player.level().dimension().location(), state.serverAttackId, 0,
				ArmorAttackType.HAND_BEAM_LANCE, variant, false,
				hit.origin(), hit.endpoint(),
				struckSomething ? craterCenter : hit.endpoint(),
				hit.impactNormal(), hit.hitKind(),
				target == null ? ArmorAttackFxPayload.NO_ENTITY_ID : target.getId(),
				charge, player.isInWaterOrBubble()));
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	/**
	 * 按剩余能量把蓄力档下调到负担得起的位置，而不是整发拒绝：满蓄力一炮很贵，
	 * 半能量就该打出半档，比「蓄满却哑火」手感好得多。档位 0..1 线性对应
	 * {@code MIN_ENERGY_COST..MAX_ENERGY_COST}，所以可以直接反解。
	 */
	private static float energyLimitedCharge(ArmorVariant variant, float rawCharge, float energy) {
		EnhancedBeamMath.LanceProfile minimum = EnhancedBeamMath.lanceProfile(variant, 0.0F);
		EnhancedBeamMath.LanceProfile maximum = EnhancedBeamMath.lanceProfile(variant, 1.0F);
		float span = maximum.energyCost() - minimum.energyCost();
		if (!(span > 0.0F)) return rawCharge;
		float affordable = Mth.clamp((energy - minimum.energyCost()) / span, 0.0F, 1.0F);
		return Math.min(rawCharge, affordable);
	}

	private static void handleHandBeamPulse(ServerPlayer player, ArmorCombatState state,
			ArmorAttackAction action, long now) {
		float energyCost = EnhancedBeamMath.pulseProfile(combatVariant(player)).energyCost();
		ArmorCombatGate.Result gate = ArmorCombatGate.evaluate(
				player, ArmorAttackType.HAND_BEAM_PULSE, action, state, now, energyCost);
		if (!acceptGate(player, state, gate, now)) return;
		if (action == ArmorAttackAction.PRESS) fireHandBeamPulse(player, state, now);
	}

	/**
	 * 灭世档的起手与释放。
	 *
	 * <p>鼠标左键是扳机，所以「按下」只是开始蓄力，真正的开火时机是**松手**。档位由服务端
	 * 按按住的长度在现场分流：不到 {@link EnhancedBeamMath#LANCE_CLASSIFY_TICKS} 就是点射，
	 * 到了就是灭世光束。这样客户端一次扳机只发两个意图包，短按的点射也没有额外的分类延迟。</p>
	 *
	 * <p>起手只收**点射档的代价**：蓄力本身不结算资源，真正的开销在释放时按蓄力档位算，
	 * 所以能量不足时按下去不会白扣一发灭世光束的钱，而是由
	 * {@link #energyLimitedCharge} 在释放那一刻降档。</p>
	 */
	private static void handleHandBeamLance(ServerPlayer player, ArmorCombatState state,
			ArmorAttackAction action, long now) {
		float minimumEnergyCost =
				EnhancedBeamMath.pulseProfile(combatVariant(player)).energyCost();
		ArmorCombatGate.Result gate = ArmorCombatGate.evaluate(
				player, ArmorAttackType.HAND_BEAM_LANCE, action, state, now, minimumEnergyCost);
		if (!acceptGate(player, state, gate, now)) return;
		if (action == ArmorAttackAction.PRESS) {
			startHandBeamCharge(player, state, now);
		} else if (action == ArmorAttackAction.RELEASE) {
			// 按住的长度是唯一的档位判据：松手就是开火。
			if (now - state.phaseStartGameTime < EnhancedBeamMath.LANCE_CLASSIFY_TICKS) {
				fireHandBeamPulse(player, state, now);
			} else {
				releaseHandBeamLance(player, state, now);
			}
		}
	}

	/**
	 * 仅由 FlightController 在结束俯冲前调用。速度、接触点和俯冲资格均来自服务端快照。
	 */
	public static void beginDiveImpactAction(ServerPlayer player) {
		if (player == null) return;
		DIVE_ACTIONS.put(player.getUUID(), new DiveImpactAction(
				NEXT_DIVE_ACTION_SEQUENCE.incrementAndGet(), player.level().dimension()));
	}

	public static void endDiveImpactAction(ServerPlayer player) {
		if (player != null) DIVE_ACTIONS.remove(player.getUUID());
	}

	public static boolean tryDivePathImpact(ServerPlayer player, Vec3 previousPosition,
			Vec3 currentPosition, Vec3 preContactVelocity, boolean horizontalCollision) {
		if (!finite(previousPosition) || !finite(currentPosition) || !finite(preContactVelocity)) {
			return false;
		}
		DiveImpactAction action = diveAction(player);
		action.observe(preContactVelocity);
		if (preContactVelocity.length() < ArmorCombatTuning.DIVE_IMPACT_MIN_SPEED) return false;

		Vec3 actualDelta = currentPosition.subtract(previousPosition);
		Vec3 sweepDelta = actualDelta;
		double plannedHorizontal = preContactVelocity.horizontalDistance();
		double actualHorizontal = actualDelta.horizontalDistance();
		if (horizontalCollision && plannedHorizontal > actualHorizontal + 0.05D) {
			sweepDelta = preContactVelocity;
		}
		if (sweepDelta.lengthSqr() < 1.0E-8D) return false;

		AABB endBox = player.getBoundingBox();
		AABB startBox = endBox.move(previousPosition.subtract(currentPosition));
		DiveContact block = horizontalCollision
				? firstBlockContact(player, startBox, previousPosition, sweepDelta) : DiveContact.miss();
		DiveContact entity = firstEntityContact(player, startBox, previousPosition, sweepDelta);
		DiveContact contact = block.hit() && (!entity.hit() || block.fraction() <= entity.fraction())
				? block : entity;
		if (!contact.hit()) return false;
		DiveImpactKind kind = contact.hitKind() == ArmorAttackHitKind.BLOCK
				? DiveImpactKind.WALL : DiveImpactKind.PATH_ENTITY;
		return commitDiveImpact(player, action, preContactVelocity, contact.position(),
				contact.normal(), contact.hitKind(), contact.targetEntityId(),
				player.isInWaterOrBubble(), kind);
	}

	public static boolean tryDiveStopImpact(ServerPlayer player, Vec3 currentVelocity,
			Vec3 stopPosition) {
		if (player == null || !player.isInWaterOrBubble()
				|| !finite(currentVelocity) || !finite(stopPosition)) return false;
		DiveImpactAction action = diveAction(player);
		action.observe(currentVelocity);
		Vec3 impactVelocity = action.peakVelocity.lengthSqr() > currentVelocity.lengthSqr()
				? action.peakVelocity : currentVelocity;
		if (impactVelocity.length() < ArmorCombatTuning.DIVE_IMPACT_MIN_SPEED) return false;
		Vec3 normal = impactVelocity.lengthSqr() < 1.0E-8D
				? new Vec3(0.0D, 1.0D, 0.0D) : impactVelocity.normalize().scale(-1.0D);
		return commitDiveImpact(player, action, impactVelocity,
				player.getBoundingBox().getCenter(), normal, ArmorAttackHitKind.BLOCK,
				ArmorAttackFxPayload.NO_ENTITY_ID, true, DiveImpactKind.WATER_PRESSURE);
	}

	/**
	 * Called by FlightController on the authoritative landing frame.
	 */
	public static boolean tryDiveImpact(ServerPlayer player, Vec3 preContactVelocity,
			Vec3 contactPosition) {
		if (!finite(preContactVelocity) || !finite(contactPosition)) return false;
		DiveImpactAction action = diveAction(player);
		action.observe(preContactVelocity);
		Vec3 impact = new Vec3(contactPosition.x,
				player.getBoundingBox().minY + 0.05D, contactPosition.z);
		return commitDiveImpact(player, action, preContactVelocity, impact,
				new Vec3(0.0D, 1.0D, 0.0D), ArmorAttackHitKind.BLOCK,
				ArmorAttackFxPayload.NO_ENTITY_ID, player.isInWaterOrBubble(),
				player.isInWaterOrBubble() ? DiveImpactKind.WATER_PRESSURE : DiveImpactKind.LANDING);
	}

	private static boolean commitDiveImpact(ServerPlayer player, DiveImpactAction action,
			Vec3 preContactVelocity, Vec3 impact, Vec3 impactNormal,
			ArmorAttackHitKind hitKind, int targetEntityId, boolean underwater,
			DiveImpactKind impactKind) {
		double speed = preContactVelocity.length();
		if (!Double.isFinite(speed) || speed < ArmorCombatTuning.DIVE_IMPACT_MIN_SPEED) return false;
		ArmorCombatState state = stateFor(player);
		long now = combatTime(player.getServer());
		boolean cooldownReady = now >= state.diveImpactCooldownUntilTick;
		if (!GravityImpactSweep.canCommit(action.sequence, action.completedSequence, cooldownReady)) {
			return false;
		}
		ArmorCombatDenyReason lifecycle = ArmorCombatGate.evaluateDiveImpactLifecycle(player);
		if (lifecycle != ArmorCombatDenyReason.NONE
				|| !state.dimension.equals(player.level().dimension())
				|| state.overheated
				|| state.energy + 0.0001F < ArmorCombatTuning.DIVE_IMPACT_ENERGY_COST) {
			return false;
		}

		if (state.phase != ArmorCombatPhase.IDLE) resetPhase(player, state, now, true);
		ArmorVariant variant = combatVariant(player);
		DiveImpactProfile profile = DiveImpactProfile.forSpeed(speed).scaled(variant.combatProfile());
		ArmorVariantAttackResolver.applyDiveImpact(player, variant, impact, preContactVelocity,
				profile.damage(), profile.radius(), profile.strength());
		action.completedSequence = action.sequence;

		state.energy = Mth.clamp(state.energy - ArmorCombatTuning.DIVE_IMPACT_ENERGY_COST,
				0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat + ArmorCombatTuning.DIVE_IMPACT_HEAT,
				0.0F, ArmorCombatTuning.MAX_HEAT);
		state.overheated = state.overheated || state.heat >= ArmorCombatTuning.MAX_HEAT;
		state.diveImpactCooldownUntilTick = now + ArmorCombatTuning.DIVE_IMPACT_COOLDOWN_TICKS;
		state.lastFireTick = now;
		state.serverAttackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.visualRevision = 0;
		state.attackType = ArmorAttackType.GRAVITY_DIVE_IMPACT;
		state.activeLeftHand = false;
		state.phase = ArmorCombatPhase.FIRING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + ArmorCombatTuning.DIVE_IMPACT_FIRING_TICKS;
		state.persistenceDirty = true;
		checkpointPersistentState(player, state, now, true);

		applyDiveResponse(player, variant, preContactVelocity, impactNormal, impactKind, underwater);
		player.fallDistance = 0.0F;
		player.hurtMarked = true;
		broadcastVisual(player, visualPayload(player, state, now));
		Vec3 direction = preContactVelocity.lengthSqr() < 1.0E-8D
				? Vec3.ZERO : preContactVelocity.normalize();
		double visualReach = variant == ArmorVariant.SWIFT
				? profile.radius() * 2.35D : Mth.clamp(speed * 0.32D, 3.0D, 8.0D);
		Vec3 effectEndpoint = direction.lengthSqr() > 1.0E-8D
				? impact.add(direction.scale(visualReach)) : impact;
		broadcastFx(player, new ArmorAttackFxPayload(
				player.getUUID(), player.level().dimension().location(), state.serverAttackId, 0,
				ArmorAttackType.GRAVITY_DIVE_IMPACT, variant, false,
				impact, impact, effectEndpoint, safeNormal(impactNormal), hitKind,
				targetEntityId, profile.strength(), underwater));
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
		return true;
	}

	private static DiveContact firstBlockContact(ServerPlayer player, AABB startBox,
			Vec3 startPosition, Vec3 delta) {
		AABB broadPhase = startBox.expandTowards(delta).inflate(0.08D);
		GravityImpactSweep.Box moving = pureBox(startBox);
		DiveContact best = DiveContact.miss();
		for (VoxelShape shape : player.level().getBlockCollisions(player, broadPhase)) {
			for (AABB box : shape.toAabbs()) {
				GravityImpactSweep.Contact hit = GravityImpactSweep.sweep(
						moving, pureVector(delta), pureBox(box), 0.015D);
				if (!hit.hit() || hit.fraction() >= best.fraction()
						|| Math.abs(hit.normal().y()) > 0.62D) continue;
				Vec3 position = startPosition.add(delta.scale(hit.fraction()))
						.add(0.0D, player.getBbHeight() * 0.5D, 0.0D);
				best = new DiveContact(true, hit.fraction(), position,
						minecraftVector(hit.normal()), ArmorAttackHitKind.BLOCK,
						ArmorAttackFxPayload.NO_ENTITY_ID);
			}
		}
		return best;
	}

	private static DiveContact firstEntityContact(ServerPlayer player, AABB startBox,
			Vec3 startPosition, Vec3 delta) {
		AABB broadPhase = startBox.expandTowards(delta).inflate(0.40D);
		GravityImpactSweep.Box moving = pureBox(startBox);
		DiveContact best = DiveContact.miss();
		for (LivingEntity target : player.serverLevel().getEntitiesOfClass(
				LivingEntity.class, broadPhase,
				candidate -> candidate != player && CombatTargetPolicy.canDamage(player, candidate))) {
			GravityImpactSweep.Contact hit = GravityImpactSweep.sweep(
					moving, pureVector(delta), pureBox(target.getBoundingBox()), 0.10D);
			if (!hit.hit() || hit.fraction() >= best.fraction()) continue;
			Vec3 position = startPosition.add(delta.scale(hit.fraction()))
					.add(0.0D, player.getBbHeight() * 0.5D, 0.0D);
			best = new DiveContact(true, hit.fraction(), position,
					minecraftVector(hit.normal()), ArmorAttackHitKind.ENTITY, target.getId());
		}
		return best;
	}

	private static void applyDiveResponse(ServerPlayer player, ArmorVariant variant,
			Vec3 incoming, Vec3 normal, DiveImpactKind kind, boolean underwater) {
		if (underwater || kind == DiveImpactKind.WATER_PRESSURE) {
			player.setDeltaMovement(incoming.x * 0.10D, 0.0D, incoming.z * 0.10D);
			return;
		}
		Vec3 safeNormal = safeNormal(normal);
		switch (kind) {
			case LANDING -> player.setDeltaMovement(incoming.x * 0.15D,
					Math.max(0.12D, incoming.y * 0.05D), incoming.z * 0.15D);
			case WALL -> {
				double intoWall = incoming.dot(safeNormal);
				Vec3 tangent = incoming.subtract(safeNormal.scale(intoWall));
				double rebound = intoWall < 0.0D ? Math.max(0.12D, -intoWall * 0.22D) : 0.12D;
				Vec3 response = tangent.scale(0.20D).add(safeNormal.scale(rebound));
				player.setDeltaMovement(response.x, Mth.clamp(response.y, -0.18D, 0.28D), response.z);
			}
			case PATH_ENTITY -> {
				double retention = variant == ArmorVariant.SWIFT ? 0.74D
						: variant == ArmorVariant.VANGUARD ? 0.20D : 0.30D;
				player.setDeltaMovement(incoming.scale(retention)
						.add(safeNormal.scale(variant == ArmorVariant.SWIFT ? 0.04D : 0.14D)));
			}
			case WATER_PRESSURE -> player.setDeltaMovement(incoming.x * 0.10D, 0.0D,
					incoming.z * 0.10D);
		}
	}

	private static DiveImpactAction diveAction(ServerPlayer player) {
		DiveImpactAction current = DIVE_ACTIONS.get(player.getUUID());
		if (current != null && current.dimension.equals(player.level().dimension())) return current;
		DiveImpactAction created = new DiveImpactAction(
				NEXT_DIVE_ACTION_SEQUENCE.incrementAndGet(), player.level().dimension());
		DIVE_ACTIONS.put(player.getUUID(), created);
		return created;
	}

	private static GravityImpactSweep.Box pureBox(AABB box) {
		return new GravityImpactSweep.Box(
				box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
	}

	private static GravityImpactSweep.Vector pureVector(Vec3 vector) {
		return new GravityImpactSweep.Vector(vector.x, vector.y, vector.z);
	}

	private static Vec3 minecraftVector(GravityImpactSweep.Vector vector) {
		return new Vec3(vector.x(), vector.y(), vector.z());
	}

	private static Vec3 safeNormal(Vec3 normal) {
		return normal == null || !finite(normal) || normal.lengthSqr() < 1.0E-8D
				? new Vec3(0.0D, 1.0D, 0.0D) : normal.normalize();
	}
	public static CruiseWristResult tryFireCruiseWrist(ServerPlayer owner, Entity carrier,
			Entity intendedTarget, boolean underwater) {
		if (owner == null || carrier == null || intendedTarget == null
				|| !owner.isAlive()
				|| owner.isSpectator() || !carrier.isAlive() || carrier.isRemoved() || intendedTarget.isRemoved()
				|| owner.level() != carrier.level() || owner.level() != intendedTarget.level()
				|| !CombatTargetPolicy.canDamage(owner, intendedTarget)) {
			return CruiseWristResult.missed();
		}
		if (!(carrier instanceof LivingEntity livingCarrier)) {
			return CruiseWristResult.missed();
		}
		ArmorVariant variant = ModItems.fullSetVariant(livingCarrier).orElse(null);
		if (variant == null) {
			return CruiseWristResult.missed();
		}

		ArmorCombatState state = cruiseStateFor(owner, carrier);
		if (state == null) return CruiseWristResult.missed();
		long now = combatTime(owner.getServer());
		if (!allowsCruiseRanged(carrier, now)) return CruiseWristResult.missed();
		if (!state.dimension.equals(carrier.level().dimension())) {
			state.dimension = carrier.level().dimension();
			return CruiseWristResult.missed();
		}
		if (state.phase != ArmorCombatPhase.IDLE) {
			return CruiseWristResult.missed();
		}
		if (state.overheated || now < state.wristCooldownUntilTick) {
			return CruiseWristResult.missed();
		}
		float energyCost = underwater
				? ArmorCombatTuning.UNDERWATER_WRIST_ENERGY_COST
				: ArmorCombatTuning.WRIST_ENERGY_COST;
		if (state.energy + 0.0001F < energyCost) {
			return CruiseWristResult.missed();
		}

		boolean leftHand = state.nextLeftHand;
		Vec3 aim = intendedTarget.getBoundingBox().getCenter()
				.subtract(carrier.getBoundingBox().getCenter());
		Vec3 origin = ArmorAnchorResolver.resolve(carrier, variant, ArmorAnchorPose.HOVER,
				ArmorSemanticAnchor.palm(leftHand), aim);
		VariantCombatProfile variantProfile = variant.combatProfile();
		double range = variantProfile.scaleWristRange(underwater
				? ArmorCombatTuning.UNDERWATER_WRIST_RANGE : ArmorCombatTuning.WRIST_RANGE);
		CombatRaycast.Result hit = CombatRaycast.castCruiseWrist(
				owner, carrier, intendedTarget, origin, range);
		if (hit.entity() != intendedTarget) {
			return CruiseWristResult.missed();
		}

		float damage = variantProfile.scaleWristDamage(underwater
				? ArmorCombatTuning.UNDERWATER_WRIST_DAMAGE : ArmorCombatTuning.WRIST_DAMAGE);
		double knockback = underwater
				? ArmorCombatTuning.UNDERWATER_WRIST_KNOCKBACK : ArmorCombatTuning.WRIST_KNOCKBACK;
		final float autonomousCharge = 0.35F;
		boolean nativeExplosion = ArmorWeaponRules.wristExplosion(
				variant, autonomousCharge, true).enabled();
		boolean hurt = !nativeExplosion
				&& intendedTarget.hurt(ModDamageTypes.gravityPulse(owner, carrier), damage);
		if (hurt && intendedTarget instanceof LivingEntity living) {
			living.knockback(knockback, carrier.getX() - living.getX(), carrier.getZ() - living.getZ());
		}
		List<ArmorVariantAttackResolver.VisualSegment> chainSegments =
				ArmorVariantAttackResolver.applyWrist(
						owner, carrier, variant, hit, intendedTarget, damage, range,
						autonomousCharge, true);

		state.nextLeftHand = !state.nextLeftHand;
		long attackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.energy = Mth.clamp(state.energy - energyCost, 0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat + ArmorCombatTuning.WRIST_HEAT,
				0.0F, ArmorCombatTuning.MAX_HEAT);
		state.overheated = state.overheated || state.heat >= ArmorCombatTuning.MAX_HEAT;
		state.wristCooldownUntilTick = now + ArmorCombatTuning.AUTO_WRIST_COOLDOWN_TICKS;
		state.lastFireTick = now;
		state.serverAttackId = attackId;

		ArmorCombatVisualStatePayload visual = new ArmorCombatVisualStatePayload(
				carrier.getUUID(), attackId, 0, ArmorAttackType.WRIST_GRAVITY_PULSE,
				ArmorCombatPhase.FIRING, leftHand, now, ArmorCombatTuning.WRIST_FIRING_TICKS);
		broadcastCarrierVisual(carrier, owner, visual);
		broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
				carrier.getUUID(), carrier.level().dimension().location(), attackId, 0,
				ArmorAttackType.WRIST_GRAVITY_PULSE, variant, leftHand,
				hit.origin(), hit.endpoint(), hit.impactNormal(), hit.hitKind(),
				intendedTarget.getId(), autonomousCharge, underwater));
		int chainEventIndex = 1;
		for (ArmorVariantAttackResolver.VisualSegment segment : chainSegments) {
			broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
					carrier.getUUID(), carrier.level().dimension().location(), attackId,
					chainEventIndex++, ArmorAttackType.WRIST_GRAVITY_PULSE, variant, leftHand,
					segment.origin(), segment.endpoint(), new Vec3(0.0D, 1.0D, 0.0D),
					ArmorAttackHitKind.ENTITY, ArmorAttackFxPayload.NO_ENTITY_ID,
					autonomousCharge, underwater));
		}
		return new CruiseWristResult(true, attackId, leftHand, intendedTarget.getId());
	}

	/** Sentinel escort chest cannon. The server validates the carrier set, ray and owner resources. */
	public static CruiseWeaponResult tryFireCruiseCore(ServerPlayer owner, Entity carrier,
			LivingEntity target) {
		ArmorVariant variant = cruiseVariant(owner, carrier);
		if (variant != ArmorVariant.SENTINEL || !validCruiseTarget(owner, carrier, target)) {
			return CruiseWeaponResult.missed();
		}

		ArmorCombatState state = cruiseStateFor(owner, carrier);
		if (state == null) return CruiseWeaponResult.missed();
		long now = combatTime(owner.getServer());
		if (!allowsCruiseRanged(carrier, now)) return CruiseWeaponResult.missed();
		if (!cruiseStateReady(carrier, state, now, state.coreCooldownUntilTick,
				ArmorCombatTuning.CRUISE_CORE_ENERGY_COST)) {
			return CruiseWeaponResult.missed();
		}
		double range = variant.combatProfile().scaleCoreRange(ArmorCombatTuning.CRUISE_CORE_RANGE);
		Vec3 aim = target.getBoundingBox().getCenter().subtract(carrier.getBoundingBox().getCenter());
		Vec3 origin = ArmorAnchorResolver.resolve(carrier, variant, ArmorAnchorPose.HOVER,
				ArmorSemanticAnchor.CHEST_CORE, aim);
		CombatRaycast.Result hit = CombatRaycast.castCruiseWrist(owner, carrier, target, origin, range);
		if (hit.entity() != target) return CruiseWeaponResult.missed();
		List<SentinelPodTrace> podTraces =
				validatedSentinelPodTraces(owner, carrier, target, range);

		float damage = variant.combatProfile().scaleCoreDamage(ArmorCombatTuning.CRUISE_CORE_DAMAGE);
		float charge = 0.72F;
		boolean nativeExplosion =
				ArmorWeaponRules.coreExplosion(variant, charge).enabled();
		boolean hurt = !nativeExplosion
				&& target.hurt(ModDamageTypes.coreBeam(owner, carrier), damage);
		if (hurt) {
			target.knockback(ArmorCombatTuning.CRUISE_CORE_KNOCKBACK,
					carrier.getX() - target.getX(), carrier.getZ() - target.getZ());
		}
		Vec3 visualEndpoint = ArmorVariantAttackResolver.applyCore(owner, carrier, variant, hit, target,
				damage, range, ArmorCombatTuning.CRUISE_CORE_KNOCKBACK, charge);
		long attackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.coreCooldownUntilTick = now + ArmorCombatTuning.CRUISE_CORE_COOLDOWN_TICKS;
		commitCruiseResources(owner, state, now, attackId,
				ArmorCombatTuning.CRUISE_CORE_ENERGY_COST, ArmorCombatTuning.CRUISE_CORE_HEAT);

		broadcastCarrierVisual(carrier, owner, new ArmorCombatVisualStatePayload(
				carrier.getUUID(), attackId, 0, ArmorAttackType.CRUISE_CORE_BEAM,
				ArmorCombatPhase.FIRING, false, now, ArmorCombatTuning.CRUISE_CORE_FIRING_TICKS));
		broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
				carrier.getUUID(), carrier.level().dimension().location(), attackId, 0,
				ArmorAttackType.CRUISE_CORE_BEAM, variant, false,
				hit.origin(), hit.endpoint(), visualEndpoint, hit.impactNormal(), hit.hitKind(),
				target.getId(), charge, carrier.isEyeInFluid(FluidTags.WATER)));
		emitSentinelPodCompanionFx(owner, carrier, attackId, charge, podTraces);
		return new CruiseWeaponResult(true, attackId);
	}

	/**
	 * Adds the Sentinel's two detached-pod fire lines to one already-authorized autonomous chest
	 * shot. These events are visual only: the main event above remains the sole damage/explosion
	 * transaction. Each pod performs its own server ray test so a wing hidden behind a wall cannot
	 * draw a beam through that wall merely because the centered chest emitter had line of sight.
	 */
	private static List<SentinelPodTrace> validatedSentinelPodTraces(
			ServerPlayer owner, Entity carrier, LivingEntity target, double mainRange) {
		List<SentinelPodTrace> traces = new ArrayList<>(2);
		double range = Math.min(ArmorCombatTuning.SENTINEL_DRONE_PULSE_RANGE, mainRange);
		for (boolean left : new boolean[] {true, false}) {
			CombatRaycast.Result podHit = sentinelPodRay(owner, carrier, target, left, range);
			if (podHit.entity() == target) traces.add(new SentinelPodTrace(left, podHit));
		}
		return traces;
	}

	private static void emitSentinelPodCompanionFx(ServerPlayer owner, Entity carrier,
			long attackId, float mainCharge, List<SentinelPodTrace> traces) {
		for (SentinelPodTrace trace : traces) {
			boolean left = trace.left();
			CombatRaycast.Result podHit = trace.hit();
			broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
					carrier.getUUID(), carrier.level().dimension().location(), attackId,
					left ? 1 : 2, ArmorAttackType.CRUISE_CORE_BEAM,
					ArmorVariant.SENTINEL, left,
					podHit.origin(), podHit.endpoint(), podHit.endpoint(),
					podHit.impactNormal(), ArmorAttackHitKind.MISS,
					ArmorAttackFxPayload.NO_ENTITY_ID,
					Mth.clamp(mainCharge * 0.52F, 0.20F, 0.55F),
					carrier.isEyeInFluid(FluidTags.WATER)));
		}
	}

	private record SentinelPodTrace(boolean left, CombatRaycast.Result hit) {
	}

	/**
	 * Exact-lane fallback used only by autonomous Sentinel AI. It alternates the physical left/right
	 * back pod, has its own cooldown, and is deliberately non-explosive. Remote R/G commands never
	 * call this entry point.
	 */
	public static CruiseWeaponResult tryFireSentinelDronePulse(ServerPlayer owner, Entity carrier,
			LivingEntity target) {
		ArmorVariant variant = cruiseVariant(owner, carrier);
		if (variant != ArmorVariant.SENTINEL || !validCruiseTarget(owner, carrier, target)) {
			return CruiseWeaponResult.missed();
		}
		ArmorCombatState state = cruiseStateFor(owner, carrier);
		if (state == null) return CruiseWeaponResult.missed();
		long now = combatTime(owner.getServer());
		if (!allowsCruiseRanged(carrier, now)) return CruiseWeaponResult.missed();
		if (!cruiseStateReady(carrier, state, now, state.sentinelDroneCooldownUntilTick,
				ArmorCombatTuning.SENTINEL_DRONE_PULSE_ENERGY_COST)) {
			return CruiseWeaponResult.missed();
		}
		// A manual G/R packet may have committed this same exact lane earlier in the server tick.
		// Do not let the automatic fallback turn that single remote command into a double shot.
		if (state.lastFireTick == now) return CruiseWeaponResult.missed();

		boolean preferredLeft = state.nextLeftSentinelDrone;
		CombatRaycast.Result hit = sentinelPodRay(owner, carrier, target, preferredLeft,
				ArmorCombatTuning.SENTINEL_DRONE_PULSE_RANGE);
		boolean firedLeft = preferredLeft;
		if (hit.entity() != target) {
			firedLeft = !preferredLeft;
			hit = sentinelPodRay(owner, carrier, target, firedLeft,
					ArmorCombatTuning.SENTINEL_DRONE_PULSE_RANGE);
		}
		if (hit.entity() != target) return CruiseWeaponResult.missed();

		boolean hurt = target.hurt(ModDamageTypes.coreBeam(owner, carrier),
				ArmorCombatTuning.SENTINEL_DRONE_PULSE_DAMAGE);
		if (hurt) {
			target.knockback(ArmorCombatTuning.SENTINEL_DRONE_PULSE_KNOCKBACK,
					carrier.getX() - target.getX(), carrier.getZ() - target.getZ());
		}
		long attackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.nextLeftSentinelDrone = !firedLeft;
		state.sentinelDroneCooldownUntilTick =
				now + ArmorCombatTuning.SENTINEL_DRONE_PULSE_COOLDOWN_TICKS;
		commitCruiseResources(owner, state, now, attackId,
				ArmorCombatTuning.SENTINEL_DRONE_PULSE_ENERGY_COST,
				ArmorCombatTuning.SENTINEL_DRONE_PULSE_HEAT);

		broadcastCarrierVisual(carrier, owner, new ArmorCombatVisualStatePayload(
				carrier.getUUID(), attackId, 0, ArmorAttackType.CRUISE_CORE_BEAM,
				ArmorCombatPhase.FIRING, firedLeft, now,
				ArmorCombatTuning.SENTINEL_DRONE_PULSE_FIRING_TICKS));
		broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
				carrier.getUUID(), carrier.level().dimension().location(), attackId, 0,
				ArmorAttackType.CRUISE_CORE_BEAM, variant, firedLeft,
				hit.origin(), hit.endpoint(), hit.endpoint(), hit.impactNormal(),
				ArmorAttackHitKind.ENTITY, target.getId(), 0.32F,
				carrier.isEyeInFluid(FluidTags.WATER)));
		return new CruiseWeaponResult(true, attackId);
	}

	private static CombatRaycast.Result sentinelPodRay(ServerPlayer owner, Entity carrier,
			LivingEntity target, boolean left, double range) {
		Vec3 aim = target.getBoundingBox().getCenter()
				.subtract(carrier.getBoundingBox().getCenter());
		Vec3 origin = ArmorAnchorResolver.resolve(carrier, ArmorVariant.SENTINEL,
				ArmorAnchorPose.HOVER, ArmorSemanticAnchor.dronePod(left), aim);
		return CombatRaycast.castCruiseWrist(owner, carrier, target, origin,
				Math.min(ArmorCombatTuning.SENTINEL_DRONE_PULSE_RANGE, range));
	}

	/** Vanguard escort ram. Damage is committed only after a valid close server collision. */
	public static CruiseWeaponResult tryCruiseImpact(ServerPlayer owner, Entity carrier,
			LivingEntity target, Vec3 velocity) {
		ArmorVariant variant = cruiseVariant(owner, carrier);
		if (variant != ArmorVariant.VANGUARD || !validCruiseTarget(owner, carrier, target)
				|| !finite(velocity)) return CruiseWeaponResult.missed();
		double speed = velocity.length();
		if (speed < ArmorCombatTuning.CRUISE_RAM_MIN_SPEED
				|| carrier.getBoundingBox().getCenter().distanceToSqr(target.getBoundingBox().getCenter())
				> ArmorCombatTuning.CRUISE_RAM_MAX_TARGET_DISTANCE
				* ArmorCombatTuning.CRUISE_RAM_MAX_TARGET_DISTANCE) {
			return CruiseWeaponResult.missed();
		}

		ArmorCombatState state = cruiseStateFor(owner, carrier);
		if (state == null) return CruiseWeaponResult.missed();
		long now = combatTime(owner.getServer());
		if (!cruiseStateReady(carrier, state, now, state.diveImpactCooldownUntilTick,
				ArmorCombatTuning.CRUISE_RAM_ENERGY_COST)) {
			return CruiseWeaponResult.missed();
		}
		float baseDamage = Math.min(ArmorCombatTuning.CRUISE_RAM_MAX_DAMAGE,
				ArmorCombatTuning.CRUISE_RAM_BASE_DAMAGE
						+ (float) (speed - ArmorCombatTuning.CRUISE_RAM_MIN_SPEED)
						* ArmorCombatTuning.CRUISE_RAM_SPEED_DAMAGE);
		float damage = variant.combatProfile().scaleDiveDamage(baseDamage);
		Vec3 impact = target.getBoundingBox().getCenter();
		boolean nativeExplosion =
				ArmorWeaponRules.cruiseRamExplosion(speed).enabled();
		if (nativeExplosion) {
			if (!ArmorVariantAttackResolver.applyCruiseRamExplosion(
					owner, carrier, impact, speed)) {
				return CruiseWeaponResult.missed();
			}
		} else {
			if (!target.hurt(ModDamageTypes.gravityImpact(owner, carrier), damage)) {
				return CruiseWeaponResult.missed();
			}
			target.knockback(ArmorCombatTuning.CRUISE_RAM_KNOCKBACK,
					carrier.getX() - target.getX(), carrier.getZ() - target.getZ());
			target.setDeltaMovement(target.getDeltaMovement()
					.add(velocity.normalize().scale(0.24D))
					.add(0.0D, 0.10D, 0.0D));
			target.hurtMarked = true;
		}

		long attackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.diveImpactCooldownUntilTick = now + ArmorCombatTuning.CRUISE_RAM_COOLDOWN_TICKS;
		commitCruiseResources(owner, state, now, attackId,
				ArmorCombatTuning.CRUISE_RAM_ENERGY_COST, ArmorCombatTuning.CRUISE_RAM_HEAT);
		Vec3 travel = velocity.normalize().scale(Mth.clamp(speed * 3.0D, 3.0D, 8.0D));
		broadcastCarrierVisual(carrier, owner, new ArmorCombatVisualStatePayload(
				carrier.getUUID(), attackId, 0, ArmorAttackType.CRUISE_RAM,
				ArmorCombatPhase.FIRING, false, now, ArmorCombatTuning.CRUISE_RAM_FIRING_TICKS));
		broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
				carrier.getUUID(), carrier.level().dimension().location(), attackId, 0,
				ArmorAttackType.CRUISE_RAM, variant, false,
				impact, impact, impact.add(travel), velocity.normalize().scale(-1.0D),
				ArmorAttackHitKind.ENTITY, target.getId(),
				Mth.clamp((float) speed / 2.0F, 0.40F, 1.0F), false));
		return new CruiseWeaponResult(true, attackId);
	}

	/**
	 * Manual nearby-suit chest command. Unlike the autonomous sentinel preference, every complete
	 * suit can answer an explicit owner G command; variant combat profiles still provide distinct
	 * range, damage and explosion behavior.
	 */
	public static CruiseWeaponResult tryFireRemoteCore(ServerPlayer owner, Entity carrier,
			LivingEntity target) {
		ArmorVariant variant = cruiseVariant(owner, carrier);
		if (variant == null || !validCruiseTarget(owner, carrier, target)) {
			return CruiseWeaponResult.missed();
		}

		ArmorCombatState state = cruiseStateFor(owner, carrier);
		if (state == null) return CruiseWeaponResult.missed();
		long now = combatTime(owner.getServer());
		if (!allowsCruiseRanged(carrier, now)) return CruiseWeaponResult.missed();
		if (!cruiseStateReady(carrier, state, now, state.coreCooldownUntilTick,
				ArmorCombatTuning.CRUISE_CORE_ENERGY_COST)) {
			return CruiseWeaponResult.missed();
		}
		double range = variant.combatProfile().scaleCoreRange(
				ArmorCombatTuning.CRUISE_CORE_RANGE);
		Vec3 aim = target.getBoundingBox().getCenter()
				.subtract(carrier.getBoundingBox().getCenter());
		Vec3 origin = ArmorAnchorResolver.resolve(carrier, variant, ArmorAnchorPose.HOVER,
				ArmorSemanticAnchor.CHEST_CORE, aim);
		CombatRaycast.Result hit =
				CombatRaycast.castCruiseWrist(owner, carrier, target, origin, range);
		if (hit.entity() != target) return CruiseWeaponResult.missed();

		float damage = variant.combatProfile().scaleCoreDamage(
				ArmorCombatTuning.CRUISE_CORE_DAMAGE);
		float charge = switch (variant) {
			case VANGUARD -> 0.78F;
			case SWIFT -> 0.64F;
			case SENTINEL -> 0.88F;
		};
		boolean nativeExplosion =
				ArmorWeaponRules.coreExplosion(variant, charge).enabled();
		boolean hurt = !nativeExplosion
				&& target.hurt(ModDamageTypes.coreBeam(owner, carrier), damage);
		if (hurt) {
			target.knockback(ArmorCombatTuning.CRUISE_CORE_KNOCKBACK,
					carrier.getX() - target.getX(), carrier.getZ() - target.getZ());
		}
		Vec3 visualEndpoint = ArmorVariantAttackResolver.applyCore(
				owner, carrier, variant, hit, target, damage, range,
				ArmorCombatTuning.CRUISE_CORE_KNOCKBACK, charge);
		long attackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.coreCooldownUntilTick = now + ArmorCombatTuning.CRUISE_CORE_COOLDOWN_TICKS;
		commitCruiseResources(owner, state, now, attackId,
				ArmorCombatTuning.CRUISE_CORE_ENERGY_COST,
				ArmorCombatTuning.CRUISE_CORE_HEAT);

		broadcastCarrierVisual(carrier, owner, new ArmorCombatVisualStatePayload(
				carrier.getUUID(), attackId, 0, ArmorAttackType.CRUISE_CORE_BEAM,
				ArmorCombatPhase.FIRING, false, now,
				ArmorCombatTuning.CRUISE_CORE_FIRING_TICKS));
		broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
				carrier.getUUID(), carrier.level().dimension().location(), attackId, 0,
				ArmorAttackType.CRUISE_CORE_BEAM, variant, false,
				hit.origin(), hit.endpoint(), visualEndpoint, hit.impactNormal(), hit.hitKind(),
				target.getId(), charge, carrier.isEyeInFluid(FluidTags.WATER)));
		return new CruiseWeaponResult(true, attackId);
	}

	/** One close-range strike used by the remote suit's low-hover melee stance. */
	public static CruiseWeaponResult tryRemoteMeleeStrike(ServerPlayer owner, Entity carrier,
			LivingEntity target) {
		ArmorVariant variant = cruiseVariant(owner, carrier);
		if (variant == null || !validCruiseTarget(owner, carrier, target)
				|| !allowsCruiseHeavyMelee(carrier, combatTime(owner.getServer()))
				|| carrier.getBoundingBox().getCenter()
						.distanceToSqr(target.getBoundingBox().getCenter()) > 3.35D * 3.35D) {
			return CruiseWeaponResult.missed();
		}
		float damage = switch (variant) {
			case VANGUARD -> 12.0F;
			case SWIFT -> 9.0F;
			case SENTINEL -> 11.0F;
		};
		if (!target.hurt(ModDamageTypes.gravityImpact(owner, carrier), damage)) {
			return CruiseWeaponResult.missed();
		}
		double knockback = switch (variant) {
			case VANGUARD -> 0.95D;
			case SWIFT -> 0.55D;
			case SENTINEL -> 0.75D;
		};
		target.knockback(knockback,
				carrier.getX() - target.getX(), carrier.getZ() - target.getZ());
		return emitRemoteMeleeFx(owner, carrier, target, variant, 0.52F, false);
	}

	/**
	 * Variant-specific J ultimate. Damage and target enumeration remain fully server-side; the
	 * controller supplies only a server policy profile, never client values.
	 */
	public static CruiseWeaponResult tryRemoteMeleeUltimate(ServerPlayer owner, Entity carrier,
			LivingEntity intendedTarget, RemoteArmorCommandPolicy.UltimateProfile profile) {
		ArmorVariant variant = cruiseVariant(owner, carrier);
		if (variant == null || profile == null
				|| !allowsCruiseHeavyMelee(carrier, combatTime(owner.getServer()))
				|| !validCruiseTarget(owner, carrier, intendedTarget)
				|| carrier.getBoundingBox().getCenter()
						.distanceToSqr(intendedTarget.getBoundingBox().getCenter()) > 6.5D * 6.5D) {
			return CruiseWeaponResult.missed();
		}
		Vec3 center = intendedTarget.getBoundingBox().getCenter();
		boolean hitAny = false;
		for (LivingEntity candidate : carrier.level().getEntitiesOfClass(
				LivingEntity.class, new AABB(center, center).inflate(profile.radius()),
				candidate -> validCruiseTarget(owner, carrier, candidate))) {
			if (owner.position().distanceToSqr(candidate.getBoundingBox().getCenter())
					> RemoteArmorCommandPolicy.MAX_COMMAND_DISTANCE
					* RemoteArmorCommandPolicy.MAX_COMMAND_DISTANCE) {
				continue;
			}
			double distance = candidate.getBoundingBox().getCenter().distanceTo(center);
			if (distance > profile.radius()) continue;
			float falloff = (float) Mth.clamp(
					1.0D - distance / Math.max(0.001D, profile.radius()) * 0.45D,
					0.55D, 1.0D);
			if (!candidate.hurt(ModDamageTypes.gravityImpact(owner, carrier),
					profile.damage() * falloff)) continue;
			Vec3 away = candidate.getBoundingBox().getCenter()
					.subtract(carrier.getBoundingBox().getCenter());
			if (away.lengthSqr() < 1.0E-8D) away = new Vec3(0.0D, 0.0D, 1.0D);
			away = away.normalize();
			candidate.setDeltaMovement(candidate.getDeltaMovement()
					.add(away.x * profile.knockback(), 0.32D + profile.knockback() * 0.18D,
							away.z * profile.knockback()));
			candidate.hurtMarked = true;
			hitAny = true;
		}
		if (!hitAny) return CruiseWeaponResult.missed();
		return emitRemoteMeleeFx(owner, carrier, intendedTarget, variant, 1.0F, true);
	}

	private static CruiseWeaponResult emitRemoteMeleeFx(ServerPlayer owner, Entity carrier,
			LivingEntity target, ArmorVariant variant, float strength, boolean ultimate) {
		long now = combatTime(owner.getServer());
		long attackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		Vec3 aim = target.getBoundingBox().getCenter()
				.subtract(carrier.getBoundingBox().getCenter());
		Vec3 origin = ArmorAnchorResolver.resolve(carrier, variant, ArmorAnchorPose.HOVER,
				ArmorSemanticAnchor.palm(false), aim);
		Vec3 endpoint = target.getBoundingBox().getCenter();
		Vec3 direction = endpoint.subtract(origin);
		Vec3 normal = direction.lengthSqr() < 1.0E-8D
				? new Vec3(0.0D, 1.0D, 0.0D) : direction.normalize().scale(-1.0D);
		double extension = ultimate ? 9.0D : 4.2D;
		Vec3 visualEndpoint = direction.lengthSqr() < 1.0E-8D
				? endpoint : origin.add(direction.normalize().scale(extension));
		broadcastCarrierVisual(carrier, owner, new ArmorCombatVisualStatePayload(
				carrier.getUUID(), attackId, 0, ArmorAttackType.ENERGY_BLADE_SLASH,
				ArmorCombatPhase.FIRING, false, now,
				ultimate ? 10 : ArmorCombatTuning.BLADE_SLASH_FIRING_TICKS));
		broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
				carrier.getUUID(), carrier.level().dimension().location(), attackId, 0,
				ArmorAttackType.ENERGY_BLADE_SLASH, variant, false,
				origin, endpoint, visualEndpoint, normal, ArmorAttackHitKind.ENTITY,
				target.getId(), strength, carrier.isEyeInFluid(FluidTags.WATER)));
		return new CruiseWeaponResult(true, attackId);
	}

	/**
	 * Vanguard autonomous ground slam. The caller must complete the dedicated
	 * ascend/hold/drop maneuver first; this method only commits the downward ground
	 * contact and therefore cannot be used as a shortcut for a forward ram.
	 */
	public static CruiseWeaponResult tryCruiseGroundSlam(ServerPlayer owner, Entity carrier,
			Vec3 impact, Vec3 velocity) {
		ArmorVariant variant = cruiseVariant(owner, carrier);
		if (variant != ArmorVariant.VANGUARD
				|| !ArmorCruiseController.isCruiseCarrier(carrier)
				|| impact == null || velocity == null
				|| !finite(impact) || !finite(velocity)
				|| -velocity.y < ArmorCombatTuning.CRUISE_SLAM_MIN_DOWNWARD_SPEED
				|| !validCruiseGroundSlamContact(carrier, impact, velocity)) {
			return CruiseWeaponResult.missed();
		}

		ArmorCombatState state = cruiseStateFor(owner, carrier);
		if (state == null) return CruiseWeaponResult.missed();
		long now = combatTime(owner.getServer());
		if (!cruiseStateReady(carrier, state, now, state.cruiseSlamCooldownUntilTick,
				ArmorCombatTuning.CRUISE_SLAM_ENERGY_COST)) {
			return CruiseWeaponResult.missed();
		}

		float damage = variant.combatProfile().scaleDiveDamage(
				ArmorCombatTuning.CRUISE_SLAM_DAMAGE);
		double radius = variant.combatProfile().scaleDiveRadius(
				ArmorCombatTuning.CRUISE_SLAM_RADIUS);
		ArmorVariantAttackResolver.applyCruiseGroundSlam(
				owner, carrier, impact, damage, radius,
				ArmorCombatTuning.CRUISE_SLAM_KNOCKBACK);

		long attackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		state.cruiseSlamCooldownUntilTick =
				now + ArmorCombatTuning.CRUISE_SLAM_COOLDOWN_TICKS;
		commitCruiseResources(owner, state, now, attackId,
				ArmorCombatTuning.CRUISE_SLAM_ENERGY_COST,
				ArmorCombatTuning.CRUISE_SLAM_HEAT);
		float strength = Mth.clamp(
				(float) (-velocity.y / 2.4D), 0.48F, 1.0F);
		broadcastCarrierVisual(carrier, owner, new ArmorCombatVisualStatePayload(
				carrier.getUUID(), attackId, 0, ArmorAttackType.CRUISE_GROUND_SLAM,
				ArmorCombatPhase.FIRING, false, now,
				ArmorCombatTuning.CRUISE_SLAM_FIRING_TICKS));
		broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
				carrier.getUUID(), carrier.level().dimension().location(), attackId, 0,
				ArmorAttackType.CRUISE_GROUND_SLAM, variant, false,
				impact, impact, impact.add(0.0D, Math.max(3.0D, radius * 0.72D), 0.0D),
				new Vec3(0.0D, 1.0D, 0.0D), ArmorAttackHitKind.BLOCK,
				ArmorAttackFxPayload.NO_ENTITY_ID, strength, false));
		return new CruiseWeaponResult(true, attackId);
	}

	/**
	 * Defense-in-depth for the server-owned maneuver. The cruise controller already sweeps the
	 * descent, but the damage entry point independently rejects stale air points, wall glances and
	 * diagonal rams. Collision shapes are inspected instead of block solidity so slabs, stairs and
	 * other partial-height surfaces remain valid landing points.
	 */
	private static boolean validCruiseGroundSlamContact(Entity carrier, Vec3 impact, Vec3 velocity) {
		double speed = velocity.length();
		if (!Double.isFinite(speed) || speed < ArmorCombatTuning.CRUISE_SLAM_MIN_DOWNWARD_SPEED
				|| -velocity.y / speed
						< ArmorCombatTuning.CRUISE_SLAM_MIN_DOWNWARD_COMPONENT) {
			return false;
		}

		Vec3 center = carrier.getBoundingBox().getCenter();
		double horizontalDistanceSqr = (center.x - impact.x) * (center.x - impact.x)
				+ (center.z - impact.z) * (center.z - impact.z);
		double maxHorizontal = ArmorCombatTuning.CRUISE_SLAM_MAX_HORIZONTAL_CONTACT_DISTANCE;
		double verticalDistance = center.y - impact.y;
		if (horizontalDistanceSqr > maxHorizontal * maxHorizontal
				|| verticalDistance < -0.10D
				|| verticalDistance > ArmorCombatTuning.CRUISE_SLAM_MAX_VERTICAL_CONTACT_DISTANCE) {
			return false;
		}

		double radius = ArmorCombatTuning.CRUISE_SLAM_GROUND_PROBE_RADIUS;
		double depth = ArmorCombatTuning.CRUISE_SLAM_GROUND_PROBE_DEPTH;
		AABB probe = new AABB(
				impact.x - radius, impact.y - depth, impact.z - radius,
				impact.x + radius, impact.y + 0.08D, impact.z + radius);
		for (VoxelShape shape : carrier.level().getBlockCollisions(carrier, probe)) {
			for (AABB box : shape.toAabbs()) {
				if (box.intersects(probe)
						&& box.maxY >= impact.y - depth
						&& box.maxY <= impact.y + 0.18D) {
					return true;
				}
			}
		}
		return false;
	}

	/** Swift escort missile rain. Launch succeeds once; every target is damaged on a later server tick. */
	public static CruiseWeaponResult tryFireCruiseMissileVolley(ServerPlayer owner, Entity carrier,
			UUID suitId, long actionSequence, long carrierRevision,
			List<? extends LivingEntity> targets) {
		ArmorVariant variant = cruiseVariant(owner, carrier);
		if (variant != ArmorVariant.SWIFT || suitId == null || actionSequence <= 0L
				|| carrierRevision <= 0L || targets == null || targets.isEmpty()) {
			return CruiseWeaponResult.missed();
		}
		double range = variant.combatProfile().scaleWristRange(ArmorCombatTuning.CRUISE_MISSILE_RANGE);
		double rangeSqr = range * range;
		Vec3 carrierCenter = carrier.getBoundingBox().getCenter();
		List<LivingEntity> eligible = new ArrayList<>(ArmorCombatTuning.CRUISE_MISSILE_MAX_TARGETS);
		Set<UUID> seen = new HashSet<>();
		for (LivingEntity candidate : targets) {
			if (eligible.size() >= ArmorCombatTuning.CRUISE_MISSILE_MAX_TARGETS) break;
			if (validCruiseTarget(owner, carrier, candidate)
					&& carrierCenter.distanceToSqr(candidate.getBoundingBox().getCenter()) <= rangeSqr
					&& seen.add(candidate.getUUID())) {
				eligible.add(candidate);
			}
		}
		if (eligible.isEmpty()) return CruiseWeaponResult.missed();

		float energyCost = eligible.size() * ArmorCombatTuning.CRUISE_MISSILE_ENERGY_PER_TARGET;
		ArmorCombatState state = cruiseStateFor(owner, carrier);
		if (state == null) return CruiseWeaponResult.missed();
		long now = combatTime(owner.getServer());
		if (!cruiseStateReady(carrier, state, now, state.wristCooldownUntilTick, energyCost)) {
			return CruiseWeaponResult.missed();
		}

		long attackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		float damage = variant.combatProfile().scaleWristDamage(ArmorCombatTuning.CRUISE_MISSILE_DAMAGE);
		int longestFlight = ArmorCombatTuning.CRUISE_MISSILE_BASE_FLIGHT_TICKS;
		ServerLevel carrierLevel = (ServerLevel) carrier.level();
		for (int index = 0; index < eligible.size(); index++) {
			LivingEntity target = eligible.get(index);
			Vec3 origin = ArmorAnchorResolver.resolve(carrier, variant, ArmorAnchorPose.HOVER,
					ArmorSemanticAnchor.missileRack(index), index / 2,
					target.getBoundingBox().getCenter().subtract(carrier.getBoundingBox().getCenter()));
			Vec3 endpoint = target.getBoundingBox().getCenter();
			int flightTicks = Math.max(ArmorCombatTuning.CRUISE_MISSILE_BASE_FLIGHT_TICKS,
					(int) Math.ceil(origin.distanceTo(endpoint) / 1.10D));
			longestFlight = Math.max(longestFlight, flightTicks);
			Vec3 direction = endpoint.subtract(origin);
			Vec3 normal = direction.lengthSqr() > 1.0E-8D
					? direction.normalize().scale(-1.0D) : new Vec3(0.0D, 1.0D, 0.0D);
			ArmorHomingMissileEntity missile = ArmorHomingMissileEntity.launch(carrierLevel,
					owner, carrier, target, suitId, actionSequence, carrierRevision,
					attackId, index, damage, origin);
			carrierLevel.addFreshEntity(missile);
			broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
					carrier.getUUID(), carrier.level().dimension().location(), attackId, index,
					ArmorAttackType.CRUISE_MISSILE_VOLLEY, variant, false,
					origin, endpoint, endpoint, normal, ArmorAttackHitKind.MISS,
					target.getId(), Mth.clamp(flightTicks / 20.0F, 0.0F, 1.0F), false));
		}
		state.wristCooldownUntilTick = now + ArmorCombatTuning.CRUISE_MISSILE_COOLDOWN_TICKS;
		float heat = ArmorCombatTuning.CRUISE_MISSILE_BASE_HEAT
				+ eligible.size() * ArmorCombatTuning.CRUISE_MISSILE_HEAT_PER_TARGET;
		commitCruiseResources(owner, state, now, attackId, energyCost, heat);
		broadcastCarrierVisual(carrier, owner, new ArmorCombatVisualStatePayload(
				carrier.getUUID(), attackId, 0, ArmorAttackType.CRUISE_MISSILE_VOLLEY,
				ArmorCombatPhase.FIRING, false, now, longestFlight));
		return new CruiseWeaponResult(true, attackId);
	}

	public static CruiseWeaponResult tryFireIndependentCruiseMissileVolley(
			ServerPlayer owner, Entity carrier, UUID suitId, long actionSequence,
			long carrierRevision, LivingEntity target, int requestedMissileCount) {
		ArmorVariant variant = cruiseVariant(owner, carrier);
		if (variant != ArmorVariant.SWIFT || suitId == null || actionSequence <= 0L
				|| carrierRevision <= 0L || !validCruiseTarget(owner, carrier, target)) {
			return CruiseWeaponResult.missed();
		}
		int missileCount = Mth.clamp(requestedMissileCount, 2, 4);
		double range = variant.combatProfile().scaleWristRange(ArmorCombatTuning.CRUISE_MISSILE_RANGE);
		Vec3 carrierCenter = carrier.getBoundingBox().getCenter();
		if (carrierCenter.distanceToSqr(target.getBoundingBox().getCenter()) > range * range) {
			return CruiseWeaponResult.missed();
		}

		float energyCost = missileCount * ArmorCombatTuning.CRUISE_MISSILE_ENERGY_PER_TARGET;
		ArmorCombatState state = cruiseStateFor(owner, carrier);
		if (state == null) return CruiseWeaponResult.missed();
		long now = combatTime(owner.getServer());
		if (!cruiseStateReady(carrier, state, now, state.wristCooldownUntilTick, energyCost)) {
			return CruiseWeaponResult.missed();
		}

		long attackId = NEXT_SERVER_ATTACK_ID.incrementAndGet();
		float damage = variant.combatProfile().scaleWristDamage(ArmorCombatTuning.CRUISE_MISSILE_DAMAGE);
		int longestFlight = ArmorCombatTuning.CRUISE_MISSILE_BASE_FLIGHT_TICKS;
		ServerLevel carrierLevel = (ServerLevel) carrier.level();
		for (int index = 0; index < missileCount; index++) {
			Vec3 origin = ArmorAnchorResolver.resolve(carrier, variant, ArmorAnchorPose.HOVER,
					ArmorSemanticAnchor.missileRack(index), index / 2,
					target.getBoundingBox().getCenter().subtract(carrier.getBoundingBox().getCenter()));
			Vec3 endpoint = target.getBoundingBox().getCenter();
			int flightTicks = Math.max(ArmorCombatTuning.CRUISE_MISSILE_BASE_FLIGHT_TICKS,
					(int) Math.ceil(origin.distanceTo(endpoint) / 1.10D));
			longestFlight = Math.max(longestFlight, flightTicks);
			Vec3 direction = endpoint.subtract(origin);
			Vec3 normal = direction.lengthSqr() > 1.0E-8D
					? direction.normalize().scale(-1.0D) : new Vec3(0.0D, 1.0D, 0.0D);
			ArmorHomingMissileEntity missile = ArmorHomingMissileEntity.launch(carrierLevel,
					owner, carrier, target, suitId, actionSequence, carrierRevision,
					attackId, index, damage, origin);
			carrierLevel.addFreshEntity(missile);
			broadcastCarrierFx(carrier, owner, new ArmorAttackFxPayload(
					carrier.getUUID(), carrier.level().dimension().location(), attackId, index,
					ArmorAttackType.CRUISE_MISSILE_VOLLEY, variant, false,
					origin, endpoint, endpoint, normal, ArmorAttackHitKind.MISS,
					target.getId(), Mth.clamp(flightTicks / 20.0F, 0.0F, 1.0F), false));
		}
		state.wristCooldownUntilTick = now + ArmorCombatTuning.CRUISE_MISSILE_COOLDOWN_TICKS;
		float heat = ArmorCombatTuning.CRUISE_MISSILE_BASE_HEAT
				+ missileCount * ArmorCombatTuning.CRUISE_MISSILE_HEAT_PER_TARGET;
		commitCruiseResources(owner, state, now, attackId, energyCost, heat);
		broadcastCarrierVisual(carrier, owner, new ArmorCombatVisualStatePayload(
				carrier.getUUID(), attackId, 0, ArmorAttackType.CRUISE_MISSILE_VOLLEY,
				ArmorCombatPhase.FIRING, false, now, longestFlight));
		return new CruiseWeaponResult(true, attackId);
	}

	private static ArmorVariant cruiseVariant(ServerPlayer owner, Entity carrier) {
		if (owner == null || carrier == null || !owner.isAlive() || owner.isSpectator()
				|| !carrier.isAlive() || carrier.isRemoved() || carrier == owner
				|| owner.level() != carrier.level() || !(carrier instanceof LivingEntity livingCarrier)) {
			return null;
		}
		return ModItems.fullSetVariant(livingCarrier).orElse(null);
	}

	private static boolean allowsCruiseRanged(Entity carrier, long gameTime) {
		return carrier instanceof LivingEntity living
				&& ArmorConfigurationAccess.allowsRangedCombat(living, gameTime);
	}

	private static boolean allowsCruiseHeavyMelee(Entity carrier, long gameTime) {
		return carrier instanceof LivingEntity living
				&& ArmorConfigurationAccess.allowsHeavyMelee(living, gameTime);
	}

	private static boolean validCruiseTarget(ServerPlayer owner, Entity carrier, LivingEntity target) {
		return owner != null && carrier != null && target != null && target != owner && target != carrier
				&& target.isAlive() && !target.isRemoved() && owner.level() == target.level()
				&& carrier.level() == target.level() && CombatTargetPolicy.canDamage(owner, target);
	}

	private static boolean cruiseStateReady(Entity carrier, ArmorCombatState state, long now,
			long cooldownUntilTick, float energyCost) {
		if (!state.dimension.equals(carrier.level().dimension())) {
			state.phase = ArmorCombatPhase.IDLE;
			state.dimension = carrier.level().dimension();
			return false;
		}
		return state.phase == ArmorCombatPhase.IDLE && !state.overheated
				&& now >= cooldownUntilTick && state.energy + 0.0001F >= energyCost;
	}

	private static void commitCruiseResources(ServerPlayer owner, ArmorCombatState state, long now,
			long attackId, float energyCost, float heat) {
		state.energy = Mth.clamp(state.energy - energyCost, 0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat + heat, 0.0F, ArmorCombatTuning.MAX_HEAT);
		state.overheated = state.overheated || state.heat >= ArmorCombatTuning.MAX_HEAT;
		state.lastFireTick = now;
		state.serverAttackId = attackId;
	}


	/** Emits only the custom impact geometry; the missile body itself is a tracked entity. */
	static void emitHomingMissileImpact(ArmorHomingMissileEntity missile, ServerPlayer owner,
			Vec3 impact, Vec3 normal, ArmorAttackHitKind hitKind, int hitEntityId) {
		if (!(missile.level() instanceof ServerLevel level) || owner == null) return;
		Entity carrier = missile.carrierId() == null ? null : level.getEntity(missile.carrierId());
		ArmorAttackFxPayload payload = new ArmorAttackFxPayload(
				missile.carrierId(), level.dimension().location(), missile.attackId(),
				1024 + missile.missileIndex(), ArmorAttackType.CRUISE_MISSILE_VOLLEY,
				ArmorVariant.SWIFT, false, missile.launchOrigin(), impact, impact, normal,
				hitKind, hitEntityId, 1.0F, false);
		broadcastCruiseFx(level, carrier, owner, payload);
	}
	private static void broadcastCruiseFx(ServerLevel level, Entity carrier, ServerPlayer owner,
			ArmorAttackFxPayload payload) {
		Set<ServerPlayer> recipients = new HashSet<>();
		if (carrier != null && !carrier.isRemoved()) recipients.addAll(PlayerLookup.tracking(carrier));
		recipients.addAll(PlayerLookup.around(level, payload.origin(), 56.0D));
		recipients.addAll(PlayerLookup.around(level, payload.endpoint(), 56.0D));
		if (owner.level() == level) recipients.add(owner);
		for (ServerPlayer recipient : recipients) {
			if (ServerPlayNetworking.canSend(recipient, ArmorAttackFxPayload.TYPE)) {
				ServerPlayNetworking.send(recipient, payload);
			}
		}
	}

	private static boolean finite(Vec3 value) {
		return value != null && Double.isFinite(value.x)
				&& Double.isFinite(value.y) && Double.isFinite(value.z);
	}
	public static void tickCruiseResources(ServerPlayer owner, Entity carrier) {
		if (owner == null || carrier == null || !owner.isAlive() || owner.isSpectator()
				|| carrier.isRemoved() || !carrier.isAlive()) return;
		ArmorCombatState state = cruiseStateFor(owner, carrier);
		if (state == null) return;
		long now = combatTime(owner.getServer());
		if (state.phase == ArmorCombatPhase.CHARGING) return;
		if (state.lastFireTick != Long.MIN_VALUE
				&& now - state.lastFireTick < ArmorCombatTuning.PASSIVE_RECOVERY_DELAY_TICKS) {
			return;
		}
		state.energy = Mth.clamp(state.energy + ArmorCombatTuning.CRUISE_RECOVERY_PER_SECOND / 20.0F, 0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat - ArmorCombatTuning.HEAT_DISSIPATION_PER_SECOND / 20.0F,
				0.0F, ArmorCombatTuning.MAX_HEAT);
		if (state.overheated && state.heat <= ArmorCombatTuning.OVERHEAT_UNLOCK_HEAT) {
			state.overheated = false;
		}
	}

	private static void fireWristPulse(ServerPlayer player, ArmorCombatState state,
			boolean underwater, long now, float energyCost, float charge) {
		boolean leftHand = state.activeLeftHand;
		state.nextLeftHand = !state.nextLeftHand;
		long attackId = state.serverAttackId > 0L
				? state.serverAttackId : NEXT_SERVER_ATTACK_ID.incrementAndGet();
		ArmorVariant variant = combatVariant(player);
		VariantCombatProfile variantProfile = variant.combatProfile();
		double range = variantProfile.scaleWristRange((underwater
				? ArmorCombatTuning.UNDERWATER_WRIST_RANGE : ArmorCombatTuning.WRIST_RANGE)
				* Mth.lerp(charge, 1.0D, ArmorCombatTuning.WRIST_MAX_RANGE_MULTIPLIER));
		float damage = variantProfile.scaleWristDamage((underwater
				? ArmorCombatTuning.UNDERWATER_WRIST_DAMAGE : ArmorCombatTuning.WRIST_DAMAGE)
				* Mth.lerp(charge, 1.0F, ArmorCombatTuning.WRIST_MAX_DAMAGE_MULTIPLIER));
		double knockback = (underwater
				? ArmorCombatTuning.UNDERWATER_WRIST_KNOCKBACK : ArmorCombatTuning.WRIST_KNOCKBACK)
				* Mth.lerp(charge, 1.0D, ArmorCombatTuning.WRIST_MAX_KNOCKBACK_MULTIPLIER);
		CombatRaycast.Result hit = CombatRaycast.castWristPulse(
				player, variant, combatAnchorPose(player), leftHand, range);
		Entity target = hit.entity();
		boolean nativeExplosion = ArmorWeaponRules.wristExplosion(
				variant, charge, false).enabled();
		if (!nativeExplosion && target != null
				&& CombatTargetPolicy.canDamage(player, target)) {
			boolean hurt = target.hurt(ModDamageTypes.gravityPulse(player), damage);
			if (hurt && target instanceof LivingEntity living) {
				living.knockback(knockback, player.getX() - living.getX(), player.getZ() - living.getZ());
			}
		}
		List<ArmorVariantAttackResolver.VisualSegment> chainSegments =
				ArmorVariantAttackResolver.applyWrist(player, variant, hit, target, damage, range, charge);

		state.energy = Mth.clamp(state.energy - energyCost, 0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat + Mth.lerp(charge,
						ArmorCombatTuning.WRIST_HEAT, ArmorCombatTuning.WRIST_MAX_HEAT),
				0.0F, ArmorCombatTuning.MAX_HEAT);
		state.overheated = state.overheated || state.heat >= ArmorCombatTuning.MAX_HEAT;
		state.wristCooldownUntilTick = now + ArmorCombatTuning.WRIST_COOLDOWN_TICKS;
		state.lastFireTick = now;
		state.serverAttackId = attackId;
		state.visualRevision = 0;
		state.attackType = ArmorAttackType.WRIST_GRAVITY_PULSE;
		state.activeLeftHand = leftHand;
		state.phase = ArmorCombatPhase.FIRING;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now + ArmorCombatTuning.WRIST_FIRING_TICKS;
		state.persistenceDirty = true;
		// 开火同时改变手序、能量、热量和冷却，属于低频关键事务，立即标脏。
		checkpointPersistentState(player, state, now, true);

		broadcastVisual(player, visualPayload(player, state, now));
		broadcastFx(player, new ArmorAttackFxPayload(
				player.getUUID(), player.level().dimension().location(), attackId, 0,
				ArmorAttackType.WRIST_GRAVITY_PULSE, variant, leftHand,
				hit.origin(), hit.endpoint(), hit.impactNormal(), hit.hitKind(),
				target == null ? ArmorAttackFxPayload.NO_ENTITY_ID : target.getId(), charge, underwater));
		int chainEventIndex = 1;
		for (ArmorVariantAttackResolver.VisualSegment segment : chainSegments) {
			broadcastFx(player, new ArmorAttackFxPayload(
					player.getUUID(), player.level().dimension().location(), attackId,
					chainEventIndex++, ArmorAttackType.WRIST_GRAVITY_PULSE, variant, leftHand,
					segment.origin(), segment.endpoint(), new Vec3(0.0D, 1.0D, 0.0D),
					ArmorAttackHitKind.ENTITY, ArmorAttackFxPayload.NO_ENTITY_ID, charge, underwater));
		}
		sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
	}

	private static void advancePhase(ServerPlayer player, ArmorCombatState state, long now) {
		if (state.phase == ArmorCombatPhase.CHARGING) {
			if (state.attackType == ArmorAttackType.HAND_BEAM_LANCE) {
				// 举着不放不是取消：满蓄力一定会打出去，只是改成自动在最大档释放。
				if (now - state.phaseStartGameTime >= EnhancedBeamMath.LANCE_MAX_HOLD_TICKS) {
					releaseHandBeamLance(player, state, now);
				}
				return;
			}
			if ((state.attackType == ArmorAttackType.CORE_BEAM
					&& now - state.phaseStartGameTime >= ArmorCombatTuning.CORE_MAX_HOLD_TICKS)
					|| (state.attackType == ArmorAttackType.WRIST_GRAVITY_PULSE
					&& now - state.phaseStartGameTime >= ArmorCombatTuning.WRIST_MAX_HOLD_TICKS)) {
				resetPhase(player, state, now, true);
				sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
			}
			return;
		}
		if (state.phase == ArmorCombatPhase.WINDUP && now >= state.phaseUntilGameTime) {
			long elapsed = Math.max(0L, now - state.phaseStartGameTime);
			if (state.attackType == ArmorAttackType.ENERGY_BLADE_COMBO
					&& elapsed >= ArmorCombatTuning.BLADE_COMBO_WINDUP_TICKS) {
				commitBladeCombo(player, state, now);
			} else if (state.attackType == ArmorAttackType.ENERGY_BLADE_SLASH
					&& ArmorWeaponRules.bladeStrikeReady(elapsed)) {
				commitBladeSlash(player, state, now);
			} else {
				resetPhase(player, state, now, true);
				sendOwnerState(player, state, ArmorCombatDenyReason.NONE, now);
			}
			return;
		}
		if (state.phase == ArmorCombatPhase.FIRING && now >= state.phaseUntilGameTime) {
			int recoveryTicks = activeRecoveryTicks(state);
			if (recoveryTicks > 0) {
				state.phase = ArmorCombatPhase.RECOVERY;
				state.phaseStartGameTime = now;
				state.phaseUntilGameTime = now + recoveryTicks;
				state.visualRevision++;
				broadcastVisual(player, visualPayload(player, state, now));
			} else {
				resetPhase(player, state, now, true);
			}
		} else if (state.phase == ArmorCombatPhase.RECOVERY && now >= state.phaseUntilGameTime) {
			resetPhase(player, state, now, true);
		}
	}

	private static int activeRecoveryTicks(ArmorCombatState state) {
		return switch (state.attackType) {
			case WRIST_GRAVITY_PULSE -> ArmorCombatTuning.WRIST_RECOVERY_TICKS;
			case CORE_BEAM -> ArmorCombatTuning.CORE_RECOVERY_TICKS;
			case ENERGY_BLADE_SLASH -> ArmorCombatTuning.BLADE_SLASH_RECOVERY_TICKS;
			case ENERGY_BLADE_COMBO -> ArmorCombatTuning.BLADE_COMBO_RECOVERY_TICKS;
			case HAND_BEAM_PULSE -> EnhancedBeamMath.PULSE_RECOVERY_TICKS;
			case HAND_BEAM_LANCE -> EnhancedBeamMath.LANCE_RECOVERY_TICKS;
			case GRAVITY_DIVE_IMPACT -> ArmorCombatTuning.DIVE_IMPACT_RECOVERY_TICKS;
			case ENERGY_BLADE_TOGGLE, CRUISE_RAM, CRUISE_CORE_BEAM,
					CRUISE_MISSILE_VOLLEY, CRUISE_GROUND_SLAM, INVALID -> 0;
		};
	}

	private static void tickResources(ServerPlayer player, ArmorCombatState state, long now) {
		if (!player.isAlive() || player.isSpectator()
				|| ArmorAssemblyController.isAnimating(player)) return;
		if (state.phase == ArmorCombatPhase.CHARGING) return;
		if (state.lastFireTick != Long.MIN_VALUE
				&& now - state.lastFireTick < ArmorCombatTuning.PASSIVE_RECOVERY_DELAY_TICKS) {
			return;
		}
		if (ModItems.fullSetVariant(player).isEmpty()) return;

		float energyPerTick;
		if (FlightController.isDiveActive(player)) {
			energyPerTick = ArmorCombatTuning.FLIGHT_RECOVERY_PER_SECOND / 20.0F;
		} else if (player.onGround() || player.getDeltaMovement().horizontalDistanceSqr() < 0.0025D) {
			energyPerTick = ArmorCombatTuning.IDLE_RECOVERY_PER_SECOND / 20.0F;
		} else {
			energyPerTick = ArmorCombatTuning.MOVING_RECOVERY_PER_SECOND / 20.0F;
		}
		state.energy = Mth.clamp(state.energy + energyPerTick, 0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(state.heat - ArmorCombatTuning.HEAT_DISSIPATION_PER_SECOND / 20.0F,
				0.0F, ArmorCombatTuning.MAX_HEAT);
		if (state.overheated && state.heat <= ArmorCombatTuning.OVERHEAT_UNLOCK_HEAT) {
			state.overheated = false;
		}
	}

	private static void resetPhase(ServerPlayer player, ArmorCombatState state, long now, boolean broadcast) {
		boolean wasActive = state.phase != ArmorCombatPhase.IDLE;
		if (wasActive) state.visualRevision++;
		state.phase = ArmorCombatPhase.IDLE;
		state.phaseStartGameTime = now;
		state.phaseUntilGameTime = now;
		if (broadcast && wasActive) broadcastVisual(player, visualPayload(player, state, now));
	}

	private static ArmorCombatVisualStatePayload visualPayload(
			ServerPlayer player, ArmorCombatState state, long now) {
		int duration = (int) Mth.clamp(state.phaseUntilGameTime - state.phaseStartGameTime, 0L, Integer.MAX_VALUE);
		return new ArmorCombatVisualStatePayload(
				player.getUUID(), state.serverAttackId, state.visualRevision,
				state.attackType, state.phase,
				state.activeLeftHand, state.phaseStartGameTime, duration);
	}

	private static void sendOwnerState(ServerPlayer player, ArmorCombatState state,
			ArmorCombatDenyReason reason, long now) {
		state.lastOwnerSyncGameTime = now;
		int cooldown = (int) Mth.clamp(state.wristCooldownUntilTick - now, 0L, Integer.MAX_VALUE);
		int coreCooldown = (int) Mth.clamp(state.coreCooldownUntilTick - now, 0L, Integer.MAX_VALUE);
		int diveCooldown = (int) Mth.clamp(state.diveImpactCooldownUntilTick - now, 0L, Integer.MAX_VALUE);
		ArmorCombatOwnerStatePayload payload = new ArmorCombatOwnerStatePayload(
				Math.max(0L, state.lastClientSequence), state.serverAttackId,
				state.energy, state.heat, state.overheated, cooldown, coreCooldown, diveCooldown,
				state.phase, reason);
		if (ServerPlayNetworking.canSend(player, ArmorCombatOwnerStatePayload.TYPE)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/** 相同连接中的拒绝快照最多每 4 tick 下发一次，避免无效请求形成回包放大。 */
	private static void sendDeniedState(ServerPlayer player, ArmorCombatState state,
			ArmorCombatDenyReason reason, long now) {
		if (state.lastDeniedSyncGameTime != Long.MIN_VALUE
				&& now - state.lastDeniedSyncGameTime < DENIED_SYNC_INTERVAL_TICKS) return;
		state.lastDeniedSyncGameTime = now;
		sendOwnerState(player, state, reason, now);
	}

	private static boolean consumeIntentToken(ArmorCombatState state, long now) {
		if (state.lastIntentRefillGameTime == Long.MIN_VALUE) {
			state.lastIntentRefillGameTime = now;
		} else {
			long elapsed = Math.max(0L, now - state.lastIntentRefillGameTime);
			state.intentTokens = Math.min(INTENT_BUCKET_CAPACITY,
					state.intentTokens + elapsed * INTENT_REFILL_PER_TICK);
			state.lastIntentRefillGameTime = now;
		}
		if (state.intentTokens < 1.0D) return false;
		state.intentTokens -= 1.0D;
		return true;
	}

	private static void broadcastVisual(ServerPlayer attacker, ArmorCombatVisualStatePayload payload) {
		Set<ServerPlayer> recipients = new HashSet<>(PlayerLookup.tracking(attacker));
		recipients.add(attacker);
		for (ServerPlayer recipient : recipients) {
			sendVisualState(recipient, payload);
		}
	}

	private static void sendVisualState(ServerPlayer recipient, ArmorCombatVisualStatePayload payload) {
		if (ServerPlayNetworking.canSend(recipient, ArmorCombatVisualStatePayload.TYPE)) {
			ServerPlayNetworking.send(recipient, payload);
		}
	}

	private static void broadcastFx(ServerPlayer attacker, ArmorAttackFxPayload payload) {
		Set<ServerPlayer> recipients = new HashSet<>(PlayerLookup.tracking(attacker));
		recipients.addAll(PlayerLookup.around(attacker.serverLevel(), payload.endpoint(), 48.0D));
		recipients.add(attacker);
		for (ServerPlayer recipient : recipients) {
			if (ServerPlayNetworking.canSend(recipient, ArmorAttackFxPayload.TYPE)) {
				ServerPlayNetworking.send(recipient, payload);
			}
		}
	}

	private static void broadcastCarrierVisual(Entity carrier, ServerPlayer owner,
			ArmorCombatVisualStatePayload payload) {
		Set<ServerPlayer> recipients = new HashSet<>(PlayerLookup.tracking(carrier));
		recipients.add(owner);
		for (ServerPlayer recipient : recipients) {
			sendVisualState(recipient, payload);
		}
	}

	private static void broadcastCarrierFx(Entity carrier, ServerPlayer owner, ArmorAttackFxPayload payload) {
		if (!(carrier.level() instanceof ServerLevel level)) return;
		Set<ServerPlayer> recipients = new HashSet<>(PlayerLookup.tracking(carrier));
		recipients.addAll(PlayerLookup.around(level, payload.endpoint(), 48.0D));
		recipients.add(owner);
		for (ServerPlayer recipient : recipients) {
			if (ServerPlayNetworking.canSend(recipient, ArmorAttackFxPayload.TYPE)) {
				ServerPlayNetworking.send(recipient, payload);
			}
		}
	}

	private static ArmorCombatState stateFor(ServerPlayer player) {
		return STATES.computeIfAbsent(player.getUUID(), ignored -> loadPersistentState(player));
	}

	private static ArmorCombatState cruiseStateFor(ServerPlayer owner, Entity carrier) {
		CruiseLaneKey key = cruiseLaneKey(owner, carrier);
		if (key == null) return null;
		return CRUISE_STATES.computeIfAbsent(key,
				ignored -> new ArmorCombatState(carrier.level().dimension()));
	}

	private static CruiseLaneKey cruiseLaneKey(ServerPlayer owner, Entity carrier) {
		if (owner == null || carrier == null || carrier == owner
				|| !(carrier instanceof LivingEntity living)
				|| owner.level() != carrier.level()) return null;
		ArmorIdentity.Identity identity =
				ArmorIdentity.read(living.getItemBySlot(EquipmentSlot.CHEST)).orElse(null);
		if (identity == null || identity.slot() != EquipmentSlot.CHEST
				|| !owner.getUUID().equals(identity.ownerId())) return null;
		return new CruiseLaneKey(owner.getUUID(), identity.suitId(), carrier.getUUID());
	}

	/** Clears one physical carrier lane and only missiles launched by the same incarnation. */
	public static void removeCruiseLane(UUID ownerId, UUID suitId, UUID carrierId,
			long carrierRevision) {
		if (ownerId == null || suitId == null || carrierId == null) return;
		CRUISE_STATES.remove(new CruiseLaneKey(ownerId, suitId, carrierId));
		ArmorHomingMissileEntity.discardByCarrier(
				suitId, carrierId, carrierRevision);
	}

	private record CruiseLaneKey(UUID ownerId, UUID suitId, UUID carrierId) {
	}

	private static ArmorCombatState loadPersistentState(ServerPlayer player) {
		ArmorCombatState state = new ArmorCombatState(player.level().dimension());
		ArmorCombatSavedData.Entry saved = ArmorCombatSavedData.find(player.getServer(), player.getUUID());
		if (saved == null) return state;

		long now = combatTime(player.getServer());
		state.energy = Mth.clamp(saved.energy(), 0.0F, ArmorCombatTuning.MAX_ENERGY);
		state.heat = Mth.clamp(saved.heat(), 0.0F, ArmorCombatTuning.MAX_HEAT);
		state.overheated = saved.overheated();
		long cooldownRemaining = Math.min(
				Math.max(0L, saved.wristCooldownRemaining()),
				Math.max(ArmorCombatTuning.CRUISE_MISSILE_COOLDOWN_TICKS,
						Math.max(ArmorCombatTuning.WRIST_COOLDOWN_TICKS,
								ArmorCombatTuning.AUTO_WRIST_COOLDOWN_TICKS)));
		state.wristCooldownUntilTick = now + cooldownRemaining;
		long coreCooldownRemaining = Math.min(
				Math.max(0L, saved.coreCooldownRemaining()),
				Math.max(ArmorCombatTuning.CORE_COOLDOWN_TICKS,
						ArmorCombatTuning.CRUISE_CORE_COOLDOWN_TICKS));
		state.coreCooldownUntilTick = now + coreCooldownRemaining;
		long diveImpactCooldownRemaining = Math.min(
				Math.max(0L, saved.diveImpactCooldownRemaining()),
				Math.max(ArmorCombatTuning.DIVE_IMPACT_COOLDOWN_TICKS,
						ArmorCombatTuning.CRUISE_RAM_COOLDOWN_TICKS));
		state.diveImpactCooldownUntilTick = now + diveImpactCooldownRemaining;
		state.nextLeftHand = saved.nextLeftHand();
		long recoveryDelayRemaining = Math.min(
				Math.max(0L, saved.recoveryDelayRemaining()),
				ArmorCombatTuning.PASSIVE_RECOVERY_DELAY_TICKS);
		if (recoveryDelayRemaining > 0L) {
			long elapsedBeforeDisconnect = ArmorCombatTuning.PASSIVE_RECOVERY_DELAY_TICKS
					- recoveryDelayRemaining;
			state.lastFireTick = now - elapsedBeforeDisconnect;
		}
		return state;
	}

	private static void checkpointPersistentState(
			ServerPlayer player, ArmorCombatState state, long now, boolean force) {
		if (!force && !state.persistenceDirty) return;

		ArmorCombatSavedData.Entry entry = persistentEntry(state, now);
		if (force) {
			ArmorCombatSavedData.flush(player.getServer(), player.getUUID(), entry);
			state.persistenceDirty = false;
			state.lastPersistenceDirtyGameTime = now;
			return;
		}

		boolean markDirty = state.lastPersistenceDirtyGameTime == Long.MIN_VALUE
				|| now - state.lastPersistenceDirtyGameTime >= PERSISTENCE_DIRTY_INTERVAL_TICKS;
		ArmorCombatSavedData.update(player.getServer(), player.getUUID(), entry, markDirty);
		if (markDirty) {
			state.persistenceDirty = false;
			state.lastPersistenceDirtyGameTime = now;
		}
	}

	private static ArmorCombatSavedData.Entry persistentEntry(ArmorCombatState state, long now) {
		long cooldownRemaining = Math.max(0L, state.wristCooldownUntilTick - now);
		long coreCooldownRemaining = Math.max(0L, state.coreCooldownUntilTick - now);
		long diveImpactCooldownRemaining = Math.max(0L, state.diveImpactCooldownUntilTick - now);
		long recoveryDelayRemaining = state.lastFireTick == Long.MIN_VALUE
				? 0L
				: Math.max(0L, ArmorCombatTuning.PASSIVE_RECOVERY_DELAY_TICKS
						- Math.max(0L, now - state.lastFireTick));
		return new ArmorCombatSavedData.Entry(
				state.energy, state.heat, state.overheated, cooldownRemaining,
				coreCooldownRemaining, diveImpactCooldownRemaining,
				recoveryDelayRemaining, state.nextLeftHand);
	}

	private enum DiveImpactKind {
		LANDING,
		WALL,
		PATH_ENTITY,
		WATER_PRESSURE
	}

	private record DiveContact(boolean hit, double fraction, Vec3 position, Vec3 normal,
			ArmorAttackHitKind hitKind, int targetEntityId) {
		private static DiveContact miss() {
			return new DiveContact(false, Double.POSITIVE_INFINITY, Vec3.ZERO,
					Vec3.ZERO, ArmorAttackHitKind.MISS, ArmorAttackFxPayload.NO_ENTITY_ID);
		}
	}

	private static final class DiveImpactAction {
		private final long sequence;
		private final ResourceKey<Level> dimension;
		private long completedSequence;
		private Vec3 peakVelocity = Vec3.ZERO;

		private DiveImpactAction(long sequence, ResourceKey<Level> dimension) {
			this.sequence = sequence;
			this.dimension = dimension;
		}

		private void observe(Vec3 velocity) {
			if (finite(velocity) && velocity.lengthSqr() > peakVelocity.lengthSqr()) {
				peakVelocity = velocity;
			}
		}
	}
	private record DiveImpactProfile(float damage, double radius, float strength) {
		private static DiveImpactProfile forSpeed(double speed) {
			if (speed >= ArmorCombatTuning.DIVE_IMPACT_HIGH_SPEED) {
				return new DiveImpactProfile(ArmorCombatTuning.DIVE_IMPACT_HIGH_DAMAGE,
						ArmorCombatTuning.DIVE_IMPACT_HIGH_RADIUS, 1.0F);
			}
			if (speed >= ArmorCombatTuning.DIVE_IMPACT_MID_SPEED) {
				return new DiveImpactProfile(ArmorCombatTuning.DIVE_IMPACT_MID_DAMAGE,
						ArmorCombatTuning.DIVE_IMPACT_MID_RADIUS, 0.70F);
			}
			return new DiveImpactProfile(ArmorCombatTuning.DIVE_IMPACT_LOW_DAMAGE,
					ArmorCombatTuning.DIVE_IMPACT_LOW_RADIUS, 0.42F);
		}

		private DiveImpactProfile scaled(VariantCombatProfile variantProfile) {
			return new DiveImpactProfile(
					variantProfile.scaleDiveDamage(damage),
					variantProfile.scaleDiveRadius(radius),
					strength);
		}
	}

	/** 只有完整同型号四件套能成为服务端战斗型号；其他状态安全回退且会被门禁拒绝。 */
	private static ArmorAnchorPose combatAnchorPose(ServerPlayer player) {
		ArmorAnchorPose inferred = ArmorAnchorResolver.inferPose(player);
		if (inferred == ArmorAnchorPose.SWIMMING) return inferred;
		if (FlightController.isDiveActive(player)) return ArmorAnchorPose.FLIGHT;
		if (FlightController.hasActiveFlightSession(player)) return ArmorAnchorPose.HOVER;
		return inferred;
	}
	private static ArmorVariant combatVariant(Entity armorHolder) {
		if (armorHolder instanceof LivingEntity living) {
			return ModItems.fullSetVariant(living).orElse(ArmorVariant.DEFAULT);
		}
		return ArmorVariant.DEFAULT;
	}


	public record CruiseWeaponResult(boolean fired, long serverAttackId) {
		private static CruiseWeaponResult missed() {
			return new CruiseWeaponResult(false, 0L);
		}
	}

	public record CruiseWristResult(boolean fired, long serverAttackId, boolean leftHand, int targetEntityId) {
		private static CruiseWristResult missed() {
			return new CruiseWristResult(false, 0L, false, ArmorAttackFxPayload.NO_ENTITY_ID);
		}
	}

	private static long combatTime(MinecraftServer server) {
		return server.overworld().getGameTime();
	}
}
