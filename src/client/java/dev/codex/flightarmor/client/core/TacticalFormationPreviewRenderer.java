package dev.codex.flightarmor.client.core;

import dev.codex.flightarmor.network.ArmorCoreSnapshotPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Static, snapshot-only tactical formation preview used by the armor-core command screen.
 * It never predicts server state and deliberately contains no clock-driven rotation or flashing.
 */
public final class TacticalFormationPreviewRenderer {
	private static final int MAX_ARMOR_NODES = 6;
	private static final int BACKGROUND = 0xF00A1820;
	private static final int GRID = 0x32265A68;
	private static final int OWNER = 0xFF65EEFF;
	private static final int PRIMARY = 0xFFFFD968;
	private static final int SELECTED = 0xFFE8FBFF;
	private static final int PLATFORM = 0xFF50737E;
	private static final int MUTED = 0xFF39535C;

	private static final Slot[] SLOTS = {
			new Slot(0.50F, 0.27F, 3),
			new Slot(0.27F, 0.51F, 3),
			new Slot(0.73F, 0.51F, 3),
			new Slot(0.12F, 0.30F, 2),
			new Slot(0.88F, 0.30F, 2),
			new Slot(0.50F, 0.61F, 2)
	};

	private TacticalFormationPreviewRenderer() {
	}

	public static void render(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
			ArmorCoreSnapshotPayload snapshot, UUID selectedSuit) {
		if (graphics == null || font == null || snapshot == null || width < 72 || height < 28) return;
		graphics.enableScissor(x, y, x + width, y + height);
		try {
			graphics.fill(x, y, x + width, y + height, BACKGROUND);
			graphics.outline(x, y, width, height, 0xFF2F5967);
			drawDepthGrid(graphics, x, y, width, height);

			int ownerX = x + width / 2;
			int ownerY = y + height - 7;
			drawOwner(graphics, ownerX, ownerY);
			List<ArmorCoreSnapshotPayload.Entry> visible = visibleEntries(snapshot);
			for (int index = 0; index < visible.size(); index++) {
				ArmorCoreSnapshotPayload.Entry entry = visible.get(index);
				Slot slot = SLOTS[index];
				int suitX = clamp(x + 7, Math.round(x + slot.x() * width), x + width - 8);
				int suitY = clamp(y + 6, Math.round(y + slot.y() * height), y + height - 10);
				int outward = Integer.compare(suitX, ownerX);
				if (outward == 0) outward = (index & 1) == 0 ? -1 : 1;
				int platformX = clamp(x + 5, suitX + outward * (slot.radius() + 3), x + width - 6);
				int platformY = clamp(y + 3, suitY - slot.radius() - 4, y + height - 14);
				boolean primary = entry.suitId().equals(snapshot.primarySuitId());
				boolean selected = entry.suitId().equals(selectedSuit);
				drawRoute(graphics, entry.status(), entry.hasHome(), platformX, platformY,
						suitX, suitY, ownerX, ownerY, statusColor(entry.status()));
				if (entry.hasHome()) {
					drawPlatform(graphics, platformX, platformY, selected, entry.status());
				}
				drawArmor(graphics, suitX, suitY, slot.radius(), entry, primary, selected);
			}
			int hidden = snapshot.entries().size() - visible.size();
			if (hidden > 0) {
				String overflow = "+" + hidden;
				graphics.text(font, overflow, x + width - font.width(overflow) - 4, y + 3,
						0xFF789EAA, false);
			}
		} finally {
			graphics.disableScissor();
		}
	}

	private static List<ArmorCoreSnapshotPayload.Entry> visibleEntries(ArmorCoreSnapshotPayload snapshot) {
		List<ArmorCoreSnapshotPayload.Entry> entries = new ArrayList<>(snapshot.entries());
		entries.sort(Comparator
				.comparing((ArmorCoreSnapshotPayload.Entry entry) ->
						!entry.suitId().equals(snapshot.primarySuitId()))
				.thenComparing(ArmorCoreSnapshotPayload.Entry::displayName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(entry -> entry.suitId().toString()));
		return entries.size() <= MAX_ARMOR_NODES
				? entries : new ArrayList<>(entries.subList(0, MAX_ARMOR_NODES));
	}

	private static void drawDepthGrid(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		int ownerX = x + width / 2;
		int ownerY = y + height - 5;
		drawLine(graphics, ownerX, ownerY, x + 4, y + 4, GRID, true);
		drawLine(graphics, ownerX, ownerY, x + width - 5, y + 4, GRID, true);
		for (int depth = 1; depth <= 3; depth++) {
			int lineY = y + 3 + depth * (height - 9) / 4;
			int inset = (3 - depth) * width / 18;
			graphics.fill(x + inset, lineY, x + width - inset, lineY + 1, GRID);
		}
	}

	private static void drawRoute(GuiGraphicsExtractor graphics, ArmorCoreSnapshotPayload.Status status, boolean hasHome,
			int platformX, int platformY, int suitX, int suitY, int ownerX, int ownerY, int color) {
		boolean invalid = status == ArmorCoreSnapshotPayload.Status.INVALID
				|| status == ArmorCoreSnapshotPayload.Status.CONFLICT
				|| status == ArmorCoreSnapshotPayload.Status.INCOMPLETE
				|| status == ArmorCoreSnapshotPayload.Status.MISSING;
		if (hasHome) {
			drawLine(graphics, platformX, platformY, suitX, suitY,
					invalid ? 0xFF75434A : MUTED, invalid);
		}
		switch (status) {
			case RETURNING -> {
				if (hasHome) {
					drawLine(graphics, suitX, suitY, platformX, platformY, color, false);
					drawRouteMarker(graphics, suitX, suitY, platformX, platformY, color);
				}
			}
			case READY, OFFLINE -> {
				drawLine(graphics, suitX, suitY, ownerX, ownerY, color, false);
				drawRouteMarker(graphics, suitX, suitY, ownerX, ownerY, color);
			}
			case ESCORTING, CRUISING -> drawLine(graphics, ownerX, ownerY, suitX, suitY, color, false);
			case WORN -> drawLine(graphics, suitX, suitY, ownerX, ownerY, OWNER, false);
			case TRANSITION -> {
				drawLine(graphics, suitX, suitY, ownerX, ownerY, color, true);
				drawRouteMarker(graphics, suitX, suitY, ownerX, ownerY, color);
			}
			case INCOMPLETE, INVALID, CONFLICT, MISSING, LOOSE -> drawLine(
					graphics, suitX, suitY, ownerX, ownerY, color, true);
		}
	}

	private static void drawRouteMarker(GuiGraphicsExtractor graphics,
			int fromX, int fromY, int toX, int toY, int color) {
		int markerX = Math.round(fromX + (toX - fromX) * 0.68F);
		int markerY = Math.round(fromY + (toY - fromY) * 0.68F);
		graphics.fill(markerX - 1, markerY - 1, markerX + 2, markerY + 2, color);
	}

	private static void drawOwner(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.fill(x - 2, y - 5, x + 3, y, 0xFF173B49);
		graphics.outline(x - 2, y - 5, 5, 5, OWNER);
		graphics.fill(x - 3, y, x + 4, y + 4, 0xFF173B49);
		graphics.outline(x - 3, y, 7, 4, OWNER);
		graphics.fill(x, y + 1, x + 1, y + 3, 0xFFE8FBFF);
	}

	private static void drawPlatform(GuiGraphicsExtractor graphics, int x, int y, boolean selected,
			ArmorCoreSnapshotPayload.Status status) {
		int color = status == ArmorCoreSnapshotPayload.Status.INVALID
				|| status == ArmorCoreSnapshotPayload.Status.CONFLICT
				|| status == ArmorCoreSnapshotPayload.Status.MISSING ? 0xFF83424C : PLATFORM;
		graphics.fill(x - 3, y, x + 4, y + 2, 0xFF172A31);
		graphics.fill(x - 2, y - 1, x + 3, y, color);
		if (selected) graphics.outline(x - 4, y - 2, 8, 5, SELECTED);
	}

	private static void drawArmor(GuiGraphicsExtractor graphics, int x, int y, int radius,
			ArmorCoreSnapshotPayload.Entry entry, boolean primary, boolean selected) {
		int color = 0xFF000000 | entry.variant().primaryLightRgb();
		int status = statusColor(entry.status());
		graphics.fill(x - radius, y - 1, x + radius + 1, y + 2, 0xFF10242D);
		graphics.fill(x - 1, y - radius, x + 2, y + radius + 1, color);
		graphics.fill(x - radius + 1, y - 2, x + radius, y - 1, status);
		if (primary) graphics.outline(x - radius - 1, y - radius - 1,
				radius * 2 + 3, radius * 2 + 3, PRIMARY);
		if (selected) graphics.outline(x - radius - 2, y - radius - 2,
				radius * 2 + 5, radius * 2 + 5, SELECTED);
	}

	private static int statusColor(ArmorCoreSnapshotPayload.Status status) {
		return switch (status) {
			case READY -> 0xFF6CFF9A;
			case OFFLINE, WORN -> 0xFF7FCBFF;
			case ESCORTING, CRUISING -> 0xFF9A7CFF;
			case RETURNING, TRANSITION -> 0xFFFFD968;
			case INCOMPLETE -> 0xFFFFB85C;
			case INVALID -> 0xFFFF6E6E;
			case CONFLICT -> 0xFFFF5A7A;
			case MISSING -> 0xFFFF7E6B;
			case LOOSE -> 0xFFFFB85C;
		};
	}

	private static void drawLine(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1,
			int color, boolean dashed) {
		int dx = Math.abs(x1 - x0);
		int sx = x0 < x1 ? 1 : -1;
		int dy = -Math.abs(y1 - y0);
		int sy = y0 < y1 ? 1 : -1;
		int error = dx + dy;
		int step = 0;
		while (true) {
			if (!dashed || (step / 2 & 1) == 0) graphics.fill(x0, y0, x0 + 1, y0 + 1, color);
			if (x0 == x1 && y0 == y1) break;
			int twice = error * 2;
			if (twice >= dy) {
				error += dy;
				x0 += sx;
			}
			if (twice <= dx) {
				error += dx;
				y0 += sy;
			}
			step++;
		}
	}

	private static int clamp(int minimum, int value, int maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}

	private record Slot(float x, float y, int radius) {
	}
}
