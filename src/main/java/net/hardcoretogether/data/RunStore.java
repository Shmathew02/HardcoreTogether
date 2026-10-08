package net.hardcoretogether.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.hardcoretogether.HardcoreTogether;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes current-run.json and run-history.json in the data folder (<world>/hardcore_together/, which
 * survives resets). Writes are atomic. A missing file loads as empty; an unreadable one is renamed to
 * "<name>.corrupt-<epoch>", logged as a warning, and loads as empty, so the server never fails to start over it.
 * No Minecraft classes, so it is unit tested directly.
 */
public class RunStore {
	public static final String HISTORY_FILE = "run-history.json";
	/**
	 * Format 2 added skin_textures to deaths, 3 added killer_name. Older files load with those empty and are
	 * rewritten in the current format (old file kept as .v<n>.bak).
	 */
	public static final int HISTORY_FORMAT = 3;
	private static final Logger LOGGER = LoggerFactory.getLogger(HardcoreTogether.MOD_ID);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

	private final Path folder;

	public RunStore(Path folder) {
		this.folder = folder;
	}

	public Path folder() {
		return folder;
	}

	public Path currentFile() {
		return folder.resolve(CurrentRun.FILE_NAME);
	}

	public Path historyFile() {
		return folder.resolve(HISTORY_FILE);
	}

	/** Null if there is no usable current run (missing, format 1, or unreadable). */
	public @Nullable CurrentRun loadCurrent() {
		Path file = currentFile();
		if (Files.notExists(file)) {
			LOGGER.warn("[HT timer] no {} yet; starting clean", file.getFileName());
			return null;
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			int format = json.has("format") ? json.get("format").getAsInt() : 0;
			if (format < CurrentRun.OLDEST_FORMAT) {
				// Format 1 was pre-release test data, numbered by world reset; the history starts fresh.
				LOGGER.warn("[HT timer] {} has old format {} (test data from before step 2b); starting clean", file.getFileName(), format);
				return null;
			}
			CurrentRun run = CurrentRun.fromJson(json);
			if (format < CurrentRun.FORMAT) {
				upgrade(file, format, () -> saveCurrent(run));
			}
			return run;
		} catch (IOException | RuntimeException e) {
			moveAside(file, e);
			return null;
		}
	}

	public void saveCurrent(CurrentRun run) throws IOException {
		Files.createDirectories(folder);
		JsonFiles.writeAtomically(currentFile(), GSON.toJson(run.toJson()));
	}

	/**
	 * The finished runs, oldest first, and the highest run number ever filed ("highest_run"), which stays when runs
	 * are deleted so run numbers are never reused. Older files have no highest_run; it is then the highest run in
	 * the list.
	 */
	public record History(List<RunHistoryEntry> runs, int highestRun) {
	}

	/** Every finished run, oldest first; empty if the file is missing or unreadable. */
	public List<RunHistoryEntry> loadHistory() {
		return loadHistoryFile().runs();
	}

	public History loadHistoryFile() {
		Path file = historyFile();
		List<RunHistoryEntry> runs = new ArrayList<>();
		if (Files.notExists(file)) {
			return new History(runs, 0);
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			for (JsonElement e : json.getAsJsonArray("runs")) {
				runs.add(RunHistoryEntry.fromJson(e.getAsJsonObject()));
			}
			int highest = json.has("highest_run") ? json.get("highest_run").getAsInt() : 0;
			for (RunHistoryEntry r : runs) {
				highest = Math.max(highest, r.run());
			}
			int format = json.has("format") ? json.get("format").getAsInt() : 1;
			if (format < HISTORY_FORMAT) {
				int h = highest;
				upgrade(file, format, () -> saveHistory(runs, h));
			}
			return new History(runs, highest);
		} catch (IOException | RuntimeException e) {
			moveAside(file, e);
			return new History(new ArrayList<>(), 0);
		}
	}

	/** Rewrites the history file with the given runs (the caller keeps the list in memory). */
	public void saveHistory(List<RunHistoryEntry> runs) throws IOException {
		saveHistory(runs, 0);
	}

	/** As above, keeping highestRun (raised to the highest run in the list) so deleted run numbers stay used. */
	public void saveHistory(List<RunHistoryEntry> runs, int highestRun) throws IOException {
		JsonArray array = new JsonArray();
		int highest = highestRun;
		for (RunHistoryEntry r : runs) {
			array.add(r.toJson());
			highest = Math.max(highest, r.run());
		}
		JsonObject json = new JsonObject();
		json.addProperty("format", HISTORY_FORMAT);
		json.addProperty("highest_run", highest);
		json.add("runs", array);
		Files.createDirectories(folder);
		JsonFiles.writeAtomically(historyFile(), GSON.toJson(json));
	}

	/**
	 * Fresh start: deletes run-history.json, current-run.json and player-stats.json. Nothing else in the
	 * data folder is touched (players.json, state.json and the config stay).
	 */
	public void deleteRunFiles() throws IOException {
		Files.deleteIfExists(historyFile());
		Files.deleteIfExists(currentFile());
		Files.deleteIfExists(folder.resolve(PlayerStats.FILE_NAME));
	}

	private interface Save {
		void run() throws IOException;
	}

	/**
	 * Keeps the old file as "<name>.v<format>.bak" (once) and rewrites it in the current format. A failed upgrade
	 * is only logged: the data is already loaded and the next save writes the new format anyway.
	 */
	private static void upgrade(Path file, int format, Save save) {
		Path backup = file.resolveSibling(file.getFileName() + ".v" + format + ".bak");
		try {
			if (Files.notExists(backup)) {
				Files.copy(file, backup);
			}
			save.run();
			LOGGER.info("[HT timer] {} upgraded from format {} (old file kept as {})", file.getFileName(), format, backup.getFileName());
		} catch (IOException e) {
			LOGGER.warn("[HT timer] could not upgrade {} from format {}: {}", file.getFileName(), format, e.toString());
		}
	}

	private static void moveAside(Path file, Exception cause) {
		try {
			Path aside = JsonFiles.moveAside(file, System.currentTimeMillis());
			LOGGER.warn("[HT timer] {} is unreadable ({}); kept as {} and starting clean", file.getFileName(), cause.toString(), aside.getFileName());
		} catch (IOException e) {
			LOGGER.warn("[HT timer] {} is unreadable ({}) and could not be renamed ({}); starting clean", file.getFileName(), cause.toString(), e.toString());
		}
	}
}
