package dev.codex.flightarmor.integrity;

/** Pure integrity arithmetic shared by the server controller and offline regression tests. */
public final class ArmorIntegrityRules {
	public static final int MAX_INTEGRITY = 1_000;
	public static final int DEGRADED_THRESHOLD = 250;
	public static final int PLATFORM_REPAIR_PER_TICK = 8;

	private ArmorIntegrityRules() {}

	public enum DamageKind {
		GENERIC(1.0D), PROJECTILE(0.9D), EXPLOSION(1.35D), FIRE(0.45D), BYPASS(1.8D);
		private final double multiplier;
		DamageKind(double multiplier) { this.multiplier = multiplier; }
	}

	public static int damageUnits(float rawDamage, DamageKind kind) {
		if (!Float.isFinite(rawDamage) || rawDamage <= 0.0F) return 0;
		DamageKind safeKind = kind == null ? DamageKind.GENERIC : kind;
		double scaled = rawDamage * 24.0D * safeKind.multiplier;
		if (!Double.isFinite(scaled)) return MAX_INTEGRITY;
		return clamp((int) Math.max(1L, Math.min(MAX_INTEGRITY, (long) Math.ceil(scaled))));
	}

	public static int applyDamage(int current, int damageUnits) {
		return clamp(clamp(current) - Math.max(0, damageUnits));
	}

	public static int repair(int current, int repairUnits) {
		return clamp(clamp(current) + Math.max(0, repairUnits));
	}

	public static boolean isDisabled(int integrity) { return clamp(integrity) == 0; }

	public static boolean isDegraded(int integrity) {
		int safe = clamp(integrity);
		return safe > 0 && safe < DEGRADED_THRESHOLD;
	}

	/** Stable flight is gated separately at zero; this floor keeps recall and platform return recoverable. */
	public static double recoveryMovementMultiplier(int integrity) {
		int safe = clamp(integrity);
		if (safe >= DEGRADED_THRESHOLD) return 1.0D;
		return 0.30D + 0.70D * safe / (double) DEGRADED_THRESHOLD;
	}

	public static double attackDelayMultiplier(int integrity) {
		int safe = clamp(integrity);
		if (safe <= 0) return Double.POSITIVE_INFINITY;
		if (safe >= DEGRADED_THRESHOLD) return 1.0D;
		return 1.0D + 2.0D * (DEGRADED_THRESHOLD - safe) / (double) DEGRADED_THRESHOLD;
	}

	public static long scaledAttackDelay(long baseDelay, int integrity) {
		if (baseDelay <= 0L) return 0L;
		double multiplier = attackDelayMultiplier(integrity);
		if (!Double.isFinite(multiplier)) return Long.MAX_VALUE;
		return Math.max(baseDelay, Math.round(baseDelay * multiplier));
	}

	public static int percent(int integrity) {
		return (int) Math.round(clamp(integrity) * 100.0D / MAX_INTEGRITY);
	}

	public static int clamp(int integrity) { return Math.max(0, Math.min(MAX_INTEGRITY, integrity)); }
}
