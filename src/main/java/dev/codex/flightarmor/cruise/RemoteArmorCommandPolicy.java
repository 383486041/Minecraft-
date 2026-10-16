package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.variant.ArmorVariant;

/** Pure timing and balance policy for nearby server-authoritative suit commands. */
public final class RemoteArmorCommandPolicy {
	public static final double MAX_COMMAND_DISTANCE = 48.0D;
	public static final int MIN_RESPONSE_TICKS = 6;
	public static final int MAX_RESPONSE_TICKS = 12;

	private RemoteArmorCommandPolicy() {
	}

	public static int responseTicks(double ownerToCarrierDistance) {
		if (!Double.isFinite(ownerToCarrierDistance)) return MAX_RESPONSE_TICKS;
		int ticks = MIN_RESPONSE_TICKS + (int) Math.floor(Math.max(0.0D, ownerToCarrierDistance) / 8.0D);
		return Math.max(MIN_RESPONSE_TICKS, Math.min(MAX_RESPONSE_TICKS, ticks));
	}

	public static UltimateProfile ultimateProfile(ArmorVariant variant) {
		return switch (variant == null ? ArmorVariant.DEFAULT : variant) {
			case VANGUARD -> new UltimateProfile(7.0D, 24.0F, 2.05D, 170);
			case SWIFT -> new UltimateProfile(5.0D, 16.0F, 1.25D, 105);
			case SENTINEL -> new UltimateProfile(6.0D, 21.0F, 1.70D, 140);
		};
	}

	public record UltimateProfile(double radius, float damage, double knockback, int cooldownTicks) {
	}
}
