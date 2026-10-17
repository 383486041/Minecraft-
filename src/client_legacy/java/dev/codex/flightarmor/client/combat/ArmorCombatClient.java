package dev.codex.flightarmor.client.combat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyClient;
import dev.codex.flightarmor.combat.ArmorAttackAction;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.combat.ArmorCombatDenyReason;
import dev.codex.flightarmor.combat.ArmorCombatPhase;
import dev.codex.flightarmor.network.ArmorAttackFxPayload;
import dev.codex.flightarmor.combat.ArmorCombatTuning;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorAttackIntentPayload;
import dev.codex.flightarmor.network.ArmorBladeStatePayload;
import dev.codex.flightarmor.network.ArmorCombatOwnerStatePayload;
import dev.codex.flightarmor.network.ArmorCombatVisualStatePayload;
import dev.codex.flightarmor.network.ArmorRemoteCommandPayload;
import dev.codex.flightarmor.network.ArmorRemoteCommandAckPayload;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 战甲武器客户端入口：只发送按键意图，伤害、目标、发射手和特效端点全部服从服务端。 */
public final class ArmorCombatClient {
	private static final int REQUEST_GAP_TICKS = 2;
	private static final int DENY_MESSAGE_GAP_TICKS = 5;
	private static final int VISUAL_STATE_GRACE_TICKS = 20;
	private static final Map<UUID, VisualAttackState> VISUAL_STATES = new HashMap<>();
	private static final Map<UUID, VisualOrder> LAST_VISUAL_ORDERS = new HashMap<>();
	private static final Map<UUID, RecoilState> RECOIL_STATES = new HashMap<>();
	private static final Map<UUID, BladeVisualState> BLADE_STATES = new HashMap<>();
	private static final Map<UUID, BladeComboState> BLADE_COMBOS = new HashMap<>();
	private static KeyMapping wristPulseKey;
	private static KeyMapping coreBeamKey;
	private static KeyMapping bladeToggleKey;
	private static KeyMapping remoteMeleeUltimateKey;
	private static KeyMapping remoteToggleKey;
	private static KeyMapping handBeamKey;
	private static boolean wristKeyWasDown;
	private static boolean wristIntentActive;
	private static boolean wristBlockedUntilRelease;
	private static boolean coreKeyWasDown;
	private static boolean coreIntentActive;
	private static boolean queuedBladeCombo;
	private static boolean coreBlockedUntilRelease;
	/**
	 * 增强能量光束是「切换 + 鼠标左键扳机」：{@code handBeamKey} 只负责切换模式，
	 * 真正的扳机是左键——按下进入蓄力、松手开火，档位由服务端按按住长度分流。
	 * 因此客户端不需要自己计时分类，只需要把「按下/松手」两个沿准确发出去。
	 */
	private static boolean handBeamArmed;
	private static boolean handBeamTriggerWasDown;
	/** 已经发出 PRESS、还没发出 RELEASE。用来在中断时补一个 CANCEL。 */
	private static boolean handBeamTriggerActive;
	private static boolean handBeamBlockedUntilRelease;
	private static boolean initialized;
	private static long nextClientSequence = 1L;
	private static long lastRequestGameTime = Long.MIN_VALUE / 2L;
	private static long lastOwnerStateGameTime;
	private static long lastDeniedSequence = Long.MIN_VALUE;
	private static long lastDenyMessageGameTime = Long.MIN_VALUE / 2L;
	private static ArmorCombatDenyReason lastDenyReason = ArmorCombatDenyReason.NONE;
	private static OwnerState ownerState = OwnerState.EMPTY;
	private static ResourceKey<Level> lastLevel;
	private static boolean remoteCommandMode;
	private static UUID remoteCommandSuitId;
	private static long remoteSelectionGeneration;
	private static long lastRemoteFeedbackTick = Long.MIN_VALUE / 2L;
	private static ArmorRemoteCommandAckPayload.Outcome lastRemoteOutcome;

	private ArmorCombatClient() {
	}

	/** 注册战斗按键，并接收 owner、visual、FX 三类 S2C 通道。 */
	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ArmorCombatHud.initialize();
		ArmorCombatFxController.initialize();
		wristPulseKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.flight_armor.wrist_pulse",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_R,
				"key.category.flight_armor"));
		coreBeamKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.flight_armor.core_beam",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				"key.category.flight_armor"));
		bladeToggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.flight_armor.energy_blade",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_X,
				"key.category.flight_armor"));
		remoteMeleeUltimateKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.flight_armor.remote_melee_ultimate",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_J,
				"key.category.flight_armor"));
		// 遥控模式一键开关：不需要打开任何面板，服务端解析最近的一套己方载体。
		remoteToggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.flight_armor.remote_toggle",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_L,
				"key.category.flight_armor"));
		// 增强能量光束：这颗键只切换「灭世模式」的开关，开火交给鼠标左键。
		handBeamKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.flight_armor.hand_beam",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_Z,
				"key.category.flight_armor"));
		ClientPlayNetworking.registerGlobalReceiver(ArmorCombatOwnerStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> receiveOwnerState(context.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(ArmorCombatVisualStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> receiveVisualState(payload)));
		ClientPlayNetworking.registerGlobalReceiver(ArmorBladeStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> receiveBladeState(context.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(ArmorAttackFxPayload.TYPE, (payload, context) ->
				context.client().execute(() -> receiveFx(context.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(ArmorRemoteCommandAckPayload.TYPE,
				(payload, context) -> context.client().execute(() ->
						receiveRemoteAck(context.client(), payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	/** 由客户端每 tick 调用；即使打开界面也会先清空累计按键，避免关闭界面后误发射。 */
	public static void tick(Minecraft client) {
		trackLevel(client);
		ArmorCombatFxController.tick(client);
		ArmorWeaponSoundController.tick(client);
		tickRecoil();
		tickBladeStates(client);
		pruneVisualStates(client);
		boolean wristDown = wristPulseKey != null && wristPulseKey.isDown();
		boolean coreDown = coreBeamKey != null && coreBeamKey.isDown();
		/*
		 * 灭世模式的扳机是鼠标左键。这里必须读「按住的真实状态」而不是每 tick 一次的沿，
		 * 否则「按住蓄力」会退化成一次点击。切换键则是点击队列语义，见下。
		 */
		boolean handTriggerDown = handBeamArmed && client.options.keyAttack.isDown();
		// 切换必须捕捉每一次点击，而不是每 tick 一次的按下沿：连点快于 tick 也不能丢。
		// 在这里统一抽干点击队列，所以打开界面/被操作捕获的 tick 也不会把点击攒到关界面后爆发。
		boolean handBeamToggleClicked = false;
		if (handBeamKey != null) {
			while (handBeamKey.consumeClick()) handBeamToggleClicked = true;
		}
		boolean bladeToggleClicked = false;
		boolean remoteUltimateClicked = false;
		boolean remoteToggleClicked = false;
		if (bladeToggleKey != null) {
			while (bladeToggleKey.consumeClick()) bladeToggleClicked = true;
		}
		if (remoteMeleeUltimateKey != null) {
			while (remoteMeleeUltimateKey.consumeClick()) remoteUltimateClicked = true;
		}
		if (remoteToggleKey != null) {
			while (remoteToggleKey.consumeClick()) remoteToggleClicked = true;
		}
		if (client.player == null || client.level == null) {
			wristKeyWasDown = wristDown;
			wristIntentActive = false;
			coreKeyWasDown = coreDown;
			coreIntentActive = false;
			handBeamTriggerWasDown = handTriggerDown;
			handBeamTriggerActive = false;
			return;
		}
		if (ArmorAssemblyClient.isOperationCapturedThisTick(client)) {
			if (wristIntentActive) {
				sendIntent(client, ArmorAttackType.WRIST_GRAVITY_PULSE, ArmorAttackAction.CANCEL);
				wristIntentActive = false;
			}
			if (coreIntentActive) {
				sendIntent(client, ArmorAttackType.CORE_BEAM, ArmorAttackAction.CANCEL);
				coreIntentActive = false;
			}
			if (handBeamTriggerActive) {
				sendIntent(client, ArmorAttackType.HAND_BEAM_LANCE, ArmorAttackAction.CANCEL);
				handBeamTriggerActive = false;
			}
			if (wristDown) wristBlockedUntilRelease = true;
			if (coreDown) coreBlockedUntilRelease = true;
			if (handTriggerDown) handBeamBlockedUntilRelease = true;
			wristKeyWasDown = wristDown;
			coreKeyWasDown = coreDown;
			handBeamTriggerWasDown = handTriggerDown;
			return;
		}
		if (client.screen != null) {
			if (wristIntentActive) {
				sendIntent(client, ArmorAttackType.WRIST_GRAVITY_PULSE, ArmorAttackAction.CANCEL);
				wristIntentActive = false;
			}
			if (coreIntentActive) {
				sendIntent(client, ArmorAttackType.CORE_BEAM, ArmorAttackAction.CANCEL);
				coreIntentActive = false;
			}
			if (handBeamTriggerActive) {
				sendIntent(client, ArmorAttackType.HAND_BEAM_LANCE, ArmorAttackAction.CANCEL);
				handBeamTriggerActive = false;
			}
			if (wristDown) wristBlockedUntilRelease = true;
			if (coreDown) coreBlockedUntilRelease = true;
			if (handTriggerDown) handBeamBlockedUntilRelease = true;
			wristKeyWasDown = wristDown;
			coreKeyWasDown = coreDown;
			handBeamTriggerWasDown = handTriggerDown;
			return;
		}
		/*
		 * 遥控模式开关是独立事务：先占用本 tick，避免与 B/N/H/M 或面板开关共用一 tick。
		 * 目标由服务端按确定性规则解析，客户端只发送意图，断线时明确报错而不是静默失败。
		 */
		if (remoteToggleClicked) {
			ArmorAssemblyClient.captureOperationThisTick(client);
			if (!sendRemoteIntent(client,
					ArmorRemoteCommandPayload.Command.TOGGLE_NEAREST_REMOTE)) {
				client.player.displayClientMessage(
						Component.translatable("message.flight_armor.server_required"), true);
			}
			wristKeyWasDown = wristDown;
			coreKeyWasDown = coreDown;
			wristBlockedUntilRelease = false;
			coreBlockedUntilRelease = false;
			handBeamTriggerWasDown = handTriggerDown;
			handBeamBlockedUntilRelease = false;
			return;
		}
		/*
		 * 战甲组合键是显式管理层，优先级高于一切上下文分流：Alt+R 恒为「召回最近战甲」，
		 * 不再靠长按计时与短按腕炮共用一个键。按住组合键期间裸 R/G 的武器语义整体让出。
		 */
		if (ArmorAssemblyClient.isModifierDown()) {
			if (wristDown && !wristKeyWasDown) {
				sendRemoteIntent(client, ArmorRemoteCommandPayload.Command.RECALL_NEAREST);
			}
			wristKeyWasDown = wristDown;
			coreKeyWasDown = coreDown;
			wristBlockedUntilRelease = false;
			coreBlockedUntilRelease = false;
			handBeamTriggerWasDown = handTriggerDown;
			handBeamBlockedUntilRelease = false;
			return;
		}
		/*
		 * An explicit nearby-suit selection is a routing mode, not an equipment fallback. While
		 * active it owns M/R/G/X/J even if another complete suit is worn, preventing one key press
		 * from ambiguously firing both the carrier and the player's armor.
		 */
		if (remoteCommandMode) {
			if (wristIntentActive) {
				sendIntent(client, ArmorAttackType.WRIST_GRAVITY_PULSE, ArmorAttackAction.CANCEL);
				wristIntentActive = false;
			}
			if (coreIntentActive) {
				sendIntent(client, ArmorAttackType.CORE_BEAM, ArmorAttackAction.CANCEL);
				coreIntentActive = false;
			}
			// 遥控模式没有对应的手炮指令，所以这两档在遥控态整体惰性，只把在途蓄力收干净。
			if (handBeamTriggerActive) {
				sendIntent(client, ArmorAttackType.HAND_BEAM_LANCE, ArmorAttackAction.CANCEL);
				handBeamTriggerActive = false;
			}
			if (bladeToggleClicked) {
				sendRemoteIntent(client, ArmorRemoteCommandPayload.Command.TOGGLE_MELEE);
			}
			if (remoteUltimateClicked) {
				sendRemoteIntent(client, ArmorRemoteCommandPayload.Command.MELEE_ULTIMATE);
			}
			if (coreDown && !coreKeyWasDown) {
				sendRemoteIntent(client, ArmorRemoteCommandPayload.Command.CORE);
			}
			if (wristDown && !wristKeyWasDown) {
				sendRemoteIntent(client, ArmorRemoteCommandPayload.Command.WRIST);
			}
			/*
			 * 遥控模式没有手炮指令，左键在这个模式里不属于扳机。必须顺手收掉「已就绪」，
			 * 否则模式混入类还会继续按它吞掉左键的挖掘——表现是「左键彻底没反应」。
			 */
			handBeamArmed = false;
			wristKeyWasDown = wristDown;
			coreKeyWasDown = coreDown;
			wristBlockedUntilRelease = false;
			coreBlockedUntilRelease = false;
			handBeamTriggerWasDown = handTriggerDown;
			handBeamBlockedUntilRelease = false;
			return;
		}
		/*
		 * 未穿齐四件时服务端不会接受腕炮/核心束，因此这一段整体保持惰性。
		 * 召回已经迁到上面的组合键层，这里不再需要任何长按计时与着装前置判据。
		 */
		if (!ModItems.hasFullSet(client.player)) {
			// 脱掉甲就自动收起：留着「已就绪」会让模式混入类继续吞掉左键，玩家会以为左键坏了。
			handBeamArmed = false;
			wristKeyWasDown = wristDown;
			coreKeyWasDown = coreDown;
			wristBlockedUntilRelease = false;
			coreBlockedUntilRelease = false;
			handBeamTriggerWasDown = handTriggerDown;
			handBeamTriggerActive = false;
			return;
		}
		if (!bladeExtended(client.player)) {
			queuedBladeCombo = false;
		} else if (queuedBladeCombo && (phase(client.player) == ArmorCombatPhase.IDLE
				|| phase(client.player) == ArmorCombatPhase.RECOVERY
						&& attackType(client.player) == ArmorAttackType.ENERGY_BLADE_COMBO)) {
			queuedBladeCombo = !sendIntent(client, ArmorAttackType.ENERGY_BLADE_COMBO,
					ArmorAttackAction.PRESS);
		}
		if (bladeToggleClicked) {
			sendIntent(client, ArmorAttackType.ENERGY_BLADE_TOGGLE, ArmorAttackAction.PRESS);
		}
		if (coreBlockedUntilRelease) {
			if (!coreDown) coreBlockedUntilRelease = false;
			coreKeyWasDown = coreDown;
		} else if (!wristIntentActive) {
			if (coreDown && !coreKeyWasDown) {
				coreIntentActive = sendIntent(client, ArmorAttackType.CORE_BEAM, ArmorAttackAction.PRESS);
			} else if (!coreDown && coreKeyWasDown && coreIntentActive) {
				sendIntent(client, ArmorAttackType.CORE_BEAM, ArmorAttackAction.RELEASE);
				coreIntentActive = false;
			}
			coreKeyWasDown = coreDown;
		} else {
			if (coreDown) coreBlockedUntilRelease = true;
			coreKeyWasDown = coreDown;
		}
		if (wristBlockedUntilRelease) {
			if (!wristDown) wristBlockedUntilRelease = false;
			wristKeyWasDown = wristDown;
		} else if (!coreIntentActive) {
			if (wristDown && !wristKeyWasDown) {
				if (bladeExtended(client.player)) {
					sendIntent(client, ArmorAttackType.ENERGY_BLADE_SLASH, ArmorAttackAction.PRESS);
					wristBlockedUntilRelease = true;
				} else {
					wristIntentActive = sendIntent(client,
							ArmorAttackType.WRIST_GRAVITY_PULSE, ArmorAttackAction.PRESS);
				}
			} else if (!wristDown && wristKeyWasDown && wristIntentActive) {
				sendIntent(client, ArmorAttackType.WRIST_GRAVITY_PULSE, ArmorAttackAction.RELEASE);
				wristIntentActive = false;
			}
			wristKeyWasDown = wristDown;
		} else {
			if (wristDown) wristBlockedUntilRelease = true;
			wristKeyWasDown = wristDown;
		}
		/*
		 * 增强能量光束（灭世档）：切换键只开模式，真正的扳机是鼠标左键。
		 *  - 按下：发 HAND_BEAM_LANCE 的 PRESS，服务端进入蓄力（循环音起、前臂发射器展开）；
		 *  - 松手：发 RELEASE，服务端按按住长度分流——短点一下是点射，按住才是灭世光束。
		 * 一次扳机恰好两个意图包，连点的节奏仍然等于点击的节奏。
		 */
		if (handBeamToggleClicked) {
			handBeamArmed = !handBeamArmed;
			client.player.displayClientMessage(Component.translatable(handBeamArmed
					? "message.flight_armor.hand_beam_armed"
					: "message.flight_armor.hand_beam_disarmed"), true);
			// 切模式时把在途的蓄力收干净，避免切走后那道光束还举着。
			if (handBeamTriggerActive) {
				sendIntent(client, ArmorAttackType.HAND_BEAM_LANCE, ArmorAttackAction.CANCEL);
				handBeamTriggerActive = false;
			}
			handBeamBlockedUntilRelease = handTriggerDown;
			handBeamTriggerWasDown = handTriggerDown;
		} else if (handBeamBlockedUntilRelease) {
			if (!handTriggerDown) handBeamBlockedUntilRelease = false;
			handBeamTriggerWasDown = handTriggerDown;
		} else if (handTriggerDown && !handBeamTriggerWasDown) {
			handBeamTriggerActive = sendIntent(client, ArmorAttackType.HAND_BEAM_LANCE,
					ArmorAttackAction.PRESS);
			handBeamTriggerWasDown = true;
		} else if (!handTriggerDown && handBeamTriggerWasDown) {
			if (handBeamTriggerActive) {
				sendIntent(client, ArmorAttackType.HAND_BEAM_LANCE, ArmorAttackAction.RELEASE);
				handBeamTriggerActive = false;
			}
			handBeamTriggerWasDown = false;
		}
	}

	/**
	 * Routes a vanilla primary attack into the armor blade timeline. A miss is still a
	 * real authored swing; block hits remain vanilla so an extended blade does not
	 * disable mining.
	 */
	public static boolean tryPrimaryBladeAttack(Minecraft client) {
		if (client.player == null || !ModItems.hasFullSet(client.player)
				|| !bladeExtended(client.player)) return false;
		if (client.hitResult != null
				&& client.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			return false;
		}
		if (phase(client.player) != ArmorCombatPhase.IDLE) {
			queuedBladeCombo = true;
			return true;
		}
		return sendIntent(client, ArmorAttackType.ENERGY_BLADE_COMBO,
				ArmorAttackAction.PRESS);
	}
	private static boolean sendIntent(Minecraft client, ArmorAttackType type, ArmorAttackAction action) {
		if (client.player == null || !ClientPlayNetworking.canSend(ArmorAttackIntentPayload.TYPE)) {
			if (client.player != null) {
				client.player.displayClientMessage(Component.translatable("message.flight_armor.server_required"), true);
			}
			return false;
		}
		long sequence = nextClientSequence++;
		if (nextClientSequence <= 0L) nextClientSequence = 1L;
		ClientPlayNetworking.send(new ArmorAttackIntentPayload(sequence, type, action));
		return true;
	}

	private static boolean sendRemoteIntent(Minecraft client,
			ArmorRemoteCommandPayload.Command command) {
		if (client.player == null
				|| !ClientPlayNetworking.canSend(ArmorRemoteCommandPayload.TYPE)) {
			return false;
		}
		long sequence = nextClientSequence++;
		if (nextClientSequence <= 0L) nextClientSequence = 1L;
		ClientPlayNetworking.send(new ArmorRemoteCommandPayload(
				sequence, remoteSelectionGeneration, command));
		return true;
	}

	private static void receiveRemoteAck(
			Minecraft client, ArmorRemoteCommandAckPayload payload) {
		remoteCommandMode = payload.remoteModeActive();
		remoteCommandSuitId = remoteCommandMode ? payload.suitId() : null;
		remoteSelectionGeneration = remoteCommandMode
				? payload.selectionGeneration() : 0L;
		if (payload.message() == null || payload.message().isBlank() || client.player == null) return;
		long now = client.level == null ? 0L : client.level.getGameTime();
		boolean stateChange = payload.outcome() == ArmorRemoteCommandAckPayload.Outcome.SELECTED
				|| payload.outcome() == ArmorRemoteCommandAckPayload.Outcome.CLEARED
				|| payload.outcome() == ArmorRemoteCommandAckPayload.Outcome.OUT_OF_RANGE;
		boolean repeat = payload.outcome() == lastRemoteOutcome
				&& now - lastRemoteFeedbackTick < 8L;
		if (stateChange || !repeat) {
			client.player.displayClientMessage(Component.literal(payload.message()), true);
			lastRemoteFeedbackTick = now;
			lastRemoteOutcome = payload.outcome();
		}
	}

	public static boolean remoteCommandModeActive() {
		return remoteCommandMode && remoteCommandSuitId != null;
	}

	public static boolean isRemoteCommandSuit(UUID suitId) {
		return remoteCommandModeActive() && suitId != null && suitId.equals(remoteCommandSuitId);
	}

	/**
	 * 遥控模式条的内容：该模式下每颗键的临时含义，键名读的是当前按键设置里的真实绑定，
	 * 玩家重绑后模式条会跟着变，不会出现过期提示。
	 */
	public static List<Component> remoteCommandModeLines() {
		return List.of(
				Component.translatable("hud.flight_armor.remote_command.wrist",
						keyLabel(wristPulseKey)),
				Component.translatable("hud.flight_armor.remote_command.core",
						keyLabel(coreBeamKey)),
				Component.translatable("hud.flight_armor.remote_command.melee",
						keyLabel(bladeToggleKey)),
				Component.translatable("hud.flight_armor.remote_command.ultimate",
						keyLabel(remoteMeleeUltimateKey)),
				Component.translatable("hud.flight_armor.remote_command.exit",
						keyLabel(remoteToggleKey)));
	}

	private static Component keyLabel(KeyMapping key) {
		return key == null ? Component.literal("?") : key.getTranslatedKeyMessage();
	}

	/**
	 * 核心面板「形态」一栏专用：把守望形态切换路由给当前选中的指令甲。
	 * 裸 M 不再走这里，因此这颗键在任何模式下都恒定等于切换身上那套甲的形态。
	 */
	public static boolean requestRemoteConfiguration(Minecraft client) {
		return remoteCommandModeActive()
				&& sendRemoteIntent(client, ArmorRemoteCommandPayload.Command.TOGGLE_CONFIGURATION);
	}

	/** 清理一次连接中的权威快照和去重记录；按键注册本身保留。 */
	public static void clear() {
		clearWorldVisuals();
		lastLevel = null;
		nextClientSequence = 1L;
		lastRequestGameTime = Long.MIN_VALUE / 2L;
		lastOwnerStateGameTime = 0L;
		lastDeniedSequence = Long.MIN_VALUE;
		lastDenyMessageGameTime = Long.MIN_VALUE / 2L;
		lastDenyReason = ArmorCombatDenyReason.NONE;
		wristKeyWasDown = false;
		wristIntentActive = false;
		wristBlockedUntilRelease = false;
		coreKeyWasDown = false;
		coreIntentActive = false;
		coreBlockedUntilRelease = false;
		handBeamTriggerWasDown = false;
		handBeamTriggerActive = false;
		handBeamBlockedUntilRelease = false;
		handBeamArmed = false;
		queuedBladeCombo = false;
		ownerState = OwnerState.EMPTY;
		remoteCommandMode = false;
		remoteCommandSuitId = null;
		remoteSelectionGeneration = 0L;
		lastRemoteFeedbackTick = Long.MIN_VALUE / 2L;
		lastRemoteOutcome = null;
		ArmorWeaponSoundController.clear();
	}

	private static void clearWorldVisuals() {
		VISUAL_STATES.clear();
		LAST_VISUAL_ORDERS.clear();
		RECOIL_STATES.clear();
		BLADE_STATES.clear();
		BLADE_COMBOS.clear();
		ArmorCombatFxController.clear();
	}

	private static void trackLevel(Minecraft client) {
		if (client.level == null) {
			if (lastLevel != null) clearWorldVisuals();
			lastLevel = null;
			return;
		}
		ResourceKey<Level> current = client.level.dimension();
		if (lastLevel != null && !lastLevel.equals(current)) clearWorldVisuals();
		lastLevel = current;
	}

	public static OwnerState ownerState() {
		return ownerState;
	}

	/** 服务端冷却快照按本地游戏时间向零倒计时，仅用于显示，不参与攻击授权。 */
	public static int wristPulseCooldownTicks() {
		return cooldownTicks(ownerState.wristPulseCooldownTicks);
	}

	public static int coreBeamCooldownTicks() {
		return cooldownTicks(ownerState.coreBeamCooldownTicks);
	}

	public static int diveImpactCooldownTicks() {
		return cooldownTicks(ownerState.diveImpactCooldownTicks);
	}

	private static int cooldownTicks(int snapshot) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return snapshot;
		long elapsed = Math.max(0L, client.level.getGameTime() - lastOwnerStateGameTime);
		return Math.max(0, snapshot - (int) Math.min(Integer.MAX_VALUE, elapsed));
	}

	public static ArmorCombatPhase phase(LivingEntity player) {
		VisualAttackState state = VISUAL_STATES.get(player.getUUID());
		return state == null ? ArmorCombatPhase.IDLE : state.phase;
	}

	public static ArmorAttackType attackType(LivingEntity player) {
		VisualAttackState state = VISUAL_STATES.get(player.getUUID());
		return state == null ? ArmorAttackType.INVALID : state.attackType;
	}

	public static boolean attacksWithLeftHand(LivingEntity player) {
		VisualAttackState state = VISUAL_STATES.get(player.getUUID());
		return state != null && state.phase != ArmorCombatPhase.IDLE && state.leftHand;
	}

	/** 该实体是否正在使用手部光束（点射或灭世档）——前臂发射器变形的唯一入口。 */
	public static boolean usesHandBeam(LivingEntity player) {
		ArmorAttackType type = attackType(player);
		return type == ArmorAttackType.HAND_BEAM_PULSE || type == ArmorAttackType.HAND_BEAM_LANCE;
	}

	/** 该实体是否正处于灭世档蓄力（服务端权威）。 */
	public static boolean handBeamCharging(LivingEntity player) {
		return attackType(player) == ArmorAttackType.HAND_BEAM_LANCE
				&& phase(player) == ArmorCombatPhase.CHARGING;
	}

	/**
	 * 本地玩家是否已切换进灭世光束模式（Z 键切换，可重绑）。
	 *
	 * <p>这是「切换后鼠标开火」的总闸：{@code Minecraft.startAttack} 与
	 * {@code MultiPlayerGameMode.continueDestroyBlock} 两个 mixin 都按它把左键让给扳机，
	 * 否则按住扳机会一边蓄力一边把脚下的方块挖掉。</p>
	 */
	public static boolean handBeamArmed() {
		return handBeamArmed;
	}

	/** 切换键当前绑定的按键名（可在按键设置里重绑）。模式是隐式的，HUD 必须把它显示出来。 */
	public static Component handBeamKeyName() {
		return handBeamKey == null ? Component.empty() : handBeamKey.getTranslatedKeyMessage();
	}

	/** Per-player three-step combo, reset after the Better Combat style combo window. */
	public static int bladeComboStep(LivingEntity player) {
		VisualAttackState attack = VISUAL_STATES.get(player.getUUID());
		BladeComboState combo = BLADE_COMBOS.get(player.getUUID());
		return attack == null
				|| (attack.attackType != ArmorAttackType.ENERGY_BLADE_SLASH
						&& attack.attackType != ArmorAttackType.ENERGY_BLADE_COMBO)
				|| combo == null ? 0 : combo.step;
	}

	/** 当前权威阶段 0..1 进度，可供以后的人物和 OBJ 攻击动画共同采样。 */
	/** True when the next combo attack arrived during the previous recovery window. */
	public static boolean bladeComboLinked(LivingEntity player) {
		BladeComboState combo = BLADE_COMBOS.get(player.getUUID());
		return combo != null && combo.linked;
	}
	public static float phaseProgress(LivingEntity player, float partialTick) {
		VisualAttackState state = VISUAL_STATES.get(player.getUUID());
		Minecraft client = Minecraft.getInstance();
		if (state == null || client.level == null) return 0.0F;
		float elapsed = client.level.getGameTime() + Mth.clamp(partialTick, 0.0F, 1.0F)
				- state.serverStartGameTime;
		return Mth.clamp(elapsed / Math.max(1, state.phaseDurationTicks), 0.0F, 1.0F);
	}

	/** 左手发射后的归一化后坐：0 为静止，1 为刚刚开火。 */
	public static float leftRecoil(LivingEntity player, float partialTick) {
		return recoil(player, partialTick, true);
	}

	/** 右手发射后的归一化后坐，左右状态互不镜像复制。 */
	public static float rightRecoil(LivingEntity player, float partialTick) {
		return recoil(player, partialTick, false);
	}

	public static float coreReleaseImpulse(LivingEntity player, float partialTick) {
		RecoilState state = RECOIL_STATES.get(player.getUUID());
		return state == null ? 0.0F : Mth.clamp(Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F),
				state.previousCore, state.core), 0.0F, 1.0F);
	}

	public static float diveImpactImpulse(LivingEntity player, float partialTick) {
		RecoilState state = RECOIL_STATES.get(player.getUUID());
		return state == null ? 0.0F : Mth.clamp(Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F),
				state.previousImpact, state.impact), 0.0F, 1.0F);
	}

	public static boolean bladeExtended(LivingEntity player) {
		BladeVisualState state = BLADE_STATES.get(player.getUUID());
		return state != null && state.extended && state.progress >= 0.72F;
	}

	static boolean hasBladeVisuals() {
		return !BLADE_STATES.isEmpty();
	}

	public static float bladeProgress(LivingEntity player, float partialTick) {
		BladeVisualState state = BLADE_STATES.get(player.getUUID());
		return state == null ? 0.0F : Mth.clamp(Mth.lerp(
				Mth.clamp(partialTick, 0.0F, 1.0F), state.previousProgress, state.progress),
				0.0F, 1.0F);
	}

	public static ArmorVariant bladeVariant(LivingEntity player) {
		BladeVisualState state = BLADE_STATES.get(player.getUUID());
		return state == null ? ArmorVariant.VANGUARD : state.variant;
	}

	private static void receiveOwnerState(Minecraft client, ArmorCombatOwnerStatePayload payload) {
		ownerState = new OwnerState(payload.acceptedClientSequence(), payload.serverAttackId(),
				payload.energy(), payload.heat(), payload.overheated(), payload.wristPulseCooldownTicks(),
				payload.coreBeamCooldownTicks(), payload.diveImpactCooldownTicks(),
				payload.phase(), payload.denyReason());
		if (client.level != null) lastOwnerStateGameTime = client.level.getGameTime();
		if (payload.acceptedClientSequence() >= nextClientSequence) {
			nextClientSequence = payload.acceptedClientSequence() + 1L;
			if (nextClientSequence <= 0L) nextClientSequence = 1L;
		}
		showDenyMessage(client, payload);
	}

	private static void showDenyMessage(Minecraft client, ArmorCombatOwnerStatePayload payload) {
		ArmorCombatDenyReason reason = payload.denyReason();
		if (reason == ArmorCombatDenyReason.NONE || reason == ArmorCombatDenyReason.DUPLICATE_SEQUENCE
				|| client.player == null) return;
		if (reason != ArmorCombatDenyReason.MISSING_FULL_SET
				&& reason != ArmorCombatDenyReason.NOT_ARMOR_OWNER
				&& reason != ArmorCombatDenyReason.NOT_ENOUGH_ENERGY
				&& reason != ArmorCombatDenyReason.OVERHEATED) return;
		long now = client.level == null ? 0L : client.level.getGameTime();
		boolean newDecision = payload.acceptedClientSequence() != lastDeniedSequence || reason != lastDenyReason;
		if (!newDecision || now - lastDenyMessageGameTime < DENY_MESSAGE_GAP_TICKS) return;
		lastDeniedSequence = payload.acceptedClientSequence();
		lastDenyReason = reason;
		lastDenyMessageGameTime = now;
		client.player.displayClientMessage(Component.translatable(
				"message.flight_armor.combat_denied." + reason.name().toLowerCase(Locale.ROOT)), true);
	}

	private static void receiveVisualState(ArmorCombatVisualStatePayload payload) {
		VisualOrder last = LAST_VISUAL_ORDERS.get(payload.attackerId());
		if (last != null && (payload.serverAttackId() < last.serverAttackId
				|| payload.serverAttackId() == last.serverAttackId
				&& payload.visualRevision() <= last.visualRevision)) return;
		LAST_VISUAL_ORDERS.put(payload.attackerId(),
				new VisualOrder(payload.serverAttackId(), payload.visualRevision()));
		if (payload.attackType() == ArmorAttackType.ENERGY_BLADE_SLASH
				|| payload.attackType() == ArmorAttackType.ENERGY_BLADE_COMBO) {
			BladeComboState previous = BLADE_COMBOS.get(payload.attackerId());
			if (previous == null || previous.attackId != payload.serverAttackId()) {
				long gap = previous == null ? Long.MAX_VALUE
						: payload.serverStartGameTime() - previous.startGameTime;
				boolean expired = previous == null
						|| previous.attackType != payload.attackType() || gap > 30L;
				int step = expired ? 0 : (previous.step + 1) % 3;
				boolean linked = !expired && gap <= 16L;
				BLADE_COMBOS.put(payload.attackerId(), new BladeComboState(
						step, payload.serverAttackId(), payload.serverStartGameTime(),
						payload.attackType(), linked));
			}
		}
		if (payload.phase() == ArmorCombatPhase.IDLE) {
			VISUAL_STATES.remove(payload.attackerId());
			return;
		}
		VISUAL_STATES.put(payload.attackerId(), new VisualAttackState(payload.serverAttackId(),
				payload.attackType(), payload.phase(), payload.leftHand(), payload.serverStartGameTime(),
				Math.max(1, payload.phaseDurationTicks())));
	}

	private static void receiveBladeState(Minecraft client, ArmorBladeStatePayload payload) {
		BladeVisualState current = BLADE_STATES.get(payload.playerId());
		if (current != null && payload.revision() < current.revision) return;
		boolean playTransition = current != null && current.extended != payload.extended();
		if (current == null) {
			current = new BladeVisualState();
			current.progress = payload.extended() ? 0.02F : 0.0F;
			current.previousProgress = current.progress;
			BLADE_STATES.put(payload.playerId(), current);
		}
		current.revision = payload.revision();
		current.extended = payload.extended();
		current.variant = payload.variant();
		current.unloadedTicks = 0;
		if (playTransition) {
			ArmorWeaponSoundController.playBladeToggle(
					client, payload.playerId(), payload.extended(), payload.variant());
		}
	}

	private static void receiveFx(Minecraft client, ArmorAttackFxPayload payload) {
		if (!ArmorCombatFxController.receive(client, payload)) return;
		RecoilState recoil = RECOIL_STATES.computeIfAbsent(payload.attackerId(), ignored -> new RecoilState());
		switch (payload.attackType()) {
			case WRIST_GRAVITY_PULSE -> {
				if (payload.leftHand()) recoil.left = 1.0F;
				else recoil.right = 1.0F;
			}
			case CORE_BEAM, CRUISE_CORE_BEAM -> recoil.core = 1.0F;
			case HAND_BEAM_PULSE -> {
				if (payload.leftHand()) recoil.left = 1.0F;
				else recoil.right = 1.0F;
			}
			case HAND_BEAM_LANCE -> {
				// 灭世档是双手托举开火：两条手臂一起后坐，且刻意不走胸口核心通道——
				// 借 core 通道会把胸炮的释放姿势叠上来，看起来像从胸口打出去的。
				recoil.left = 1.0F;
				recoil.right = 1.0F;
			}
			case ENERGY_BLADE_SLASH, ENERGY_BLADE_COMBO -> {
				// Blade motion owns its complete windup/contact/recovery timeline. Feeding it
				// into the wrist-cannon recoil lane made the first combo snap into an aim pose.
			}
			case GRAVITY_DIVE_IMPACT, CRUISE_RAM, CRUISE_GROUND_SLAM ->
				recoil.impact = Mth.clamp(payload.charge(), 0.35F, 1.0F);
			case ENERGY_BLADE_TOGGLE, CRUISE_MISSILE_VOLLEY, INVALID -> {
			}
		}
	}

	private static void tickBladeStates(Minecraft client) {
		Iterator<Map.Entry<UUID, BladeVisualState>> iterator = BLADE_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, BladeVisualState> entry = iterator.next();
			BladeVisualState state = entry.getValue();
			state.previousProgress = state.progress;
			float target = state.extended ? 1.0F : 0.0F;
			float step = state.extended ? 0.10F : 0.125F;
			state.progress = Mth.approach(state.progress, target, step);
			if (!state.extended && state.progress <= 0.0F && state.previousProgress <= 0.0F) {
				iterator.remove();
			}
		}
	}

	private static void tickRecoil() {
		Iterator<Map.Entry<UUID, RecoilState>> iterator = RECOIL_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			RecoilState state = iterator.next().getValue();
			state.previousLeft = state.left;
			state.previousRight = state.right;
			state.previousCore = state.core;
			state.previousImpact = state.impact;
			state.left *= 0.66F;
			state.right *= 0.66F;
			state.core *= 0.72F;
			state.impact *= 0.78F;
			if (state.left < 0.01F) state.left = 0.0F;
			if (state.right < 0.01F) state.right = 0.0F;
			if (state.core < 0.01F) state.core = 0.0F;
			if (state.impact < 0.01F) state.impact = 0.0F;
			if (state.left == 0.0F && state.right == 0.0F
					&& state.core == 0.0F && state.impact == 0.0F
					&& state.previousLeft == 0.0F && state.previousRight == 0.0F
					&& state.previousCore == 0.0F && state.previousImpact == 0.0F) iterator.remove();
		}
	}

	private static float recoil(LivingEntity player, float partialTick, boolean leftHand) {
		RecoilState state = RECOIL_STATES.get(player.getUUID());
		if (state == null) return 0.0F;
		return Mth.clamp(leftHand
				? Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), state.previousLeft, state.left)
				: Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), state.previousRight, state.right), 0.0F, 1.0F);
	}

	private static void pruneVisualStates(Minecraft client) {
		if (client.level == null) {
			VISUAL_STATES.clear();
			return;
		}
		long gameTime = client.level.getGameTime();
		VISUAL_STATES.entrySet().removeIf(entry -> {
			VisualAttackState state = entry.getValue();
			return state.phase != ArmorCombatPhase.CHARGING
					&& gameTime - state.serverStartGameTime
					> state.phaseDurationTicks + VISUAL_STATE_GRACE_TICKS;
		});
		LAST_VISUAL_ORDERS.entrySet().removeIf(entry ->
				!VISUAL_STATES.containsKey(entry.getKey())
						&& !RECOIL_STATES.containsKey(entry.getKey()));
	}

	public record OwnerState(long acceptedClientSequence, long serverAttackId,
			float energy, float heat, boolean overheated, int wristPulseCooldownTicks,
			int coreBeamCooldownTicks, int diveImpactCooldownTicks,
			ArmorCombatPhase phase, ArmorCombatDenyReason denyReason) {
		private static final OwnerState EMPTY = new OwnerState(0L, 0L,
				ArmorCombatTuning.MAX_ENERGY, 0.0F, false, 0, 0, 0,
				ArmorCombatPhase.IDLE, ArmorCombatDenyReason.NONE);
	}

	private record VisualAttackState(long serverAttackId, ArmorAttackType attackType,
			ArmorCombatPhase phase, boolean leftHand, long serverStartGameTime, int phaseDurationTicks) {
	}

	private record VisualOrder(long serverAttackId, int visualRevision) {
	}

	private record BladeComboState(int step, long attackId, long startGameTime,
			ArmorAttackType attackType, boolean linked) {
	}

	private static final class RecoilState {
		private float previousLeft;
		private float left;
		private float previousRight;
		private float right;
		private float previousCore;
		private float core;
		private float previousImpact;
		private float impact;
	}

	private static final class BladeVisualState {
		private long revision;
		private boolean extended;
		private ArmorVariant variant = ArmorVariant.VANGUARD;
		private float previousProgress;
		private float progress;
		private int unloadedTicks;
	}
}
