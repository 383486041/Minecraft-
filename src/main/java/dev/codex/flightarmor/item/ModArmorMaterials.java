package dev.codex.flightarmor.item;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;

import java.util.Map;

public final class ModArmorMaterials {
	public static final int BASE_DURABILITY = 90;

	public static final ArmorMaterial FLIGHT = new ArmorMaterial(
			BASE_DURABILITY,
			Map.of(
					ArmorType.BOOTS, 5,
					ArmorType.LEGGINGS, 9,
					ArmorType.CHESTPLATE, 11,
					ArmorType.HELMET, 5,
					ArmorType.BODY, 14
			),
			15,
			Holder.direct(SoundEvents.EMPTY),
			6.0F,
			0.15F,
			ItemTags.REPAIRS_IRON_ARMOR,
			ResourceKey.create(EquipmentAssets.ROOT_ID, FlightArmorMod.id("flight"))
	);

	private ModArmorMaterials() {
	}

	public static void initialize() {
		// 触发静态初始化。
	}
}
