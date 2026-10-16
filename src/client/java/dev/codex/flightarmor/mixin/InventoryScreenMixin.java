package dev.codex.flightarmor.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the extra core slot directly above the vanilla offhand slot. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	private static final ResourceLocation SLOT_SPRITE =
			ResourceLocation.withDefaultNamespace("container/slot");

	@Inject(method = "renderBg", at = @At("TAIL"))
	private void flightArmor$renderCoreSlot(GuiGraphics graphics, float partialTick,
			int mouseX, int mouseY, CallbackInfo ci) {
		AbstractContainerScreenAccessor layout = (AbstractContainerScreenAccessor) (Object) this;
		int x = layout.flightArmor$getLeftPos() + 76;
		int y = layout.flightArmor$getTopPos() + 43;
		graphics.blitSprite(SLOT_SPRITE, x, y, 18, 18);
		int color = 0xFF55E6FF;
		graphics.fill(x + 8, y + 4, x + 10, y + 6, color);
		graphics.fill(x + 6, y + 6, x + 12, y + 8, color);
		graphics.fill(x + 7, y + 8, x + 11, y + 10, color);
		graphics.fill(x + 8, y + 10, x + 10, y + 12, color);
	}
}
