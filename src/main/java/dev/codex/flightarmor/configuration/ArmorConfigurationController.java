package dev.codex.flightarmor.configuration;

import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorConfigurationTogglePayload;
import dev.codex.flightarmor.platform.ArmorIdentity;
import dev.codex.flightarmor.platform.ChargingPlatformBlockEntity;
import dev.codex.flightarmor.platform.ChargingPlatformController;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative Sentinel form switch.
 *
 * <p>M is only an intent. The server revalidates the complete matching set, ownership, and all
 * armor movement locks before mutating the chest item's persistent configuration.</p>
 */
public final class ArmorConfigurationController {
	private static final long MIN_TOGGLE_GAP_TICKS = 6L;
	private static final Map<UUID, Long> LAST_CLIENT_SEQUENCE = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_TOGGLE_TICK = new ConcurrentHashMap<>();

	private ArmorConfigurationController() {
	}

	public static void handleToggle(ServerPlayer player,
			ArmorConfigurationTogglePayload payload) {
		if (player == null || payload == null
				|| !acceptClientSequence(player.getUUID(), payload.clientSequence())) return;
		if (!player.isAlive() || player.isSpectator() || player.isSleeping()
				|| player.isPassenger()) {
			deny(player, "message.flight_armor.configuration.unavailable");
			return;
		}
		if (ArmorAssemblyController.isAnimating(player)
				|| ArmorCoreController.hasActiveQuickSwitch(player)) {
			deny(player, "message.flight_armor.configuration.busy");
			return;
		}
		// 形态切换是核心网络能力：核心离线时战甲仍能飞，但不能换形态。
		if (!ArmorCoreController.coreNetworkActive(player)) {
			deny(player, "message.flight_armor.core_offline.form");
			return;
		}

		Optional<ArmorVariant> fullSet = ModItems.fullSetVariant(player);
		if (fullSet.isEmpty()) {
			deny(player, "message.flight_armor.configuration.no_full_set");
			return;
		}
		if (fullSet.get() != ArmorVariant.SENTINEL) {
			deny(player, "message.flight_armor.configuration.sentinel_only");
			return;
		}
		// A transform is a whole-suit transaction: all four parts must belong to this owner and
		// carry the same exact suit id. Unlike basic boot flight, an unclaimed set cannot transform.
		if (!hasCompleteOwnedSuitIdentity(player)
				|| !ChargingPlatformController.canUseEquippedFlightArmor(player)) {
			deny(player, "message.flight_armor.configuration.not_owner");
			return;
		}

		long now = player.serverLevel().getGameTime();
		long lastToggle = LAST_TOGGLE_TICK.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2L);
		if (now - lastToggle < MIN_TOGGLE_GAP_TICKS) {
			deny(player, "message.flight_armor.configuration.busy");
			return;
		}

		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		ArmorConfigurationData.Snapshot current = ArmorConfigurationData.read(chest);
		if (ArmorConfigurationAccess.isTransitioning(current, now)
				|| ArmorConfigurationAccess.isWingmenTransitioning(current, now)) {
			deny(player, "message.flight_armor.configuration.busy");
			return;
		}
		// The detachable modules are real entities after H deployment. They must physically
		// return and lock before the whole back frame is allowed to change configuration.
		if (current.wingmenDeployed()) {
			deny(player, "message.flight_armor.configuration.recall_wingmen");
			return;
		}
		SentinelArmorMode nextMode = current.mode().next();
		long nextRevision = current.revision() >= Long.MAX_VALUE - 1L
				? 1L : current.revision() + 1L;
		if (!ArmorConfigurationData.set(chest, nextMode, nextRevision, now)) {
			deny(player, "message.flight_armor.configuration.unavailable");
			return;
		}
		LAST_TOGGLE_TICK.put(player.getUUID(), now);
		player.getInventory().setChanged();
		player.inventoryMenu.broadcastChanges();
		if (player.containerMenu != player.inventoryMenu) {
			player.containerMenu.broadcastChanges();
		}
		String resultKey = nextMode == SentinelArmorMode.OVERDRIVE_FLIGHT
				? "message.flight_armor.configuration.overdrive"
				: "message.flight_armor.configuration.breach";
		player.displayClientMessage(Component.translatable(resultKey), true);
	}

	/** Server-authoritative M-key form switch for one selected cruise/escort carrier. */
	public static RemoteToggleResult toggleRemote(ServerPlayer owner, ArmorStand carrier) {
		if (owner == null || carrier == null || !carrier.isAlive()) {
			return RemoteToggleResult.failed("指令战甲载体无效");
		}
		if (ArmorCoreController.hasActiveQuickSwitch(owner)
				|| ModItems.fullSetVariant(carrier).orElse(null) != ArmorVariant.SENTINEL) {
			return RemoteToggleResult.failed("当前指令战甲没有可切换的双形态");
		}
		UUID suitId = null;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ArmorIdentity.Identity identity = ArmorIdentity.read(carrier.getItemBySlot(slot)).orElse(null);
			if (identity == null || identity.slot() != slot
					|| !identity.ownerId().equals(owner.getUUID())) {
				return RemoteToggleResult.failed("指令战甲身份或部件不完整");
			}
			if (suitId != null && !suitId.equals(identity.suitId())) {
				return RemoteToggleResult.failed("指令战甲部件身份不一致");
			}
			suitId = identity.suitId();
		}
		long now = owner.serverLevel().getGameTime();
		ItemStack chest = carrier.getItemBySlot(EquipmentSlot.CHEST);
		ArmorConfigurationData.Snapshot current = ArmorConfigurationData.read(chest);
		if (ArmorConfigurationAccess.isTransitioning(current, now)
				|| ArmorConfigurationAccess.isWingmenTransitioning(current, now)) {
			return RemoteToggleResult.failed("战甲仍在执行上一段变形动画");
		}
		if (current.wingmenDeployed()) {
			return RemoteToggleResult.failed("请先召回僚机，再切换战甲形态");
		}
		SentinelArmorMode nextMode = current.mode().next();
		long nextRevision = current.revision() >= Long.MAX_VALUE - 1L
				? 1L : current.revision() + 1L;
		if (!ArmorConfigurationData.set(chest, nextMode, nextRevision, now)) {
			return RemoteToggleResult.failed("远程形态切换未能写入战甲");
		}
		carrier.setItemSlot(EquipmentSlot.CHEST, chest);
		return RemoteToggleResult.success(nextMode == SentinelArmorMode.OVERDRIVE_FLIGHT
				? "指令战甲正在切换为超驰飞行形态"
				: "指令战甲正在切换为重装攻坚形态");
	}

	public record RemoteToggleResult(boolean success, String message) {
		private static RemoteToggleResult success(String message) {
			return new RemoteToggleResult(true, message);
		}

		private static RemoteToggleResult failed(String message) {
			return new RemoteToggleResult(false, message);
		}
	}

	public static void remove(ServerPlayer player) {
		if (player == null) return;
		remove(player.getUUID());
	}

	public static void remove(UUID playerId) {
		if (playerId == null) return;
		LAST_CLIENT_SEQUENCE.remove(playerId);
		LAST_TOGGLE_TICK.remove(playerId);
	}

	public static void clearAll() {
		LAST_CLIENT_SEQUENCE.clear();
		LAST_TOGGLE_TICK.clear();
	}

	private static boolean acceptClientSequence(UUID playerId, long clientSequence) {
		if (playerId == null || clientSequence <= 0L) return false;
		Long previous = LAST_CLIENT_SEQUENCE.get(playerId);
		if (previous != null && clientSequence <= previous) return false;
		LAST_CLIENT_SEQUENCE.put(playerId, clientSequence);
		return true;
	}

	private static boolean hasCompleteOwnedSuitIdentity(ServerPlayer player) {
		UUID suitId = null;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			ArmorIdentity.Identity identity = ArmorIdentity.read(stack).orElse(null);
			if (identity == null || identity.slot() != slot
					|| !identity.ownerId().equals(player.getUUID())) return false;
			if (suitId != null && !suitId.equals(identity.suitId())) return false;
			suitId = identity.suitId();
		}
		return suitId != null;
	}

	private static void deny(ServerPlayer player, String translationKey) {
		player.displayClientMessage(Component.translatable(translationKey), true);
	}
}
