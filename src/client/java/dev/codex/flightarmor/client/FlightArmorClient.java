package dev.codex.flightarmor.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.client.animation.FlightJointAnimationSetup;
import dev.codex.flightarmor.client.animation.FlightPoseController;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyClient;
import dev.codex.flightarmor.client.audio.ArmorSoundController;
import dev.codex.flightarmor.client.combat.ArmorCombatClient;
import dev.codex.flightarmor.client.combat.ArmorHomingMissileRenderer;
import dev.codex.flightarmor.client.combat.SentinelWingmanRenderer;
import dev.codex.flightarmor.client.configuration.ArmorConfigurationClient;
import dev.codex.flightarmor.combat.ModCombatEntities;
import dev.codex.flightarmor.client.cruise.ArmorCruiseClient;
import dev.codex.flightarmor.client.cruise.ArmorEscortClient;
import dev.codex.flightarmor.client.cruise.CruiseCombatClient;
import dev.codex.flightarmor.client.core.ArmorCoreClient;
import dev.codex.flightarmor.client.core.ArmorCoreCommandScreen;
import dev.codex.flightarmor.client.core.ArmorCoreHud;
import dev.codex.flightarmor.client.environment.LowAltitudeFeedbackClient;
import dev.codex.flightarmor.client.flight.SonicBoomFxClient;
import dev.codex.flightarmor.client.hud.FlightSpeedHud;
import dev.codex.flightarmor.client.render.SwiftFlightTrailRenderer;
import dev.codex.flightarmor.client.render.obj.FlightObjArmorRenderer;
import dev.codex.flightarmor.client.render.obj.FlightArmorItemPreviewRenderer;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.client.platform.ChargingPlatformRenderer;
import dev.codex.flightarmor.client.platform.ChargingPlatformScreen;
import dev.codex.flightarmor.client.platform.PlatformRecallClient;
import dev.codex.flightarmor.client.suitpanel.ArmorSuitPanelClient;
import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.client.telemetry.FlightSpeedTelemetry;
import dev.codex.flightarmor.flight.FlightInputArbitration;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.FlightInputPayload;
import dev.codex.flightarmor.network.FlightVisualStatePayload;
import dev.codex.flightarmor.network.AutomaticHoverAckPayload;
import dev.codex.flightarmor.network.AutomaticHoverEngagePayload;
import dev.codex.flightarmor.network.AutomaticHoverEndPayload;
import dev.codex.flightarmor.variant.ArmorVariantData;
import dev.codex.flightarmor.platform.ModPlatformContent;
import dev.codex.flightarmor.core.ModCoreContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class FlightArmorClient implements ClientModInitializer {
	private static final int DOUBLE_TAP_WINDOW_TICKS = 7;
	private static final int MIN_DOUBLE_TAP_GAP_TICKS = 2;
	private static final int AUTOMATIC_HOVER_REQUEST_TTL_TICKS = 10;
	private static UUID hoveringPlayerId;
	private static final Map<UUID, FlightVisualState> FLIGHT_VISUAL_STATES = new HashMap<>();
	private static int hoverForward;
	private static int hoverStrafe;
	private static int hoverVertical;
	private boolean active;
	private boolean diving;
	private boolean brakeToHoverLatch;
	private int lastJumpPressTick = Integer.MIN_VALUE / 2;
	private boolean jumpWasDown;
	private int lastPacketTick = Integer.MIN_VALUE / 2;
	private PendingAutomaticHover pendingAutomaticHover;
	private long flightSessionToken;
	private long lastClosedAutomaticHoverToken;
	private final ArmorSoundController soundController = new ArmorSoundController();
	private InputSnapshot lastSent = InputSnapshot.OFF;
	private ResourceKey<Level> lastDimension;
	private KeyMapping toggleDiveKey;

	@Override
	public void onInitializeClient() {
		FlightArmorClientSettingsStore.initialize();
		registerArmorVariantModels();
        EntityRendererRegistry.register(ModCombatEntities.HOMING_MISSILE, ArmorHomingMissileRenderer::new);
		EntityRendererRegistry.register(ModCombatEntities.SENTINEL_WINGMAN,
				SentinelWingmanRenderer::new);
		FlightJointAnimationSetup.initialize();
		FlightObjArmorRenderer.initialize();
		FlightArmorItemPreviewRenderer.initialize();
		FlightArmorClientSmokeTest.initialize();
		SwiftFlightTrailRenderer.initialize();
		ChargingPlatformRenderer.initialize();
		MenuScreens.register(ModPlatformContent.CHARGING_PLATFORM_MENU, ChargingPlatformScreen::new);
		MenuScreens.register(ModCoreContent.ARMOR_CORE_MENU, ArmorCoreCommandScreen::new);
		PlatformRecallClient.initialize();
		ArmorAssemblyClient.initialize();
		ArmorCruiseClient.initialize();
		ArmorEscortClient.initialize();
		ArmorCoreClient.initialize();
		ArmorConfigurationClient.initialize();
		ArmorCoreHud.initialize();
		ArmorSuitPanelClient.initialize();
		CruiseCombatClient.initialize();
		ArmorCombatClient.initialize();
		FlightSpeedHud.initialize();
		LowAltitudeFeedbackClient.initialize();
		SonicBoomFxClient.initialize();
		ClientPlayNetworking.registerGlobalReceiver(FlightVisualStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> receiveVisualState(context.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(AutomaticHoverEngagePayload.TYPE, (payload, context) ->
				context.client().execute(() -> receiveAutomaticHoverEngage(payload)));
		ClientPlayNetworking.registerGlobalReceiver(AutomaticHoverEndPayload.TYPE, (payload, context) ->
				context.client().execute(() -> receiveAutomaticHoverEnd(context.client(), payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			resetFlightVisuals();
			FlightSpeedTelemetry.reset();
			pendingAutomaticHover = null;
			flightSessionToken = 0L;
			lastClosedAutomaticHoverToken = 0L;
			brakeToHoverLatch = false;
			soundController.clear();
		});
		toggleDiveKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.flight_armor.toggle_dive",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.category.flight_armor"));
		ClientTickEvents.END_CLIENT_TICK.register(this::tickClient);
	}

	private static void registerArmorVariantModels() {
		var propertyId = FlightArmorMod.id("variant");
		ItemProperties.register(ModItems.FLIGHT_HELMET, propertyId,
				(stack, level, entity, seed) -> variantModelValue(stack));
		ItemProperties.register(ModItems.FLIGHT_CHESTPLATE, propertyId,
				(stack, level, entity, seed) -> variantModelValue(stack));
		ItemProperties.register(ModItems.FLIGHT_LEGGINGS, propertyId,
				(stack, level, entity, seed) -> variantModelValue(stack));
		ItemProperties.register(ModItems.FLIGHT_BOOTS, propertyId,
				(stack, level, entity, seed) -> variantModelValue(stack));
	}

	private static float variantModelValue(ItemStack stack) {
		return switch (ArmorVariantData.get(stack)) {
			case VANGUARD -> 0.0F;
			case SWIFT -> 1.0F;
			case SENTINEL -> 2.0F;
		};
	}

	private void tickClient(Minecraft client) {
		ArmorAssemblyClient.tick(client);
		ArmorCoreClient.tick(client);
		ArmorConfigurationClient.tick(client);
		CruiseCombatClient.tick(client);
		ArmorCombatClient.tick(client);
		Player player = client.player;
		if (player == null || client.level == null) {
			active = false; diving = false; brakeToHoverLatch = false; lastSent = InputSnapshot.OFF; lastDimension = null;
			pendingAutomaticHover = null;
			flightSessionToken = 0L;
			lastClosedAutomaticHoverToken = 0L;
			resetFlightVisuals();
			soundController.clear();
			FlightPoseController.tick(client);
			return;
		}
		ResourceKey<Level> currentDimension = client.level.dimension();
		if (lastDimension != null && !lastDimension.equals(currentDimension)) {
			setActive(player, false);
			pendingAutomaticHover = null;
		}
		lastDimension = currentDimension;

		boolean flightGear = ModItems.hasFlightGear(player);
		boolean armorAssembling = ArmorAssemblyClient.isAssembling(player);
		boolean bootAssemblyHoverReady = ArmorAssemblyClient.isBootHoverReady(player)
				|| ArmorCruiseClient.isBootHoverReady(player)
				|| ArmorCruiseClient.isEmergencyRecallHoverReady(player);
		boolean coreFlightReady = flightGear && !armorAssembling;
		boolean flightReady = coreFlightReady || bootAssemblyHoverReady;
		if (!flightReady || player.isPassenger() || player.isInLava() || player.isSpectator()) setActive(player, false);
		tryEngageAutomaticHover(player, flightGear, armorAssembling, flightReady);
		boolean bootOnlyAssemblyHover = active && bootAssemblyHoverReady && !coreFlightReady;
		if (bootOnlyAssemblyHover && diving) setDiving(player, false);
		boolean jumpDown = client.screen == null && client.options.keyJump.isDown();
		boolean jumpPressed = jumpDown && !jumpWasDown;
		jumpWasDown = jumpDown;
		boolean toggleDiveClicked = false;
		if (client.screen == null) {
			while (toggleDiveKey.consumeClick()) toggleDiveClicked = true;
		}
		if (client.screen == null
				&& !ArmorAssemblyClient.isOperationCapturedThisTick(client)) {
			// Do not consume vanilla jump clicks; creative flight keeps its own double-tap handling.
			if (jumpPressed && (flightGear || armorAssembling)) handleJumpPress(player, flightGear, armorAssembling);
			if (toggleDiveClicked && active && !bootOnlyAssemblyHover) setDiving(player, !diving);
		}
		if (active && player.onGround()) setActive(player, false);

		boolean controlsUnlocked = active && !bootOnlyAssemblyHover && client.screen == null;
		FlightInputArbitration.Resolved resolved = FlightInputArbitration.resolve(
				new FlightInputArbitration.Inputs(
						controlsUnlocked,
						ArmorAssemblyClient.isOperationCapturedThisTick(client)
								|| isPlatformInteractionCaptured(client)
								|| toggleDiveClicked,
						ArmorAssemblyClient.isWardrobeChordCaptured(),
						client.options.keySprint.isDown(),
						client.options.keySprint.same(client.options.keyJump),
						client.options.keySprint.same(client.options.keyShift),
						client.options.keySprint.same(client.options.keyDown),
						client.options.keyUp.isDown(),
						client.options.keyDown.isDown(),
						client.options.keyLeft.isDown(),
						client.options.keyRight.isDown(),
						client.options.keyJump.isDown(),
						client.options.keyShift.isDown()));
		boolean boosting = resolved.boosting();
		boolean movingForward = resolved.movingForward();
		boolean rawMovingBackward = resolved.movingBackward();
		boolean movingLeft = resolved.movingLeft();
		boolean movingRight = resolved.movingRight();
		boolean movingBackward = rawMovingBackward;
		if (brakeToHoverLatch) {
			if (!rawMovingBackward || movingForward) {
				brakeToHoverLatch = false;
			} else {
				movingBackward = false;
				boosting = false;
				if (diving) setDiving(player, false);
			}
		}
		boolean ascending = resolved.ascending();
		boolean descending = resolved.descending();
		boolean moving = movingForward != movingBackward || movingLeft != movingRight || ascending != descending;
		updateHoverMotion(player, movingForward, movingBackward, movingLeft, movingRight, ascending, descending);
		float steeringYaw = quantizeSteeringAngle(Mth.wrapDegrees(player.getYRot()));
		float steeringPitch = quantizeSteeringAngle(
				Mth.clamp(player.getXRot(), -90.0F, 90.0F));
		InputSnapshot current = new InputSnapshot(flightSessionToken, active, diving, boosting,
				movingForward, rawMovingBackward, movingLeft, movingRight, ascending, descending,
				steeringYaw, steeringPitch);
		if (active) {
			// 创造模式原版飞行与战甲物理不能同时生效；战甲激活期间临时关闭原版 flying。
			player.getAbilities().flying = false;
			FlightPhysics.apply(player,
					FlightPhysics.lookDirection(steeringYaw, steeringPitch),
					diving, boosting, movingForward, movingBackward,
					movingLeft, movingRight, ascending, descending);
		}
		boolean sentInput = sendInputWhenNeeded(player, current);
		if (!active && sentInput && flightSessionToken != 0L) {
			lastClosedAutomaticHoverToken = flightSessionToken;
			flightSessionToken = 0L;
			lastSent = InputSnapshot.OFF;
		}
		updateFlightVisuals(client, player, active, diving, boosting, moving);
		soundController.tick(client);
		FlightPoseController.tick(client);
	}

	private static boolean isPlatformInteractionCaptured(Minecraft client) {
		if (client.level == null || !client.options.keyUse.isDown()
				|| !client.options.keyShift.isDown()
				|| !(client.hitResult instanceof BlockHitResult hit)) {
			return false;
		}
		return client.level.getBlockState(hit.getBlockPos()).is(ModPlatformContent.CHARGING_PLATFORM);
	}

	/** 服务端已经验证过下落状态；这里最多等待十 tick 的装备同步，然后接入现有悬停输入。 */
	private void tryEngageAutomaticHover(Player player, boolean flightGear,
			boolean armorAssembling, boolean flightReady) {
		PendingAutomaticHover pending = pendingAutomaticHover;
		if (pending == null) return;
		if (pending.remainingTicks <= 0) {
			pendingAutomaticHover = null;
			return;
		}
		pending.remainingTicks--;
		AutomaticHoverEngagePayload payload = pending.payload;
		if (!player.isAlive() || player.isSleeping() || player.isPassenger()
				|| player.isInLava() || player.isSpectator()
				|| !player.level().dimension().location().equals(payload.dimension())) {
			pendingAutomaticHover = null;
			return;
		}
		// 地面轻抬的服务端位置包可能比接管事件晚一帧；保留请求，等客户端确认已经离地。
		if (player.onGround()) return;
		if (!flightReady || !automaticSourceReady(player, payload, flightGear, armorAssembling)) return;

		pendingAutomaticHover = null;
		flightSessionToken = payload.engagementId();
		lastJumpPressTick = Integer.MIN_VALUE / 2;
		diving = false;
		player.stopFallFlying();
		FlightPhysics.prepareHoverEngagement(player);
		setActive(player, true);
		sendAutomaticHoverAck(payload.engagementId());
	}

	private void receiveAutomaticHoverEngage(AutomaticHoverEngagePayload payload) {
		if (payload.engagementId() == lastClosedAutomaticHoverToken) return;
		if (payload.engagementId() == flightSessionToken) {
			pendingAutomaticHover = null;
			// 同一会话的重发只补确认，不再次刹车或重播启用动作。
			if (active) sendAutomaticHoverAck(payload.engagementId());
			return;
		}
		pendingAutomaticHover = new PendingAutomaticHover(payload, AUTOMATIC_HOVER_REQUEST_TTL_TICKS);
	}

	private static void sendAutomaticHoverAck(long engagementId) {
		if (ClientPlayNetworking.canSend(AutomaticHoverAckPayload.TYPE)) {
			ClientPlayNetworking.send(new AutomaticHoverAckPayload(engagementId));
		}
	}

	private static boolean automaticSourceReady(Player player, AutomaticHoverEngagePayload payload,
			boolean flightGear, boolean armorAssembling) {
		return switch (payload.source()) {
			case ASSEMBLY_BOOTS -> ArmorAssemblyClient.isBootHoverSessionValid(
					player, payload.sourceSequenceId(), payload.sourceAttemptId());
			case DOCKING_BOOTS -> ArmorCruiseClient.isBootHoverReady(
					player, payload.sourceSequenceId(), payload.sourceAttemptId())
					|| (flightGear && ArmorCruiseClient.isBootHoverSessionValid(
					player, payload.sourceSequenceId(), payload.sourceAttemptId()));
			case EMERGENCY_RECALL -> ArmorCruiseClient.isEmergencyRecallHoverSessionValid(
					player, payload.sourceSequenceId(), payload.sourceAttemptId());
			case ASSEMBLY_COMPLETE -> flightGear && !armorAssembling && ArmorAssemblyClient.isCompleted(
					player, payload.sourceSequenceId(), payload.sourceAttemptId());
			case DOCKING_COMPLETE -> flightGear && !armorAssembling && ArmorCruiseClient.isCompleted(
					player, payload.sourceSequenceId(), payload.sourceAttemptId());
			case QUICK_SWITCH_COMPLETE -> ModItems.hasFullSet(player)
					&& !armorAssembling;
			case ORDINARY_EQUIP -> flightGear && !armorAssembling;
		};
	}

	private void receiveAutomaticHoverEnd(Minecraft client, AutomaticHoverEndPayload payload) {
		if (pendingAutomaticHover != null
				&& pendingAutomaticHover.payload.engagementId() == payload.engagementId()) {
			pendingAutomaticHover = null;
		}
		if (flightSessionToken != payload.engagementId()) {
			lastClosedAutomaticHoverToken = payload.engagementId();
			return;
		}
		if (client.player != null) {
			setActive(client.player, false);
		} else {
			active = false;
			diving = false;
			clearHoverMotion();
		}
		lastClosedAutomaticHoverToken = flightSessionToken;
		flightSessionToken = 0L;
		lastSent = InputSnapshot.OFF;
	}

	private void handleJumpPress(Player player, boolean flightGear, boolean armorAssembling) {
		// Stay transparent without armor so vanilla creative-flight double tap remains untouched.
		if (!flightGear && !armorAssembling) return;
		int now = player.tickCount;
		int gap = now - lastJumpPressTick;
		lastJumpPressTick = now;
		if (gap < MIN_DOUBLE_TAP_GAP_TICKS || gap > DOUBLE_TAP_WINDOW_TICKS) return;
		lastJumpPressTick = Integer.MIN_VALUE / 2;
		if (armorAssembling) {
			player.displayClientMessage(Component.translatable("message.flight_armor.assembly_in_progress"), true); return;
		}
		if (!flightGear) return;
		if (!ClientPlayNetworking.canSend(FlightInputPayload.TYPE)) {
			player.displayClientMessage(Component.translatable("message.flight_armor.server_required"), true); return;
		}
		// 双击空格只负责悬空开关；接地俯冲是本模组的主动作，改由按住战甲组合键（默认左 Alt）显式请求，
		// 不再与“平稳收工”共用一个手势。按住组合键时该手势只解释为俯冲，不会顺带切换悬空。
		if (ArmorAssemblyClient.isModifierDown()) {
			if (active && !diving && ModItems.hasFullSet(player) && hasGroundSlamHeight(player)) {
				setDiving(player, true);
				Vec3 current = player.getDeltaMovement();
				player.setDeltaMovement(current.x * 0.22D, Math.min(current.y, -1.15D),
						current.z * 0.22D);
			}
			return;
		}
		if (!active) flightSessionToken = 0L;
		setActive(player, !active);
		if (active) player.setDeltaMovement(player.getDeltaMovement().add(0.0D, 0.32D, 0.0D));
	}

	private static boolean hasGroundSlamHeight(Player player) {
		Vec3 start = player.position().add(0.0D, 0.10D, 0.0D);
		Vec3 end = start.add(0.0D, -48.0D, 0.0D);
		BlockHitResult hit = player.level().clip(new ClipContext(
				start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK
				&& start.y - hit.getLocation().y >= 4.0D;
	}

	private void setActive(Player player, boolean next) {
		if (active == next) return;
		FlightPhysics.resetMotionSample(player);
		FlightSpeedTelemetry.reset(player);
		active = next;
		if (next) {
			player.getAbilities().flying = false;
		}
		if (!next) {
			setDiving(player, false);
			clearHoverMotion();
			brakeToHoverLatch = false;
		}
	}

	private void setDiving(Player player, boolean next) {
		if (diving == next) return;
		diving = next;
		if (next) clearHoverMotion();
		if (next) player.startFallFlying(); else player.stopFallFlying();
	}

	private boolean sendInputWhenNeeded(Player player, InputSnapshot current) {
		if (!ClientPlayNetworking.canSend(FlightInputPayload.TYPE)) return false;
		if (current.equals(lastSent) && player.tickCount - lastPacketTick < 2) return false;
		ClientPlayNetworking.send(new FlightInputPayload(current.flightSessionToken(), current.active(),
				current.diving(), current.boosting(), current.movingForward(),
				current.movingBackward(), current.movingLeft(), current.movingRight(),
				current.ascending(), current.descending(),
				current.steeringYaw(), current.steeringPitch()));
		lastSent = current; lastPacketTick = player.tickCount;
		return true;
	}

	private static float quantizeSteeringAngle(float value) {
		if (!Float.isFinite(value)) return 0.0F;
		return Math.round(value * 4.0F) * 0.25F;
	}

	private void updateHoverMotion(Player player, boolean movingForward, boolean movingBackward, boolean movingLeft,
			boolean movingRight, boolean ascending, boolean descending) {
		if (!active || diving) {
			clearHoverMotion();
			return;
		}
		hoveringPlayerId = player.getUUID();
		hoverForward = (movingForward ? 1 : 0) - (movingBackward ? 1 : 0);
		hoverStrafe = (movingRight ? 1 : 0) - (movingLeft ? 1 : 0);
		hoverVertical = (ascending ? 1 : 0) - (descending ? 1 : 0);
	}

	private static void clearHoverMotion() {
		hoveringPlayerId = null;
		hoverForward = 0;
		hoverStrafe = 0;
		hoverVertical = 0;
	}

	private void receiveVisualState(Minecraft client, FlightVisualStatePayload payload) {
		FlightVisualState state = FLIGHT_VISUAL_STATES.computeIfAbsent(payload.playerId(), ignored -> new FlightVisualState());
		state.serverActive = payload.active();
		state.diving = payload.active() && payload.diving();
		state.boosting = payload.active() && payload.boosting();
		state.moving = payload.active() && payload.moving();
		state.unloadedTicks = 0;

		// The server only forces an active dive back to hover after sustained S braking.
		// Mirror that authoritative transition so local prediction cannot re-enter dive next tick.
		Player player = client.player;
		if (player != null && payload.playerId().equals(player.getUUID())
				&& payload.active() && !payload.diving() && active && diving) {
			FlightPhysics.settleBrakeToHover(player);
			brakeToHoverLatch = true;
			setDiving(player, false);
		}
	}

	private static void updateFlightVisuals(Minecraft client, Player localPlayer, boolean localActive,
			boolean localDiving, boolean localBoosting, boolean localMoving) {
		UUID localPlayerId = localPlayer.getUUID();
		FlightVisualState localState = FLIGHT_VISUAL_STATES.computeIfAbsent(localPlayerId, ignored -> new FlightVisualState());
		// 本地输入直接驱动目标值，不必等待一次网络往返；服务器包仍负责其他玩家的状态。
		localState.localActive = localActive;
		localState.diving = localActive && localDiving;
		localState.boosting = localActive && localBoosting;
		localState.moving = localActive && localMoving;

		Iterator<Map.Entry<UUID, FlightVisualState>> iterator = FLIGHT_VISUAL_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, FlightVisualState> entry = iterator.next();
			UUID playerId = entry.getKey();
			FlightVisualState state = entry.getValue();
			boolean playerLoaded = client.level != null && client.level.getPlayerByUUID(playerId) != null;
			if (playerLoaded) {
				state.unloadedTicks = 0;
			} else {
				state.unloadedTicks++;
			}
			boolean targetActive = playerId.equals(localPlayerId)
					? state.localActive
					: state.serverActive && state.unloadedTicks <= 40;
			state.tick(targetActive);
			if (!playerId.equals(localPlayerId) && !targetActive && state.progress == 0.0F
					&& (!state.serverActive || state.unloadedTicks > 40)) {
				iterator.remove();
			}
		}
	}

	private static void resetFlightVisuals() {
		FLIGHT_VISUAL_STATES.clear();
	}

	public static boolean isHovering(Player player) {
		return hoveringPlayerId != null && hoveringPlayerId.equals(player.getUUID());
	}

	public static int hoverForward() {
		return hoverForward;
	}

	public static int hoverStrafe() {
		return hoverStrafe;
	}

	public static int hoverVertical() {
		return hoverVertical;
	}

	public static boolean isFlightEffectActive(Player player) {
		return flightVisualProgress(player, 1.0F) > 0.03F || player.isFallFlying();
	}

	public static float flightVisualProgress(Player player, float partialTick) {
		FlightVisualState state = FLIGHT_VISUAL_STATES.get(player.getUUID());
		if (state != null) {
			return Mth.clamp(Mth.lerp(partialTick, state.previousProgress, state.progress), 0.0F, 1.0F);
		}

		return player.isFallFlying() ? 1.0F : 0.0F;
	}

	/** 为俯冲专属装甲变形提供平滑的 0..1 进度，切换模式时不会瞬间弹开。 */
	public static float diveVisualProgress(Player player, float partialTick) {
		FlightVisualState state = FLIGHT_VISUAL_STATES.get(player.getUUID());
		if (state != null) {
			return Mth.clamp(Mth.lerp(partialTick, state.previousDiveProgress, state.diveProgress), 0.0F, 1.0F);
		}
		return player.isFallFlying() ? 1.0F : 0.0F;
	}

	/** Ctrl 加速视觉进度独立平滑，避免按键瞬间切色，也不依赖渲染线程直接读取键盘状态。 */
	public static float boostVisualProgress(Player player, float partialTick) {
		FlightVisualState state = FLIGHT_VISUAL_STATES.get(player.getUUID());
		if (state != null) {
			return Mth.clamp(Mth.lerp(partialTick, state.previousBoostProgress, state.boostProgress), 0.0F, 1.0F);
		}
		return player.isSprinting() ? 1.0F : 0.0F;
	}

	/** 供盔甲渲染器区分悬停展开与俯冲展开。 */
	public static boolean isDiveVisualActive(Player player) {
		FlightVisualState state = FLIGHT_VISUAL_STATES.get(player.getUUID());
		return state != null ? state.diving : player.isFallFlying();
	}

	/**
	 * 声音系统只读取这份离散快照；本地玩家使用即时输入，其他玩家使用服务端广播。
	 */
	public static FlightAudioSnapshot flightAudioSnapshot(Player player) {
		FlightVisualState state = FLIGHT_VISUAL_STATES.get(player.getUUID());
		if (state == null) {
			return new FlightAudioSnapshot(player.isFallFlying(), player.isFallFlying(), false, false);
		}
		Minecraft client = Minecraft.getInstance();
		boolean local = client.player != null && client.player.getUUID().equals(player.getUUID());
		boolean stateActive = local ? state.localActive : state.serverActive;
		return new FlightAudioSnapshot(stateActive, stateActive && state.diving,
				stateActive && state.boosting, stateActive && state.moving);
	}

	public record FlightAudioSnapshot(boolean active, boolean diving, boolean boosting, boolean moving) {
	}

	private static final class FlightVisualState {
		private float previousProgress;
		private float progress;
		private float previousDiveProgress;
		private float diveProgress;
		private float previousBoostProgress;
		private float boostProgress;
		private boolean serverActive;
		private boolean localActive;
		private boolean diving;
		private boolean boosting;
		private boolean moving;
		private int unloadedTicks;

		private void tick(boolean active) {
			previousProgress = progress;
			previousDiveProgress = diveProgress;
			previousBoostProgress = boostProgress;
			float response = active ? 0.10F : 0.08F;
			progress += ((active ? 1.0F : 0.0F) - progress) * response;
			float diveTarget = active && diving ? 1.0F : 0.0F;
			diveProgress += (diveTarget - diveProgress) * 0.09F;
			float boostTarget = active && boosting ? 1.0F : 0.0F;
			boostProgress += (boostTarget - boostProgress) * (boostTarget > boostProgress ? 0.09F : 0.07F);
			if (progress < 0.002F) {
				progress = 0.0F;
			}
			if (diveProgress < 0.002F) {
				diveProgress = 0.0F;
			}
			if (boostProgress < 0.002F) {
				boostProgress = 0.0F;
			}
			if (!active && progress == 0.0F) {
				diving = false;
			}
		}
	}

	private static final class PendingAutomaticHover {
		private final AutomaticHoverEngagePayload payload;
		private int remainingTicks;

		private PendingAutomaticHover(AutomaticHoverEngagePayload payload, int remainingTicks) {
			this.payload = payload;
			this.remainingTicks = remainingTicks;
		}
	}

	private record InputSnapshot(long flightSessionToken, boolean active, boolean diving,
			boolean boosting, boolean movingForward,
			boolean movingBackward, boolean movingLeft, boolean movingRight,
			boolean ascending, boolean descending,
			float steeringYaw, float steeringPitch) {
		private static final InputSnapshot OFF = new InputSnapshot(
				0L, false, false, false, false, false, false, false,
				false, false, 0.0F, 0.0F);
	}
}
