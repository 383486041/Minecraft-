package dev.codex.flightarmor.core;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 纯指令菜单。核心身份由服务端 SavedData 管理，界面不再伪装成物品容器，
 * 因而不会出现需要拖动核心、平台或玩家背包物品才能完成配置的交互。
 */
public final class ArmorCoreMenu extends AbstractContainerMenu {
	public ArmorCoreMenu(int containerId, Inventory inventory) {
		this(containerId, inventory, new SimpleContainer(1));
	}

	ArmorCoreMenu(int containerId, Inventory inventory, Container coreInventory) {
		super(ModCoreContent.ARMOR_CORE_MENU, containerId);
		// 保留三参构造器以兼容旧服务端打开路径；coreInventory 不再暴露为菜单槽位。
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	@Override public boolean stillValid(Player player) { return player.isAlive(); }
}
