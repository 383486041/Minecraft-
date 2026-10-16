package dev.codex.flightarmor.configuration;

import java.util.Locale;
import java.util.Optional;

/**
 * Stable two-form configuration for the Sentinel heavy armor.
 *
 * <p>The serialized names are item data and must not be renamed. Only Sentinel owns this
 * configuration; Vanguard and Swift deliberately remain single-form suits.</p>
 */
public enum SentinelArmorMode {
	OVERDRIVE_FLIGHT("overdrive_flight"),
	BREACH_MELEE("breach_melee");

	public static final SentinelArmorMode DEFAULT = OVERDRIVE_FLIGHT;

	private final String serializedName;

	SentinelArmorMode(String serializedName) {
		this.serializedName = serializedName;
	}

	public String serializedName() {
		return serializedName;
	}

	public SentinelArmorMode next() {
		return this == OVERDRIVE_FLIGHT ? BREACH_MELEE : OVERDRIVE_FLIGHT;
	}

	public static Optional<SentinelArmorMode> find(String serializedName) {
		if (serializedName == null || serializedName.isBlank()) return Optional.empty();
		String normalized = serializedName.toLowerCase(Locale.ROOT);
		for (SentinelArmorMode mode : values()) {
			if (mode.serializedName.equals(normalized)) return Optional.of(mode);
		}
		return Optional.empty();
	}

	public static SentinelArmorMode fromSerializedName(String serializedName) {
		return find(serializedName).orElse(DEFAULT);
	}
}
