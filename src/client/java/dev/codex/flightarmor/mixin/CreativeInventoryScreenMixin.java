package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.core.ArmorCoreSlot;
import dev.codex.flightarmor.network.ArmorCoreCreativeSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Keeps the core slot usable on the creative inventory tab as well as the survival screen. */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeInventoryScreenMixin {
	private static final ResourceLocation SLOT_SPRITE =
			ResourceLocation.withDefaultNamespace("container/slot");

	@Unique private ItemStack flightArmor$coreBeforeClick = ItemStack.EMPTY;
	@Unique private boolean flightArmor$trackCoreClick;


	@ModifyArgs(method = "selectTab",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen$SlotWrapper;"
							+ "<init>(Lnet/minecraft/world/inventory/Slot;III)V"))
	private void flightArmor$positionCoreSlot(Args args) {
		Slot target = args.get(0);
		if (!(target instanceof ArmorCoreSlot)) return;
		args.set(2, 35);
		args.set(3, 38);
	}

	@Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
	private void flightArmor$captureCoreBeforeClick(Slot slot, int slotId, int mouseButton,
			ClickType clickType, CallbackInfo ci) {
		CreativeModeInventoryScreen self = (CreativeModeInventoryScreen) (Object) this;
		ArmorCoreSlot coreSlot = flightArmor$unwrapCoreSlot(slot);
		if (!self.isInventoryOpen() || coreSlot == null) {
			flightArmor$trackCoreClick = false;
			return;
		}
		if (clickType != ClickType.PICKUP) {
			// The creative protocol ignores appended slot 46. Unsupported composite
			// gestures are cancelled instead of mutating only the client and risking
			// deletion/duplication. The normal survival inventory still supports them.
			flightArmor$trackCoreClick = false;
			flightArmor$coreBeforeClick = ItemStack.EMPTY;
			ci.cancel();
			return;
		}
		flightArmor$trackCoreClick = true;
		flightArmor$coreBeforeClick = flightArmor$trackCoreClick ? flightArmor$currentCoreStack() : ItemStack.EMPTY;
	}

	@Inject(method = "slotClicked", at = @At("RETURN"))
	private void flightArmor$syncCoreAfterClick(Slot slot, int slotId, int mouseButton,
			ClickType clickType, CallbackInfo ci) {
		if (!flightArmor$trackCoreClick) return;
		flightArmor$trackCoreClick = false;
		flightArmor$syncTrackedCore();
	}

	@Unique
	private static ArmorCoreSlot flightArmor$unwrapCoreSlot(Slot slot) {
		if (slot == null) return null;
		Slot target = slot;
		if (slot instanceof CreativeSlotWrapperAccessor wrapper) target = wrapper.flightArmor$getTarget();
		return target instanceof ArmorCoreSlot coreSlot ? coreSlot : null;
	}

	@Unique
	private void flightArmor$syncTrackedCore() {
		ItemStack after = flightArmor$currentCoreStack();
		if (ItemStack.matches(flightArmor$coreBeforeClick, after)) return;
		if (!ClientPlayNetworking.canSend(ArmorCoreCreativeSyncPayload.TYPE)) return;
		Minecraft client = Minecraft.getInstance();
		ItemStack carriedAfter = client.player == null
				? ItemStack.EMPTY : client.player.inventoryMenu.getCarried();
		// Slot and cursor are one compare-and-set transaction. Sending only the slot
		// caused the server's empty cursor to erase a core picked up in creative mode.
		ClientPlayNetworking.send(new ArmorCoreCreativeSyncPayload(
				after, flightArmor$coreBeforeClick, carriedAfter));
	}

	@Unique
	private static ItemStack flightArmor$currentCoreStack() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return ItemStack.EMPTY;
		for (Slot candidate : client.player.inventoryMenu.slots) {
			if (!(candidate instanceof ArmorCoreSlot)) continue;
			ItemStack stack = candidate.getItem();
			return stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
		}
		return ItemStack.EMPTY;
	}
	@Inject(method = "renderBg", at = @At("TAIL"))
	private void flightArmor$renderCoreSlot(GuiGraphics graphics, float partialTick,
			int mouseX, int mouseY, CallbackInfo ci) {
		CreativeModeInventoryScreen self = (CreativeModeInventoryScreen) (Object) this;
		if (!self.isInventoryOpen()) return;
		AbstractContainerScreenAccessor layout = (AbstractContainerScreenAccessor) (Object) this;
		int x = layout.flightArmor$getLeftPos() + 34;
		int y = layout.flightArmor$getTopPos() + 37;
		graphics.blitSprite(SLOT_SPRITE, x, y, 18, 18);
		int color = 0xFF55E6FF;
		graphics.fill(x + 8, y + 4, x + 10, y + 6, color);
		graphics.fill(x + 6, y + 6, x + 12, y + 8, color);
		graphics.fill(x + 7, y + 8, x + 11, y + 10, color);
		graphics.fill(x + 8, y + 10, x + 10, y + 12, color);
	}
}
