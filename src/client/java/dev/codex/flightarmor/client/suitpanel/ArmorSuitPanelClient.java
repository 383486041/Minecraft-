package dev.codex.flightarmor.client.suitpanel;

import dev.codex.flightarmor.network.ArmorSuitPanelActionPayload;
import dev.codex.flightarmor.network.ArmorSuitPanelSnapshotPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Receives authoritative snapshots and submits only button intents. */
public final class ArmorSuitPanelClient {
	private static boolean initialized;

	private ArmorSuitPanelClient() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientPlayNetworking.registerGlobalReceiver(ArmorSuitPanelSnapshotPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					if (payload.closeRequested()) {
						if (context.client().gui.screen() instanceof ArmorSuitPanelScreen screen
								&& screen.matchesSession(payload.sessionId())) {
							context.client().gui.setScreen(null);
						}
						return;
					}
					if (payload.openRequested()
							|| context.client().gui.screen() instanceof ArmorSuitPanelScreen screen
							&& screen.matchesSession(payload.sessionId())) {
						context.client().gui.setScreen(new ArmorSuitPanelScreen(payload));
					}
				}));
	}

	static void send(long sessionId, ArmorSuitPanelActionPayload.Action action) {
		if (!ClientPlayNetworking.canSend(ArmorSuitPanelActionPayload.TYPE)) return;
		ClientPlayNetworking.send(new ArmorSuitPanelActionPayload(sessionId, action));
	}
}
