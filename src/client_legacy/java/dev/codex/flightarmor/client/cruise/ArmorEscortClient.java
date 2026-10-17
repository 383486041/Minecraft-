package dev.codex.flightarmor.client.cruise;

import dev.codex.flightarmor.network.ArmorEscortStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 多副战甲载体 UUID 镜像；用于客户端隐藏 ArmorStand 木架。 */
public final class ArmorEscortClient {
	private static final Map<UUID, ArmorEscortStatePayload> BY_CARRIER = new HashMap<>();
	private static final Map<UUID, ArmorEscortStatePayload> BY_SUIT = new HashMap<>();

	private ArmorEscortClient() {}

	public static void initialize() {
		ClientPlayNetworking.registerGlobalReceiver(ArmorEscortStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> receive(payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	private static void receive(ArmorEscortStatePayload payload) {
		ArmorEscortStatePayload old = BY_SUIT.remove(payload.suitId());
		if (old != null) BY_CARRIER.remove(old.carrierId());
		if (!payload.active()) return;
		BY_SUIT.put(payload.suitId(), payload);
		BY_CARRIER.put(payload.carrierId(), payload);
	}

	public static boolean isEscortCarrier(Entity entity) {
		return entity != null && BY_CARRIER.containsKey(entity.getUUID());
	}

	public static ArmorEscortStatePayload.EscortPhase phaseOf(Entity entity) {
		ArmorEscortStatePayload state = entity == null ? null : BY_CARRIER.get(entity.getUUID());
		return state == null ? ArmorEscortStatePayload.EscortPhase.ESCORTING : state.phase();
	}

	/** Read-only visual hint; server guard state remains authoritative. */
	public static boolean guardEnabled(Entity entity) {
		ArmorEscortStatePayload state = entity == null ? null : BY_CARRIER.get(entity.getUUID());
		return state != null && state.guardEnabled();
	}

	public static void clear() {
		BY_CARRIER.clear();
		BY_SUIT.clear();
	}
}
