package dev.codex.flightarmor.item;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.ArmorType;

import java.util.Optional;

public final class ModItems {
	public static final ResourceKey<Item> FLIGHT_HELMET_KEY = itemKey("flight_helmet");
	public static final ResourceKey<Item> FLIGHT_CHESTPLATE_KEY = itemKey("flight_chestplate");
	public static final ResourceKey<Item> FLIGHT_LEGGINGS_KEY = itemKey("flight_leggings");
	public static final ResourceKey<Item> FLIGHT_BOOTS_KEY = itemKey("flight_boots");
	public static final ResourceKey<Item> ARMOR_NAVIGATION_CORE_KEY = itemKey("armor_navigation_core");

	public static final FlightArmorItem FLIGHT_HELMET = register(FLIGHT_HELMET_KEY, new FlightArmorItem(FLIGHT_HELMET_KEY, ArmorType.HELMET));
	public static final FlightArmorItem FLIGHT_CHESTPLATE = register(FLIGHT_CHESTPLATE_KEY, new FlightArmorItem(FLIGHT_CHESTPLATE_KEY, ArmorType.CHESTPLATE));
	public static final FlightArmorItem FLIGHT_LEGGINGS = register(FLIGHT_LEGGINGS_KEY, new FlightArmorItem(FLIGHT_LEGGINGS_KEY, ArmorType.LEGGINGS));
	public static final FlightArmorItem FLIGHT_BOOTS = register(FLIGHT_BOOTS_KEY, new FlightArmorItem(FLIGHT_BOOTS_KEY, ArmorType.BOOTS));
	public static final ArmorNavigationCoreItem ARMOR_NAVIGATION_CORE = register(ARMOR_NAVIGATION_CORE_KEY,
			new ArmorNavigationCoreItem(new Item.Properties().setId(ARMOR_NAVIGATION_CORE_KEY).stacksTo(1)));

	private ModItems() {
	}

	public static void initialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(entries -> {
			entries.accept(ARMOR_NAVIGATION_CORE);
			for (ArmorVariant variant : ArmorVariant.values()) {
				entries.accept(creativeVariantStack(FLIGHT_HELMET, variant));
				entries.accept(creativeVariantStack(FLIGHT_CHESTPLATE, variant));
				entries.accept(creativeVariantStack(FLIGHT_LEGGINGS, variant));
				entries.accept(creativeVariantStack(FLIGHT_BOOTS, variant));
			}
		});
	}

	private static ItemStack creativeVariantStack(Item item, ArmorVariant variant) {
		ItemStack stack = new ItemStack(item);
		ArmorVariantData.set(stack, variant);
		return stack;
	}

	public static boolean hasFullSet(LivingEntity entity) {
		return entity.getItemBySlot(EquipmentSlot.HEAD).is(FLIGHT_HELMET)
				&& entity.getItemBySlot(EquipmentSlot.CHEST).is(FLIGHT_CHESTPLATE)
				&& entity.getItemBySlot(EquipmentSlot.LEGS).is(FLIGHT_LEGGINGS)
				&& entity.getItemBySlot(EquipmentSlot.FEET).is(FLIGHT_BOOTS);
	}

	/**
	 * 仅当四件都是本模组战甲且型号完全一致时返回套装型号。
	 * 混装仍可依靠战靴飞行，但不得获得完整型号的动力上限、防御或抗击退被动。
	 */
	public static Optional<ArmorVariant> fullSetVariant(LivingEntity entity) {
		if (!hasFullSet(entity)) return Optional.empty();
		ArmorVariant expected = ArmorVariantData.get(entity.getItemBySlot(EquipmentSlot.HEAD));
		return ArmorVariantData.get(entity.getItemBySlot(EquipmentSlot.CHEST)) == expected
				&& ArmorVariantData.get(entity.getItemBySlot(EquipmentSlot.LEGS)) == expected
				&& ArmorVariantData.get(entity.getItemBySlot(EquipmentSlot.FEET)) == expected
				? Optional.of(expected) : Optional.empty();
	}

	/** 单穿战靴及混装时的动力型号只取战靴自身数据。 */
	public static Optional<ArmorVariant> bootsVariant(LivingEntity entity) {
		ItemStack boots = entity.getItemBySlot(EquipmentSlot.FEET);
		return boots.is(FLIGHT_BOOTS)
				? Optional.of(ArmorVariantData.get(boots))
				: Optional.empty();
	}

	/** 返回指定原版装甲槽严格对应的凌空战甲物品。 */
	public static Item flightArmorForSlot(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> FLIGHT_HELMET;
			case CHEST -> FLIGHT_CHESTPLATE;
			case LEGS -> FLIGHT_LEGGINGS;
			case FEET -> FLIGHT_BOOTS;
			default -> throw new IllegalArgumentException("不是装甲槽: " + slot);
		};
	}

	public static boolean isExactFlightArmor(ItemStack stack, EquipmentSlot slot) {
		return stack.is(flightArmorForSlot(slot));
	}

	/** 完整套装或单独装备凌空战靴，都能启动动力飞行。 */
	public static boolean hasFlightGear(LivingEntity entity) {
		return hasFullSet(entity) || entity.getItemBySlot(EquipmentSlot.FEET).is(FLIGHT_BOOTS);
	}

	public static ResourceKey<Item> itemKey(String path) {
		return ResourceKey.create(Registries.ITEM, FlightArmorMod.id(path));
	}

	private static <T extends Item> T register(ResourceKey<Item> key, T item) {
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	private static <T extends Item> T register(String path, T item) {
		return Registry.register(BuiltInRegistries.ITEM, itemKey(path), item);
	}
}
