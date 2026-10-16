package dev.codex.flightarmor;

import dev.codex.flightarmor.flight.FlightController;
import dev.codex.flightarmor.flight.AutomaticEquipHoverController;
import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.combat.ArmorCombatController;
import dev.codex.flightarmor.combat.ArmorCombatDenyReason;
import dev.codex.flightarmor.combat.ModCombatEntities;
import dev.codex.flightarmor.combat.SentinelWingmanController;
import dev.codex.flightarmor.configuration.ArmorConfigurationController;
import dev.codex.flightarmor.cruise.ArmorCruiseCombatController;
import dev.codex.flightarmor.cruise.ArmorCruiseController;
import dev.codex.flightarmor.cruise.ArmorEscortController;
import dev.codex.flightarmor.cruise.ArmorRemoteCommandController;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.illumination.ArmorIlluminationController;
import dev.codex.flightarmor.illumination.ModIlluminationContent;
import dev.codex.flightarmor.item.ModArmorMaterials;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.platform.ChargingPlatformController;
import dev.codex.flightarmor.network.ArmorCruiseStatePayload;
import dev.codex.flightarmor.network.ArmorCruiseCombatCommandPayload;
import dev.codex.flightarmor.network.ArmorCruiseCombatStatePayload;
import dev.codex.flightarmor.network.FlightInputPayload;
import dev.codex.flightarmor.network.FlightVisualStatePayload;
import dev.codex.flightarmor.network.SonicBoomFxPayload;
import dev.codex.flightarmor.network.ArmorAssemblyRequestPayload;
import dev.codex.flightarmor.network.ArmorAssemblyStatePayload;
import dev.codex.flightarmor.network.ArmorPlatformRecallPayload;
import dev.codex.flightarmor.network.AutomaticHoverEngagePayload;
import dev.codex.flightarmor.network.AutomaticHoverAckPayload;
import dev.codex.flightarmor.network.AutomaticHoverEndPayload;
import dev.codex.flightarmor.network.ArmorWardrobeRequestPayload;
import dev.codex.flightarmor.network.ArmorAttackFxPayload;
import dev.codex.flightarmor.network.ArmorAttackIntentPayload;
import dev.codex.flightarmor.network.ArmorCombatOwnerStatePayload;
import dev.codex.flightarmor.network.ArmorCombatVisualStatePayload;
import dev.codex.flightarmor.network.ArmorBladeStatePayload;
import dev.codex.flightarmor.network.ArmorCoreActionPayload;
import dev.codex.flightarmor.network.ArmorCoreOpenPayload;
import dev.codex.flightarmor.network.ArmorCoreSnapshotPayload;
import dev.codex.flightarmor.network.ArmorCoreCreativeSyncPayload;
import dev.codex.flightarmor.network.ArmorConfigurationTogglePayload;
import dev.codex.flightarmor.network.ArmorEscortStatePayload;
import dev.codex.flightarmor.network.ArmorQuickSwitchPayload;
import dev.codex.flightarmor.network.ArmorRemoteCommandPayload;
import dev.codex.flightarmor.network.ArmorRemoteCommandAckPayload;
import dev.codex.flightarmor.network.SentinelWingmanCommandPayload;
import dev.codex.flightarmor.wardrobe.ArmorWardrobeController;
import dev.codex.flightarmor.sound.ModSoundEvents;
import dev.codex.flightarmor.suitpanel.ArmorSuitPanelController;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FlightArmorMod implements ModInitializer {
	public static final String MOD_ID = "flight_armor";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModArmorMaterials.initialize();
		ModItems.initialize();
		ModIlluminationContent.initialize();
		ModCombatEntities.initialize();
		ChargingPlatformController.initialize();
		ArmorCoreController.initialize();
		ArmorSuitPanelController.initialize();
		ModSoundEvents.initialize();
		ArmorCruiseController.initialize();
		ArmorEscortController.initialize();
		ArmorCombatController.initialize();

		PayloadTypeRegistry.playC2S().register(FlightInputPayload.TYPE, FlightInputPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ArmorAttackIntentPayload.TYPE, ArmorAttackIntentPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ArmorRemoteCommandPayload.TYPE,
				ArmorRemoteCommandPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ArmorCruiseCombatCommandPayload.TYPE,
				ArmorCruiseCombatCommandPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(SentinelWingmanCommandPayload.TYPE,
				SentinelWingmanCommandPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(AutomaticHoverAckPayload.TYPE, AutomaticHoverAckPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ArmorAssemblyRequestPayload.TYPE, ArmorAssemblyRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ArmorWardrobeRequestPayload.TYPE, ArmorWardrobeRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ArmorCoreOpenPayload.TYPE, ArmorCoreOpenPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ArmorCoreActionPayload.TYPE, ArmorCoreActionPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ArmorQuickSwitchPayload.TYPE, ArmorQuickSwitchPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ArmorCoreCreativeSyncPayload.TYPE,
				ArmorCoreCreativeSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ArmorConfigurationTogglePayload.TYPE,
				ArmorConfigurationTogglePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(FlightVisualStatePayload.TYPE, FlightVisualStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(SonicBoomFxPayload.TYPE, SonicBoomFxPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(AutomaticHoverEngagePayload.TYPE, AutomaticHoverEngagePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(AutomaticHoverEndPayload.TYPE, AutomaticHoverEndPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorAssemblyStatePayload.TYPE, ArmorAssemblyStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorPlatformRecallPayload.TYPE, ArmorPlatformRecallPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorCruiseStatePayload.TYPE, ArmorCruiseStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorCruiseCombatStatePayload.TYPE,
				ArmorCruiseCombatStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorCombatOwnerStatePayload.TYPE, ArmorCombatOwnerStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorCombatVisualStatePayload.TYPE, ArmorCombatVisualStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorBladeStatePayload.TYPE, ArmorBladeStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorAttackFxPayload.TYPE, ArmorAttackFxPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorCoreSnapshotPayload.TYPE, ArmorCoreSnapshotPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorEscortStatePayload.TYPE, ArmorEscortStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ArmorRemoteCommandAckPayload.TYPE,
				ArmorRemoteCommandAckPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(FlightInputPayload.TYPE,
				(payload, context) -> FlightController.handleInput(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ArmorAttackIntentPayload.TYPE,
				(payload, context) -> ArmorCombatController.handleIntent(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ArmorRemoteCommandPayload.TYPE,
				(payload, context) -> ArmorRemoteCommandController.handleCommand(
						context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ArmorCruiseCombatCommandPayload.TYPE,
				(payload, context) -> ArmorCruiseCombatController.handleCommand(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(SentinelWingmanCommandPayload.TYPE,
				(payload, context) -> SentinelWingmanController.handleCommand(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(AutomaticHoverAckPayload.TYPE,
				(payload, context) -> FlightController.handleAutomaticHoverAck(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ArmorAssemblyRequestPayload.TYPE,
				(payload, context) -> ArmorCruiseController.handleRequest(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(ArmorWardrobeRequestPayload.TYPE,
				(payload, context) -> ArmorWardrobeController.handleRequest(context.player(), payload.action()));
		ServerPlayNetworking.registerGlobalReceiver(ArmorCoreOpenPayload.TYPE,
				(payload, context) -> ArmorCoreController.openMenu(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(ArmorCoreActionPayload.TYPE,
				(payload, context) -> ArmorCoreController.handleAction(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ArmorQuickSwitchPayload.TYPE,
				(payload, context) -> ArmorCoreController.handleQuickSwitch(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ArmorCoreCreativeSyncPayload.TYPE,
				(payload, context) -> ArmorCoreController.handleCreativeCoreSync(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ArmorConfigurationTogglePayload.TYPE,
				(payload, context) -> ArmorConfigurationController.handleToggle(context.player(), payload));
		EntityTrackingEvents.START_TRACKING.register((trackedEntity, trackingPlayer) -> {
			if (trackedEntity instanceof net.minecraft.server.level.ServerPlayer trackedPlayer) {
				FlightController.syncVisualStateTo(trackedPlayer, trackingPlayer);
				ArmorAssemblyController.syncStateTo(trackedPlayer, trackingPlayer);
				ArmorCruiseController.syncPlatformRecallTo(trackedPlayer, trackingPlayer);
				ArmorCruiseController.syncStateTo(trackedPlayer, trackingPlayer);
				ArmorCombatController.syncStateTo(trackedPlayer, trackingPlayer);
			}
			ArmorCruiseController.syncCarrierStateTo(trackedEntity, trackingPlayer);
			ArmorCruiseCombatController.syncCarrierTo(trackedEntity, trackingPlayer);
			ArmorEscortController.syncCarrierTo(trackedEntity, trackingPlayer);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ArmorCoreController.handleJoin(handler.player);
			AutomaticEquipHoverController.handleJoin(handler.player);
			ArmorCruiseController.handleJoin(handler.player);
			ArmorEscortController.handleJoin(handler.player);
			ArmorCruiseController.syncAllTo(handler.player);
			ArmorCruiseCombatController.syncAllTo(handler.player);
			ArmorEscortController.syncAllTo(handler.player);
			ArmorCombatController.handleJoin(handler.player);
			ArmorCoreController.sendSnapshot(handler.player);
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			SentinelWingmanController.tickServer(server);
			ArmorCruiseController.tickServer(server);
			ArmorEscortController.tickServer(server);
			ArmorRemoteCommandController.tickServer(server);
			ArmorAssemblyController.tickServer(server);
			AutomaticEquipHoverController.tickServer(server);
			FlightController.tickServer(server);
			ArmorCoreController.tickServer(server);
			ArmorCombatController.tickServer(server);
			ArmorIlluminationController.tickServer(server);
		});
		ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> {
					ArmorCruiseController.handleDisconnect(handler.player);
					ArmorEscortController.handleDisconnect(handler.player);
					ArmorCruiseCombatController.cancelByOwnerId(handler.player.getUUID());
					ArmorRemoteCommandController.remove(handler.player);
					ArmorAssemblyController.remove(handler.player);
					AutomaticEquipHoverController.remove(handler.player);
					FlightController.remove(handler.player);
					ArmorWardrobeController.remove(handler.player);
					ArmorCombatController.remove(handler.player);
					ArmorCoreController.handleDisconnect(handler.player.getUUID());
					ArmorConfigurationController.remove(handler.player);
					SentinelWingmanController.removeOwner(handler.player.getUUID());
				});
		// LEAVE 明确发生在玩家数据保存前；DISCONNECT 中的第二次调用是无状态幂等兜底。
		ServerPlayerEvents.LEAVE.register(ArmorCruiseController::handleDisconnect);
		ServerPlayerEvents.LEAVE.register(ArmorEscortController::handleDisconnect);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			SentinelWingmanController.removeOwner(oldPlayer.getUUID());
			ArmorRemoteCommandController.remove(oldPlayer);
			ArmorCruiseController.handleRespawn(oldPlayer, newPlayer, alive);
			AutomaticEquipHoverController.handleRespawn(oldPlayer, newPlayer);
			ArmorCombatController.handleRespawn(newPlayer);
			ArmorCoreController.handleRespawn(newPlayer);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
				SentinelWingmanController.removeOwner(player.getUUID());
				ArmorRemoteCommandController.remove(player);
				ArmorCombatController.cancel(player, ArmorCombatDenyReason.PLAYER_DEAD);
				AutomaticEquipHoverController.remove(player);
				ArmorCruiseController.handleDeath(player);
				ArmorCoreController.handleDeath(player);
			}
		});
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
				(player, origin, destination) -> {
					SentinelWingmanController.removeOwner(player.getUUID());
					ArmorRemoteCommandController.remove(player);
					ArmorCombatController.handleDimensionChange(player);
					ArmorCruiseController.handleDimensionChange(player);
					AutomaticEquipHoverController.handleDimensionChange(player);
				});
		ServerLifecycleEvents.SERVER_STARTED.register(ArmorCruiseController::recoverLoadedCarriers);
		ServerLifecycleEvents.SERVER_STARTED.register(ArmorEscortController::recoverLoadedCarriers);
		ServerLifecycleEvents.BEFORE_SAVE.register(
				(server, flush, force) -> ArmorEscortController.flushPersistentState(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(ArmorCruiseController::handleServerStopping);
		ServerLifecycleEvents.SERVER_STOPPING.register(ArmorEscortController::handleServerStopping);
		ServerLifecycleEvents.SERVER_STOPPING.register(ArmorCoreController::handleServerStopping);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ArmorRemoteCommandController.clearAll());
		ServerLifecycleEvents.SERVER_STOPPING.register(
				server -> SentinelWingmanController.clearRuntimeIndex());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> AutomaticEquipHoverController.clear());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ArmorConfigurationController.clearAll());
		// 关服前必须收干净：光源是真实方块，留着就会写进存档。
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ArmorIlluminationController.clear());
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			SentinelWingmanController.handleEntityLoad(entity);
			ArmorCruiseController.handleEntityLoad(entity, level);
			ArmorEscortController.handleEntityLoad(entity, level);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			SentinelWingmanController.handleEntityUnload(entity);
			ArmorCoreController.handleEntityUnload(entity, level);
			ArmorEscortController.handleEntityUnload(entity, level);
		});

		LOGGER.info("凌空战甲已加载：单穿凌空战靴或装备完整套装均可飞行");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
