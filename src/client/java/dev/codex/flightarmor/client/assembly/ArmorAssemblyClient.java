package dev.codex.flightarmor.client.assembly;

import com.mojang.blaze3d.platform.InputConstants;
import dev.codex.flightarmor.client.FlightArmorKeyMappings;
import dev.codex.flightarmor.assembly.ArmorAssemblySlotMask;
import dev.codex.flightarmor.assembly.ArmorAssemblyTiming;
import dev.codex.flightarmor.client.audio.ArmorAssemblySoundController;
import dev.codex.flightarmor.network.ArmorAssemblyRequestPayload;
import dev.codex.flightarmor.network.ArmorAssemblyStatePayload;
import dev.codex.flightarmor.network.ArmorQuickSwitchPayload;
import dev.codex.flightarmor.network.ArmorWardrobeAction;
import dev.codex.flightarmor.network.ArmorWardrobeRequestPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 客户端分件着甲快照、按键和按服务端游戏时间推导的连续进度。 */
public final class ArmorAssemblyClient {
	private static final long ANY_ATTEMPT_ID = Long.MIN_VALUE;
	private static final int REQUEST_COOLDOWN_TICKS = 5;
	private static final int WARDROBE_REQUEST_COOLDOWN_TICKS = 2;
	/** 可见飞入与服务端展示事务共用同一时长，不产生动画后的等待尾巴。 */
	private static final Map<UUID, VisualState> STATES = new HashMap<>();
	private static KeyMapping summonKey;
	private static KeyMapping wardrobeUpKey;
	private static KeyMapping wardrobeDownKey;
	private static KeyMapping quickSwitchKey;
	private static KeyMapping wardrobeModifierKey;
	private static long lastRequestGameTime = Long.MIN_VALUE / 2L;
	private static long lastWardrobeRequestGameTime = Long.MIN_VALUE / 2L;
	private static long lastQuickSwitchGameTime = Long.MIN_VALUE / 2L;
	private static long nextQuickSwitchSequence;
	private static int wardrobeChordSuppressionTicks;
	private static int capturedOperationTick = Integer.MIN_VALUE;
	private static ResourceKey<Level> lastDimension;

	private ArmorAssemblyClient() {
	}

	public static void initialize() {
		summonKey = FlightArmorKeyMappings.register("key.flight_armor.summon_armor", InputConstants.KEY_B);
		wardrobeUpKey = FlightArmorKeyMappings.register("key.flight_armor.wardrobe_up", InputConstants.KEY_UP);
		wardrobeDownKey = FlightArmorKeyMappings.register("key.flight_armor.wardrobe_down", InputConstants.KEY_DOWN);
		quickSwitchKey = FlightArmorKeyMappings.register("key.flight_armor.quick_switch", InputConstants.KEY_N);
		// 衣柜组合键独立于疾跑/推进键：默认左 Alt，避免与默认左 Ctrl 的疾跑推进互相触发。
		wardrobeModifierKey = FlightArmorKeyMappings.register("key.flight_armor.wardrobe_modifier", InputConstants.KEY_LALT);
		ClientPlayNetworking.registerGlobalReceiver(ArmorAssemblyStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> receive(payload)));
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof Player player) {
				STATES.remove(player.getUUID());
				ArmorAssemblySoundController.remove(player.getUUID());
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	/** 由客户端主 tick 调用，只消费一次 B 键请求；动画本身不需要逐 tick 改写状态。 */
	public static void tick(Minecraft client) {
		trackDimension(client);
		ArmorAssemblySoundController.tick(client);
		if (wardrobeChordSuppressionTicks > 0) wardrobeChordSuppressionTicks--;
		if (summonKey == null || wardrobeUpKey == null || wardrobeDownKey == null
				|| quickSwitchKey == null || wardrobeModifierKey == null) return;
		boolean summonClicked = drainClicks(summonKey);
		boolean wardrobeUpClicked = drainClicks(wardrobeUpKey);
		boolean wardrobeDownClicked = drainClicks(wardrobeDownKey);
		boolean quickSwitchClicked = drainClicks(quickSwitchKey);
		if (client.player == null || client.level == null || client.gui.screen() != null) return;
		boolean transactionClicked = summonClicked || wardrobeUpClicked
				|| wardrobeDownClicked || quickSwitchClicked;
		if (transactionClicked) captureOperationThisTick(client);
		boolean wardrobeModifierDown = wardrobeModifierKey.isDown();
		if (wardrobeModifierDown && summonClicked) {
			wardrobeChordSuppressionTicks = 2;
			sendWardrobeRequest(client, ArmorWardrobeAction.FULL_TOGGLE);
		} else if (wardrobeModifierDown && wardrobeUpClicked != wardrobeDownClicked) {
			wardrobeChordSuppressionTicks = 2;
			sendWardrobeRequest(client, wardrobeUpClicked
					? ArmorWardrobeAction.STEP_EQUIP : ArmorWardrobeAction.STEP_REMOVE);
		} else if (!wardrobeModifierDown && summonClicked) {
			sendSummonRequest(client);
		} else if (quickSwitchClicked) {
			sendQuickSwitchRequest(client);
		}
	}

	private static boolean drainClicks(KeyMapping keyMapping) {
		boolean clicked = false;
		while (keyMapping.consumeClick()) clicked = true;
		return clicked;
	}

	private static void sendSummonRequest(Minecraft client) {
		long now = client.level.getGameTime();
		if (now - lastRequestGameTime < REQUEST_COOLDOWN_TICKS) return;
		if (!ClientPlayNetworking.canSend(ArmorAssemblyRequestPayload.TYPE)) return;
		ClientPlayNetworking.send(new ArmorAssemblyRequestPayload());
		lastRequestGameTime = now;
	}

	private static void sendWardrobeRequest(Minecraft client, ArmorWardrobeAction action) {
		long now = client.level.getGameTime();
		if (now - lastWardrobeRequestGameTime < WARDROBE_REQUEST_COOLDOWN_TICKS) return;
		if (!ClientPlayNetworking.canSend(ArmorWardrobeRequestPayload.TYPE)) return;
		ClientPlayNetworking.send(new ArmorWardrobeRequestPayload(action));
		lastWardrobeRequestGameTime = now;
	}
	private static void sendQuickSwitchRequest(Minecraft client) {
		long now = client.level.getGameTime();
		if (now - lastQuickSwitchGameTime < REQUEST_COOLDOWN_TICKS) return;
		if (!ClientPlayNetworking.canSend(ArmorQuickSwitchPayload.TYPE)) return;
		ClientPlayNetworking.send(new ArmorQuickSwitchPayload(++nextQuickSwitchSequence));
		lastQuickSwitchGameTime = now;
	}

	private static void receive(ArmorAssemblyStatePayload payload) {
		VisualState old = STATES.get(payload.playerId());
		if (old != null && payload.sequenceId() < old.sequenceId) return;
		STATES.put(payload.playerId(), new VisualState(
				payload.sequenceId(),
				payload.startGameTime(),
				Math.max(1, payload.durationTicks()),
				payload.active(),
				payload.active() && payload.assembling(),
				payload.affectedSlotMask(),
				payload.retracting(),
				payload.timelineStartTick(),
				Math.max(payload.timelineStartTick() + 1, payload.timelineEndTick()),
				payload.locksFlight()));
		ArmorAssemblySoundController.receive(payload);
	}

	/** 总流程进度。没有活动快照时保持 1，确保普通装备状态仍完整渲染。 */
	public static float assemblyProgress(Player player, float partialTick) {
		VisualState state = STATES.get(player.getUUID());
		if (state == null) return 1.0F;
		// active=false 是服务端的“旧流程已失效”墓碑，不代表以后手动穿上的装甲也应被隐藏。
		if (!state.active) return 1.0F;
		if (!state.animating) return 1.0F;
		if (state.retracting) {
			return Mth.clamp(elapsedTicks(state, partialTick) / state.durationTicks, 0.0F, 1.0F);
		}
		float start = visualStartTick(state);
		return Mth.clamp((visualCanonicalTick(state, partialTick) - start)
				/ Math.max(1.0F, state.timelineEndTick - start), 0.0F, 1.0F);
	}

	/**
	 * 返回逻辑部件 0..1 的贴合进度。非目标槽恒为 1；脱甲目标槽按 1→0 反向复用飞入轨迹。
	 */
	public static float partProgress(Player player, ArmorAssemblyPart part, float partialTick) {
		VisualState state = STATES.get(player.getUUID());
		if (state == null) return 1.0F;
		if (!state.active) return 1.0F;
		if (part == null || !ArmorAssemblySlotMask.contains(state.affectedSlotMask, part.equipmentSlot())) {
			return 1.0F;
		}
		if (!state.animating) return state.retracting ? 0.0F : 1.0F;
		float attachment = Mth.clamp((visualCanonicalTick(state, partialTick) - part.startTick())
				/ part.durationTicks(), 0.0F, 1.0F);
		return attachment;
	}

	/** 所有穿入/脱出动画共用的视觉忙碌状态。 */
	public static boolean isAnimating(Player player) {
		VisualState state = STATES.get(player.getUUID());
		return state != null && isVisualAnimating(state);
	}

	/** 只有战靴未锁定的着装流程会阻断常规飞行。 */
	public static boolean isAssembling(Player player) {
		VisualState state = STATES.get(player.getUUID());
		/*
		 * The packet's animating bit describes the server transaction, but the client can keep
		 * that last snapshot after its authored timeline has already ended. Treating the bit as
		 * an everlasting flight lock leaves a fully equipped player in boot-only hover: takeoff
		 * works, while every WASD input is discarded. The visual canonical clock is the same
		 * clock used by the assembly renderer, so controls unlock exactly when attachment ends.
		 */
		return state != null && state.locksFlight && isVisualAnimating(state);
	}

	private static void trackDimension(Minecraft client) {
		ResourceKey<Level> current = client.level == null ? null : client.level.dimension();
		// Preserve the previous key across the brief null-level handoff between dimensions.
		// Disconnect invokes clear(), so retaining it here cannot leak across servers.
		if (current == null) return;
		if (lastDimension != null && !lastDimension.equals(current)) {
			/*
			 * Assembly transactions are dimension-bound on the server. Keeping their old visual
			 * startGameTime after a dimension clock rollback can otherwise recreate a long-lived
			 * client-only flight lock even though the server already cancelled the transaction.
		 */
			STATES.clear();
			ArmorAssemblySoundController.clear();
			lastRequestGameTime = Long.MIN_VALUE / 2L;
			lastWardrobeRequestGameTime = Long.MIN_VALUE / 2L;
			lastQuickSwitchGameTime = Long.MIN_VALUE / 2L;
			wardrobeChordSuppressionTicks = 0;
			capturedOperationTick = Integer.MIN_VALUE;
		}
		lastDimension = current;
	}

	public static boolean affectsSlot(Player player, EquipmentSlot slot) {
		VisualState state = STATES.get(player.getUUID());
		return state != null && isVisualAnimating(state)
				&& ArmorAssemblySlotMask.contains(state.affectedSlotMask, slot);
	}

	public static boolean isRetracting(Player player) {
		VisualState state = STATES.get(player.getUUID());
		return state != null && isVisualAnimating(state) && state.retracting;
	}

	/** 衣柜组合键捕获后的极短窗口内，禁止把该组合键同时解释为飞行加速。 */
	public static boolean isWardrobeChordCaptured() {
		return wardrobeChordSuppressionTicks > 0;
	}

	/**
	 * 战甲管理组合键（默认左 Alt）当前是否按下。整套/逐件穿脱、最近甲召回、接地俯冲统一读取它，
	 * 让「组合键 + 动作」成为唯一一层显式的战甲管理修饰键，而不是各按键各自硬编码物理键。
	 */
	public static boolean isModifierDown() {
		return wardrobeModifierKey != null && wardrobeModifierKey.isDown();
	}

	/** Claims the current input tick before a client operation sends its first packet. */
	public static void captureOperationThisTick(Minecraft client) {
		if (client != null && client.player != null) {
			capturedOperationTick = client.player.tickCount;
		}
	}

	/** Prevents B/N/wardrobe/core transactions from sharing one client tick with K/H/M/R/G/V/J/X actions. */
	public static boolean isOperationCapturedThisTick(Minecraft client) {
		return client != null && client.player != null
				&& capturedOperationTick == client.player.tickCount;
	}

	/** 当前分件序列的左右战靴都已完成视觉锁定，可接收服务端临时悬停授权。 */
	public static boolean isBootHoverReady(Player player) {
		VisualState state = STATES.get(player.getUUID());
		return state != null && isBootHoverReady(state, 0L, ANY_ATTEMPT_ID);
	}

	/** 带序列校验的版本，用于拒绝迟到的旧自动悬停事件。 */
	public static boolean isBootHoverReady(Player player, long sequenceId) {
		VisualState state = STATES.get(player.getUUID());
		return state != null && isBootHoverReady(state, sequenceId, ANY_ATTEMPT_ID);
	}

	public static boolean isBootHoverReady(Player player, long sequenceId, long attemptId) {
		VisualState state = STATES.get(player.getUUID());
		return state != null && isBootHoverReady(state, sequenceId, attemptId);
	}

	/** 允许确认包在完整组装快照之后到达，但必须仍属于同一有效序列。 */
	public static boolean isBootHoverSessionValid(Player player, long sequenceId, long attemptId) {
		VisualState state = STATES.get(player.getUUID());
		if (state == null || !state.active || state.sequenceId != sequenceId
				|| state.startGameTime != attemptId) {
			return false;
		}
		return !state.animating || isBootHoverReady(state, sequenceId, attemptId);
	}

	public static boolean isCompleted(Player player, long sequenceId, long attemptId) {
		VisualState state = STATES.get(player.getUUID());
		return state != null && state.active && !state.animating
				&& state.sequenceId == sequenceId && state.startGameTime == attemptId;
	}

	public static boolean isAssembled(Player player) {
		VisualState state = STATES.get(player.getUUID());
		return state == null || !state.active || !state.animating;
	}

	public static void clear() {
		STATES.clear();
		lastRequestGameTime = Long.MIN_VALUE / 2L;
		lastWardrobeRequestGameTime = Long.MIN_VALUE / 2L;
		lastQuickSwitchGameTime = Long.MIN_VALUE / 2L;
		nextQuickSwitchSequence = 0L;
		wardrobeChordSuppressionTicks = 0;
		capturedOperationTick = Integer.MIN_VALUE;
		lastDimension = null;
		ArmorAssemblySoundController.clear();
	}

	private static float elapsedTicks(VisualState state, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return 0.0F;
		return Math.max(0.0F, client.level.getGameTime() + Mth.clamp(partialTick, 0.0F, 1.0F)
				- state.startGameTime);
	}

	private static boolean isVisualAnimating(VisualState state) {
		if (!state.active) return false;
		// 脱甲完成后仍保留服务端的两 tick 墓碑；穿甲则在高速视觉贴合完成后立即归还普通动作。
		if (state.retracting) return state.animating || state.retracting;
		return state.animating
				&& visualCanonicalTick(state, 1.0F) < state.timelineEndTick - 0.001F;
	}

	private static float canonicalTick(VisualState state, float partialTick) {
		float normalized = Mth.clamp(elapsedTicks(state, partialTick) / state.durationTicks, 0.0F, 1.0F);
		return state.retracting
				? Mth.lerp(normalized, state.timelineEndTick, state.timelineStartTick)
				: Mth.lerp(normalized, state.timelineStartTick, state.timelineEndTick);
	}

	/**
	 * 客户端在同一权威时长内使用机械加速曲线重新参数化；战靴授权、ACK 与物品所有权
	 * 仍读取未加速的 canonicalTick，因此不会因视觉领先而提前获得能力。
	 */
	private static float visualCanonicalTick(VisualState state, float partialTick) {
		if (state.retracting) return canonicalTick(state, partialTick);
		float visualDuration = state.durationTicks;
		float normalized = Mth.clamp(elapsedTicks(state, partialTick) / visualDuration, 0.0F, 1.0F);
		// 保留快速起步和末端磁吸，但降低前半段跳跃感，让分件轨迹具有可读性。
		float ballistic = 1.0F - (float) Math.pow(1.0F - normalized, 1.55D);
		float start = visualStartTick(state);
		return Mth.lerp(ballistic, start, state.timelineEndTick);
	}

	private static float visualStartTick(VisualState state) {
		boolean fullSet = (state.affectedSlotMask & ArmorAssemblySlotMask.FULL_SET)
				== ArmorAssemblySlotMask.FULL_SET;
		// LEFT_BOOT 在 tick 0 起飞，略提前 0.15 tick 可确保收到快照的第一帧已有空间位移。
		return fullSet ? -0.15F : state.timelineStartTick;
	}

	private static boolean isBootHoverReady(VisualState state, long sequenceId, long attemptId) {
		if (!state.active || !state.animating || state.retracting
				|| !ArmorAssemblySlotMask.contains(state.affectedSlotMask, EquipmentSlot.FEET)
				|| (sequenceId != 0L && state.sequenceId != sequenceId)
				|| (attemptId != ANY_ATTEMPT_ID && state.startGameTime != attemptId)) {
			return false;
		}
		return canonicalTick(state, 1.0F) >= ArmorAssemblyTiming.BOOTS_LOCK_TICKS;
	}

	private record VisualState(long sequenceId, long startGameTime, int durationTicks,
			boolean active, boolean animating, int affectedSlotMask, boolean retracting,
			int timelineStartTick, int timelineEndTick, boolean locksFlight) {
	}
}
