package dev.codex.flightarmor.platform;

import dev.codex.flightarmor.item.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 四个真实平台槽位；没有影子库存，GUI、方块渲染和召回事务读取同一组 ItemStack。 */
public final class ChargingPlatformMenu extends AbstractContainerMenu {
	public static final int PLATFORM_SLOT_COUNT = 4;
	private final Container platform;
	private final Player viewer;

	public ChargingPlatformMenu(int containerId, Inventory inventory) {
		this(containerId, inventory, new SimpleContainer(PLATFORM_SLOT_COUNT));
	}

	public ChargingPlatformMenu(int containerId, Inventory inventory, Container platform) {
		super(ModPlatformContent.CHARGING_PLATFORM_MENU, containerId);
		checkContainerSize(platform, PLATFORM_SLOT_COUNT);
		this.platform = platform;
		this.viewer = inventory.player;
		platform.startOpen(inventory.player);

		for (int index = 0; index < PLATFORM_SLOT_COUNT; index++) {
			final int platformIndex = index;
			addSlot(new Slot(platform, index, 53 + index * 24, 31) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					if (!ModItems.isExactFlightArmor(stack,
							ChargingPlatformBlockEntity.ARMOR_SLOTS.get(platformIndex))) return false;
					return !(ChargingPlatformMenu.this.platform instanceof ChargingPlatformBlockEntity blockEntity)
							|| !(viewer instanceof ServerPlayer serverPlayer)
							|| blockEntity.canInsertFromMenu(serverPlayer, platformIndex, stack);
				}

				@Override
				public boolean mayPickup(Player player) {
					return !(ChargingPlatformMenu.this.platform instanceof ChargingPlatformBlockEntity blockEntity)
							|| blockEntity.canTakeFromMenu(player);
				}

				@Override
				public int getMaxStackSize() {
					return 1;
				}

				@Override
				public void setByPlayer(ItemStack stack, ItemStack oldStack) {
					super.setByPlayer(stack, oldStack);
					reconcileNow();
				}

				@Override
				public void onTake(Player player, ItemStack stack) {
					super.onTake(player, stack);
					reconcileNow();
				}

				private void reconcileNow() {
					if (viewer instanceof ServerPlayer serverPlayer
							&& ChargingPlatformMenu.this.platform instanceof ChargingPlatformBlockEntity blockEntity) {
						blockEntity.reconcileMenuContents(serverPlayer);
					}
				}
			});
		}

		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new Slot(inventory, column + row * 9 + 9,
						8 + column * 18, 76 + row * 18));
			}
		}
		for (int column = 0; column < 9; column++) {
			addSlot(new Slot(inventory, column, 8 + column * 18, 134));
		}
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = getSlot(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack source = slot.getItem();
		ItemStack original = source.copy();
		if (index < PLATFORM_SLOT_COUNT) {
			if (!moveItemStackTo(source, PLATFORM_SLOT_COUNT, slots.size(), true)) return ItemStack.EMPTY;
		} else {
			EquipmentSlot armorSlot = ChargingPlatformBlockEntity.ARMOR_SLOTS.stream()
					.filter(candidate -> ModItems.isExactFlightArmor(source, candidate))
					.findFirst().orElse(null);
			if (armorSlot == null) return ItemStack.EMPTY;
			int target = ChargingPlatformBlockEntity.ARMOR_SLOTS.indexOf(armorSlot);
			if (!moveItemStackTo(source, target, target + 1, false)) return ItemStack.EMPTY;
		}
		if (source.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		if (source.getCount() == original.getCount()) return ItemStack.EMPTY;
		slot.onTake(player, source);
		return original;
	}

	@Override
	public void slotsChanged(Container container) {
		super.slotsChanged(container);
		if (container == platform && viewer instanceof ServerPlayer serverPlayer
				&& platform instanceof ChargingPlatformBlockEntity blockEntity) {
			blockEntity.reconcileMenuContents(serverPlayer);
		}
	}

	@Override
	public boolean stillValid(Player player) {
		return platform.stillValid(player);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		platform.stopOpen(player);
	}
}
