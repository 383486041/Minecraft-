package dev.codex.flightarmor.client.core;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.configuration.ArmorConfigurationData;
import dev.codex.flightarmor.configuration.SentinelArmorMode;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorCoreSnapshotPayload;
import dev.codex.flightarmor.platform.ArmorIdentity;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Compact read-only list of the armor suits known by the equipped navigation core.
 *
 * <p>This deliberately reuses the same authoritative snapshot as the core panel. It does not
 * maintain a second client-side armor registry and never exposes buttons or coordinates.</p>
 */
public final class ArmorCoreHud {
	private static final int PANEL_X = 10;
	private static final int PANEL_Y = 10;
	private static final int PANEL_WIDTH = 184;
	private static final int HEADER_HEIGHT = 18;
	private static final int ROW_HEIGHT = 12;
	private static final int MAX_VISIBLE_ROWS = 4;
	private static boolean initialized;

	private ArmorCoreHud() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		HudElementRegistry.addLast(FlightArmorMod.id("core_hud"), (graphics, deltaTracker) -> render(graphics));
	}

	private static void render(GuiGraphicsExtractor graphics) {
		Minecraft client = Minecraft.getInstance();
		if (!FlightArmorClientSettingsStore.get().hudEnabled()
				|| client.player == null || client.gui.screen() != null) return;
		ArmorCoreSnapshotPayload snapshot = ArmorCoreClient.snapshot();
		if (!snapshot.coreEquipped()) {
			renderOfflineNotice(graphics, client);
			return;
		}

		List<ArmorCoreSnapshotPayload.Entry> entries = ordered(snapshot);
		if (entries.isEmpty()) return;
		int visibleRows = Math.min(MAX_VISIBLE_ROWS, entries.size());
		int hiddenRows = entries.size() - visibleRows;
		int panelHeight = HEADER_HEIGHT + visibleRows * ROW_HEIGHT
				+ (hiddenRows > 0 ? ROW_HEIGHT : 0) + 4;
		int panelRight = PANEL_X + PANEL_WIDTH;
		int panelBottom = PANEL_Y + panelHeight;

		graphics.fill(PANEL_X, PANEL_Y, panelRight, panelBottom, 0xB5071018);
		graphics.fill(PANEL_X, PANEL_Y, PANEL_X + 2, panelBottom, 0xFF38E8FF);
		graphics.fill(PANEL_X + 3, PANEL_Y + 1, panelRight - 1, PANEL_Y + 2, 0x6638E8FF);
		Component title = Component.translatable(
				"hud.flight_armor.armor_list.title", entries.size());
		graphics.text(client.font, title, PANEL_X + 7, PANEL_Y + 6,
				0xFFE6FAFF, true);

		for (int index = 0; index < visibleRows; index++) {
			ArmorCoreSnapshotPayload.Entry entry = entries.get(index);
			boolean primary = entry.suitId().equals(snapshot.primarySuitId());
			int rowTop = PANEL_Y + HEADER_HEIGHT + index * ROW_HEIGHT;
			int accent = 0xFF000000 | entry.variant().primaryLightRgb();
			if ((index & 1) != 0) {
				graphics.fill(PANEL_X + 3, rowTop, panelRight - 2, rowTop + ROW_HEIGHT,
						0x35182A33);
			}
			graphics.fill(PANEL_X + 4, rowTop + 2, PANEL_X + 6, rowTop + 10, accent);

			Component status = statusLabel(client, entry);
			int statusWidth = client.font.width(status);
			int nameWidth = Math.max(24, PANEL_WIDTH - statusWidth - 31);
			String marker = primary ? "◆ " : "◇ ";
			String name = client.font.plainSubstrByWidth(
					marker + entry.displayName(), nameWidth);
			graphics.text(client.font, name, PANEL_X + 9, rowTop + 2, accent, true);
			graphics.text(client.font, status,
					panelRight - 7 - statusWidth, rowTop + 2,
					statusColor(entry.status()), true);
		}

		if (hiddenRows > 0) {
			Component more = Component.translatable(
					"hud.flight_armor.armor_list.more", hiddenRows);
			graphics.text(client.font, more, PANEL_X + 9,
					PANEL_Y + HEADER_HEIGHT + visibleRows * ROW_HEIGHT + 2,
					0xFFAFC5CC, true);
		}
	}

	/**
	 * 核心离线时 HUD 不再整块消失——那正是「核心没有存在感」的来源。只要还穿着凌空战甲，
	 * 就在原位置留一条状态条，说明形态切换与遥控已被核心离线锁定。
	 */
	private static void renderOfflineNotice(GuiGraphicsExtractor graphics, Minecraft client) {
		if (!ModItems.hasFlightGear(client.player)) return;
		Component line = Component.translatable("hud.flight_armor.core_offline");
		int width = Math.min(PANEL_WIDTH, client.font.width(line) + 12);
		int bottom = PANEL_Y + 15;
		graphics.fill(PANEL_X, PANEL_Y, PANEL_X + width, bottom, 0xB5350C10);
		graphics.fill(PANEL_X, PANEL_Y, PANEL_X + 2, bottom, 0xFFFF7A8A);
		graphics.text(client.font,
				client.font.plainSubstrByWidth(line.getString(), Math.max(0, width - 12)),
				PANEL_X + 7, PANEL_Y + 4, 0xFFFFC7CF, true);
	}

	private static List<ArmorCoreSnapshotPayload.Entry> ordered(
			ArmorCoreSnapshotPayload snapshot) {
		UUID primary = snapshot.primarySuitId();
		List<ArmorCoreSnapshotPayload.Entry> entries =
				new ArrayList<>(snapshot.entries());
		// Lost bookkeeping records remain available in the full core panel for recovery/forget,
		// but never pollute the always-on operational HUD.
		entries.removeIf(entry -> entry.status() == ArmorCoreSnapshotPayload.Status.MISSING);
		entries.sort(Comparator
				.comparing((ArmorCoreSnapshotPayload.Entry entry) ->
						primary == null || !primary.equals(entry.suitId()))
				.thenComparingInt(entry -> statusOrder(entry.status()))
				.thenComparing(ArmorCoreSnapshotPayload.Entry::displayName,
						String.CASE_INSENSITIVE_ORDER));
		return entries;
	}

	private static Component statusLabel(ArmorCoreSnapshotPayload.Status status) {
		return Component.translatable("hud.flight_armor.armor_list.status."
				+ status.name().toLowerCase(Locale.ROOT));
	}

	private static Component statusLabel(Minecraft client,
			ArmorCoreSnapshotPayload.Entry entry) {
		if (entry.status() == ArmorCoreSnapshotPayload.Status.WORN
				&& client.player != null) {
			ItemStack chest = client.player.getItemBySlot(EquipmentSlot.CHEST);
			ArmorIdentity.Identity identity = ArmorIdentity.read(chest).orElse(null);
			ArmorConfigurationData.Snapshot configuration =
					ArmorConfigurationData.equippedSentinel(client.player).orElse(null);
			if (identity != null && configuration != null
					&& identity.matches(client.player.getUUID(), entry.suitId(),
							EquipmentSlot.CHEST)) {
				String mode = configuration.mode() == SentinelArmorMode.BREACH_MELEE
						? "worn_breach" : "worn_overdrive";
				return Component.translatable(
						"hud.flight_armor.armor_list.status." + mode);
			}
		}
		return statusLabel(entry.status());
	}

	private static int statusOrder(ArmorCoreSnapshotPayload.Status status) {
		return switch (status) {
			case WORN -> 0;
			case CRUISING -> 1;
			case ESCORTING -> 2;
			case RETURNING, TRANSITION -> 3;
			case READY -> 4;
			case LOOSE -> 5;
			case OFFLINE -> 6;
			case INCOMPLETE, INVALID, CONFLICT, MISSING -> 7;
		};
	}

	private static int statusColor(ArmorCoreSnapshotPayload.Status status) {
		return switch (status) {
			case WORN -> 0xFF71F6A1;
			case CRUISING -> 0xFF38E8FF;
			case ESCORTING -> 0xFFCF82FF;
			case RETURNING, TRANSITION -> 0xFFFFC94D;
			case READY -> 0xFF8BC7FF;
			case LOOSE, INCOMPLETE -> 0xFFFFA85C;
			case OFFLINE -> 0xFF92A3AA;
			case INVALID, CONFLICT, MISSING -> 0xFFFF6678;
		};
	}
}
