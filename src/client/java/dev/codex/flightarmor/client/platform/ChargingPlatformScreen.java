package dev.codex.flightarmor.client.platform;

import dev.codex.flightarmor.platform.ChargingPlatformMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 不依赖贴图的清晰四槽平台界面；槽位仍由原版容器协议绘制和同步。 */
public final class ChargingPlatformScreen extends AbstractContainerScreen<ChargingPlatformMenu> {
	private static final String[] SLOT_LABELS = {"战靴", "护腿", "胸甲", "头盔"};

	public ChargingPlatformScreen(ChargingPlatformMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 158);
		inventoryLabelY = 65;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		int left = leftPos;
		int top = topPos;
		graphics.fill(left, top, left + imageWidth, top + imageHeight, 0xEE101820);
		graphics.fill(left + 4, top + 4, left + imageWidth - 4, top + 61, 0xFF172936);
		graphics.outline(left, top, imageWidth, imageHeight, 0xFF55E8FF);
		for (int index = 0; index < 4; index++) {
			int x = left + 52 + index * 24;
			int y = top + 30;
			graphics.fill(x, y, x + 18, y + 18, 0xFF081014);
			graphics.outline(x, y, 18, 18, index == 0 ? 0xFFB9FFFF : 0xFF5B7C8A);
			graphics.centeredText(font, SLOT_LABELS[index], x + 9, y + 22, 0xFFC8E8EF);
		}
		// 玩家背包与快捷栏底板。
		graphics.fill(left + 7, top + 75, left + 169, top + 153, 0xFF0B1117);
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 9; column++) {
				int y = top + (row == 3 ? 133 : 75 + row * 18);
				graphics.outline(left + 7 + column * 18, y, 18, 18, 0xFF34434B);
			}
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.text(font, title, 8, 7, 0xFFE8FBFF, false);
		graphics.text(font, "放齐同型号四件后自动认主并接入核心网络", 8, 17, 0xFF79C9D8, false);
		graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFFB7CDD4, false);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
