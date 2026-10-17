package dev.codex.flightarmor.client;

import dev.codex.flightarmor.network.ArmorAssemblyStatePayload;
import dev.codex.flightarmor.network.ArmorAttackFxPayload;
import dev.codex.flightarmor.network.ArmorBladeStatePayload;
import dev.codex.flightarmor.network.ArmorCombatOwnerStatePayload;
import dev.codex.flightarmor.network.ArmorCombatVisualStatePayload;
import dev.codex.flightarmor.network.ArmorCoreSnapshotPayload;
import dev.codex.flightarmor.network.ArmorCruiseCombatStatePayload;
import dev.codex.flightarmor.network.ArmorCruiseStatePayload;
import dev.codex.flightarmor.network.ArmorEscortStatePayload;
import dev.codex.flightarmor.network.ArmorPlatformRecallPayload;
import dev.codex.flightarmor.network.ArmorRemoteCommandAckPayload;
import dev.codex.flightarmor.network.AutomaticHoverAckPayload;
import dev.codex.flightarmor.network.AutomaticHoverEndPayload;
import dev.codex.flightarmor.network.AutomaticHoverEngagePayload;
import dev.codex.flightarmor.network.FlightVisualStatePayload;
import dev.codex.flightarmor.network.SonicBoomFxPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Phase 1 client stub for Minecraft 26.3 migration.
 * Keeps network receivers active and prevents disconnects/timeouts while rendering pipeline is rebuilt in Phase 2.
 */
public final class FlightArmorClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("flight_armor/client");
	private static UUID hoveringPlayerId;

	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing FlightArmorClient (MC 26.3 Phase 1)");

		// Register clientbound packet receivers
		ClientPlayNetworking.registerGlobalReceiver(FlightVisualStatePayload.TYPE, (payload, context) -> {
			// Phase 1 stub: visual state update
		});

		ClientPlayNetworking.registerGlobalReceiver(SonicBoomFxPayload.TYPE, (payload, context) -> {
			// Phase 1 stub: sonic boom fx
		});

		ClientPlayNetworking.registerGlobalReceiver(AutomaticHoverEngagePayload.TYPE, (payload, context) -> {
			context.client().execute(() -> {
				if (ClientPlayNetworking.canSend(AutomaticHoverAckPayload.TYPE)) {
					ClientPlayNetworking.send(new AutomaticHoverAckPayload(payload.engagementId()));
				}
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(AutomaticHoverEndPayload.TYPE, (payload, context) -> {
			// Phase 1 stub: automatic hover end
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorAssemblyStatePayload.TYPE, (payload, context) -> {
			// Phase 1 stub: assembly state
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorPlatformRecallPayload.TYPE, (payload, context) -> {
			// Phase 1 stub: platform recall
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorCruiseStatePayload.TYPE, (payload, context) -> {
			// Phase 1 stub: cruise state
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorCruiseCombatStatePayload.TYPE, (payload, context) -> {
			// Phase 1 stub: cruise combat state
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorCombatOwnerStatePayload.TYPE, (payload, context) -> {
			// Phase 1 stub: combat owner state
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorCombatVisualStatePayload.TYPE, (payload, context) -> {
			// Phase 1 stub: combat visual state
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorBladeStatePayload.TYPE, (payload, context) -> {
			// Phase 1 stub: blade state
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorAttackFxPayload.TYPE, (payload, context) -> {
			// Phase 1 stub: attack fx
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorCoreSnapshotPayload.TYPE, (payload, context) -> {
			// Phase 1 stub: core snapshot
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorEscortStatePayload.TYPE, (payload, context) -> {
			// Phase 1 stub: escort state
		});

		ClientPlayNetworking.registerGlobalReceiver(ArmorRemoteCommandAckPayload.TYPE, (payload, context) -> {
			// Phase 1 stub: remote command ack
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			hoveringPlayerId = null;
		});
	}

	public static boolean isHovering(Player player) {
		return hoveringPlayerId != null && hoveringPlayerId.equals(player.getUUID());
	}

	public static int hoverForward() {
		return 0;
	}

	public static int hoverStrafe() {
		return 0;
	}

	public static int hoverVertical() {
		return 0;
	}

	public static boolean isFlightEffectActive(Player player) {
		return player.isFallFlying();
	}

	public static float flightVisualProgress(Player player, float partialTick) {
		return player.isFallFlying() ? 1.0F : 0.0F;
	}

	public static float diveVisualProgress(Player player, float partialTick) {
		return player.isFallFlying() ? 1.0F : 0.0F;
	}

	public static float boostVisualProgress(Player player, float partialTick) {
		return player.isSprinting() ? 1.0F : 0.0F;
	}

	public static boolean isDiveVisualActive(Player player) {
		return player.isFallFlying();
	}

	public static FlightAudioSnapshot flightAudioSnapshot(Player player) {
		return new FlightAudioSnapshot(player.isFallFlying(), player.isFallFlying(), false, false);
	}

	public record FlightAudioSnapshot(boolean active, boolean diving, boolean boosting, boolean moving) {
	}
}
