package dev.codex.flightarmor.client.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.codex.flightarmor.FlightArmorMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Loads and atomically persists the client-only presentation configuration. */
public final class FlightArmorClientSettingsStore {
	private static final String FILE_NAME = "flight_armor-client.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static FlightArmorClientSettings current = FlightArmorClientSettings.defaults();
	private static Path configPath;
	private static boolean initialized;

	private FlightArmorClientSettingsStore() {
	}

	public static synchronized void initialize() {
		if (initialized) return;
		initialized = true;
		configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		if (Files.isRegularFile(configPath)) {
			current = load(configPath);
		} else {
			persist(current);
		}
	}

	public static FlightArmorClientSettings get() {
		ensureInitialized();
		return current;
	}

	/**
	 * Replaces all local preferences and saves them. The in-memory selection remains active
	 * even if the filesystem write fails, so the settings page always gives immediate feedback.
	 */
	public static synchronized boolean set(FlightArmorClientSettings settings) {
		ensureInitialized();
		current = settings == null ? FlightArmorClientSettings.defaults()
				: new FlightArmorClientSettings(settings.schemaVersion(), settings.hudEnabled(),
				settings.trailEnabled(), settings.environmentFeedbackEnabled(), settings.masterVolume());
		return persist(current);
	}

	public static float masterVolume() {
		return (float) get().masterVolume();
	}

	private static void ensureInitialized() {
		if (!initialized) initialize();
	}

	private static FlightArmorClientSettings load(Path path) {
		FlightArmorClientSettings defaults = FlightArmorClientSettings.defaults();
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) throw new IOException("root value is not an object");
			JsonObject object = root.getAsJsonObject();
			return new FlightArmorClientSettings(
					FlightArmorClientSettings.CURRENT_SCHEMA_VERSION,
					booleanValue(object, "hudEnabled", defaults.hudEnabled()),
					booleanValue(object, "trailEnabled", defaults.trailEnabled()),
					booleanValue(object, "environmentFeedbackEnabled",
							defaults.environmentFeedbackEnabled()),
					doubleValue(object, "masterVolume", defaults.masterVolume()));
		} catch (Exception exception) {
			FlightArmorMod.LOGGER.warn("Could not read client settings {}; using defaults for this session",
					path, exception);
			return defaults;
		}
	}

	private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
		try {
			JsonElement value = object.get(key);
			return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static double doubleValue(JsonObject object, String key, double fallback) {
		try {
			JsonElement value = object.get(key);
			return value != null && value.isJsonPrimitive() ? value.getAsDouble() : fallback;
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static boolean persist(FlightArmorClientSettings settings) {
		if (configPath == null) return false;
		Path temporary = configPath.resolveSibling(configPath.getFileName() + ".tmp");
		try {
			Files.createDirectories(configPath.getParent());
			Files.writeString(temporary, encode(settings), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
					StandardOpenOption.WRITE);
			try {
				Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException exception) {
			FlightArmorMod.LOGGER.warn("Could not save client settings {}", configPath, exception);
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			return false;
		}
	}

	private static String encode(FlightArmorClientSettings settings) {
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", FlightArmorClientSettings.CURRENT_SCHEMA_VERSION);
		object.addProperty("hudEnabled", settings.hudEnabled());
		object.addProperty("trailEnabled", settings.trailEnabled());
		object.addProperty("environmentFeedbackEnabled", settings.environmentFeedbackEnabled());
		object.addProperty("masterVolume", settings.masterVolume());
		return GSON.toJson(object) + System.lineSeparator();
	}
}
