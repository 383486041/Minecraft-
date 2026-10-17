package dev.codex.flightarmor.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import dev.codex.flightarmor.FlightArmorMod;

public final class FlightArmorKeyMappings {
	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(FlightArmorMod.id("flight_armor"));

	private FlightArmorKeyMappings() {
	}

	public static KeyMapping register(String name, int defaultKeyCode) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, defaultKeyCode, CATEGORY));
	}
}
