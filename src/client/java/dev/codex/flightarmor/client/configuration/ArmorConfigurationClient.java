package dev.codex.flightarmor.client.configuration;

import com.mojang.blaze3d.platform.InputConstants;
import dev.codex.flightarmor.client.FlightArmorKeyMappings;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyClient;
import dev.codex.flightarmor.network.ArmorConfigurationTogglePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** M-key client intent for the Sentinel's two server-authoritative configurations. */
public final class ArmorConfigurationClient {
	private static KeyMapping toggleKey;
	private static long nextClientSequence = 1L;
	private static boolean initialized;

	private ArmorConfigurationClient() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		toggleKey = FlightArmorKeyMappings.register(
				"key.flight_armor.toggle_configuration",
				InputConstants.KEY_M);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				nextClientSequence = 1L);
	}

	/**
	 * Always drains M first so pressing it in a menu never queues a form change for the first
	 * gameplay tick after that menu closes.
	 */
	public static void tick(Minecraft client) {
		boolean clicked = false;
		if (toggleKey != null) {
			while (toggleKey.consumeClick()) clicked = true;
		}
		if (!clicked || client.player == null || client.level == null
				|| client.gui.screen() != null
				|| ArmorAssemblyClient.isOperationCapturedThisTick(client)) return;
		// M 恒定等于守望双形态切换：遥控态不再劫持这颗键。遥控甲的形态配置改由核心面板里
		// 「形态」一栏操作，避免同一颗键在看不见的模式下悄悄换义。
		if (!ClientPlayNetworking.canSend(ArmorConfigurationTogglePayload.TYPE)) {
			client.player.sendOverlayMessage(
					Component.translatable("message.flight_armor.server_required"));
			return;
		}
		ArmorAssemblyClient.captureOperationThisTick(client);
		long sequence = nextClientSequence++;
		if (nextClientSequence <= 0L) nextClientSequence = 1L;
		ClientPlayNetworking.send(new ArmorConfigurationTogglePayload(sequence));
	}
}
