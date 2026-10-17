package dev.codex.flightarmor.item;

import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.equipment.ArmorType;

import java.util.function.Consumer;

public final class FlightArmorItem extends Item {
	private final ArmorType armorType;

	public FlightArmorItem(ArmorType type) {
		super(new Item.Properties().humanoidArmor(ModArmorMaterials.FLIGHT, type).fireResistant());
		this.armorType = type;
	}

	public ArmorType getArmorType() {
		return armorType;
	}

	@Override
	public Component getName(ItemStack stack) {
		ArmorVariant variant = ArmorVariantData.get(stack);
		return Component.translatable("item.flight_armor.variant." + variant.serializedName(),
				super.getName(stack));
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
			Consumer<Component> tooltip, TooltipFlag flag) {
		ArmorVariant variant = ArmorVariantData.get(stack);
		ChatFormatting variantColor = switch (variant) {
			case VANGUARD -> ChatFormatting.AQUA;
			case SWIFT -> ChatFormatting.LIGHT_PURPLE;
			case SENTINEL -> ChatFormatting.GOLD;
		};
		tooltip.accept(Component.literal("\u6218\u7532\u578b\u53f7\uff1a" + variant.chineseName()).withStyle(variantColor));
		tooltip.accept(Component.translatable("tooltip.flight_armor.variant." + variant.serializedName())
				.withStyle(variantColor));
		tooltip.accept(Component.translatable("tooltip.flight_armor.set_bonus").withStyle(ChatFormatting.AQUA));
		tooltip.accept(Component.translatable("tooltip.flight_armor.controls").withStyle(ChatFormatting.GRAY));
	}
}
