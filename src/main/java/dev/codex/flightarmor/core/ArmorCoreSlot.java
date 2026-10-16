package dev.codex.flightarmor.core;

import dev.codex.flightarmor.item.ModItems;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

/** The visible, server-authoritative navigation-core equipment slot in the player inventory. */
public final class ArmorCoreSlot extends Slot {
	public ArmorCoreSlot(Container container, int x, int y) {
		super(container, 0, x, y);
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return stack.is(ModItems.ARMOR_NAVIGATION_CORE) && container.canPlaceItem(getContainerSlot(), stack);
	}

	@Override
	public boolean mayPickup(Player player) {
		return (!(player instanceof ServerPlayer serverPlayer)
				|| !ArmorCoreController.isCoreChangeLocked(serverPlayer))
				&& container.stillValid(player) && super.mayPickup(player);
	}

	@Override
	public int getMaxStackSize() {
		return 1;
	}

	@Override
	public int getMaxStackSize(ItemStack stack) {
		return 1;
	}
}
