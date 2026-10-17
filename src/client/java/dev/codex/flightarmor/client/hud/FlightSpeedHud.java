package dev.codex.flightarmor.client.hud;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.client.FlightArmorClient;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.client.telemetry.FlightSpeedTelemetry;
import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/**
 * 动力飞行空速显示。数值来自统一的真实位移遥测，因此碰撞、制动和加速会与声音、灯光一致。
 */
public final class FlightSpeedHud {
	private static final int MIN_PANEL_WIDTH = 142;
	private static final int PANEL_HEIGHT = 52;
	private static boolean initialized;

	private FlightSpeedHud() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		FlightSpeedTelemetry.initialize();
		HudElementRegistry.addLast(FlightArmorMod.id("flight_speed"), (graphics, deltaTracker) ->
				render(graphics, deltaTracker.getGameTimeDeltaPartialTick(false)));
	}

	private static void render(GuiGraphicsExtractor graphics, float partialTick) {
		if (!FlightArmorClientSettingsStore.get().hudEnabled()) return;
		Minecraft client = Minecraft.getInstance();
		Player player = client.player;
		if (player == null || !isPoweredFlightVisible(player)) return;

		double interpolatedBlocksPerTick =
				FlightSpeedTelemetry.interpolatedBlocksPerTick(player, partialTick);
		double metersPerSecond = FlightPhysics.metersPerSecond(interpolatedBlocksPerTick);
		double kilometersPerHour = FlightPhysics.kilometersPerHour(interpolatedBlocksPerTick);
		double mach = FlightSpeedTelemetry.mediumMach(player, partialTick);
		SpeedBand band = SpeedBand.forMach(mach);

		ArmorVariant variant = equippedFlightVariant(player);
		Component title = Component.translatable("item.flight_armor.variant." + variant.serializedName(),
				Component.translatable("hud.flight_armor.speed.title"));
		Component status = Component.translatable(band.translationKey);
		Component units = Component.translatable("hud.flight_armor.speed.units",
				format(metersPerSecond, 1), format(kilometersPerHour, 0));
		Component machLine = Component.translatable("hud.flight_armor.speed.mach", format(mach, 2));
		int contentWidth = Math.max(client.font.width(units),
				client.font.width(title) + 8 + client.font.width(status));
		contentWidth = Math.max(contentWidth, client.font.width(machLine));
		int panelWidth = Math.max(MIN_PANEL_WIDTH, contentWidth + 12);
		int x = 10;
		// Center-left avoids the top-right combat panel and the bottom-center action bar/hotbar.
		int y = Math.max(24, (graphics.guiHeight() - PANEL_HEIGHT) / 2);

		graphics.fill(x, y, x + panelWidth, y + PANEL_HEIGHT, 0xB4071018);
		graphics.fill(x, y, x + 2, y + PANEL_HEIGHT, band.color);
		graphics.fill(x + 3, y + 1, x + panelWidth - 1, y + 2, 0x5538E8FF);
		graphics.text(client.font, title, x + 7, y + 6,
				0xFF000000 | variant.primaryLightRgb(), true);
		graphics.text(client.font, status,
				x + panelWidth - 6 - client.font.width(status), y + 6, band.color, true);
		graphics.text(client.font, units, x + 7, y + 19, 0xFFF4FCFF, true);
		graphics.text(client.font, machLine, x + 7, y + 31, band.color, true);

		int barLeft = x + 7;
		int barRight = x + panelWidth - 7;
		int barTop = y + 44;
		graphics.fill(barLeft, barTop, barRight, barTop + 4, 0xCC102630);
		// The scale ends at Mach 1.5; the fixed white notch marks Mach 1 exactly.
		float barProgress = Mth.clamp((float) (mach / 1.5D), 0.0F, 1.0F);
		graphics.fill(barLeft, barTop, barLeft + Math.round((barRight - barLeft) * barProgress),
				barTop + 4, band.color);
		int machOneX = barLeft + Math.round((barRight - barLeft) / 1.5F);
		graphics.fill(machOneX, barTop - 1, machOneX + 1, barTop + 5, 0xFFFFFFFF);
	}

	private static boolean isPoweredFlightVisible(Player player) {
		return ModItems.hasFlightGear(player) && FlightArmorClient.flightAudioSnapshot(player).active();
	}

	private static ArmorVariant equippedFlightVariant(Player player) {
		return ModItems.fullSetVariant(player)
				.orElseGet(() -> ModItems.bootsVariant(player).orElse(ArmorVariant.DEFAULT));
	}

	private static String format(double value, int decimals) {
		return String.format(Locale.ROOT, "%1$." + decimals + "f", value);
	}

	private enum SpeedBand {
		SUBSONIC("hud.flight_armor.speed.subsonic", 0xFF65EEFF, 0.0D, 0.85D),
		TRANSONIC("hud.flight_armor.speed.transonic", 0xFFFFD968, 0.85D, 1.05D),
		SUPERSONIC("hud.flight_armor.speed.supersonic", 0xFFFF7B66, 1.05D, Double.MAX_VALUE);

		private final String translationKey;
		private final int color;
		private final double minMach;
		private final double maxMach;

		SpeedBand(String translationKey, int color, double minMach, double maxMach) {
			this.translationKey = translationKey;
			this.color = color;
			this.minMach = minMach;
			this.maxMach = maxMach;
		}

		private static SpeedBand forMach(double mach) {
			for (SpeedBand band : values()) {
				if (mach >= band.minMach && mach < band.maxMach) {
					return band;
				}
			}
			return SUBSONIC;
		}
	}
}
