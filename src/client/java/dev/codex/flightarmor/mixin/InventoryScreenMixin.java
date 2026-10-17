package dev.codex.flightarmor.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the extra core slot directly above the vanilla offhand slot. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	private static final Identifier SLOT_SPRITE =
			Identifier.withDefaultNamespace("container/slot");

	@Inject(method = "extractBackground", at = @At("TAIL"))
	private void flightArmor$renderCoreSlot(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick, CallbackInfo ci) {
		AbstractContainerScreenAccessor layout = (AbstractContainerScreenAccessor) (Object) this;
		int x = layout.flightArmor$getLeftPos() + 76;
		int y = layout.flightArmor$getTopPos() + 43;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x, y, 18, 18);
		int color = 0xFF55E6FF;
		graphics.fill(x + 8, y + 4, x + 10, y + 6, color);
		graphics.fill(x + 6, y + 6, x + 12, y + 8, color);
		graphics.fill(x + 7, y + 8, x + 11, y + 10, color);
		graphics.fill(x + 8, y + 10, x + 10, y + 12, color);
	}
}
