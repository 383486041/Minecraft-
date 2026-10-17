package dev.codex.flightarmor.client.settings;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Compact local presentation settings page opened from the armor-core command panel. */
public final class FlightArmorClientSettingsScreen extends Screen {
	private static final int PANEL_WIDTH = 336;
	private static final int PANEL_HEIGHT = 218;
	private static final int CYAN = 0xFF65EEFF;
	private final Screen parent;
	private FlightArmorClientSettings draft;
	private Button hudButton;
	private Button trailButton;
	private Button environmentButton;
	private VolumeSlider volumeSlider;
	private int left;
	private int top;

	public FlightArmorClientSettingsScreen(Screen parent) {
		super(Component.translatable("screen.flight_armor.client_settings.title"));
		this.parent = parent;
		this.draft = FlightArmorClientSettingsStore.get();
	}

	@Override
	protected void init() {
		left = (width - PANEL_WIDTH) / 2;
		top = (height - PANEL_HEIGHT) / 2;
		int buttonX = left + 18;
		int buttonWidth = PANEL_WIDTH - 36;
		hudButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
			draft = draft.withHudEnabled(!draft.hudEnabled());
			refreshLabels();
		}).bounds(buttonX, top + 48, buttonWidth, 20).build());
		trailButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
			draft = draft.withTrailEnabled(!draft.trailEnabled());
			refreshLabels();
		}).bounds(buttonX, top + 74, buttonWidth, 20).build());
		environmentButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
			draft = draft.withEnvironmentFeedbackEnabled(!draft.environmentFeedbackEnabled());
			refreshLabels();
		}).bounds(buttonX, top + 100, buttonWidth, 20).build());
		volumeSlider = addRenderableWidget(new VolumeSlider(buttonX, top + 126,
				buttonWidth, 20, draft.masterVolume()));
		addRenderableWidget(Button.builder(Component.translatable("screen.flight_armor.client_settings.reset"), button -> {
			draft = FlightArmorClientSettings.defaults();
			volumeSlider.setVolume(draft.masterVolume());
			refreshLabels();
		}).bounds(left + 18, top + 181, 96, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.flight_armor.client_settings.guide"),
				button -> {
					if (minecraft != null) minecraft.gui.setScreen(new FlightArmorGuideScreen(this));
				})
				.bounds(left + 120, top + 181, 96, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.flight_armor.client_settings.done"),
				button -> closeAndSave())
				.bounds(left + PANEL_WIDTH - 114, top + 181, 96, 20).build());
		refreshLabels();
	}

	private void refreshLabels() {
		if (hudButton != null) hudButton.setMessage(toggleLabel(
			"screen.flight_armor.client_settings.hud", draft.hudEnabled()));
		if (trailButton != null) trailButton.setMessage(toggleLabel(
			"screen.flight_armor.client_settings.trail", draft.trailEnabled()));
		if (environmentButton != null) environmentButton.setMessage(
			toggleLabel("screen.flight_armor.client_settings.environment",
				draft.environmentFeedbackEnabled()));
		if (volumeSlider != null) volumeSlider.updateMessage();
	}

	private static Component toggleLabel(String labelKey, boolean enabled) {
		return Component.translatable("screen.flight_armor.client_settings.toggle",
				Component.translatable(labelKey),
				Component.translatable(enabled ? "screen.flight_armor.client_settings.on"
						: "screen.flight_armor.client_settings.off"));
	}

	private void closeAndSave() {
		FlightArmorClientSettingsStore.set(draft);
		if (minecraft != null) minecraft.gui.setScreen(parent);
	}

	@Override
	public void onClose() {
		closeAndSave();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		extractBackground(graphics, mouseX, mouseY, partialTick);
		graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xF20A1218);
		graphics.fill(left + 1, top + 1, left + PANEL_WIDTH - 1, top + 29, 0xFF102630);
		graphics.outline(left, top, PANEL_WIDTH, PANEL_HEIGHT, CYAN);
		graphics.outline(left + 3, top + 3, PANEL_WIDTH - 6, PANEL_HEIGHT - 6, 0xFF294852);
		graphics.centeredText(font, title, left + PANEL_WIDTH / 2, top + 11, 0xFFEAFBFF);
		graphics.centeredText(font,
				Component.translatable("screen.flight_armor.client_settings.local_only"),
				left + PANEL_WIDTH / 2, top + 34, 0xFF87AEB9);
		graphics.centeredText(font,
				Component.translatable("screen.flight_armor.client_settings.config_path"),
				left + PANEL_WIDTH / 2, top + 155, 0xFF6E929D);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private final class VolumeSlider extends AbstractSliderButton {
		private VolumeSlider(int x, int y, int width, int height, double value) {
			super(x, y, width, height, Component.empty(), value);
			updateMessage();
		}

		private void setVolume(double next) {
			value = Math.max(0.0D, Math.min(1.0D, next));
			draft = draft.withMasterVolume(value);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("screen.flight_armor.client_settings.master_volume",
					Math.round(value * 100.0D)));
		}

		@Override
		protected void applyValue() {
			draft = draft.withMasterVolume(value);
		}
	}
}
