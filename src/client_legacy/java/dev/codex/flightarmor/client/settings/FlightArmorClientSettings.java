package dev.codex.flightarmor.client.settings;

/**
 * Client-only presentation preferences. These values must never be used to authorize
 * combat, escort, terrain destruction, or any other server-owned behavior.
 */
public record FlightArmorClientSettings(
		int schemaVersion,
		boolean hudEnabled,
		boolean trailEnabled,
		boolean environmentFeedbackEnabled,
		double masterVolume) {
	public static final int CURRENT_SCHEMA_VERSION = 1;
	public static final double DEFAULT_MASTER_VOLUME = 1.0D;

	public FlightArmorClientSettings {
		schemaVersion = CURRENT_SCHEMA_VERSION;
		if (!Double.isFinite(masterVolume)) masterVolume = DEFAULT_MASTER_VOLUME;
		masterVolume = Math.max(0.0D, Math.min(1.0D, masterVolume));
	}

	public static FlightArmorClientSettings defaults() {
		return new FlightArmorClientSettings(CURRENT_SCHEMA_VERSION,
				true, true, true, DEFAULT_MASTER_VOLUME);
	}

	public FlightArmorClientSettings withHudEnabled(boolean enabled) {
		return new FlightArmorClientSettings(schemaVersion, enabled, trailEnabled,
				environmentFeedbackEnabled, masterVolume);
	}

	public FlightArmorClientSettings withTrailEnabled(boolean enabled) {
		return new FlightArmorClientSettings(schemaVersion, hudEnabled, enabled,
				environmentFeedbackEnabled, masterVolume);
	}

	public FlightArmorClientSettings withEnvironmentFeedbackEnabled(boolean enabled) {
		return new FlightArmorClientSettings(schemaVersion, hudEnabled, trailEnabled,
				enabled, masterVolume);
	}

	public FlightArmorClientSettings withMasterVolume(double volume) {
		return new FlightArmorClientSettings(schemaVersion, hudEnabled, trailEnabled,
				environmentFeedbackEnabled, volume);
	}
}
