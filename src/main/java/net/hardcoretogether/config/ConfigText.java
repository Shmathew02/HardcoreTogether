package net.hardcoretogether.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes config/hardcore_together.json with a comment above each option. Gson's JsonParser reads in lenient
 * mode, so the // comments load fine; they are rewritten from here whenever the mod saves the file.
 */
public final class ConfigText {
	private static final Gson GSON = new Gson();

	private static final List<String> HEADER = List.of(
		"Hardcore Together server settings. Read once at startup: restart the server after editing.",
		"Missing options are added back with their defaults; unknown options are kept but ignored.");

	/** Every option in file order, with its comment lines. */
	static final Map<String, List<String>> OPTIONS = new LinkedHashMap<>();

	static {
		option("countdown_seconds", "Seconds between a death and the world reset (at least 1).");
		option("fixed_seed", "Seed for every new world. Empty: a new random seed each run.");
		option("difficulty", "Difficulty of every new world: peaceful, easy, normal or hard.");
		option("lock_difficulty", "true: nobody can change the difficulty in game (like a hardcore world).");
		option("timer_save_seconds", "How often the run timer is saved to disk, in seconds (at least 5).");
		option("hall_width", "Size of the Death Hall region in blocks, centred on the hall spawn (1 to 256).",
			"Edit mode, backups and the hall rules cover this region.",
			"This doesn't resize the hall build itself; it only sets the area the hall rules, edit mode and backups",
			"cover. Leave it alone unless you've built a bigger hall.");
		option("hall_height");
		option("hall_depth");
		option("hall_below_floor", "How far the hall region reaches below the spawn floor, in blocks.");
		option("arrows_mode", "Floor arrow lights that lead to the portal: wave, blink or solid.");
		option("arrows_band_length", "Wave mode: how many lamps are lit at once.");
		option("arrows_step_ticks", "Wave mode: ticks before the lit band moves one lamp (20 ticks = 1 second).");
		option("arrows_pause_ticks", "Wave mode: ticks to wait between waves.");
		option("arrows_blink_ticks", "Blink mode: ticks on, then the same ticks off.");
		option("always_lit_offset", "Redstone lamps from this many blocks above the hall spawn floor upward stay lit.");
		option("allow_test_deaths", "Enables /ht test-death for testing. Test deaths are recorded like real ones",
			"(statue, plaque, stats, record boards) unless an admin clears them. Keep false on a real server.");
		option("plaque_billboard", "Statue plaques: vertical turns to face each viewer, fixed faces the way the statue faces.");
		option("plaque_background_argb", "Plaque background colour as 0xAARRGGBB (AA = opacity).");
		option("deaths_board_max_players", "How many players the Deaths board lists (1 to 100).");
		option("records_title_color", "Colour of the record board titles, as #RRGGBB.");
		option("wall_board_background_argb", "Background of the Runs and Causes wall boards, as 0xAARRGGBB.");
		option("causes_board_min_text_scale", "Smallest text size on the Causes board (0.25 to 10). When the list no longer",
			"fits at this size it splits into two columns, then ends with \"+N more\".");
		option("start_mode", "portal: after a reset everyone waits in the Death Hall and the run starts once all",
			"online players stand in the portal. auto: players are sent into the new world at once.");
		option("portal_effects", "Particles and a portal hum at the portal while it is ready.");
	}

	private ConfigText() {
	}

	private static void option(String key, String... comment) {
		OPTIONS.put(key, List.of(comment));
	}

	/** The file contents: known options in order with their comments, then any unknown keys. */
	public static String render(JsonObject json) {
		StringBuilder out = new StringBuilder();
		for (String line : HEADER) {
			out.append("// ").append(line).append('\n');
		}
		out.append("{\n");
		List<String> keys = new ArrayList<>();
		OPTIONS.keySet().stream().filter(json::has).forEach(keys::add);
		json.keySet().stream().filter(k -> !OPTIONS.containsKey(k)).forEach(keys::add);
		for (int i = 0; i < keys.size(); i++) {
			String key = keys.get(i);
			List<String> comment = OPTIONS.get(key);
			if (comment != null && !comment.isEmpty()) {
				if (i > 0) {
					out.append('\n');
				}
				for (String line : comment) {
					out.append("  // ").append(line).append('\n');
				}
			}
			JsonElement value = json.get(key);
			out.append("  ").append(GSON.toJson(key)).append(": ").append(GSON.toJson(value));
			out.append(i < keys.size() - 1 ? ",\n" : "\n");
		}
		out.append("}\n");
		return out.toString();
	}
}
