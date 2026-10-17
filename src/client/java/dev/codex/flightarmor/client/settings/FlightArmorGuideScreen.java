package dev.codex.flightarmor.client.settings;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Five-page, client-only visual guide for the armor's current controls and workflows. */
public final class FlightArmorGuideScreen extends Screen {
	private static final int MAX_PANEL_WIDTH = 448;
	private static final int MAX_PANEL_HEIGHT = 244;
	private static final int PAGE_COUNT = Page.values().length;
	private final Screen parent;
	private int pageIndex;
	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private Button previousButton;
	private Button nextButton;

	public FlightArmorGuideScreen(Screen parent) {
		super(Component.translatable("screen.flight_armor.guide.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(300, width - 20));
		panelHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(224, height - 12));
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;

		int tabsLeft = left + 10;
		int tabsWidth = panelWidth - 20;
		int gap = 3;
		int tabWidth = (tabsWidth - gap * (PAGE_COUNT - 1)) / PAGE_COUNT;
		for (Page page : Page.values()) {
			int x = tabsLeft + page.ordinal() * (tabWidth + gap);
			int widthForTab = page == Page.WEAPONS
					? tabsLeft + tabsWidth - x : tabWidth;
			addRenderableWidget(new GuideTabButton(x, top + 29, widthForTab, 23, page));
		}

		int footerY = top + panelHeight - 27;
		previousButton = addRenderableWidget(Button.builder(
				Component.translatable("screen.flight_armor.guide.previous"),
				button -> selectPage(pageIndex - 1)).bounds(left + 12, footerY, 76, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.flight_armor.guide.back"),
				button -> onClose()).bounds(left + panelWidth / 2 - 42, footerY, 84, 20).build());
		nextButton = addRenderableWidget(Button.builder(
				Component.translatable("screen.flight_armor.guide.next"),
				button -> selectPage(pageIndex + 1))
				.bounds(left + panelWidth - 88, footerY, 76, 20).build());
		updateNavigation();
	}

	private void selectPage(int requestedIndex) {
		pageIndex = Math.max(0, Math.min(PAGE_COUNT - 1, requestedIndex));
		updateNavigation();
	}

	private void updateNavigation() {
		if (previousButton != null) previousButton.active = pageIndex > 0;
		if (nextButton != null) nextButton.active = pageIndex + 1 < PAGE_COUNT;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		extractBackground(graphics, mouseX, mouseY, partialTick);
		Page page = Page.values()[pageIndex];
		int accent = page.accent;
		graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF4071017);
		graphics.fill(left + 1, top + 1, left + panelWidth - 1, top + 26, 0xFF102630);
		graphics.outline(left, top, panelWidth, panelHeight, accent);
		graphics.outline(left + 3, top + 3, panelWidth - 6, panelHeight - 6, 0xFF294852);
		graphics.text(font, title, left + 10, top + 9, 0xFFEAFBFF, false);
		Component pageCounter = Component.translatable("screen.flight_armor.guide.page_counter",
				pageIndex + 1, PAGE_COUNT);
		graphics.text(font, pageCounter,
				left + panelWidth - 10 - font.width(pageCounter), top + 9, accent, false);

		renderPage(graphics, page);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void renderPage(GuiGraphicsExtractor graphics, Page page) {
		int footerY = top + panelHeight - 27;
		int illustrationLeft = left + 12;
		int illustrationRight = left + 80;
		int textX = left + 92;
		int textWidth = Math.max(150, panelWidth - 104);
		int stepStartY = top + 90;
		int stepGap = Math.max(31, (footerY - 25 - stepStartY) / 3);

		graphics.fill(illustrationLeft, top + 58, illustrationRight, footerY - 8, 0xD90B1C24);
		graphics.outline(illustrationLeft, top + 58,
				illustrationRight - illustrationLeft, footerY - top - 66, 0xFF31515D);
		drawPageGlyph(graphics, page, illustrationLeft + 22, top + 64, 24, page.accent);

		graphics.text(font, Component.translatable(page.key("title")),
				textX, top + 60, page.accent, false);
		drawWrapped(graphics, Component.translatable(page.key("subtitle")),
				textX, top + 72, textWidth, 1, 0xFF91B2BC);

		int railX = illustrationLeft + 34;
		int firstCenterY = stepStartY + 7;
		int lastCenterY = stepStartY + stepGap * 2 + 7;
		graphics.fill(railX - 1, firstCenterY, railX + 1, lastCenterY, 0xFF31515D);
		for (int step = 0; step < 3; step++) {
			int y = stepStartY + step * stepGap;
			renderStepNode(graphics, railX, y, step + 1, page.accent);
			graphics.text(font, Component.translatable(page.key("step." + (step + 1) + ".title")),
					textX, y, 0xFFEAFBFF, false);
			drawWrapped(graphics,
					Component.translatable(page.key("step." + (step + 1) + ".body")),
					textX, y + 11, textWidth, 2, 0xFF9CBBC4);
		}

		graphics.fill(textX, footerY - 18, left + panelWidth - 12, footerY - 17, page.accent);
		drawWrapped(graphics, Component.translatable(page.key("tip")),
				textX, footerY - 14, textWidth, 1, 0xFFFFD77A);
	}

	private void drawWrapped(GuiGraphicsExtractor graphics, Component text, int x, int y,
			int width, int maximumLines, int color) {
		List<FormattedCharSequence> lines = font.split(text, width);
		for (int line = 0; line < Math.min(maximumLines, lines.size()); line++) {
			graphics.text(font, lines.get(line), x, y + line * 9, color, false);
		}
	}

	private void renderStepNode(GuiGraphicsExtractor graphics, int centerX, int y, int step, int accent) {
		graphics.fill(centerX - 8, y - 1, centerX + 9, y + 16, 0xFF0B1820);
		graphics.outline(centerX - 8, y - 1, 17, 17, accent);
		String number = Integer.toString(step);
		graphics.text(font, number, centerX - font.width(number) / 2, y + 3, 0xFFFFFFFF, false);
		if (step < 3) {
			graphics.fill(centerX - 2, y + 19, centerX + 3, y + 21, accent);
			graphics.fill(centerX - 1, y + 21, centerX + 2, y + 23, accent);
		}
	}

	private static void drawPageGlyph(GuiGraphicsExtractor graphics, Page page,
			int x, int y, int size, int color) {
		int dark = 0xFF17313C;
		switch (page) {
			case PLATFORM -> {
				graphics.fill(x - 9, y + 8, x + 10, y + 13, dark);
				graphics.outline(x - 9, y + 8, 19, 5, color);
				graphics.fill(x - 4, y + 3, x + 5, y + 8, color);
			}
			case SUMMON -> {
				graphics.fill(x - 9, y + 2, x - 3, y + 8, color);
				graphics.fill(x - 6, y - 1, x, y + 11, color);
				graphics.fill(x + 2, y + 4, x + 9, y + 6, color);
				graphics.fill(x + 7, y + 1, x + 10, y + 9, color);
			}
			case FLIGHT -> {
				graphics.fill(x - 2, y - 1, x + 3, y + 14, color);
				graphics.fill(x - 10, y + 3, x - 2, y + 8, color);
				graphics.fill(x + 3, y + 3, x + 11, y + 8, color);
				graphics.fill(x - 1, y + 14, x + 2, y + 18, 0xFFFFFFFF);
			}
			case ESCORT -> {
				graphics.fill(x - 3, y + 3, x + 4, y + 10, color);
				graphics.fill(x - 10, y + 9, x - 5, y + 14, dark);
				graphics.outline(x - 10, y + 9, 5, 5, color);
				graphics.fill(x + 6, y + 9, x + 11, y + 14, dark);
				graphics.outline(x + 6, y + 9, 5, 5, color);
			}
			case WEAPONS -> {
				graphics.fill(x - 9, y + 3, x - 2, y + 10, color);
				graphics.fill(x, y + 5, x + 10, y + 8, 0xFFFFFFFF);
				graphics.fill(x + 10, y + 3, x + 12, y + 10, color);
			}
		}
	}

	@Override
	public void onClose() {
		if (minecraft != null) minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private enum Page {
		PLATFORM("platform", 0xFF65EEFF),
		SUMMON("summon", 0xFFFFD968),
		FLIGHT("flight", 0xFF7FDFFF),
		ESCORT("escort", 0xFF9A7CFF),
		WEAPONS("weapons", 0xFFFF6F7D);

		private final String id;
		private final int accent;

		Page(String id, int accent) {
			this.id = id;
			this.accent = accent;
		}

		private String tabKey() {
			return "screen.flight_armor.guide.tab." + id;
		}

		private String key(String suffix) {
			return "screen.flight_armor.guide.page." + id + "." + suffix;
		}
	}

	private final class GuideTabButton extends AbstractButton {
		private final Page page;

		private GuideTabButton(int x, int y, int width, int height, Page page) {
			super(x, y, width, height, Component.translatable(page.tabKey()));
			this.page = page;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			selectPage(page.ordinal());
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			boolean selected = pageIndex == page.ordinal();
			boolean compact = getWidth() < 64;
			int fill = selected ? 0xFF173B49 : isHoveredOrFocused() ? 0xFF172A34 : 0xEE101F27;
			int border = selected || isHoveredOrFocused() ? page.accent : 0xFF31515D;
			graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
			graphics.outline(getX(), getY(), getWidth(), getHeight(), border);
			if (!compact) drawPageGlyph(graphics, page, getX() + 9, getY() + 5, 10, page.accent);
			int labelX = getX() + (compact ? 4 : 18);
			String label = font.plainSubstrByWidth(getMessage().getString(),
					getWidth() - (compact ? 8 : 20));
			graphics.text(font, label, labelX, getY() + 7,
					selected ? 0xFFFFFFFF : 0xFFB7D0D7, false);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
