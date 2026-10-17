package dev.codex.flightarmor.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads inherited screen layout fields from their declaring class. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int flightArmor$getLeftPos();

	@Accessor("topPos")
	int flightArmor$getTopPos();
}
