package dev.codex.flightarmor.client.platform;

import dev.codex.flightarmor.platform.ChargingPlatformBlockEntity;
import dev.codex.flightarmor.platform.ChargingPlatformDisplayMode;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 平台陈列的单次机械过渡。
 *
 * <p>状态仅在整甲/散件模式发生改变时推进；模式稳定后所有数值恒定，因而不会让平台上的
 * 战甲重新出现循环旋转、呼吸或闪烁。弱键表也不会延长方块实体的生命周期。</p>
 */
final class ChargingPlatformVisualState {
	static final float TRANSITION_TICKS = 18.0F;
	private static final Map<ChargingPlatformBlockEntity, Transition> TRANSITIONS = new WeakHashMap<>();

	private ChargingPlatformVisualState() {
	}

	static Sample sample(ChargingPlatformBlockEntity platform, float partialTick) {
		ChargingPlatformDisplayMode target = platform.getDisplayMode();
		double now = platform.getLevel() == null
				? 0.0D : platform.getLevel().getGameTime() + partialTick;
		Transition transition = TRANSITIONS.get(platform);
		if (transition == null) {
			transition = new Transition(target, target, now);
			TRANSITIONS.put(platform, transition);
		} else if (transition.target() != target) {
			ChargingPlatformDisplayMode visible = dominantMode(transition, now);
			transition = new Transition(visible, target, now);
			TRANSITIONS.put(platform, transition);
		}

		if (transition.from() == transition.target()) return settled(target);
		float progress = Mth.clamp(
				(float) ((now - transition.startedAt()) / TRANSITION_TICKS), 0.0F, 1.0F);
		if (progress >= 1.0F) {
			TRANSITIONS.put(platform, new Transition(target, target, now));
			return settled(target);
		}

		if (target == ChargingPlatformDisplayMode.ASSEMBLED) {
			float partsScale = 1.0F - smoothWindow(progress, 0.00F, 0.58F);
			float assembledScale = smoothWindow(progress, 0.24F, 0.82F);
			float lockProgress = smoothWindow(progress, 0.48F, 1.00F);
			return new Sample(assembledScale, partsScale, lockProgress, true);
		}

		float lockProgress = 1.0F - smoothWindow(progress, 0.00F, 0.34F);
		float assembledScale = 1.0F - smoothWindow(progress, 0.12F, 0.68F);
		float partsScale = smoothWindow(progress, 0.38F, 1.00F);
		return new Sample(assembledScale, partsScale, lockProgress, true);
	}

	private static ChargingPlatformDisplayMode dominantMode(Transition transition, double now) {
		float progress = Mth.clamp(
				(float) ((now - transition.startedAt()) / TRANSITION_TICKS), 0.0F, 1.0F);
		return progress < 0.5F ? transition.from() : transition.target();
	}

	private static Sample settled(ChargingPlatformDisplayMode mode) {
		return mode == ChargingPlatformDisplayMode.ASSEMBLED
				? new Sample(1.0F, 0.0F, 1.0F, false)
				: new Sample(0.0F, 1.0F, 0.0F, false);
	}

	private static float smoothWindow(float value, float start, float end) {
		float normalized = Mth.clamp(
				(value - start) / Math.max(0.001F, end - start), 0.0F, 1.0F);
		return normalized * normalized * (3.0F - 2.0F * normalized);
	}

	static record Sample(float assembledScale, float partsScale,
			float lockProgress, boolean transitioning) {
	}

	private record Transition(ChargingPlatformDisplayMode from,
			ChargingPlatformDisplayMode target, double startedAt) {
	}
}
