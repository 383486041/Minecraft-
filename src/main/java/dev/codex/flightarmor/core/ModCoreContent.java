package dev.codex.flightarmor.core;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

public final class ModCoreContent {
	public static final MenuType<ArmorCoreMenu> ARMOR_CORE_MENU =
			new MenuType<>(ArmorCoreMenu::new, FeatureFlags.DEFAULT_FLAGS);
	private static boolean initialized;

	private ModCoreContent() {}

	public static synchronized void initialize() {
		if (initialized) return;
		Registry.register(BuiltInRegistries.MENU, FlightArmorMod.id("armor_core"), ARMOR_CORE_MENU);
		initialized = true;
	}
}
