package dev.codex.flightarmor.client.cruise;

import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import dev.codex.flightarmor.cruise.RecallApproachProfile;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorCruiseStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 客户端独立巡航状态镜像；B 键仍由原有着甲客户端发送同一种请求。 */
public final class ArmorCruiseClient {
	private static final long ANY_ATTEMPT_ID = Long.MIN_VALUE;
	public static final String CARRIER_TAG = "flight_armor_cruise_carrier";
	private static final UUID ZERO_UUID = new UUID(0L, 0L);
	private static final Map<UUID, CruiseState> BY_OWNER = new HashMap<>();
	private static final Map<UUID, CruiseState> BY_CARRIER = new HashMap<>();
	/** IDLE 包可能比实体移除早一帧到达；墓碑在这段窗口内压制载体的标签兜底识别。 */
	private static final Map<UUID, Long> RETIRED_CARRIERS = new HashMap<>();
	/** DOCKING->IDLE 与装备同步包之间的极短交接桥，防止旧载体消失后玩家身上空一帧。 */
	private static final Map<UUID, Long> DOCKING_HANDOFF_UNTIL = new HashMap<>();

	private ArmorCruiseClient() {
	}

	public static void initialize() {
		// CruiseRecallFxRenderer.initialize();
		ClientPlayNetworking.registerGlobalReceiver(ArmorCruiseStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> receive(payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	private static void receive(ArmorCruiseStatePayload payload) {
		pruneRetiredCarriers();
		CruiseState previous = BY_OWNER.get(payload.playerId());
		if (previous != null && (payload.sequenceId() < previous.sequenceId
				|| payload.sequenceId() == previous.sequenceId
				&& payload.phaseStartGameTime() < previous.phaseStartGameTime)) {
			return;
		}
		boolean carrierChanged = previous != null
				&& !previous.carrierId.equals(payload.carrierId());
		if (previous != null && !ZERO_UUID.equals(previous.carrierId)) {
			BY_CARRIER.remove(previous.carrierId);
			// 超距/跨维度折跃会在相位中点替换载体。旧 UUID 即使尚未收到实体移除包，
			// 也必须立即进入墓碑窗口，避免一帧木架或新旧两套战甲同时出现。
			if (payload.phase() == ArmorCruisePhase.IDLE || carrierChanged) {
				Minecraft client = Minecraft.getInstance();
				long now = client.level == null ? 0L : client.level.getGameTime();
				RETIRED_CARRIERS.put(previous.carrierId, now + 40L);
			}
		}

		CruiseState next = new CruiseState(payload.playerId(), payload.sequenceId(), payload.phase(),
				payload.carrierId(), payload.carrierEntityId(), payload.phaseStartGameTime(),
				payload.emergencyRecallAttemptId());
		if (previous != null && previous.phase == ArmorCruisePhase.DOCKING
				&& next.phase == ArmorCruisePhase.IDLE) {
			Minecraft client = Minecraft.getInstance();
			long now = client.level == null ? 0L : client.level.getGameTime();
			DOCKING_HANDOFF_UNTIL.put(next.ownerId, now + 8L);
		} else if (next.phase != ArmorCruisePhase.IDLE) {
			DOCKING_HANDOFF_UNTIL.remove(next.ownerId);
		}
		BY_OWNER.put(payload.playerId(), next);
		if (next.phase != ArmorCruisePhase.DEPLOYING) {
			CruiseDeploymentClient.clearOwner(next.ownerId);
		}
		if (next.phase != ArmorCruisePhase.IDLE && !ZERO_UUID.equals(next.carrierId)) {
			RETIRED_CARRIERS.remove(next.carrierId);
			BY_CARRIER.put(next.carrierId, next);
		}
		displayLocalStatus(previous, next);
	}

	private static void displayLocalStatus(CruiseState previous, CruiseState next) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !client.player.getUUID().equals(next.ownerId)) {
			return;
		}
		boolean phaseChanged = previous == null || previous.phase != next.phase;
		boolean carrierChanged = previous != null && !previous.carrierId.equals(next.carrierId);
		if (!phaseChanged && !(next.phase == ArmorCruisePhase.PHASING && carrierChanged)) return;
		boolean respawnRecall = previous != null && previous.phase == ArmorCruisePhase.OWNER_DOWN
				&& (next.phase == ArmorCruisePhase.PHASING || next.phase == ArmorCruisePhase.RECALLING);
		String key = switch (next.phase) {
			case CRUISING -> previous != null && previous.phase == ArmorCruisePhase.DEPLOYING
					? null : "message.flight_armor.cruise_enabled";
			case DEPLOYING -> "message.flight_armor.cruise_enabled";
			case RECALLING -> respawnRecall
					? "message.flight_armor.cruise_respawn_recall"
					: "message.flight_armor.cruise_recalling";
			case PHASING -> carrierChanged
					? "message.flight_armor.cruise_phase_reentry"
					: respawnRecall
						? "message.flight_armor.cruise_respawn_recall"
						: "message.flight_armor.cruise_phasing";
			case OWNER_DOWN -> "message.flight_armor.cruise_owner_down";
			case DOCKING -> "message.flight_armor.cruise_docking";
			case IDLE -> previous != null && previous.phase == ArmorCruisePhase.DOCKING
					? "message.flight_armor.cruise_arrived" : null;
		};
		if (key != null) {
			client.player.sendOverlayMessage(Component.translatable(key));
		}
	}

	public static boolean isCruiseCarrier(LivingEntity entity) {
		if (isRetiredCarrier(entity)) {
			return false;
		}
		if (BY_CARRIER.containsKey(entity.getUUID()) || entity.entityTags().contains(CARRIER_TAG)) {
			return true;
		}
		// 自定义 payload 与实体装备包到达顺序可能相差一帧；这组服务端特征用于消除木架闪现。
		return entity instanceof ArmorStand stand && ModItems.hasFullSet(stand)
				&& stand.showArms() && !stand.showBasePlate() && stand.isNoGravity() && stand.isSilent();
	}

	/**
	 * 墓碑载体已经由服务端完成物品转移，只是在客户端实体列表中尚未移除。
	 * 渲染器应在该极短窗口内同时跳过木架与旧装备层。
	 */
	public static boolean isRetiredCarrier(Entity entity) {
		Long expiresAt = RETIRED_CARRIERS.get(entity.getUUID());
		if (expiresAt == null) return false;
		Minecraft client = Minecraft.getInstance();
		if (client.level != null && client.level.getGameTime() <= expiresAt) return true;
		RETIRED_CARRIERS.remove(entity.getUUID());
		return false;
	}

	private static void pruneRetiredCarriers() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		long now = client.level.getGameTime();
		RETIRED_CARRIERS.entrySet().removeIf(entry -> entry.getValue() < now);
		DOCKING_HANDOFF_UNTIL.entrySet().removeIf(entry -> entry.getValue() < now);
	}

	public static ArmorCruisePhase phaseOfCarrier(LivingEntity entity) {
		CruiseState state = BY_CARRIER.get(entity.getUUID());
		if (state != null) {
			return state.phase;
		}
		return entity.entityTags().contains(CARRIER_TAG) ? ArmorCruisePhase.CRUISING : ArmorCruisePhase.IDLE;
	}

	/** 晚段整甲贴合只读取持有者的客户端姿势，不修改玩家位置或服务端状态。 */
	public static Player ownerOfCarrier(LivingEntity entity) {
		CruiseState state = BY_CARRIER.get(entity.getUUID());
		Minecraft client = Minecraft.getInstance();
		return state == null || client.level == null ? null : client.level.getPlayerByUUID(state.ownerId);
	}

	/**
	 * 返回整体贴合的连续进度。时间基准来自服务端快照，因此低帧率或网络抖动不会延长动画。
	 */
	public static float dockingProgress(LivingEntity entity, float partialTick) {
		return phaseProgress(entity, ArmorCruisePhase.DOCKING,
				ArmorCruisePhase.DOCKING_DURATION_TICKS, partialTick);
	}

	public static float dockingProgress(CruiseState state, float partialTick) {
		return phaseProgress(state, ArmorCruisePhase.DOCKING,
				ArmorCruisePhase.DOCKING_DURATION_TICKS, partialTick);
	}

	/** 当前整甲贴合序列已经越过双靴视觉移交点。 */
	public static boolean isBootHoverReady(Player player) {
		CruiseState state = BY_OWNER.get(player.getUUID());
		return state != null && isBootHoverReady(state, 0L, ANY_ATTEMPT_ID);
	}

	/** 带序列校验的版本，用于拒绝旧巡航载体发出的迟到悬停事件。 */
	public static boolean isBootHoverReady(Player player, long sequenceId) {
		CruiseState state = BY_OWNER.get(player.getUUID());
		return state != null && isBootHoverReady(state, sequenceId, ANY_ATTEMPT_ID);
	}

	public static boolean isBootHoverReady(Player player, long sequenceId, long attemptId) {
		CruiseState state = BY_OWNER.get(player.getUUID());
		return state != null && isBootHoverReady(state, sequenceId, attemptId);
	}

	/** 贴合完成后的 IDLE 墓碑仍可确认同一次战靴会话，其他重试或回退阶段均拒绝。 */
	public static boolean isBootHoverSessionValid(Player player, long sequenceId, long attemptId) {
		CruiseState state = BY_OWNER.get(player.getUUID());
		if (state == null || state.sequenceId != sequenceId || state.phaseStartGameTime != attemptId) {
			return false;
		}
		return state.phase == ArmorCruisePhase.IDLE || isBootHoverReady(state, sequenceId, attemptId);
	}

	public static boolean isCompleted(Player player, long sequenceId, long attemptId) {
		CruiseState state = BY_OWNER.get(player.getUUID());
		return state != null && state.phase == ArmorCruisePhase.IDLE
				&& state.sequenceId == sequenceId && state.phaseStartGameTime == attemptId;
	}

	private static boolean isBootHoverReady(CruiseState state, long sequenceId, long attemptId) {
		return state.phase == ArmorCruisePhase.DOCKING
				&& (sequenceId == 0L || state.sequenceId == sequenceId)
				&& (attemptId == ANY_ATTEMPT_ID || state.phaseStartGameTime == attemptId)
				&& dockingProgress(state, 1.0F) >= DockingTimeline.BOOTS_TRANSFER;
	}

	public static boolean isEmergencyRecallHoverReady(Player player) {
		CruiseState state = BY_OWNER.get(player.getUUID());
		return state != null && state.emergencyRecallAttemptId != 0L
				&& isRecallPhase(state.phase);
	}

	/** 客户端只接管服务端快照中同一序列、同一救援 attempt 的自动悬停。 */
	public static boolean isEmergencyRecallHoverSessionValid(Player player,
			long sequenceId, long attemptId) {
		CruiseState state = BY_OWNER.get(player.getUUID());
		return state != null && attemptId != 0L && state.sequenceId == sequenceId
				&& state.emergencyRecallAttemptId == attemptId && isRecallPhase(state.phase);
	}

	private static boolean isRecallPhase(ArmorCruisePhase phase) {
		return phase == ArmorCruisePhase.RECALLING
				|| phase == ArmorCruisePhase.PHASING
				|| phase == ArmorCruisePhase.DOCKING;
	}

	/**
	 * 玩家骨骼上的晚段包覆进度。返回负数表示不渲染；IDLE 后若真实装备包尚未到达，
	 * 最多维持 8 tick 的最终态视觉桥，一旦检测到完整套装就立即让普通 ArmorRenderer 接管。
	 */
	public static float dockingOverlayProgress(Player player, float partialTick) {
		// 装备同步包可能比 IDLE 快一帧到达；真实四件一旦可见，覆盖层立刻退场，杜绝 z-fighting。
		if (ModItems.hasFullSet(player)) {
			DOCKING_HANDOFF_UNTIL.remove(player.getUUID());
			return -1.0F;
		}
		CruiseState state = BY_OWNER.get(player.getUUID());
		if (state != null && state.phase == ArmorCruisePhase.DOCKING) {
			float progress = dockingProgress(state, partialTick);
			return progress >= DockingTimeline.ZONE_LOCK_START ? progress : -1.0F;
		}
		Long expiresAt = DOCKING_HANDOFF_UNTIL.get(player.getUUID());
		Minecraft client = Minecraft.getInstance();
		long now = client.level == null ? Long.MAX_VALUE : client.level.getGameTime();
		if (expiresAt != null && now <= expiresAt && !ModItems.hasFullSet(player)) return 1.0F;
		DOCKING_HANDOFF_UNTIL.remove(player.getUUID());
		return -1.0F;
	}

	/**
	 * 整甲折跃使用服务端 phaseStartGameTime 作为唯一时钟。载体在中点换 UUID 后，
	 * 新实体仍会从同一条 12 tick 时间线的后半段继续展开，不会重新播放收束动画。
	 */
	public static float phasingProgress(LivingEntity entity, float partialTick) {
		return phaseProgress(entity, ArmorCruisePhase.PHASING,
				ArmorCruisePhase.PHASING_DURATION_TICKS, partialTick);
	}

	public static float phasingProgress(CruiseState state, float partialTick) {
		return phaseProgress(state, ArmorCruisePhase.PHASING,
				ArmorCruisePhase.PHASING_DURATION_TICKS, partialTick);
	}

	/**
	 * 召回阶段刚切入时的短过渡。直接从巡航切到召回，或折跃重新展开后继续返航时，
	 * 整甲会用共享的 2 tick 时间从直立姿态迅速压低为远距低风阻姿态。
	 */
	public static float recallingEntryProgress(LivingEntity entity, float partialTick) {
		return phaseProgress(entity, ArmorCruisePhase.RECALLING,
				RecallApproachProfile.ENTRY_POSE_TICKS, partialTick);
	}

	private static float phaseProgress(LivingEntity entity, ArmorCruisePhase expectedPhase,
			int durationTicks, float partialTick) {
		return phaseProgress(BY_CARRIER.get(entity.getUUID()), expectedPhase, durationTicks, partialTick);
	}

	private static float phaseProgress(CruiseState state, ArmorCruisePhase expectedPhase,
			int durationTicks, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		if (state == null || state.phase != expectedPhase || client.level == null) return 0.0F;
		float elapsed = client.level.getGameTime() + Mth.clamp(partialTick, 0.0F, 1.0F)
				- state.phaseStartGameTime;
		return Mth.clamp(elapsed / durationTicks, 0.0F, 1.0F);
	}

	public static Entity resolveCarrier(Minecraft client, CruiseState state) {
		if (client.level == null || state.phase == ArmorCruisePhase.IDLE || state.carrierEntityId < 0) {
			return null;
		}
		Entity entity = client.level.getEntity(state.carrierEntityId);
		return entity != null && (state.carrierId.equals(entity.getUUID()) || entity.entityTags().contains(CARRIER_TAG))
				? entity : null;
	}

	public static Collection<CruiseState> states() {
		return Collections.unmodifiableCollection(BY_OWNER.values());
	}

	/** 返回指定持有者当前的巡航快照，供同一客户端内的守卫控制复用。 */
	public static CruiseState stateForOwner(UUID ownerId) {
		return BY_OWNER.get(ownerId);
	}

	public static void clear() {
		BY_OWNER.clear();
		BY_CARRIER.clear();
		RETIRED_CARRIERS.clear();
		DOCKING_HANDOFF_UNTIL.clear();
		CruiseDeploymentClient.clear();
	}

	public record CruiseState(UUID ownerId, long sequenceId, ArmorCruisePhase phase, UUID carrierId,
			int carrierEntityId, long phaseStartGameTime, long emergencyRecallAttemptId) {
	}
}
