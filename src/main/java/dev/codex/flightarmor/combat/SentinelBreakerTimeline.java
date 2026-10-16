package dev.codex.flightarmor.combat;

/**
 * Shared deployment timeline for the Sentinel suit's heavy melee form.
 *
 * <p>The stages are intentionally staggered: the forearm receiver opens first,
 * then the internal load-bearing spine extends, the translucent blade surface
 * projects from that support, and only the two narrow cutting edges intensify
 * after the mechanical structure is visibly in place. Rendering both camera
 * profiles from this sample prevents first- and third-person deployment from
 * drifting apart.</p>
 */
public final class SentinelBreakerTimeline {
	private SentinelBreakerTimeline() {
	}

	public static Sample sample(float rawProgress) {
		float progress = clamp(rawProgress);
		float unlock = smoothstep(0.00F, 0.16F, progress);
		float jawOpen = smoothstep(0.06F, 0.30F, progress);
		float carriage = smoothstep(0.12F, 0.40F, progress);
		float spineBase = smoothstep(0.22F, 0.48F, progress);
		float spineMiddle = smoothstep(0.34F, 0.62F, progress);
		float spineTip = smoothstep(0.46F, 0.74F, progress);
		float breakerHead = smoothstep(0.54F, 0.88F, progress);
		float energyEdge = smoothstep(0.72F, 0.98F, progress);
		float lock = smoothstep(0.86F, 1.00F, progress);
		return new Sample(progress, unlock, jawOpen, carriage,
				spineBase, spineMiddle, spineTip, breakerHead, energyEdge, lock);
	}

	private static float smoothstep(float start, float end, float value) {
		float normalized = clamp((value - start) / (end - start));
		return normalized * normalized * (3.0F - 2.0F * normalized);
	}

	private static float clamp(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}

	public record Sample(
			float progress,
			float unlock,
			float jawOpen,
			float carriage,
			float spineBase,
			float spineMiddle,
			float spineTip,
			float breakerHead,
			float energyEdge,
			float lock) {
	}
}
