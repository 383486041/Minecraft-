package dev.codex.flightarmor.item;

import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public final class FlightArmorItem extends ArmorItem {
	public FlightArmorItem(Type type) {
		super(ModArmorMaterials.FLIGHT, type, new Item.Properties().durability(type.getDurability(ModArmorMaterials.BASE_DURABILITY)).fireResistant());
	}

	@Override
	public Component getName(ItemStack stack) {
		ArmorVariant variant = ArmorVariantData.get(stack);
		return Component.translatable("item.flight_armor.variant." + variant.serializedName(),
				super.getName(stack));
	}

	@Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		ArmorVariant variant = ArmorVariantData.get(stack);
		ChatFormatting variantColor = switch (variant) {
			case VANGUARD -> ChatFormatting.AQUA;
			case SWIFT -> ChatFormatting.LIGHT_PURPLE;
			case SENTINEL -> ChatFormatting.GOLD;
		};
		tooltip.add(Component.literal("\u6218\u7532\u578b\u53f7\uff1a" + variant.chineseName()).withStyle(variantColor));
		tooltip.add(Component.translatable("tooltip.flight_armor.variant." + variant.serializedName())
				.withStyle(variantColor));
		tooltip.add(Component.translatable("tooltip.flight_armor.set_bonus").withStyle(ChatFormatting.AQUA));
		tooltip.add(Component.translatable("tooltip.flight_armor.controls").withStyle(ChatFormatting.GRAY));
	}
}
