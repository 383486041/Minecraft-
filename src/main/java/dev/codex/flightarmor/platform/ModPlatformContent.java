package dev.codex.flightarmor.platform;

import dev.codex.flightarmor.FlightArmorMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.flag.FeatureFlags;

/** 充能平台的集中注册点；主入口只需调用一次 {@link #initialize()}。 */
public final class ModPlatformContent {
	public static final ChargingPlatformBlock CHARGING_PLATFORM = new ChargingPlatformBlock(
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(4.5F, 12.0F)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops()
					.noOcclusion()
					.lightLevel(state -> 8)
					.pushReaction(PushReaction.BLOCK));
	public static final BlockItem CHARGING_PLATFORM_ITEM = new BlockItem(
			CHARGING_PLATFORM, new Item.Properties().fireResistant());
	public static final BlockEntityType<ChargingPlatformBlockEntity> CHARGING_PLATFORM_BLOCK_ENTITY =
			BlockEntityType.Builder.of(ChargingPlatformBlockEntity::new, CHARGING_PLATFORM).build(null);
	public static final MenuType<ChargingPlatformMenu> CHARGING_PLATFORM_MENU =
			new MenuType<>(ChargingPlatformMenu::new, FeatureFlags.DEFAULT_FLAGS);

	private static boolean initialized;

	private ModPlatformContent() {
	}

	public static synchronized void initialize() {
		if (initialized) return;
		Registry.register(BuiltInRegistries.BLOCK, FlightArmorMod.id("charging_platform"), CHARGING_PLATFORM);
		Registry.register(BuiltInRegistries.ITEM, FlightArmorMod.id("charging_platform"), CHARGING_PLATFORM_ITEM);
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, FlightArmorMod.id("charging_platform"),
				CHARGING_PLATFORM_BLOCK_ENTITY);
		Registry.register(BuiltInRegistries.MENU, FlightArmorMod.id("charging_platform"),
				CHARGING_PLATFORM_MENU);
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
				.register(entries -> entries.accept(CHARGING_PLATFORM_ITEM));
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.COMBAT)
				.register(entries -> entries.accept(CHARGING_PLATFORM_ITEM));
		initialized = true;
	}
}
