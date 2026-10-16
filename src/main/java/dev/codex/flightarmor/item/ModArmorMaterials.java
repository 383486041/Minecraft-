package dev.codex.flightarmor.item;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.EnumMap;
import java.util.List;

public final class ModArmorMaterials {
	public static final int BASE_DURABILITY = 90;

	public static final Holder<ArmorMaterial> FLIGHT = Registry.registerForHolder(
			BuiltInRegistries.ARMOR_MATERIAL,
			FlightArmorMod.id("flight"),
			new ArmorMaterial(
					createDefenseValues(),
					15,
					// 分件着甲由统一时间线逐件播发，避免四个装备槽同 tick 叠出四次原版穿戴声。
					Holder.direct(SoundEvents.EMPTY),
					() -> Ingredient.of(Items.PHANTOM_MEMBRANE),
					List.of(new ArmorMaterial.Layer(FlightArmorMod.id("flight"))),
					6.0F,
					0.15F
			)
	);

	private ModArmorMaterials() {
	}

	public static void initialize() {
		// 触发静态注册。
	}

	private static EnumMap<ArmorItem.Type, Integer> createDefenseValues() {
		EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
		defense.put(ArmorItem.Type.BOOTS, 5);
		defense.put(ArmorItem.Type.LEGGINGS, 9);
		defense.put(ArmorItem.Type.CHESTPLATE, 11);
		defense.put(ArmorItem.Type.HELMET, 5);
		defense.put(ArmorItem.Type.BODY, 14);
		return defense;
	}
}
