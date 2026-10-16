package dev.codex.flightarmor.wardrobe;

import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorWardrobeAction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 服务端权威的整套收纳和逐件穿脱入口；具体物品事务由统一分件状态机执行。 */
public final class ArmorWardrobeController {
	private static final int REQUEST_COOLDOWN_TICKS = 2;
	private static final Map<UUID, Integer> LAST_REQUEST_TICKS = new ConcurrentHashMap<>();
	private static final List<EquipmentSlot> REMOVE_ORDER = List.of(
			EquipmentSlot.HEAD,
			EquipmentSlot.CHEST,
			EquipmentSlot.LEGS,
			EquipmentSlot.FEET
	);
	private static final List<EquipmentSlot> EQUIP_ORDER = List.of(
			EquipmentSlot.FEET,
			EquipmentSlot.LEGS,
			EquipmentSlot.CHEST,
			EquipmentSlot.HEAD
	);

	private ArmorWardrobeController() {
	}

	public static void handleRequest(ServerPlayer player, ArmorWardrobeAction action) {
		if (!player.isAlive() || player.isSpectator()) {
			deny(player, "当前状态无法整理战甲");
			return;
		}
		if (player.containerMenu != player.inventoryMenu) {
			deny(player, "请先关闭当前容器界面再整理战甲");
			return;
		}
		Integer lastRequestTick = LAST_REQUEST_TICKS.get(player.getUUID());
		if (lastRequestTick != null && player.tickCount - lastRequestTick < REQUEST_COOLDOWN_TICKS) return;
		LAST_REQUEST_TICKS.put(player.getUUID(), player.tickCount);
		if (ArmorCoreController.hasActiveQuickSwitch(player)) {
			deny(player, "战甲快速切换事务进行中，完成或回滚前不能逐件穿脱");
			return;
		}
		if (ArmorAssemblyController.isAnimating(player)) {
			deny(player, "战甲部件正在移动，暂时不能执行下一步");
			return;
		}
		switch (action) {
			case FULL_TOGGLE -> toggleFullSet(player);
			case STEP_REMOVE -> removeNextPart(player);
			case STEP_EQUIP -> equipNextPart(player);
		}
	}

	public static void remove(ServerPlayer player) {
		LAST_REQUEST_TICKS.remove(player.getUUID());
	}

	private static void toggleFullSet(ServerPlayer player) {
		List<EquipmentSlot> equipped = equippedFlightArmorSlots(player);
		if (!equipped.isEmpty()) {
			ArmorAssemblyController.beginRetraction(player, equipped);
			return;
		}
		ArmorAssemblyController.handleRequest(player);
	}

	private static void removeNextPart(ServerPlayer player) {
		for (EquipmentSlot slot : REMOVE_ORDER) {
			if (player.getItemBySlot(slot).is(ModItems.flightArmorForSlot(slot))) {
				ArmorAssemblyController.beginRetraction(player, List.of(slot));
				return;
			}
		}
		deny(player, "没有更多已穿戴的战甲部件");
	}

	private static void equipNextPart(ServerPlayer player) {
		for (EquipmentSlot slot : EQUIP_ORDER) {
			ItemStack equipped = player.getItemBySlot(slot);
			if (equipped.is(ModItems.flightArmorForSlot(slot))) continue;
			if (!equipped.isEmpty()) {
				deny(player, partName(slot) + "槽位已被其他装备占用");
				return;
			}
			ArmorAssemblyController.beginEquipPart(player, slot);
			return;
		}
		deny(player, "整套战甲已经穿戴完成");
	}

	private static List<EquipmentSlot> equippedFlightArmorSlots(ServerPlayer player) {
		List<EquipmentSlot> result = new ArrayList<>(4);
		for (EquipmentSlot slot : REMOVE_ORDER) {
			if (player.getItemBySlot(slot).is(ModItems.flightArmorForSlot(slot))) result.add(slot);
		}
		return result;
	}

	private static String partName(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> "头盔";
			case CHEST -> "胸甲";
			case LEGS -> "护腿";
			case FEET -> "战靴";
			default -> "战甲";
		};
	}

	private static void deny(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message), true);
	}
}
