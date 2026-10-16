package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.core.ArmorCoreInventory;
import dev.codex.flightarmor.core.ArmorCoreSlot;
import dev.codex.flightarmor.item.ModItems;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds a real synchronized navigation-core equipment slot to the vanilla player inventory. */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin {

	@Inject(method = "<init>", at = @At("TAIL"))
	private void flightArmor$addCoreSlot(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
		((AbstractContainerMenuInvoker) (Object) this).flightArmor$invokeAddSlot(
				new ArmorCoreSlot(new ArmorCoreInventory(owner), 77, 44));
	}

	@Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
	private void flightArmor$quickMoveCore(Player player, int index,
			CallbackInfoReturnable<ItemStack> cir) {
		InventoryMenu menu = (InventoryMenu) (Object) this;
		if (index < 0 || index >= menu.slots.size()) return;
		Slot coreSlot = menu.slots.stream()
				.filter(ArmorCoreSlot.class::isInstance)
				.findFirst().orElse(null);
		if (coreSlot == null) return;
		int coreIndex = menu.slots.indexOf(coreSlot);
		if (coreIndex < 0) return;
		Slot source = menu.getSlot(index);
		if (!source.hasItem()) return;
		ItemStack sourceStack = source.getItem();
		boolean fromCoreSlot = source == coreSlot;
		boolean intoEmptyCoreSlot = sourceStack.is(ModItems.ARMOR_NAVIGATION_CORE)
				&& !coreSlot.hasItem();
		if (!fromCoreSlot && !intoEmptyCoreSlot) return;
		if (fromCoreSlot && !coreSlot.mayPickup(player)) {
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}

		ItemStack original = sourceStack.copy();
		AbstractContainerMenuInvoker menuAccess = (AbstractContainerMenuInvoker) (Object) this;
		boolean moved = fromCoreSlot
				? menuAccess.flightArmor$invokeMoveItemStackTo(sourceStack, InventoryMenu.INV_SLOT_START,
						InventoryMenu.USE_ROW_SLOT_END, false)
				: menuAccess.flightArmor$invokeMoveItemStackTo(sourceStack, coreIndex,
						coreIndex + 1, false);
		if (!moved) {
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}
		if (sourceStack.isEmpty()) source.setByPlayer(ItemStack.EMPTY, original);
		else source.setChanged();
		if (sourceStack.getCount() == original.getCount()) {
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}
		source.onTake(player, sourceStack);
		cir.setReturnValue(original);
	}
}
