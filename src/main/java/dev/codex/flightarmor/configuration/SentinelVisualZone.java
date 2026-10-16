package dev.codex.flightarmor.configuration;

import java.util.Locale;

/**
 * Stable group-name routing contract for Sentinel's whole-suit configuration animation.
 *
 * <p>Keeping this classifier free of client/Minecraft dependencies lets offline tests prove
 * that real generated groups from every armor slot enter a transformation branch. It also
 * prevents a future wing-only refactor from silently dropping faceplate, gauntlet, knee or
 * boot articulation.</p>
 */
public enum SentinelVisualZone {
	HEAD,
	TORSO,
	ARM,
	LEG,
	BOOT,
	WING,
	DRONE_POD,
	NONE;

	public static SentinelVisualZone fromGroup(String rawName) {
		if (rawName == null || rawName.isBlank()) return NONE;
		String name = rawName.toLowerCase(Locale.ROOT);
		/*
		 * These four visible helmet surfaces are deliberately shared clearance
		 * meshes, not Sentinel-authored outer plates. They still belong to the
		 * transforming face assembly. Flexible seals/underlayers remain unclassified
		 * so the articulated shell never opens a skin gap.
		 */
		if (name.equals("head_faceplate_gunmetal")
				|| name.equals("visor_lens_visor")
				|| name.equals("head_rear_left_gunmetal")
				|| name.equals("head_rear_right_gunmetal")) {
			return HEAD;
		}
		if (!name.contains("_sentinel_")) return NONE;
		if (name.contains("sentinel_drone_pod")) return DRONE_POD;
		if (name.contains("sentinel_wing")) return WING;
		if (name.startsWith("head_") || name.startsWith("visor_")) return HEAD;
		if (name.startsWith("body_") || name.startsWith("core_")) return TORSO;
		if (name.startsWith("arm_")) return ARM;
		if (name.startsWith("leg_")) return LEG;
		if (name.startsWith("boot_")) return BOOT;
		return NONE;
	}
}
