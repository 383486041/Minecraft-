package dev.codex.flightarmor.client.core;

import dev.codex.flightarmor.core.ArmorCoreAction;
import dev.codex.flightarmor.core.ArmorEscortProfile;
import dev.codex.flightarmor.core.ArmorCoreMenu;
import dev.codex.flightarmor.network.ArmorCoreSnapshotPayload;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsScreen;
import dev.codex.flightarmor.client.combat.ArmorCombatClient;
import dev.codex.flightarmor.runtime.SuitRuntimeSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 装甲核心召唤指挥面板。客户端只展示，平台与物品状态始终由服务端重新校验。 */
public final class ArmorCoreCommandScreen extends AbstractContainerScreen<ArmorCoreMenu> {
	private static final int LIST_X = 10;
	private static final int LIST_Y = 60;
	private static final int LIST_W = 190;
	private static final int ROW_H = 24;
	private static final int VISIBLE_ROWS = 7;
	private static final int FORMATION_X = 10;
	private static final int FORMATION_Y = 34;
	private static final int FORMATION_W = 190;
	private static final int FORMATION_H = 58;
	private static final int CYAN = 0xFF65EEFF;
	private static final int GOLD = 0xFFFFD968;
	private static final long RECEIPT_TIMEOUT_NANOS = 10_000_000_000L;

	private UUID selectedSuit;
	private int listOffset;
	private long awaitingSequence = -1L;
	private long awaitingStartedNanos;
	private long layoutRevision = Long.MIN_VALUE;
	private UUID awaitingSuit;
	private ArmorCoreAction awaitingAction;
	private String pendingText = "";
	private String awaitingTargetName = "";
	private long timedOutSequence = -1L;
	private UUID timedOutSuit;
	private ArmorCoreAction timedOutAction;
	private String localNotice = "";
	private CommandButton bindButton;
	private CommandButton refreshButton;
	private CommandButton primaryButton;
	private CommandButton recallButton;
	private CommandButton deployButton;
	private CommandButton forgetButton;
	private CommandButton escortButton;
	private CommandButton recallAllButton;
	private CommandButton returnButton;
	private CommandButton commandAutonomousButton;
	private CommandButton commandDirectedButton;
	private CommandButton attackModeButton;
	private CommandButton attackRangeButton;
	private CommandButton escortStanceButton;

	public ArmorCoreCommandScreen(ArmorCoreMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		imageWidth = 520;
		imageHeight = 330;
	}

	@Override
	protected void init() {
		super.init();
		CommandButton settingsButton = new CommandButton(leftPos + 284, topPos + 5, 76, 20,
				Component.translatable("screen.flight_armor.core.client_settings"), CYAN, false,
				() -> minecraft.setScreen(new FlightArmorClientSettingsScreen(this)));
		settingsButton.setTooltip(Tooltip.create(Component.translatable("screen.flight_armor.core.client_settings.tooltip")));
		addRenderableWidget(settingsButton);
		bindButton = button(364, 5, 104, 20, "绑定准星平台", CYAN, false,
				ArmorCoreAction.BIND_LOOKED_AT, null, "正在接入平台…");
		refreshButton = button(472, 5, 38, 20, "刷新", 0xFF7EA8B7, false,
				ArmorCoreAction.REFRESH, null, "正在刷新网络…");
		commandAutonomousButton = button(214, 116, 69, 18, "自主控制", 0xFF75D9A6, false,
				ArmorCoreAction.TOGGLE_REMOTE_COMMAND, true, "正在交还自主控制…");
		commandDirectedButton = button(287, 116, 69, 18, "指令模式", GOLD, true,
				ArmorCoreAction.TOGGLE_REMOTE_COMMAND, true, "正在接管指令路由…");
		attackModeButton = button(362, 116, 148, 18, "攻击：仅敌对", 0xFFFF6F7D, false,
				ArmorCoreAction.CYCLE_ATTACK_MODE, true, "正在更新交战规则…");
		attackRangeButton = button(362, 137, 148, 18, "作战半径：32 格", CYAN, false,
				ArmorCoreAction.CYCLE_ATTACK_RANGE, true, "正在更新作战半径…");
		escortStanceButton = button(214, 137, 142, 18, "任务位置：跟随自主", 0xFF9A7CFF, false,
				ArmorCoreAction.TOGGLE_ESCORT_STANCE, true, "正在循环护航任务位置…");
		primaryButton = button(208, 177, 92, 18, "设为主战甲", GOLD, false,
				ArmorCoreAction.SET_PRIMARY, true, "正在写入主甲权限…");
		recallButton = button(304, 177, 206, 18, "召回并着装", CYAN, true,
				ArmorCoreAction.RECALL_SELECTED, true, "正在发出主召回指令…");
		deployButton = button(304, 177, 206, 18, "释放当前战甲", 0xFF9A7CFF, true,
				ArmorCoreAction.DEPLOY_SELECTED_WORN, true, "正在释放当前战甲…");
		deployButton.visible = false;
		forgetButton = button(304, 177, 206, 18, "忘记失联战甲", 0xFFFF6F7D, false,
				ArmorCoreAction.FORGET_MISSING, true, "正在重新校验并移除失联记录…");
		forgetButton.visible = false;
		escortButton = button(208, 198, 92, 18, "召唤护航", 0xFF9A7CFF, false,
				ArmorCoreAction.RECALL_AS_ESCORT, true, "正在启动副甲护航…");
		returnButton = button(304, 198, 92, 18, "返平台", 0xFF75D9A6, false,
				ArmorCoreAction.RETURN_ESCORT, true, "正在发送返航指令…");
		returnButton.visible = false;
		// 全部召回只使用已经设置的主战甲，不能被当前列表选择偷偷改写。
		recallAllButton = button(400, 294, 110, 20, "执行编队方案", GOLD, false,
				ArmorCoreAction.RECALL_ALL, null, "正在调度全部战甲…");
	}

	private CommandButton button(int x, int y, int width, int height, String label, int accent,
			boolean primaryStyle, ArmorCoreAction action, Boolean useSelection, String pending) {
		CommandButton result = new CommandButton(leftPos + x, topPos + y, width, height,
				Component.literal(label), accent, primaryStyle, () ->
				send(action, Boolean.TRUE.equals(useSelection) ? selectedSuit : null, pending));
		return addRenderableWidget(result);
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		ArmorCoreSnapshotPayload snapshot = ArmorCoreClient.snapshot();
		List<ArmorCoreSnapshotPayload.Entry> entries = ordered(snapshot);
		if (matchesPendingReceipt(snapshot)) {
			clearAwaiting();
			localNotice = "";
		} else if (awaitingSequence >= 0L
				&& System.nanoTime() - awaitingStartedNanos >= RECEIPT_TIMEOUT_NANOS) {
			expireAwaiting();
		}
		if (matchesTimedOutReceipt(snapshot)) {
			clearTimedOut();
			localNotice = "";
		}
		if (selectedSuit == null || entries.stream().noneMatch(entry -> entry.suitId().equals(selectedSuit))) {
			selectedSuit = entries.stream()
					.filter(entry -> entry.suitId().equals(snapshot.primarySuitId()))
					.findFirst().or(() -> entries.stream().findFirst())
					.map(ArmorCoreSnapshotPayload.Entry::suitId).orElse(null);
		}
		int maxOffset = Math.max(0, entries.size() - VISIBLE_ROWS);
		listOffset = Math.max(0, Math.min(listOffset, maxOffset));
		if (layoutRevision != snapshot.revision() && selectedSuit != null) {
			int selectedIndex = entries.indexOf(selected(entries));
			if (selectedIndex < listOffset) listOffset = Math.max(0, selectedIndex);
			else if (selectedIndex >= listOffset + VISIBLE_ROWS) listOffset = Math.min(maxOffset,
					selectedIndex - VISIBLE_ROWS + 1);
		}
		layoutRevision = snapshot.revision();

		ArmorCoreSnapshotPayload.Entry selected = selected(entries);
		boolean busy = awaitingSequence >= 0L;
		boolean online = snapshot.coreEquipped();
		boolean selectable = selected != null;
		boolean actionable = selectable && selected.actionMask() != 0;
		boolean configurable = actionable
				&& selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_SET_ESCORT);
		boolean canSetPrimary = actionable
				&& selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_SET_PRIMARY);
		boolean canRecallAndWear = actionable
				&& selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR);
		boolean canDeployWorn = actionable
				&& selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_DEPLOY_WORN);
		boolean canLaunchEscort = actionable
				&& selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_LAUNCH_ESCORT);
		boolean canReturnHome = actionable
				&& selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_RETURN_HOME);
		boolean canForgetMissing = actionable
				&& selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_FORGET_MISSING);
		boolean commandSelectable = selectable
				&& (selected.status() == ArmorCoreSnapshotPayload.Status.CRUISING
						|| selected.status() == ArmorCoreSnapshotPayload.Status.ESCORTING);
		boolean cruising = selectable
				&& selected.status() == ArmorCoreSnapshotPayload.Status.CRUISING;
		boolean returning = selectable
				&& selected.status() == ArmorCoreSnapshotPayload.Status.RETURNING;
		ArmorCoreSnapshotPayload.Entry primary = entries.stream()
				.filter(entry -> entry.suitId().equals(snapshot.primarySuitId()))
				.findFirst().orElse(null);

		bindButton.active = online && !busy;
		refreshButton.active = !busy;
		primaryButton.visible = canSetPrimary;
		primaryButton.active = online && !busy && canSetPrimary;
		recallButton.active = online && !busy && canRecallAndWear;
		recallButton.visible = canRecallAndWear;
		deployButton.active = online && !busy && canDeployWorn;
		deployButton.visible = canDeployWorn;
		forgetButton.visible = canForgetMissing;
		forgetButton.active = online && !busy && canForgetMissing;
		escortButton.visible = canLaunchEscort;
		returnButton.visible = canReturnHome || returning;
		escortButton.active = online && !busy && canLaunchEscort;
		returnButton.active = online && !busy && canReturnHome;
		escortButton.setMessage(Component.literal(cruising ? "转为护航" : "召唤护航"));
		returnButton.setMessage(Component.literal(returning ? "返航中" : "返平台"));
		boolean commanded = selected != null
				&& ArmorCombatClient.isRemoteCommandSuit(selected.suitId());
		commandAutonomousButton.active = online && !busy && commandSelectable && commanded;
		commandDirectedButton.active = online && !busy && commandSelectable && !commanded;
		commandAutonomousButton.setSelected(commandSelectable && !commanded);
		commandDirectedButton.setSelected(commandSelectable && commanded);
		attackModeButton.active = online && !busy && configurable;
		attackRangeButton.active = online && !busy && configurable;
		escortStanceButton.active = online && !busy && configurable;
		// “执行核心方案”是核心级命令。客户端只确认存在主甲，具体状态和全局事务冲突由服务端校验。
		recallAllButton.active = online && !busy && primary != null;

		if (selected == null) {
			attackModeButton.setMessage(Component.literal("交战规则：未选择"));
			attackRangeButton.setMessage(Component.literal("作战半径：--"));
			escortStanceButton.setMessage(Component.literal("任务位置：未选择"));
		} else {
			attackModeButton.setMessage(Component.literal("交战规则：" + attackModeLabel(selected.attackMode())));
			attackRangeButton.setMessage(Component.literal("作战半径：" + selected.rangeBlocks() + " 格"));
			escortStanceButton.setMessage(Component.literal("任务位置：" + escortStanceLabel(selected.stance())));
		}

		bindButton.setTooltip(tip(online ? "对准 6 格内的完整充能平台，将其接入当前核心"
				: "手持导航核心右键即可激活，不需要拖入装备槽"));
		refreshButton.setTooltip(tip("重新读取所有绑定平台和护航战甲的权威状态"));
		primaryButton.setTooltip(tip(primaryReason(snapshot, selected)));
		recallButton.setTooltip(tip(recallReason(selected, false)));
		deployButton.setTooltip(tip(canDeployWorn
				? "释放当前完整穿戴战甲；若已有另一套独立巡航甲，本套将成为可单独指挥的护航甲"
				: statusDescription(selected == null
						? ArmorCoreSnapshotPayload.Status.INVALID : selected.status())));
		forgetButton.setTooltip(tip(forgetReason(selected)));
		escortButton.setTooltip(tip(recallReason(selected, true)));
		returnButton.setTooltip(tip(returnReason(selected, returning)));
		commandAutonomousButton.setTooltip(tip(commandSelectable
				? "让所选战甲恢复自主巡航与自动战斗，不再接收 M/R/G/X/J"
				: "只有已加载的独立巡航或护航战甲可以切换控制权"));
		commandDirectedButton.setTooltip(tip(commandSelectable
				? "接管所选战甲；M/R/G/X/J 将统一发送给这套战甲"
				: "只有已加载的独立巡航或护航战甲可以进入指令模式"));
		attackModeButton.setTooltip(tip(attackModeReason(selected)));
		attackRangeButton.setTooltip(tip("循环设置护航战甲主动离开锚点搜索目标的最大半径"));
		escortStanceButton.setTooltip(tip(escortStanceReason(selected)));
		recallAllButton.setTooltip(tip(recallAllReason(primary)));
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
		int left = leftPos;
		int top = topPos;
		graphics.fill(left, top, left + imageWidth, top + imageHeight, 0xF4081118);
		graphics.fill(left + 1, top + 1, left + imageWidth - 1, top + 29, 0xFF0C1D27);
		graphics.fill(left + 1, top + 29, left + imageWidth - 1, top + 31, CYAN);
		graphics.renderOutline(left, top, imageWidth, imageHeight, CYAN);
		graphics.renderOutline(left + 3, top + 3, imageWidth - 6, imageHeight - 6, 0xFF294852);

		drawSection(graphics, left + 10, top + 38, 190, 202, "战甲列表", CYAN);
		drawSection(graphics, left + 208, top + 38, 302, 58, "所选战甲", GOLD);
		drawSection(graphics, left + 208, top + 98, 302, 56, "控制权与战斗策略", CYAN);
		drawSection(graphics, left + 208, top + 158, 302, 58, "装甲动作", 0xFF9A7CFF);
		drawSection(graphics, left + 208, top + 220, 302, 56, "编队方案", GOLD);
		drawSection(graphics, left + 10, top + 244, 190, 76, "指令回执", 0xFF78B4C5);
		drawSection(graphics, left + 208, top + 280, 302, 40, "编队执行", 0xFF75D9A6);

		renderList(graphics, left, top);
		renderDetails(graphics, left, top);
	}

	private void drawSection(GuiGraphics graphics, int x, int y, int width, int height,
			String title, int accent) {
		graphics.fill(x, y, x + width, y + height, 0xED0B1820);
		graphics.renderOutline(x, y, width, height, 0xFF31515D);
		graphics.fill(x + 1, y + 1, x + 4, y + height - 1, accent);
		graphics.drawString(font, title, x + 10, y + 7, accent, false);
	}
	private void renderList(GuiGraphics graphics, int left, int top) {
		graphics.fill(left + LIST_X, top + LIST_Y, left + LIST_X + LIST_W, top + LIST_Y + ROW_H * VISIBLE_ROWS, 0xEE0B1820);
		graphics.renderOutline(left + LIST_X, top + LIST_Y, LIST_W, ROW_H * VISIBLE_ROWS, 0xFF30515E);
		ArmorCoreSnapshotPayload snapshot = ArmorCoreClient.snapshot();
		List<ArmorCoreSnapshotPayload.Entry> entries = ordered(snapshot);
		for (int row = 0; row < VISIBLE_ROWS; row++) {
			int index = listOffset + row;
			int y = top + LIST_Y + row * ROW_H;
			if (index >= entries.size()) {
				graphics.fill(left + LIST_X + 1, y + 1, left + LIST_X + LIST_W - 4, y + ROW_H - 1,
						row % 2 == 0 ? 0x35142A34 : 0x25142A34);
				continue;
			}
			ArmorCoreSnapshotPayload.Entry entry = entries.get(index);
			boolean chosen = entry.suitId().equals(selectedSuit);
			boolean main = entry.suitId().equals(snapshot.primarySuitId());
			boolean commanded = ArmorCombatClient.isRemoteCommandSuit(entry.suitId());
			int right = left + LIST_X + LIST_W - 4;
			graphics.fill(left + LIST_X + 1, y + 1, right, y + ROW_H - 1,
					chosen ? 0xFF173B49 : row % 2 == 0 ? 0xC7122630 : 0xC70E2029);
			graphics.fill(left + LIST_X + 1, y + 1, left + LIST_X + 3, y + ROW_H - 1,
					chosen ? CYAN : variantColor(entry));
			if (chosen) graphics.renderOutline(left + LIST_X + 1, y + 1, LIST_W - 5, ROW_H - 2, CYAN);
			String name = font.plainSubstrByWidth(entry.displayName(), 92);
			graphics.drawString(font, (commanded ? "▶ " : main ? "◆ " : "◇ ") + name, left + LIST_X + 6, y + 2,
					main ? GOLD : 0xFFE8F7FA, false);
			String status = statusLabel(entry.status());
			graphics.drawString(font, status, right - font.width(status) - 4, y + 2,
					statusColor(entry.status()), false);
			String secondary = (main ? "主战甲" : "副战甲") + " · "
					+ entry.variant().chineseName() + " · "
					+ (entry.hasHome() ? "已设返回平台" : "无返回平台");
			graphics.drawString(font, font.plainSubstrByWidth(secondary, LIST_W - 16),
					left + LIST_X + 7, y + 14, 0xFF789EAA, false);
		}
		if (entries.size() > VISIBLE_ROWS) {
			int trackHeight = ROW_H * VISIBLE_ROWS - 4;
			int thumbHeight = Math.max(12, trackHeight * VISIBLE_ROWS / entries.size());
			int thumbY = top + LIST_Y + 2 + (trackHeight - thumbHeight) * listOffset
					/ (entries.size() - VISIBLE_ROWS);
			int x = left + LIST_X + LIST_W - 3;
			graphics.fill(x, top + LIST_Y + 2, x + 1, top + LIST_Y + 2 + trackHeight, 0xFF29414B);
			graphics.fill(x - 1, thumbY, x + 2, thumbY + thumbHeight, CYAN);
		}
	}

	private void renderDetails(GuiGraphics graphics, int left, int top) {
		ArmorCoreSnapshotPayload snapshot = ArmorCoreClient.snapshot();
		ArmorCoreSnapshotPayload.Entry entry = selected(ordered(snapshot));
		if (entry == null) {
			graphics.drawCenteredString(font, "从左侧选择一套战甲",
					left + 359, top + 68, 0xFF6E929D);
			return;
		}
		boolean primary = entry.suitId().equals(snapshot.primarySuitId());
		boolean commanded = ArmorCombatClient.isRemoteCommandSuit(entry.suitId());
		int accent = variantColor(entry);
		String title = (commanded ? "▶ " : primary ? "◆ " : "◇ ") + entry.displayName();
		graphics.drawString(font, font.plainSubstrByWidth(title, 184),
				left + 218, top + 59, accent, false);
		String status = detailedStatus(entry.status());
		graphics.drawString(font, status, left + 500 - font.width(status), top + 59,
				statusColor(entry.status()), false);
		String identity = (primary ? "主战甲" : "副战甲") + " · " + entry.variant().chineseName()
				+ " · " + (commanded ? "指令模式" : "自主控制");
		graphics.drawString(font, font.plainSubstrByWidth(identity, 280),
				left + 218, top + 72, 0xFFA8C8D1, false);
		String location = dimensionLabel(entry) + " · " + distanceLabel(entry) + " · "
				+ entry.x() + " / " + entry.y() + " / " + entry.z();
		graphics.drawString(font, font.plainSubstrByWidth(location, 280),
				left + 218, top + 84, 0xFF688D98, false);
	}
	@Override
	protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		ArmorCoreSnapshotPayload snapshot = ArmorCoreClient.snapshot();
		List<ArmorCoreSnapshotPayload.Entry> entries = ordered(snapshot);
		long operable = entries.stream().filter(entry -> entry.actionMask() != 0).count();
		graphics.drawString(font, "装甲核心 · 统一指挥中心", 10, 11,
				snapshot.coreEquipped() ? 0xFFE8FBFF : 0xFFFF8080, false);
		String count = "关联 " + entries.size() + " 套 · 可操作 " + operable + " 套";
		graphics.drawString(font, count, 142, 11, 0xFF8DB5C0, false);
		// 核心的角色必须写在面板上：它是「网络能力」的开关，而不是一件可以忽略的饰品。
		String network = snapshot.coreEquipped()
				? "网络能力已解锁：形态切换 · 遥控指令 · 编队召回"
				: "网络能力已锁定：核心离线，形态切换与遥控指令不可用";
		graphics.drawString(font, network, 10, 21,
				snapshot.coreEquipped() ? 0xFF7FE3A4 : 0xFFFF8A6B, false);

		if (entries.isEmpty()) {
			graphics.drawCenteredString(font,
					snapshot.coreEquipped() ? "尚未关联战甲" : "核心离线",
					LIST_X + LIST_W / 2, 132,
					snapshot.coreEquipped() ? 0xFFFFD77A : 0xFF708A93);
		}

		ArmorCoreSnapshotPayload.Entry primary = entries.stream()
				.filter(entry -> entry.suitId().equals(snapshot.primarySuitId()))
				.findFirst().orElse(null);
		long escorts = entries.stream()
				.filter(entry -> primary == null || !entry.suitId().equals(primary.suitId()))
				.filter(entry -> entry.status() != ArmorCoreSnapshotPayload.Status.MISSING)
				.filter(entry -> entry.status() != ArmorCoreSnapshotPayload.Status.INVALID)
				.count();
		String primaryPlan = primary == null
				? "主甲：未设置"
				: "主甲：" + primary.displayName() + " → 召回并穿戴";
		String escortPlan = "副甲：" + escorts + " 套 → 按各自策略进入护航";
		graphics.drawString(font, font.plainSubstrByWidth(primaryPlan, 280),
				218, 242, primary == null ? 0xFFFFC66C : GOLD, false);
		graphics.drawString(font, font.plainSubstrByWidth(escortPlan, 280),
				218, 257, 0xFF9CC1CB, false);
		renderReceipt(graphics, snapshot, entries);
	}
	private void renderReceipt(GuiGraphics graphics, ArmorCoreSnapshotPayload snapshot,
			List<ArmorCoreSnapshotPayload.Entry> entries) {
		boolean waiting = awaitingSequence >= 0L;
		boolean showResult = shouldShowResult(snapshot);
		boolean removedTargetResult = showResult && snapshot.resultSuitId() != null
				&& entries.stream().noneMatch(entry -> entry.suitId().equals(snapshot.resultSuitId()));
		String receiptTitle = waiting ? "指令发送中"
				: !localNotice.isBlank() ? "本地等待状态"
				: removedTargetResult ? "已移除记录的结果"
				: showResult ? "上次指令结果" : "指令说明";
		graphics.drawString(font, receiptTitle, 20, 264,
				waiting ? CYAN : 0xFF8DB5C0, false);
		String message;
		int color;
		if (waiting) {
			message = awaitingTargetName.isBlank()
					? pendingText : pendingText + "  目标：" + awaitingTargetName;
			color = CYAN;
		} else if (!localNotice.isBlank()) {
			message = localNotice;
			color = 0xFFFFD16B;
		} else if (showResult) {
			message = snapshot.resultMessage();
			color = resultColor(message);
		} else if (!snapshot.coreEquipped()) {
			message = "手持导航核心右键激活；面板不需要放入任何物品。";
			color = 0xFFFFB174;
		} else if (entries.isEmpty()) {
			message = "对准 6 格内完整平台，再点击绑定准星平台。";
			color = 0xFFFFD77A;
		} else {
			message = "设置主甲和副甲策略后，按 B 直接执行整套核心方案。";
			color = 0xFF9CC1CB;
		}
		List<FormattedCharSequence> lines = font.split(Component.literal(message), 170);
		for (int index = 0; index < Math.min(3, lines.size()); index++) {
			graphics.drawString(font, lines.get(index), 20, 278 + index * 11, color, false);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		List<ArmorCoreSnapshotPayload.Entry> entries = ordered(ArmorCoreClient.snapshot());
		int x = (int) mouseX - leftPos;
		int y = (int) mouseY - topPos;
		if (x >= LIST_X && x < LIST_X + LIST_W && y >= LIST_Y && y < LIST_Y + ROW_H * VISIBLE_ROWS) {
			int index = listOffset + (y - LIST_Y) / ROW_H;
			if (index >= 0 && index < entries.size()) {
				selectedSuit = entries.get(index).suitId();
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		int max = Math.max(0, ArmorCoreClient.snapshot().entries().size() - VISIBLE_ROWS);
		if (max > 0 && mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + LIST_W
				&& mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + ROW_H * VISIBLE_ROWS) {
			listOffset = Math.max(0, Math.min(max, listOffset + (vertical < 0.0D ? 1 : -1)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// 近距战甲/核心面板必须保留世界与目标可见，不启用 1.21 菜单高斯模糊。
		graphics.fill(0, 0, width, height, 0x30000000);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		renderBg(graphics, partialTick, mouseX, mouseY);
		renderLabels(graphics, mouseX, mouseY);
		for (GuiEventListener child : children()) {
			if (child instanceof Renderable renderable) {
				renderable.render(graphics, mouseX, mouseY, partialTick);
			}
		}
		renderTooltip(graphics, mouseX, mouseY);
		ArmorCoreSnapshotPayload.Entry hovered = hoveredEntry(mouseX, mouseY);
		if (hovered != null) {
			graphics.renderTooltip(font, Component.literal(statusDescription(hovered.status())), mouseX, mouseY);
		}
	}

	private void send(ArmorCoreAction action, UUID suitId, String pending) {
		if (awaitingSequence >= 0L) return;
		ArmorCoreSnapshotPayload snapshot = ArmorCoreClient.snapshot();
		String targetName = suitId == null
				? globalTargetLabel(action)
				: snapshot.entries().stream()
						.filter(entry -> entry.suitId().equals(suitId))
						.findFirst()
						.map(ArmorCoreSnapshotPayload.Entry::displayName)
						.orElse("已选战甲");
		long sequence = ArmorCoreClient.send(action, suitId);
		if (sequence < 0L) return;
		clearTimedOut();
		localNotice = "";
		awaitingSequence = sequence;
		awaitingStartedNanos = System.nanoTime();
		awaitingAction = action;
		awaitingSuit = suitId;
		pendingText = pending;
		awaitingTargetName = targetName;
	}

	private void expireAwaiting() {
		String target = awaitingTargetName.isBlank() ? "该指令" : awaitingTargetName;
		timedOutSequence = awaitingSequence;
		timedOutAction = awaitingAction;
		timedOutSuit = awaitingSuit;
		clearAwaiting();
		localNotice = "等待“" + target + "”的服务端回执超时；未在客户端改写状态，请点击刷新。";
	}

	private void clearAwaiting() {
		awaitingSequence = -1L;
		awaitingStartedNanos = 0L;
		awaitingSuit = null;
		awaitingAction = null;
		pendingText = "";
		awaitingTargetName = "";
	}

	private void clearTimedOut() {
		timedOutSequence = -1L;
		timedOutSuit = null;
		timedOutAction = null;
	}

	private boolean matchesPendingReceipt(ArmorCoreSnapshotPayload snapshot) {
		return awaitingSequence >= 0L
				&& snapshot.hasActionReceipt()
				&& snapshot.resultClientSequence() == awaitingSequence
				&& snapshot.resultAction() == awaitingAction
				&& Objects.equals(snapshot.resultSuitId(), awaitingSuit);
	}

	private boolean matchesTimedOutReceipt(ArmorCoreSnapshotPayload snapshot) {
		return timedOutSequence >= 0L
				&& snapshot.hasActionReceipt()
				&& snapshot.resultClientSequence() == timedOutSequence
				&& snapshot.resultAction() == timedOutAction
				&& Objects.equals(snapshot.resultSuitId(), timedOutSuit);
	}

	private boolean shouldShowResult(ArmorCoreSnapshotPayload snapshot) {
		if (snapshot.resultMessage().isBlank()) return false;
		if (!snapshot.hasActionReceipt() || snapshot.resultSuitId() == null) return true;
		if (snapshot.resultSuitId().equals(selectedSuit)) return true;
		return snapshot.entries().stream()
				.noneMatch(entry -> entry.suitId().equals(snapshot.resultSuitId()));
	}

	private static String globalTargetLabel(ArmorCoreAction action) {
		return switch (action) {
			case BIND_LOOKED_AT -> "准星平台";
			case REFRESH -> "核心网络";
			case RECALL_ALL -> "整套核心方案";
			default -> "";
		};
	}

	private ArmorCoreSnapshotPayload.Entry hoveredEntry(double mouseX, double mouseY) {
		int x = (int) mouseX - leftPos;
		int y = (int) mouseY - topPos;
		if (x < LIST_X || x >= LIST_X + LIST_W || y < LIST_Y || y >= LIST_Y + ROW_H * VISIBLE_ROWS) {
			return null;
		}
		List<ArmorCoreSnapshotPayload.Entry> entries = ordered(ArmorCoreClient.snapshot());
		int index = listOffset + (y - LIST_Y) / ROW_H;
		return index >= 0 && index < entries.size() ? entries.get(index) : null;
	}

	private ArmorCoreSnapshotPayload.Entry selected(List<ArmorCoreSnapshotPayload.Entry> entries) {
		if (selectedSuit == null) return null;
		return entries.stream().filter(entry -> entry.suitId().equals(selectedSuit)).findFirst().orElse(null);
	}

	private static List<ArmorCoreSnapshotPayload.Entry> ordered(ArmorCoreSnapshotPayload snapshot) {
		List<ArmorCoreSnapshotPayload.Entry> entries = new ArrayList<>(snapshot.entries());
		entries.sort(Comparator
				.comparing((ArmorCoreSnapshotPayload.Entry entry) ->
						!entry.suitId().equals(snapshot.primarySuitId()))
				.thenComparingInt(entry -> statusOrder(entry.status()))
				.thenComparing(ArmorCoreSnapshotPayload.Entry::displayName, String.CASE_INSENSITIVE_ORDER));
		return entries;
	}

	private static Tooltip tip(String text) {
		return Tooltip.create(Component.literal(text));
	}

	private static int statusOrder(ArmorCoreSnapshotPayload.Status status) {
		return switch (status) {
			case READY -> 0;
			case OFFLINE -> 1;
			case WORN -> 2;
			case CRUISING -> 3;
			case ESCORTING -> 4;
			case RETURNING -> 5;
			case LOOSE -> 6;
			case MISSING -> 7;
			case INCOMPLETE -> 8;
			case TRANSITION -> 9;
			case INVALID, CONFLICT -> 10;
		};
	}

	private static String primaryReason(ArmorCoreSnapshotPayload snapshot,
			ArmorCoreSnapshotPayload.Entry selected) {
		if (selected == null) return "请先在列表中选择一套战甲";
		if (selected.suitId().equals(snapshot.primarySuitId())) return "这套战甲已经是主战甲";
		if (selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_SET_PRIMARY)) {
			return "设为主战甲；原主甲自动转为副甲并保留自己的护航策略";
		}
		return statusDescription(selected.status());
	}

	private static String recallReason(ArmorCoreSnapshotPayload.Entry selected, boolean escort) {
		if (selected == null) return "请先在列表中选择一套战甲";
		SuitRuntimeSnapshot.AllowedAction action = escort
				? SuitRuntimeSnapshot.AllowedAction.CAN_LAUNCH_ESCORT
				: SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR;
		if (selected.allows(action)) return escort
				? selected.status() == ArmorCoreSnapshotPayload.Status.CRUISING
						? "把独立巡航战甲转为护航状态，并按当前策略自动作战"
						: "离开平台，自动巡航并开启战斗模式"
				: "高速飞向主人并自动着装";
		return statusDescription(selected.status());
	}

	private static String returnReason(ArmorCoreSnapshotPayload.Entry selected, boolean returning) {
		if (selected == null) return "请先在列表中选择一套战甲";
		if (!selected.hasHome()) return "这套战甲没有绑定返回平台，请先为它设置平台";
		if (returning) return "战甲正在返回原绑定平台";
		if (selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_RETURN_HOME)) {
			return "停止护航并飞回原绑定平台静态待机";
		}
		return statusDescription(selected.status());
	}

	private static String forgetReason(ArmorCoreSnapshotPayload.Entry selected) {
		if (selected == null) return "请先选择一套失联战甲";
		if (selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_FORGET_MISSING)) {
			return "服务端会再次确认没有穿戴、平台或载体证据，再从当前核心移除这套失联记录";
		}
		return "只有经服务端确认失联或散落、且没有活动事务的战甲才能移除记录";
	}

	private static String recallAllReason(ArmorCoreSnapshotPayload.Entry primary) {
		if (primary == null) return "请先选择一套有效战甲并设为主战甲";
		return "立即执行编队：主甲着装，其余战甲按各自护航策略启动。快捷键 B 仅在装甲槽为空时执行编队；完整穿戴时 B 会释放当前战甲";
	}

	private static String statusDescription(ArmorCoreSnapshotPayload.Status status) {
		return switch (status) {
			case READY -> "平台完整，可立即召唤";
			case OFFLINE -> "平台区块未加载，召唤时会远程校验";
			case WORN -> "这套战甲当前已由玩家穿戴";
			case CRUISING -> "这套主战甲正在独立巡航，可按 B 召回着装";
			case ESCORTING -> "这套战甲正在编队护航与战斗";
			case INCOMPLETE -> "平台缺少一个或多个战甲部件";
			case RETURNING -> "这套战甲正在返回原绑定平台";
			case TRANSITION -> "战甲正在执行召回、着装、脱离或切换事务";
			case CONFLICT -> "检测到位置或身份冲突，已禁用自动移动操作";
			case INVALID -> "平台或战甲身份校验失败";
			case MISSING -> "未发现穿戴、平台或载体证据；不会自动删除，可确认后移除记录";
			case LOOSE -> "战甲最后记录为散落状态，可能仍以掉落物存在";
		};
	}

	private static String attackModeLabel(ArmorEscortProfile.CombatPolicy mode) {
		return switch (mode) {
			case OFF -> "关闭";
			case HOSTILE -> "仅敌对";
			case ALL -> "所有生物";
		};
	}

	private static String escortStanceLabel(ArmorEscortProfile.EscortAnchor stance) {
		return switch (stance) {
			case FOLLOW -> "跟随自主";
			case CLOSE -> "贴身跟随";
			case HOLD -> "原地待命";
		};
	}

	private static String attackModeReason(ArmorCoreSnapshotPayload.Entry selected) {
		if (selected == null) return "请先选择一套副战甲";
		return switch (selected.attackMode()) {
			case OFF -> "当前不会主动攻击；点击切换为只攻击敌对生物";
			case HOSTILE -> "当前只攻击敌对生物；点击切换为攻击所有非主人、非队友生物";
			case ALL -> "当前攻击所有合法生物；点击关闭自动攻击";
		};
	}

	private static String escortStanceReason(ArmorCoreSnapshotPayload.Entry selected) {
		if (selected == null) return "请先选择一套副战甲";
		return switch (selected.stance()) {
			case FOLLOW -> "点击切到贴身跟随：战甲压到约 1 格紧贴身边，并且不再主动索敌";
			case CLOSE -> "点击固定当前位置并原地待命；接敌后仍会返回该锚点";
			case HOLD -> "点击恢复跟随自主：回到编队并按交战规则自主索敌";
		};
	}

	private static int resultColor(String message) {
		if (message.contains("失败") || message.contains("不能") || message.contains("无效")
				|| message.contains("不存在") || message.contains("未启动")) return 0xFFFF7777;
		if (message.contains("请先") || message.contains("缺少") || message.contains("没有")) return 0xFFFFD16B;
		return 0xFF78F5C0;
	}

	private static int variantColor(ArmorCoreSnapshotPayload.Entry entry) {
		return 0xFF000000 | entry.variant().primaryLightRgb();
	}

	private static String statusLabel(ArmorCoreSnapshotPayload.Status status) {
		return switch (status) {
			case READY -> "待机";
			case INCOMPLETE -> "缺件";
			case OFFLINE -> "远端";
			case WORN -> "穿戴";
			case CRUISING -> "巡航";
			case ESCORTING -> "护航";
			case INVALID -> "失效";
			case RETURNING -> "返航";
			case TRANSITION -> "变形中";
			case CONFLICT -> "冲突";
			case MISSING -> "失联";
			case LOOSE -> "散落";
		};
	}

	private static String detailedStatus(ArmorCoreSnapshotPayload.Status status) {
		return switch (status) {
			case READY -> "平台就绪";
			case INCOMPLETE -> "平台缺件";
			case OFFLINE -> "远程待机";
			case WORN -> "已经穿戴";
			case CRUISING -> "独立巡航";
			case ESCORTING -> "编队护航";
			case INVALID -> "绑定失效";
			case RETURNING -> "返回平台";
			case TRANSITION -> "事务进行中";
			case CONFLICT -> "状态冲突";
			case MISSING -> "位置失联";
			case LOOSE -> "散落待找回";
		};
	}

	private static int statusColor(ArmorCoreSnapshotPayload.Status status) {
		return switch (status) {
			case READY, ESCORTING, CRUISING -> 0xFF6CFF9A;
			case OFFLINE, WORN -> 0xFF7FCBFF;
			case INCOMPLETE -> 0xFFFFD46A;
			case RETURNING -> 0xFFFFD968;
			case INVALID -> 0xFFFF6E6E;
			case TRANSITION -> 0xFFFFD968;
			case CONFLICT -> 0xFFFF5A7A;
			case MISSING -> 0xFFFF7E6B;
			case LOOSE -> 0xFFFFB85C;
		};
	}

	private static String dimensionLabel(ArmorCoreSnapshotPayload.Entry entry) {
		return switch (entry.dimension().toString()) {
			case "minecraft:overworld" -> "主世界";
			case "minecraft:the_nether" -> "下界";
			case "minecraft:the_end" -> "末地";
			default -> entry.dimension().toString();
		};
	}

	private static String distanceLabel(ArmorCoreSnapshotPayload.Entry entry) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null
				|| !minecraft.player.level().dimension().location().equals(entry.dimension())) return "跨维度";
		double dx = minecraft.player.getX() - (entry.x() + 0.5D);
		double dy = minecraft.player.getY() - (entry.y() + 0.5D);
		double dz = minecraft.player.getZ() - (entry.z() + 0.5D);
		return Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + " 格";
	}

	private final class CommandButton extends AbstractButton {
		private final int accent;
		private final boolean primaryStyle;
		private final Runnable action;
		private boolean selected;

		private CommandButton(int x, int y, int width, int height, Component message,
				int accent, boolean primaryStyle, Runnable action) {
			super(x, y, width, height, message);
			this.accent = accent;
			this.primaryStyle = primaryStyle;
			this.action = action;
		}

		private void setSelected(boolean selected) {
			this.selected = selected;
		}

		@Override
		public void onPress() {
			if (active) action.run();
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			int fill = selected ? 0xFF173B49 : !active ? 0xE511171C : primaryStyle
					? (isHoveredOrFocused() ? 0xFF26768A : 0xFF1A5667)
					: (isHoveredOrFocused() ? 0xFF203B47 : 0xEE101F27);
			int border = selected ? accent
					: !active ? 0xFF35434A : isHoveredOrFocused() ? 0xFFFFFFFF : accent;
			int textColor = selected ? 0xFFFFFFFF : !active ? 0xFF68767C : 0xFFEFFFFF;
			graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
			graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), border);
			if (selected) graphics.fill(getX() + 2, getY() + 2, getX() + getWidth() - 2,
					getY() + 4, accent);
			graphics.drawCenteredString(ArmorCoreCommandScreen.this.font, getMessage(),
					getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, textColor);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}



