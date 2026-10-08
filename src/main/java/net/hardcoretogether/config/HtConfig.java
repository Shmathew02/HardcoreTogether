package net.hardcoretogether.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.hall.ArrowPath;
import net.hardcoretogether.hall.HallBounds;

import net.minecraft.world.Difficulty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server settings from config/hardcore_together.json, read once at startup. Missing keys are added with their
 * defaults; existing values are never changed.
 */
public record HtConfig(int countdownSeconds, String fixedSeed, Difficulty difficulty, boolean lockDifficulty, int timerSaveSeconds,
		HallBounds hallBounds, ArrowPath.Settings arrows, int alwaysLitOffset, boolean allowTestDeaths, String plaqueBillboard,
		int plaqueBackgroundArgb, int deathsBoardMaxPlayers, int recordsTitleColor, int wallBoardBackgroundArgb,
		double causesBoardMinTextScale, boolean portalStart, boolean portalEffects) {
	public static final String FILE_NAME = "hardcore_together.json";
	public static final int DEFAULT_COUNTDOWN_SECONDS = 10;
	public static final Difficulty DEFAULT_DIFFICULTY = Difficulty.HARD;
	public static final boolean DEFAULT_LOCK_DIFFICULTY = true;
	public static final int DEFAULT_TIMER_SAVE_SECONDS = 60;
	/** Hall lamps from the spawn block y + this up to the top of the hall region are always lit. */
	public static final int DEFAULT_ALWAYS_LIT_OFFSET = 3;
	/** Statue plaques: "vertical" turns to face every viewer, "fixed" faces the statue's way. */
	public static final String DEFAULT_PLAQUE_BILLBOARD = "vertical";
	/** About 20% opaque black. */
	public static final int DEFAULT_PLAQUE_BACKGROUND = 0x33000000;
	public static final int DEFAULT_DEATHS_BOARD_MAX_PLAYERS = 10;
	/** Record board titles: Minecraft red. */
	public static final int DEFAULT_RECORDS_TITLE_COLOR = 0xFF5555;
	/** The wall-mounted Runs and Causes boards: about 35% opaque black, to stand out on dark stone. */
	public static final int DEFAULT_WALL_BOARD_BACKGROUND = 0x59000000;
	/**
	 * The causes board never shrinks its text below this scale (font pixel 0.025 blocks, so glyphs about 0.18 blocks
	 * tall: easy to read from anywhere near the wall); below it the list splits into two columns, then "+N more".
	 */
	public static final double DEFAULT_CAUSES_BOARD_MIN_TEXT_SCALE = 1.0;
	/** start_mode: "portal" (players wait in the hall and walk through the portal) or "auto" (sent at once). */
	public static final String DEFAULT_START_MODE = "portal";

	public static HtConfig defaults() {
		return new HtConfig(DEFAULT_COUNTDOWN_SECONDS, "", DEFAULT_DIFFICULTY, DEFAULT_LOCK_DIFFICULTY, DEFAULT_TIMER_SAVE_SECONDS,
			HallBounds.DEFAULT, ArrowPath.Settings.DEFAULT, DEFAULT_ALWAYS_LIT_OFFSET, false, DEFAULT_PLAQUE_BILLBOARD, DEFAULT_PLAQUE_BACKGROUND,
			DEFAULT_DEATHS_BOARD_MAX_PLAYERS, DEFAULT_RECORDS_TITLE_COLOR, DEFAULT_WALL_BOARD_BACKGROUND,
			DEFAULT_CAUSES_BOARD_MIN_TEXT_SCALE, true, true);
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/** An empty fixed_seed means every run gets a new random seed. */
	public boolean hasFixedSeed() {
		return !fixedSeed.isBlank();
	}

	public static HtConfig load() {
		Path path = path();
		if (Files.notExists(path)) {
			JsonObject json = new JsonObject();
			addMissingDefaults(json);
			write(path, json);
			HardcoreTogether.LOGGER.info("Created default config at {}", path);
			return defaults();
		}

		try {
			JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			List<String> added = addMissingDefaults(json);
			if (!added.isEmpty()) {
				write(path, json);
				HardcoreTogether.LOGGER.info("Added missing config keys with defaults: {}", String.join(", ", added));
			}

			int countdown = json.get("countdown_seconds").getAsInt();
			if (countdown < 1) {
				HardcoreTogether.LOGGER.warn("countdown_seconds must be at least 1, got {}; using {}", countdown, DEFAULT_COUNTDOWN_SECONDS);
				countdown = DEFAULT_COUNTDOWN_SECONDS;
			}
			String seed = json.get("fixed_seed").isJsonNull() ? "" : json.get("fixed_seed").getAsString().trim();
			Difficulty difficulty = parseDifficulty(json.get("difficulty").getAsString());
			boolean lock = json.get("lock_difficulty").getAsBoolean();
			int timerSave = json.get("timer_save_seconds").getAsInt();
			if (timerSave < 5) {
				HardcoreTogether.LOGGER.warn("timer_save_seconds must be at least 5, got {}; using {}", timerSave, DEFAULT_TIMER_SAVE_SECONDS);
				timerSave = DEFAULT_TIMER_SAVE_SECONDS;
			}
			HallBounds bounds = new HallBounds(
				hallSize(json, "hall_width", HallBounds.DEFAULT.width(), 1, HallBounds.MAX_SIZE),
				hallSize(json, "hall_height", HallBounds.DEFAULT.height(), 1, HallBounds.MAX_SIZE),
				hallSize(json, "hall_depth", HallBounds.DEFAULT.depth(), 1, HallBounds.MAX_SIZE),
				hallSize(json, "hall_below_floor", HallBounds.DEFAULT.belowFloor(), 0, HallBounds.MAX_SIZE));
			ArrowPath.Settings d = ArrowPath.Settings.DEFAULT;
			String modeText = json.get("arrows_mode").getAsString();
			ArrowPath.Mode mode = ArrowPath.Mode.byId(modeText).orElseGet(() -> {
				HardcoreTogether.LOGGER.warn("Invalid arrows_mode \"{}\" (allowed: wave, blink, solid); using {}", modeText, d.mode().id());
				return d.mode();
			});
			ArrowPath.Settings arrows = new ArrowPath.Settings(mode,
				hallSize(json, "arrows_band_length", d.bandLength(), 1, 1000),
				hallSize(json, "arrows_step_ticks", d.stepTicks(), 1, 1200),
				hallSize(json, "arrows_pause_ticks", d.pauseTicks(), 0, 1200),
				hallSize(json, "arrows_blink_ticks", d.blinkTicks(), 1, 1200));
			int alwaysLit = hallSize(json, "always_lit_offset", DEFAULT_ALWAYS_LIT_OFFSET, -HallBounds.MAX_SIZE, HallBounds.MAX_SIZE);
			boolean testDeaths = json.get("allow_test_deaths").getAsBoolean();
			String billboard = json.get("plaque_billboard").getAsString().trim().toLowerCase(Locale.ROOT);
			if (!billboard.equals("vertical") && !billboard.equals("fixed")) {
				HardcoreTogether.LOGGER.warn("plaque_billboard must be vertical or fixed, got \"{}\"; using {}", billboard, DEFAULT_PLAQUE_BILLBOARD);
				billboard = DEFAULT_PLAQUE_BILLBOARD;
			}
			int background = parseArgb(json.get("plaque_background_argb").getAsString(), DEFAULT_PLAQUE_BACKGROUND);
			int deathsMax = hallSize(json, "deaths_board_max_players", DEFAULT_DEATHS_BOARD_MAX_PLAYERS, 1, 100);
			int titleColor = parseArgb(json.get("records_title_color").getAsString(), DEFAULT_RECORDS_TITLE_COLOR) & 0xFFFFFF;
			return new HtConfig(countdown, seed, difficulty, lock, timerSave, bounds, arrows, alwaysLit, testDeaths, billboard, background,
				deathsMax, titleColor, parseArgb(json.get("wall_board_background_argb").getAsString(), DEFAULT_WALL_BOARD_BACKGROUND),
				minScale(json), startMode(json), json.get("portal_effects").getAsBoolean());
		} catch (IOException | IllegalStateException | JsonParseException | UnsupportedOperationException | ClassCastException e) {
			HardcoreTogether.LOGGER.error("Could not read {}, using defaults", path, e);
			return defaults();
		}
	}

	/** Adds every key the file is missing, with its default; existing values are never touched. */
	static List<String> addMissingDefaults(JsonObject json) {
		List<String> added = new ArrayList<>();
		if (!json.has("countdown_seconds")) {
			json.addProperty("countdown_seconds", DEFAULT_COUNTDOWN_SECONDS);
			added.add("countdown_seconds");
		}
		if (!json.has("fixed_seed")) {
			json.addProperty("fixed_seed", "");
			added.add("fixed_seed");
		}
		if (!json.has("difficulty")) {
			json.addProperty("difficulty", DEFAULT_DIFFICULTY.getSerializedName());
			added.add("difficulty");
		}
		if (!json.has("lock_difficulty")) {
			json.addProperty("lock_difficulty", DEFAULT_LOCK_DIFFICULTY);
			added.add("lock_difficulty");
		}
		if (!json.has("timer_save_seconds")) {
			json.addProperty("timer_save_seconds", DEFAULT_TIMER_SAVE_SECONDS);
			added.add("timer_save_seconds");
		}
		addDefault(json, added, "hall_width", HallBounds.DEFAULT.width());
		addDefault(json, added, "hall_height", HallBounds.DEFAULT.height());
		addDefault(json, added, "hall_depth", HallBounds.DEFAULT.depth());
		addDefault(json, added, "hall_below_floor", HallBounds.DEFAULT.belowFloor());
		if (!json.has("arrows_mode")) {
			json.addProperty("arrows_mode", ArrowPath.Settings.DEFAULT.mode().id());
			added.add("arrows_mode");
		}
		addDefault(json, added, "arrows_band_length", ArrowPath.Settings.DEFAULT.bandLength());
		addDefault(json, added, "arrows_step_ticks", ArrowPath.Settings.DEFAULT.stepTicks());
		addDefault(json, added, "arrows_pause_ticks", ArrowPath.Settings.DEFAULT.pauseTicks());
		addDefault(json, added, "arrows_blink_ticks", ArrowPath.Settings.DEFAULT.blinkTicks());
		addDefault(json, added, "always_lit_offset", DEFAULT_ALWAYS_LIT_OFFSET);
		if (!json.has("allow_test_deaths")) {
			// /ht test-death. Test deaths are recorded like real ones (statue, plaque, stats, record boards)
			// unless an admin clears them, so leave this false on a real server.
			json.addProperty("allow_test_deaths", false);
			added.add("allow_test_deaths");
		}
		if (!json.has("plaque_billboard")) {
			json.addProperty("plaque_billboard", DEFAULT_PLAQUE_BILLBOARD);
			added.add("plaque_billboard");
		}
		addDefault(json, added, "deaths_board_max_players", DEFAULT_DEATHS_BOARD_MAX_PLAYERS);
		if (!json.has("start_mode")) {
			json.addProperty("start_mode", DEFAULT_START_MODE);
			added.add("start_mode");
		}
		if (!json.has("portal_effects")) {
			json.addProperty("portal_effects", true);
			added.add("portal_effects");
		}
		if (!json.has("causes_board_min_text_scale")) {
			json.addProperty("causes_board_min_text_scale", DEFAULT_CAUSES_BOARD_MIN_TEXT_SCALE);
			added.add("causes_board_min_text_scale");
		}
		if (!json.has("wall_board_background_argb")) {
			json.addProperty("wall_board_background_argb", String.format("0x%08X", DEFAULT_WALL_BOARD_BACKGROUND));
			added.add("wall_board_background_argb");
		}
		if (!json.has("records_title_color")) {
			json.addProperty("records_title_color", String.format("#%06X", DEFAULT_RECORDS_TITLE_COLOR));
			added.add("records_title_color");
		}
		if (!json.has("plaque_background_argb")) {
			json.addProperty("plaque_background_argb", String.format("0x%08X", DEFAULT_PLAQUE_BACKGROUND));
			added.add("plaque_background_argb");
		}
		return added;
	}

	private static void addDefault(JsonObject json, List<String> added, String key, int value) {
		if (!json.has(key)) {
			json.addProperty(key, value);
			added.add(key);
		}
	}

	/** An int setting in [min, max], or the fallback (with a warning) outside it. */
	private static int hallSize(JsonObject json, String key, int fallback, int min, int max) {
		int value = json.get(key).getAsInt();
		if (value < min || value > max) {
			HardcoreTogether.LOGGER.warn("{} must be between {} and {}, got {}; using {}", key, min, max, value, fallback);
			return fallback;
		}
		return value;
	}

	/** True for start_mode portal, false for auto. */
	private static boolean startMode(JsonObject json) {
		String v = json.get("start_mode").getAsString().trim().toLowerCase(Locale.ROOT);
		if (!v.equals("portal") && !v.equals("auto")) {
			HardcoreTogether.LOGGER.warn("start_mode must be portal or auto, got \"{}\"; using {}", v, DEFAULT_START_MODE);
			return true;
		}
		return v.equals("portal");
	}

	/** causes_board_min_text_scale: the smallest text scale, 0.25 to 10. */
	private static double minScale(JsonObject json) {
		double v = json.get("causes_board_min_text_scale").getAsDouble();
		if (v < 0.25 || v > 10) {
			HardcoreTogether.LOGGER.warn("causes_board_min_text_scale must be between 0.25 and 10, got {}; using {}", v, DEFAULT_CAUSES_BOARD_MIN_TEXT_SCALE);
			return DEFAULT_CAUSES_BOARD_MIN_TEXT_SCALE;
		}
		return v;
	}

	/** "0x33000000", "#FF5555", "33000000" or a plain decimal int; the fallback (with a warning) otherwise. */
	private static int parseArgb(String value, int fallback) {
		String v = value.trim();
		try {
			if (v.startsWith("0x") || v.startsWith("0X") || v.startsWith("#")) {
				return (int) Long.parseLong(v.substring(v.startsWith("#") ? 1 : 2), 16);
			}
			return v.length() == 8 || v.length() == 6 ? (int) Long.parseLong(v, 16) : Integer.parseInt(v);
		} catch (NumberFormatException e) {
			HardcoreTogether.LOGGER.warn("\"{}\" is not a colour; using 0x{}", value, Integer.toHexString(fallback));
			return fallback;
		}
	}

	private static Difficulty parseDifficulty(String value) {
		String v = value.trim().toLowerCase(Locale.ROOT);
		for (Difficulty d : Difficulty.values()) {
			if (d.getSerializedName().equals(v)) {
				return d;
			}
		}
		HardcoreTogether.LOGGER.warn("Invalid difficulty \"{}\" in {} (allowed: peaceful, easy, normal, hard); using {}",
			value, FILE_NAME, DEFAULT_DIFFICULTY.getSerializedName());
		return DEFAULT_DIFFICULTY;
	}

	/** Saves a new arrows_mode in the config file, leaving every other key as it is. */
	public static void saveArrowsMode(ArrowPath.Mode mode) throws IOException {
		Path path = path();
		JsonObject json = Files.exists(path) ? JsonParser.parseString(Files.readString(path)).getAsJsonObject() : new JsonObject();
		json.addProperty("arrows_mode", mode.id());
		Files.createDirectories(path.getParent());
		Files.writeString(path, ConfigText.render(json));
	}

	private static void write(Path path, JsonObject json) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, ConfigText.render(json));
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("Could not write config to {}", path, e);
		}
	}
}
