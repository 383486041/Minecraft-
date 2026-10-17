package dev.codex.flightarmor.client.core;

import com.mojang.blaze3d.platform.InputConstants;
import dev.codex.flightarmor.client.FlightArmorKeyMappings;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyClient;
import dev.codex.flightarmor.core.ArmorCoreAction;
import dev.codex.flightarmor.network.ArmorCoreActionPayload;
import dev.codex.flightarmor.network.ArmorCoreOpenPayload;
import dev.codex.flightarmor.network.ArmorCoreSnapshotPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 核心网络按键与核心菜单快照；客户端从不决定平台是否合法或物品移动结果。 */
public final class ArmorCoreClient {
	private static final long STANCE_CYCLE_COOLDOWN_TICKS = 5L;
	private static final AtomicLong NEXT_SEQUENCE = new AtomicLong();
	private static KeyMapping openKey;
	private static KeyMapping escortStanceKey;
	private static long lastStanceCycleGameTime = Long.MIN_VALUE / 2L;
	private static ArmorCoreSnapshotPayload snapshot =
			new ArmorCoreSnapshotPayload(0L, false, null, List.of(), -1L, null, null, "");

	private ArmorCoreClient() {}

	public static void initialize() {
		openKey = FlightArmorKeyMappings.register("key.flight_armor.armor_core", InputConstants.KEY_K);
		// 一键循环护航副甲任务位置：跟随自主 → 贴身跟随 → 原地待命。不需要打开面板，
		// 也不指定某套甲；由服务端挑出全部已加载护航副甲并统一循环。
		escortStanceKey = FlightArmorKeyMappings.register(
				"key.flight_armor.escort_stance_cycle", InputConstants.KEY_C);
		ClientPlayNetworking.registerGlobalReceiver(ArmorCoreSnapshotPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					if (payload.revision() >= snapshot.revision()) snapshot = payload;
				}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				snapshot = new ArmorCoreSnapshotPayload(0L, false, null, List.of(),
						-1L, null, null, ""));
	}

	public static void tick(Minecraft client) {
		if (openKey == null || escortStanceKey == null) return;
		boolean openClicked = false;
		while (openKey.consumeClick()) openClicked = true;
		boolean stanceClicked = false;
		while (escortStanceKey.consumeClick()) stanceClicked = true;
		if (client.player == null || client.level == null || client.gui.screen() != null) return;
		if (openClicked && !ArmorAssemblyClient.isOperationCapturedThisTick(client)
				&& ClientPlayNetworking.canSend(ArmorCoreOpenPayload.TYPE)) {
			ArmorAssemblyClient.captureOperationThisTick(client);
			ClientPlayNetworking.send(new ArmorCoreOpenPayload());
		}
		if (stanceClicked && !ArmorAssemblyClient.isOperationCapturedThisTick(client)) {
			long now = client.level.getGameTime();
			if (now - lastStanceCycleGameTime < STANCE_CYCLE_COOLDOWN_TICKS) return;
			ArmorAssemblyClient.captureOperationThisTick(client);
			lastStanceCycleGameTime = now;
			send(ArmorCoreAction.CYCLE_ESCORT_STANCE, null);
		}
	}

	public static ArmorCoreSnapshotPayload snapshot() {
		return snapshot;
	}

	public static long send(ArmorCoreAction action, UUID suitId) {
		if (!ClientPlayNetworking.canSend(ArmorCoreActionPayload.TYPE)) return -1L;
		long sequence = NEXT_SEQUENCE.incrementAndGet();
		ClientPlayNetworking.send(new ArmorCoreActionPayload(sequence, action, suitId));
		return sequence;
	}
}
