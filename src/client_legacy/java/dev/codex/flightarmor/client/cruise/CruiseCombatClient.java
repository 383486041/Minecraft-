package dev.codex.flightarmor.client.cruise;

import com.mojang.blaze3d.platform.InputConstants;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyClient;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import dev.codex.flightarmor.configuration.SentinelArmorMode;
import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import dev.codex.flightarmor.cruise.CruiseCombatBehavior;
import dev.codex.flightarmor.cruise.CruiseCombatMode;
import dev.codex.flightarmor.cruise.CruiseCombatStage;
import dev.codex.flightarmor.network.ArmorCruiseCombatCommandPayload;
import dev.codex.flightarmor.network.ArmorCruiseCombatStatePayload;
import dev.codex.flightarmor.network.ArmorCruiseStatePayload;
import dev.codex.flightarmor.network.SentinelWingmanCommandPayload;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 独立巡航战甲的客户端守卫控制与服务端权威视觉快照。 */
public final class CruiseCombatClient {
	private static final Map<UUID, CombatState> BY_OWNER = new HashMap<>();
	private static final Map<UUID, CombatState> BY_CARRIER = new HashMap<>();
	private static KeyMapping guardModeKey;
	private static ResourceKey<Level> lastLevel;
	private static long nextClientSequence = 1L;
	private static boolean initialized;

	private CruiseCombatClient() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		guardModeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.flight_armor.cruise_guard",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_H,
				"key.category.flight_armor"));
		ClientPlayNetworking.registerGlobalReceiver(ArmorCruiseCombatStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> receive(context.client(), payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	/** 每帧先消费 H，界面打开时不把积压点击带回游戏。 */
	public static void tick(Minecraft client) {
		boolean clicked = false;
		if (guardModeKey != null) {
			while (guardModeKey.consumeClick()) clicked = true;
		}
		if (client.level == null || client.player == null) {
			clearWorldState();
			lastLevel = null;
			return;
		}
		ResourceKey<Level> currentLevel = client.level.dimension();
		if (lastLevel != null && !lastLevel.equals(currentLevel)) {
			clearWorldState();
		}
		lastLevel = currentLevel;
		if (!clicked || client.screen != null) return;
		if (ArmorAssemblyClient.isOperationCapturedThisTick(client)) return;

		ArmorConfigurationAccess.ConfiguredSentinel sentinel =
				ArmorConfigurationAccess.configuredSentinel(client.player).orElse(null);
		long now = client.level.getGameTime();
		if (sentinel != null
				&& client.player.getUUID().equals(sentinel.ownerId())
				&& sentinel.snapshot().mode() == SentinelArmorMode.OVERDRIVE_FLIGHT
				&& !ArmorConfigurationAccess.isTransitioning(sentinel.snapshot(), now)) {
			if (!ClientPlayNetworking.canSend(SentinelWingmanCommandPayload.TYPE)) {
				client.player.displayClientMessage(
						Component.translatable("message.flight_armor.server_required"), true);
				return;
			}
			ArmorAssemblyClient.captureOperationThisTick(client);
			long clientSequence = nextClientSequence++;
			if (nextClientSequence <= 0L) nextClientSequence = 1L;
			ClientPlayNetworking.send(new SentinelWingmanCommandPayload(
					clientSequence, sentinel.suitId()));
			return;
		}

		ArmorCruiseClient.CruiseState cruise = ArmorCruiseClient.stateForOwner(client.player.getUUID());
		if (cruise == null || cruise.phase() != ArmorCruisePhase.CRUISING
				|| ArmorCruiseStatePayload.NO_CARRIER_ID.equals(cruise.carrierId())) {
			client.player.displayClientMessage(
					Component.translatable("message.flight_armor.cruise_guard_requires_cruise"), true);
			return;
		}
		if (!ClientPlayNetworking.canSend(ArmorCruiseCombatCommandPayload.TYPE)) {
			client.player.displayClientMessage(Component.translatable("message.flight_armor.server_required"), true);
			return;
		}

		CombatState current = BY_OWNER.get(client.player.getUUID());
		CruiseCombatMode requested = current != null && current.mode == CruiseCombatMode.GUARD
				? CruiseCombatMode.PASSIVE : CruiseCombatMode.GUARD;
		long clientSequence = nextClientSequence++;
		if (nextClientSequence <= 0L) nextClientSequence = 1L;
		ClientPlayNetworking.send(new ArmorCruiseCombatCommandPayload(
				clientSequence, cruise.sequenceId(), cruise.carrierId(), requested));
	}

	private static void receive(Minecraft client, ArmorCruiseCombatStatePayload payload) {
		CombatState previous = BY_OWNER.get(payload.ownerId());
		if (previous != null && payload.combatRevision() < previous.combatRevision) return;
		if (previous != null && payload.combatRevision() == previous.combatRevision
				&& !ArmorCruiseStatePayload.NO_CARRIER_ID.equals(previous.carrierId)
				&& ArmorCruiseStatePayload.NO_CARRIER_ID.equals(payload.carrierId())) {
			// 追踪者补发包可能缺少载体 UUID；不能让它覆盖同修订号的完整快照。
			return;
		}

		CruiseCombatMode mode = payload.mode() == CruiseCombatMode.GUARD
				? CruiseCombatMode.GUARD : CruiseCombatMode.PASSIVE;
		CruiseCombatBehavior behavior = normalizeBehavior(mode, payload.behavior());
		ArmorAttackType attackType = payload.attackType() == null
				? ArmorAttackType.INVALID : payload.attackType();
		CruiseCombatStage stage = normalizeStage(
				mode, behavior, attackType, payload.stage());
		long stageStart = payload.phaseStartGameTime();
		int stageTicks = Math.max(1, payload.phaseDurationTicks());
		if (previous != null
				&& previous.carrierId.equals(payload.carrierId())
				&& previous.cruiseSequenceId == payload.cruiseSequenceId()
				&& previous.stage == stage) {
			// Repeated authority snapshots must not restart a continuous maneuver every tick.
			stageStart = Math.min(previous.phaseStartGameTime, stageStart);
			stageTicks = Math.max(previous.phaseDurationTicks, stageTicks);
		}
		CombatState next = new CombatState(
				payload.ownerId(), payload.carrierId(), payload.cruiseSequenceId(), payload.combatRevision(),
				payload.serverAttackId(), mode, behavior, attackType, stage,
				payload.targetEntityId(), payload.leftHand(), stageStart, stageTicks);
		if (previous != null && !ArmorCruiseStatePayload.NO_CARRIER_ID.equals(previous.carrierId)) {
			BY_CARRIER.remove(previous.carrierId, previous);
		}
		BY_OWNER.put(next.ownerId, next);
		if (!ArmorCruiseStatePayload.NO_CARRIER_ID.equals(next.carrierId)) {
			BY_CARRIER.put(next.carrierId, next);
		}

		if (client.player != null && client.player.getUUID().equals(next.ownerId)
				&& (previous == null && next.mode == CruiseCombatMode.GUARD
				|| previous != null && previous.mode != next.mode)) {
			String key = next.mode == CruiseCombatMode.GUARD
					? "message.flight_armor.cruise_guard_enabled"
					: "message.flight_armor.cruise_guard_disabled";
			client.player.displayClientMessage(Component.translatable(key), true);
		}
	}

	private static CruiseCombatBehavior normalizeBehavior(CruiseCombatMode mode,
			CruiseCombatBehavior behavior) {
		if (mode != CruiseCombatMode.GUARD) return CruiseCombatBehavior.PASSIVE;
		return switch (behavior) {
			case SEARCHING, AIMING, FIRING, COOLDOWN -> behavior;
			case PASSIVE, INVALID -> CruiseCombatBehavior.SEARCHING;
		};
	}

	private static CruiseCombatStage normalizeStage(CruiseCombatMode mode,
			CruiseCombatBehavior behavior, ArmorAttackType attackType, CruiseCombatStage stage) {
		if (mode != CruiseCombatMode.GUARD) return CruiseCombatStage.IDLE;
		if (stage == null || stage == CruiseCombatStage.INVALID) {
			return CruiseCombatStage.fromLegacy(mode, behavior, attackType);
		}
		return stage;
	}

	public static CruiseCombatMode modeOfCarrier(LivingEntity entity) {
		CombatState state = BY_CARRIER.get(entity.getUUID());
		return state == null ? CruiseCombatMode.PASSIVE : state.mode;
	}

	public static CruiseCombatBehavior behaviorOfCarrier(LivingEntity entity) {
		CombatState state = BY_CARRIER.get(entity.getUUID());
		return state == null ? CruiseCombatBehavior.PASSIVE : state.behavior;
	}

	public static boolean leftHandOfCarrier(LivingEntity entity) {
		CombatState state = BY_CARRIER.get(entity.getUUID());
		return state != null && state.leftHand;
	}

	public static ArmorAttackType attackTypeOfCarrier(LivingEntity entity) {
		CombatState state = entity == null ? null : BY_CARRIER.get(entity.getUUID());
		return state == null ? ArmorAttackType.INVALID : state.attackType;
	}

	public static CruiseCombatStage stageOfCarrier(LivingEntity entity) {
		CombatState state = entity == null ? null : BY_CARRIER.get(entity.getUUID());
		return state == null ? CruiseCombatStage.IDLE : state.stage;
	}

	/** 返回服务端阶段时间线的连续进度，渲染器只据此换色，不自行推断攻击。 */
	public static float behaviorProgress(LivingEntity entity, float partialTick) {
		CombatState state = BY_CARRIER.get(entity.getUUID());
		Minecraft client = Minecraft.getInstance();
		if (state == null || client.level == null) return 0.0F;
		float elapsed = client.level.getGameTime() + Mth.clamp(partialTick, 0.0F, 1.0F)
				- state.phaseStartGameTime;
		return Mth.clamp(elapsed / state.phaseDurationTicks, 0.0F, 1.0F);
	}

	public static float stageProgress(LivingEntity entity, float partialTick) {
		return behaviorProgress(entity, partialTick);
	}

	public static void clear() {
		clearWorldState();
		lastLevel = null;
		nextClientSequence = 1L;
	}

	private static void clearWorldState() {
		BY_OWNER.clear();
		BY_CARRIER.clear();
	}

	private record CombatState(
			UUID ownerId,
			UUID carrierId,
			long cruiseSequenceId,
			long combatRevision,
			long serverAttackId,
			CruiseCombatMode mode,
			CruiseCombatBehavior behavior,
			ArmorAttackType attackType,
			CruiseCombatStage stage,
			int targetEntityId,
			boolean leftHand,
			long phaseStartGameTime,
			int phaseDurationTicks) {
	}
}
