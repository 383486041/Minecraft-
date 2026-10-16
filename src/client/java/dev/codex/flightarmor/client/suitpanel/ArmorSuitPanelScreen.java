package dev.codex.flightarmor.client.suitpanel;

import dev.codex.flightarmor.network.ArmorSuitPanelActionPayload;
import dev.codex.flightarmor.network.ArmorSuitPanelSnapshotPayload;
import dev.codex.flightarmor.network.ArmorSuitPanelSnapshotPayload.Capability;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Large, server-authoritative status and nearby-control card for one physical suit. */
public final class ArmorSuitPanelScreen extends Screen {
	private static final int PANEL_WIDTH = 520;
	private static final int PANEL_HEIGHT = 352;
	private static final int CYAN = 0xFF65EEFF;
	private static final int TEXT = 0xFFC8E4EB;
	private static final int MUTED = 0xFF88A8B1;
	private static final int GOOD = 0xFF79F4C1;
	private static final int WARNING = 0xFFFFC66C;
	private static final int CARD = 0xE0102028;
	private static final int CARD_BORDER = 0xFF31515D;

	private final ArmorSuitPanelSnapshotPayload snapshot;
	private int left;
	private int top;

	ArmorSuitPanelScreen(ArmorSuitPanelSnapshotPayload snapshot) {
		super(Component.literal("战甲状态与近距控制"));
		this.snapshot = snapshot;
	}

	boolean matchesSession(long sessionId) {
		return snapshot.sessionId() == sessionId;
	}

	@Override
	protected void init() {
		left = (width - PANEL_WIDTH) / 2;
		top = (height - PANEL_HEIGHT) / 2;

		List<ButtonSpec> primaryRow = new ArrayList<>();
		if (snapshot.ownerView()
				&& snapshot.primaryAction() != ArmorSuitPanelSnapshotPayload.PrimaryAction.NONE) {
			String label = snapshot.primaryAction()
					== ArmorSuitPanelSnapshotPayload.PrimaryAction.SWITCH
					? "切换至此战甲"
					: "召回并穿戴";
			primaryRow.add(new ButtonSpec(
					label,
					ArmorSuitPanelActionPayload.Action.PRIMARY,
					snapshot.allows(Capability.PRIMARY)));
		}
		if (snapshot.ownerView()) {
			primaryRow.add(new ButtonSpec(
					"打开统一指令中心",
					ArmorSuitPanelActionPayload.Action.OPEN_COMMAND_CENTER,
					true));
		}
		if (primaryRow.isEmpty()) {
			primaryRow.add(new ButtonSpec(
					snapshot.ownerView() ? "当前状态无可执行的穿戴 / 指令动作" : "只读面板 · 仅主人可控制",
					ArmorSuitPanelActionPayload.Action.REFRESH,
					false));
		}
		addButtonRow(primaryRow, top + 302);

		List<ButtonSpec> utilityRow = new ArrayList<>();
		if (snapshot.ownerView()
				&& snapshot.subjectKind() == ArmorSuitPanelSnapshotPayload.SubjectKind.PLATFORM) {
			utilityRow.add(new ButtonSpec(
					"切换整甲 / 分件陈列",
					ArmorSuitPanelActionPayload.Action.TOGGLE_DISPLAY,
					snapshot.allows(Capability.TOGGLE_DISPLAY)));
		}
		utilityRow.add(new ButtonSpec(
				"刷新",
				ArmorSuitPanelActionPayload.Action.REFRESH,
				snapshot.allows(Capability.REFRESH)));
		addButtonRow(utilityRow, top + 328);

		addRenderableWidget(new PanelButton(
				left + PANEL_WIDTH - 66, top + 328, 56, 20,
				Component.literal("关闭"), this::onClose));
	}

	private void addButtonRow(List<ButtonSpec> specs, int y) {
		if (specs.isEmpty()) return;
		int rightReserve = y == top + 328 ? 72 : 10;
		int available = PANEL_WIDTH - 20 - rightReserve;
		int gap = 6;
		int buttonWidth = (available - gap * Math.max(0, specs.size() - 1)) / specs.size();
		int x = left + 10;
		for (ButtonSpec spec : specs) {
			PanelButton button = addRenderableWidget(new PanelButton(
					x, y, buttonWidth, 20, Component.literal(spec.label()),
					() -> ArmorSuitPanelClient.send(snapshot.sessionId(), spec.action())));
			button.active = spec.enabled();
			x += buttonWidth + gap;
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		/*
		 * This is an in-world tactical overlay, not a pause/menu screen. Screen#renderBackground
		 * enables Minecraft's post-process blur in 1.21, which makes the suit being inspected
		 * impossible to see. A light flat scrim preserves contrast without blurring the world.
		 */
		renderBackground(graphics, mouseX, mouseY, partialTick);
		int accent = 0xFF000000 | snapshot.variant().primaryLightRgb();
		graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xF4091117);
		graphics.fill(left + 1, top + 1, left + PANEL_WIDTH - 1, top + 29, 0xFF102630);
		graphics.fill(left + 1, top + 29, left + PANEL_WIDTH - 1, top + 31, accent);
		graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, accent);
		graphics.renderOutline(left + 3, top + 3, PANEL_WIDTH - 6, PANEL_HEIGHT - 6, 0xFF294852);

		String title = "战甲控制终端  /  " + subjectLabel(snapshot.subjectKind());
		graphics.drawString(font, title, left + 10, top + 10, 0xFFEAFBFF, false);
		String view = snapshot.ownerView() ? "● 主人链路已认证" : "○ 只读观察";
		graphics.drawString(
				font,
				view,
				left + PANEL_WIDTH - 10 - font.width(view),
				top + 10,
				snapshot.ownerView() ? GOOD : WARNING,
				false);

		drawStatusStrip(graphics, accent);
		drawPhysicalCard(graphics);
		drawIntegrityCard(graphics);
		drawStateMatrix(graphics, accent);
		drawCommandCard(graphics, accent);
		drawNotice(graphics);
		renderPanelWidgets(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// Never delegate to Screen here: Minecraft 1.21.1 applies the menu blur there.
		graphics.fill(0, 0, width, height, 0x28000000);
	}

	private void renderPanelWidgets(
			GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		for (GuiEventListener child : children()) {
			if (child instanceof Renderable renderable) {
				renderable.render(graphics, mouseX, mouseY, partialTick);
			}
		}
	}
	private void drawStatusStrip(GuiGraphics graphics, int accent) {
		int x = left + 10;
		int y = top + 36;
		int w = PANEL_WIDTH - 20;
		graphics.fill(x, y, x + w, y + 46, 0xE012222B);
		graphics.fill(x, y, x + 5, y + 46, accent);
		graphics.renderOutline(x, y, w, 46, CARD_BORDER);

		String suitCode = "ID " + snapshot.suitId().toString().substring(0, 8).toUpperCase();
		graphics.drawString(font, suitCode, x + 14, y + 24, MUTED, false);
		String model = snapshot.variant().chineseName() + "  /  " + snapshot.roleText();

		String transitionBadge = snapshot.transitioning() ? "迁移 / 锁定中" : snapshot.lifecycleText();
		int badgeWidth = Math.min(160, Math.max(88, font.width(transitionBadge) + 16));
		int badgeX = x + w - badgeWidth - 9;
		int badgeColor = snapshot.transitioning() ? WARNING : GOOD;
		drawClipped(graphics, snapshot.displayName(),
				x + 14, y + 8, badgeX - x - 28, accent);
		drawClipped(graphics, model, x + 112, y + 24, w - 130, TEXT);
		graphics.fill(badgeX, y + 7, x + w - 9, y + 22, 0xD0162A32);
		graphics.renderOutline(badgeX, y + 7, badgeWidth, 15, badgeColor);
		String shownTransition = clippedText(transitionBadge, badgeWidth - 16);
		graphics.drawString(font, shownTransition,
				badgeX + (badgeWidth - font.width(shownTransition)) / 2, y + 11, badgeColor, false);

		String carrierState = commandStateText();
		graphics.drawString(font, carrierState,
				x + w - 9 - font.width(carrierState), y + 37,
				snapshot.selectedForCommands() ? CYAN : MUTED, false);
	}

	private void drawField(
			GuiGraphics graphics, String label, String value, int x, int y) {
		graphics.drawString(font, label + "  ", x, y, MUTED, false);
		graphics.drawString(font, value, x + font.width(label + "  "), y, TEXT, false);
	}

	private void drawPhysicalCard(GuiGraphics graphics) {
		int x = left + 10;
		int y = top + 88;
		int w = 243;
		drawCardFrame(graphics, x, y, w, 78, "物理载体", CYAN);
		drawField(graphics, "主人", snapshot.ownerName(), x + 10, y + 24);
		drawField(graphics, "类别", subjectLabel(snapshot.subjectKind()), x + 10, y + 39);
		drawField(graphics, "距离", snapshot.distanceText(), x + 10, y + 54);
		graphics.drawString(font, "坐标  ", x + 10, y + 69, MUTED, false);
		drawClipped(graphics, snapshot.positionText(),
				x + 10 + font.width("坐标  "), y + 69, w - 20 - font.width("坐标  "), TEXT);
	}

	private void drawIntegrityCard(GuiGraphics graphics) {
		int x = left + 259;
		int y = top + 88;
		int w = PANEL_WIDTH - 269;
		boolean conflictFree = snapshot.conflictText().equals("无冲突");
		drawCardFrame(graphics, x, y, w, 78, "完整性 / 事务",
				conflictFree ? GOOD : WARNING);
		graphics.drawString(font, "装甲完整度", x + 10, y + 24, MUTED, false);
		drawClipped(graphics, snapshot.integrityText(),
				x + 79, y + 24, w - 89, TEXT);
		graphics.drawString(font, conflictFree ? "● 无冲突" : "▲ 操作受限",
				x + 10, y + 42, conflictFree ? GOOD : WARNING, false);
		List<FormattedCharSequence> lines = font.split(
				Component.literal(snapshot.conflictText()), w - 20);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			graphics.drawString(font, lines.get(i), x + 10, y + 58 + i * 10, TEXT, false);
		}
	}

	private void drawStateMatrix(GuiGraphics graphics, int accent) {
		int x = left + 10;
		int y = top + 172;
		int w = PANEL_WIDTH - 20;
		graphics.fill(x, y, x + w, y + 42, CARD);
		graphics.renderOutline(x, y, w, 42, CARD_BORDER);
		graphics.drawString(font, "状态总线", x + 10, y + 7, accent, false);
		int cellY = y + 19;
		int gap = 5;
		int cellW = (w - 20 - gap * 3) / 4;
		drawStateCell(graphics, x + 10, cellY, cellW, "物理", snapshot.lifecycleText(), snapshot.transitioning() ? WARNING : GOOD);
		drawStateCell(graphics, x + 10 + (cellW + gap), cellY, cellW, "任务", taskStateText(), accent);
		drawStateCell(graphics, x + 10 + (cellW + gap) * 2, cellY, cellW, "控制", controlStateText(), snapshot.selectedForCommands() ? GOOD : MUTED);
		drawStateCell(graphics, x + 10 + (cellW + gap) * 3, cellY, cellW, "战斗", combatStateText(), snapshot.ownerView() ? CYAN : MUTED);
	}

	private void drawStateCell(GuiGraphics graphics, int x, int y, int width, String label, String value, int color) {
		graphics.fill(x, y, x + width, y + 18, 0xE0132A34);
		graphics.renderOutline(x, y, width, 18, 0xFF294E5A);
		graphics.drawString(font, label, x + 5, y + 6, MUTED, false);
		drawClipped(graphics, value, x + 5 + font.width(label) + 5, y + 6, width - font.width(label) - 15, color);
	}

	private void drawCommandCard(GuiGraphics graphics, int accent) {
		int x = left + 10;
		int y = top + 220;
		int w = PANEL_WIDTH - 20;
		graphics.fill(x, y, x + w, y + 46, CARD);
		graphics.renderOutline(x, y, w, 46, CARD_BORDER);
		graphics.drawString(font, "统一指令入口", x + 10, y + 7, accent, false);
		String link = snapshot.subjectKind() == ArmorSuitPanelSnapshotPayload.SubjectKind.PLATFORM
				? "平台陈列不可接收战斗指令"
				: snapshot.selectedForCommands() ? "当前由核心指令中心接管 · M/R/G/X/J 生效" : "模式设置统一移至核心指令中心";
		graphics.drawString(font, link, x + w - 10 - font.width(link), y + 7, snapshot.selectedForCommands() ? GOOD : MUTED, false);

		int keyY = y + 19;
		int keyWidth = (w - 20 - 4 * 5) / 5;
		drawKeyCell(graphics, x + 10, keyY, keyWidth, "M", "形态切换");
		drawKeyCell(graphics, x + 10 + keyWidth + 5, keyY, keyWidth, "R", "腕炮 / 准星");
		drawKeyCell(graphics, x + 10 + (keyWidth + 5) * 2, keyY, keyWidth, "G", "胸炮 / 准星");
		drawKeyCell(graphics, x + 10 + (keyWidth + 5) * 3, keyY, keyWidth, "X", "近战 / 贴地");
		drawKeyCell(graphics, x + 10 + (keyWidth + 5) * 4, keyY, keyWidth, "J", "近战大招");
	}

	private void drawNotice(GuiGraphics graphics) {
		int x = left + 10;
		int y = top + 272;
		int w = PANEL_WIDTH - 20;
		graphics.fill(x, y, x + w, y + 24, 0xB80A151B);
		graphics.renderOutline(x, y, w, 24, 0xFF223B44);
		graphics.drawString(font, "系统回执", x + 9, y + 9,
				snapshot.ownerView() ? CYAN : WARNING, false);
		String notice = snapshot.notice().isBlank()
				? "等待服务端指令；关闭面板后 H 收放守望僚机或切换巡航警戒。"
				: snapshot.notice();
		drawClipped(graphics, notice, x + 68, y + 9, w - 77,
				snapshot.ownerView() ? 0xFF9FC2CB : WARNING);
	}

	private void drawCardFrame(GuiGraphics graphics, int x, int y, int width, int height,
			String title, int titleColor) {
		graphics.fill(x, y, x + width, y + height, CARD);
		graphics.renderOutline(x, y, width, height, CARD_BORDER);
		graphics.fill(x, y, x + 4, y + height, titleColor);
		graphics.drawString(font, title, x + 10, y + 8, titleColor, false);
	}

	private void drawKeyCell(GuiGraphics graphics, int x, int y, int width,
			String key, String action) {
		graphics.fill(x, y, x + width, y + 25, 0xE0132A34);
		graphics.renderOutline(x, y, width, 25, 0xFF294E5A);
		graphics.fill(x + 1, y + 1, x + 22, y + 24, 0xFF1E4652);
		graphics.drawCenteredString(font, key, x + 11, y + 8, 0xFFEAFBFF);
		drawClipped(graphics, action, x + 28, y + 8, width - 34, TEXT);
	}

	private void drawClipped(
			GuiGraphics graphics, String value, int x, int y, int maxWidth, int color) {
		graphics.drawString(font, clippedText(value, maxWidth), x, y, color, false);
	}

	private String clippedText(String value, int maxWidth) {
		String shown = value == null ? "" : value;
		if (font.width(shown) <= maxWidth) return shown;
		return font.plainSubstrByWidth(
				shown, Math.max(0, maxWidth - font.width("…"))) + "…";
	}

	private String commandStateText() {
		if (snapshot.subjectKind() == ArmorSuitPanelSnapshotPayload.SubjectKind.PLATFORM) return "平台陈列 · 无战斗链路";
		return controlStateText() + "  /  " + taskStateText();
	}

	private String taskStateText() {
		if (snapshot.subjectKind() == ArmorSuitPanelSnapshotPayload.SubjectKind.PLATFORM) return "平台待机";
		if (!snapshot.followingOwner()) return "原地待命";
		return snapshot.subjectKind() == ArmorSuitPanelSnapshotPayload.SubjectKind.ESCORT ? "护航编队" : "独立巡航";
	}

	private String controlStateText() {
		if (snapshot.subjectKind() == ArmorSuitPanelSnapshotPayload.SubjectKind.PLATFORM) return "平台控制";
		return snapshot.selectedForCommands() ? "指令模式" : "自主控制";
	}

	private String combatStateText() {
		if (snapshot.subjectKind() == ArmorSuitPanelSnapshotPayload.SubjectKind.PLATFORM) return "武器锁定";
		return snapshot.selectedForCommands() ? "可切换近战" : "策略自动";
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static String subjectLabel(ArmorSuitPanelSnapshotPayload.SubjectKind kind) {
		return switch (kind) {
			case PLATFORM -> "充能平台";
			case CRUISE -> "独立巡航";
			case ESCORT -> "护航战甲";
		};
	}

	private final class PanelButton extends AbstractButton {
		private final Runnable action;

		private PanelButton(int x, int y, int width, int height,
				Component label, Runnable action) {
			super(x, y, width, height, label);
			this.action = action;
		}

		@Override
		public void onPress() {
			if (active) action.run();
		}

		@Override
		protected void renderWidget(
				GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			int accent = 0xFF000000 | snapshot.variant().primaryLightRgb();
			int background = !active ? 0xC00A1318
					: isHoveredOrFocused() ? 0xF01A3540 : 0xE010252E;
			int border = !active ? 0xFF31434A : isHoveredOrFocused() ? 0xFFEAFBFF : accent;
			graphics.fill(getX(), getY(), getRight(), getBottom(), background);
			graphics.fill(getX(), getY(), getX() + 3, getBottom(), border);
			graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), border);
			String label = clippedText(getMessage().getString(), getWidth() - 14);
			int color = active ? 0xFFEAFBFF : 0xFF667A82;
			graphics.drawCenteredString(font, label,
					getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
	private record ButtonSpec(
			String label, ArmorSuitPanelActionPayload.Action action, boolean enabled) {
	}
}

