package dev.codex.flightarmor.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Accesses protected menu primitives from subclasses without shadowing inherited members. */
@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuInvoker {
	@Invoker("addSlot")
	Slot flightArmor$invokeAddSlot(Slot slot);

	@Invoker("moveItemStackTo")
	boolean flightArmor$invokeMoveItemStackTo(ItemStack stack, int startIndex, int endIndex,
			boolean reverseDirection);
}
