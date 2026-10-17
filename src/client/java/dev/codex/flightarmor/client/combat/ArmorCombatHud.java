package dev.codex.flightarmor.client.combat;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.combat.ArmorCombatPhase;
import dev.codex.flightarmor.combat.ArmorCombatTuning;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/** 靠近准星下方的轻量武器资源 HUD，不接管原版热键栏或准星。 */
final class ArmorCombatHud {
	private static final int BAR_WIDTH = 148;
	private static boolean initialized;

	private ArmorCombatHud() {
	}

	static void initialize() {
		if (initialized) return;
		initialized = true;
		HudElementRegistry.addLast(FlightArmorMod.id("combat_hud"), (graphics, deltaTracker) -> render(graphics));
	}

	private static void render(GuiGraphicsExtractor graphics) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		// 遥控选择是一个隐式模态：即使身上没穿整套战甲，也要常驻显示当前路由状态。
		renderRemoteCommandBanner(graphics, client);
		ArmorVariant variant = ModItems.fullSetVariant(client.player).orElse(null);
		if (variant == null) return;

		ArmorCombatClient.OwnerState state = ArmorCombatClient.ownerState();
		float energy = Mth.clamp(state.energy() / ArmorCombatTuning.MAX_ENERGY, 0.0F, 1.0F);
		float heat = Mth.clamp(state.heat() / ArmorCombatTuning.MAX_HEAT, 0.0F, 1.0F);
		int cooldown = ArmorCombatClient.wristPulseCooldownTicks();
		int coreCooldown = ArmorCombatClient.coreBeamCooldownTicks();
		int diveCooldown = ArmorCombatClient.diveImpactCooldownTicks();
		Component resources = Component.translatable("hud.flight_armor.combat_status",
				Math.round(state.energy()), Math.round(state.heat()), cooldown, coreCooldown, diveCooldown);
		Component status = Component.translatable(
				"item.flight_armor.variant." + variant.serializedName(), resources);
		// Keep resource values away from the vanilla action-bar message at bottom center.
		int panelWidth = Math.max(BAR_WIDTH, client.font.width(status));
		int x = graphics.guiWidth() - panelWidth - 10;
		int y = 22;
		int barX = x + panelWidth - BAR_WIDTH;
		int textX = x + panelWidth - client.font.width(status);

		graphics.fill(barX - 2, y - 2, barX + BAR_WIDTH + 2, y + 15, 0x8A071018);
		graphics.text(client.font, status, textX, y - 11,
				state.overheated() ? 0xFFFF5A62 : 0xFFE4F9FF, true);

		graphics.fill(barX, y, barX + BAR_WIDTH, y + 5, 0xCC10212A);
		graphics.fill(barX, y, barX + Math.round(BAR_WIDTH * energy), y + 5,
				0xFF000000 | variant.primaryLightRgb());
		graphics.fill(barX, y + 8, barX + BAR_WIDTH, y + 13, 0xCC281522);
		int heatWidth = Math.round(BAR_WIDTH * heat);
		graphics.fill(barX + BAR_WIDTH - heatWidth, y + 8, barX + BAR_WIDTH, y + 13,
				state.overheated() ? 0xFFFF394F : 0xFFE34CFF);
		if (cooldown > 0) {
			int readyWidth = Math.round(BAR_WIDTH * (1.0F - Mth.clamp(cooldown / 8.0F, 0.0F, 1.0F)));
			graphics.fill(barX, y + 5, barX + readyWidth, y + 7, 0xFFFFD95A);
		}
		ArmorAttackType chargingAttack = ArmorCombatClient.attackType(client.player);
		boolean lanceCharge = chargingAttack == ArmorAttackType.HAND_BEAM_LANCE;
		if ((chargingAttack == ArmorAttackType.CORE_BEAM || lanceCharge)
				&& ArmorCombatClient.phase(client.player) == ArmorCombatPhase.CHARGING) {
			float charge = ArmorCombatClient.phaseProgress(client.player, 1.0F);
			int chargeWidth = Math.round(BAR_WIDTH * charge);
			graphics.fill(barX, y + 5, barX + chargeWidth, y + 7,
					charge >= 0.999F ? 0xFFFFFFFF : 0xFF000000 | variant.primaryLightRgb());
			// 灭世档用独立文案：同一条蓄力条承担两种武器，读数不能含糊。
			Component charging = Component.translatable(lanceCharge
							? "hud.flight_armor.lance_charge"
							: "hud.flight_armor.core_charge",
					Math.round(charge * 100.0F));
			graphics.text(client.font, charging,
					x + panelWidth - client.font.width(charging), y + 16,
					lanceCharge ? 0xFFFFF3C2 : 0xFFFFE8FF, true);
		}
		/*
		 * 灭世模式是隐式模态：开着的时候左键不再是挖矿而是扳机。不常驻显示的话，玩家会以为
		 * 「左键坏了」。这里把切换键名一起显示，因为它是可重绑的，写死文案会说谎。
		 */
		if (ArmorCombatClient.handBeamArmed()) {
			Component armed = Component.translatable("hud.flight_armor.hand_beam_armed",
					ArmorCombatClient.handBeamKeyName());
			graphics.text(client.font, armed, x + panelWidth - client.font.width(armed),
					y + 24, 0xFFFFF3C2, true);
		}
	}

	/**
	 * 常驻的遥控指令模式条：右侧垂直居中，与左侧速度 HUD 对称，避开顶部资源条与底部动作栏。
	 * 不只显示「处于遥控态」，而是把该模式下每颗键的临时含义直接列出来，
	 * 让「模式改变按键」从惊喜变成看得见。键名随按键设置实时变化。
	 */
	private static void renderRemoteCommandBanner(GuiGraphicsExtractor graphics, Minecraft client) {
		if (!ArmorCombatClient.remoteCommandModeActive()
				|| !FlightArmorClientSettingsStore.get().hudEnabled()) return;
		Component title = Component.translatable("hud.flight_armor.remote_command");
		List<Component> lines = ArmorCombatClient.remoteCommandModeLines();
		int contentWidth = client.font.width(title);
		for (Component line : lines) {
			contentWidth = Math.max(contentWidth, client.font.width(line));
		}
		int panelWidth = contentWidth + 16;
		int lineHeight = 11;
		int panelHeight = 16 + lines.size() * lineHeight;
		int x = graphics.guiWidth() - panelWidth - 10;
		int y = Math.max(10, (graphics.guiHeight() - panelHeight) / 2);
		graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xB40F0A1E);
		graphics.fill(x, y, x + 2, y + panelHeight, 0xFFCF82FF);
		graphics.text(client.font, title, x + 7, y + 4, 0xFFE9D9FF, true);
		int lineY = y + 16;
		for (Component line : lines) {
			graphics.text(client.font, line, x + 8, lineY, 0xFFCFA8E8, true);
			lineY += lineHeight;
		}
	}
}
