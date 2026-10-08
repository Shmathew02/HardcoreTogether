package net.hardcoretogether.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <world>/hardcore_together/player-stats.json: lifetime stats per player, next to the players.json
 * registry. Derived data: everything here can be recomputed from run-history.json alone ({@link #rebuild}), and
 * {@link #apply} is the one incremental update, made once per run end. A missing or unreadable file is rebuilt
 * from the history (an unreadable one is kept as "<name>.corrupt-<epoch>"). No Minecraft classes, unit tested.
 */
public final class PlayerStats {
	public static final String FILE_NAME = "player-stats.json";
	private static final int FORMAT = 1;
	private static final Logger LOGGER = LoggerFactory.getLogger(HardcoreTogether.MOD_ID);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

	/**
	 * One player's lifetime stats over finished runs. longestRun and furthestDayRun are 0 when the player has no
	 * runs yet. name is the newest name seen in the history (null if the history has none).
	 */
	public record Stats(@Nullable String name, int runsPlayed, int deaths, long totalPlaytimeMillis, long longestRunMillis,
			int longestRun, long furthestDay, int furthestDayRun) {
		public static final Stats NONE = new Stats(null, 0, 0, 0, 0, 0, 0, 0);
	}

	/**
	 * What /stats shows: finished runs plus the current ACTIVE run so far. A record held by the current run is
	 * flagged as in progress.
	 */
	public record View(int runsPlayed, int deaths, long totalPlaytimeMillis, long longestRunMillis, int longestRun,
			boolean longestInProgress, long furthestDay, int furthestDayRun, boolean furthestInProgress) {
	}

	private final Map<UUID, Stats> players;
	/** Highest run number applied; history entries above it are applied on load (a lost stats write). */
	private int lastRun;

	private PlayerStats(Map<UUID, Stats> players, int lastRun) {
		this.players = players;
		this.lastRun = lastRun;
	}

	public static PlayerStats empty() {
		return new PlayerStats(new HashMap<>(), 0);
	}

	/** Recomputes every player's stats from the finished runs alone. */
	public static PlayerStats rebuild(List<RunHistoryEntry> history) {
		PlayerStats stats = empty();
		history.forEach(stats::apply);
		return stats;
	}

	/** Adds one finished run: participants get the run, its time and any records; the dead player a death. */
	public void apply(RunHistoryEntry run) {
		run.participantMillis().forEach((uuid, millis) -> {
			Stats s = get(uuid);
			boolean longer = s.longestRun() == 0 || run.finalActiveMillis() > s.longestRunMillis();
			boolean further = s.furthestDayRun() == 0 || run.finalDay() > s.furthestDay();
			String name = run.participantNames().getOrDefault(uuid, s.name());
			players.put(uuid, new Stats(name, s.runsPlayed() + 1, s.deaths(), s.totalPlaytimeMillis() + millis,
				longer ? run.finalActiveMillis() : s.longestRunMillis(), longer ? run.run() : s.longestRun(),
				further ? run.finalDay() : s.furthestDay(), further ? run.run() : s.furthestDayRun()));
		});
		RunDeath death = run.death();
		if ("death".equals(run.endKind()) && death != null) {
			Stats s = get(death.uuid());
			players.put(death.uuid(), new Stats(death.name(), s.runsPlayed(), s.deaths() + 1, s.totalPlaytimeMillis(),
				s.longestRunMillis(), s.longestRun(), s.furthestDay(), s.furthestDayRun()));
		}
		lastRun = Math.max(lastRun, run.run());
	}

	public Stats get(UUID uuid) {
		return players.getOrDefault(uuid, Stats.NONE);
	}

	public boolean has(UUID uuid) {
		return players.containsKey(uuid);
	}

	public Map<UUID, Stats> all() {
		return Map.copyOf(players);
	}

	public int lastRun() {
		return lastRun;
	}

	/** A player whose newest name in the history matches (case-insensitive), for players missing from the registry. */
	public Optional<UUID> findByName(String name) {
		String wanted = name.toLowerCase(Locale.ROOT);
		return players.entrySet().stream()
			.filter(e -> e.getValue().name() != null && e.getValue().name().toLowerCase(Locale.ROOT).equals(wanted))
			.map(Map.Entry::getKey)
			.findFirst();
	}

	/**
	 * Stats including the current run. current is null (or not ACTIVE) when there is no live run; liveMillis is
	 * this player's time in it so far, runMillis and runDay the run's own live time and day. The current run
	 * counts only if the player has been in it.
	 */
	public View view(UUID uuid, @Nullable CurrentRun current, long runMillis, long runDay) {
		Stats s = get(uuid);
		Long liveMillis = current == null || !current.isActive() ? null : current.participantMillis().get(uuid);
		if (liveMillis == null) {
			return new View(s.runsPlayed(), s.deaths(), s.totalPlaytimeMillis(), s.longestRunMillis(), s.longestRun(), false,
				s.furthestDay(), s.furthestDayRun(), false);
		}
		boolean longer = runMillis > s.longestRunMillis() || s.longestRun() == 0;
		boolean further = runDay > s.furthestDay() || s.furthestDayRun() == 0;
		return new View(s.runsPlayed(), s.deaths(), s.totalPlaytimeMillis() + liveMillis,
			longer ? runMillis : s.longestRunMillis(), longer ? current.run() : s.longestRun(), longer,
			further ? runDay : s.furthestDay(), further ? current.run() : s.furthestDayRun(), further);
	}

	// ---- Persistence ----

	/**
	 * Loads the stats, or rebuilds them from the history if the file is missing or unreadable (logged as a
	 * warning; an unreadable file is kept aside). History runs newer than the file (a stats write that was lost)
	 * are applied. Saves whenever it rebuilt or caught up.
	 */
	public static PlayerStats loadOrRebuild(Path folder, List<RunHistoryEntry> history) {
		Path file = folder.resolve(FILE_NAME);
		PlayerStats stats = null;
		if (Files.notExists(file)) {
			LOGGER.warn("[HT stats] no {} yet; rebuilding from {} finished run(s) in {}", FILE_NAME, history.size(), RunStore.HISTORY_FILE);
		} else {
			try {
				stats = fromJson(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
			} catch (IOException | RuntimeException e) {
				String kept;
				try {
					kept = "kept as " + JsonFiles.moveAside(file, System.currentTimeMillis()).getFileName();
				} catch (IOException moveFailed) {
					kept = "could not be renamed: " + moveFailed;
				}
				LOGGER.warn("[HT stats] {} is unreadable ({}; {}); rebuilding from {} finished run(s) in {}", FILE_NAME, e.toString(), kept,
					history.size(), RunStore.HISTORY_FILE);
			}
		}
		if (stats == null) {
			stats = rebuild(history);
			LOGGER.info("[HT stats] rebuilt: {} player(s) from {} run(s)", stats.players.size(), history.size());
			stats.trySave(folder);
			return stats;
		}
		int missed = 0;
		int known = stats.lastRun;
		for (RunHistoryEntry run : history) {
			if (run.run() > known) {
				stats.apply(run);
				missed++;
			}
		}
		if (missed > 0) {
			LOGGER.warn("[HT stats] {} was missing {} finished run(s); applied them from {}", FILE_NAME, missed, RunStore.HISTORY_FILE);
			stats.trySave(folder);
		}
		return stats;
	}

	public void save(Path folder) throws IOException {
		Files.createDirectories(folder);
		JsonFiles.writeAtomically(folder.resolve(FILE_NAME), GSON.toJson(toJson()));
	}

	/** Logs a warning on failure; the stats stay in memory and the next save (or a rebuild) fixes the file. */
	public boolean trySave(Path folder) {
		try {
			save(folder);
			return true;
		} catch (IOException e) {
			LOGGER.warn("[HT stats] could not write {} ({}); kept in memory", FILE_NAME, e.toString());
			return false;
		}
	}

	JsonObject toJson() {
		JsonObject players = new JsonObject();
		this.players.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(en -> {
			Stats s = en.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("name", s.name());
			o.addProperty("runs_played", s.runsPlayed());
			o.addProperty("deaths", s.deaths());
			o.addProperty("total_playtime_millis", s.totalPlaytimeMillis());
			o.addProperty("longest_run_millis", s.longestRunMillis());
			o.addProperty("longest_run", s.longestRun() == 0 ? null : s.longestRun());
			o.addProperty("furthest_day", s.furthestDay());
			o.addProperty("furthest_day_run", s.furthestDayRun() == 0 ? null : s.furthestDayRun());
			players.add(en.getKey().toString(), o);
		});
		JsonObject json = new JsonObject();
		json.addProperty("format", FORMAT);
		json.addProperty("last_run", lastRun);
		json.add("players", players);
		return json;
	}

	static PlayerStats fromJson(JsonObject json) {
		Map<UUID, Stats> map = new HashMap<>();
		JsonObject players = json.getAsJsonObject("players");
		for (String key : players.keySet()) {
			JsonObject o = players.getAsJsonObject(key);
			map.put(UUID.fromString(key), new Stats(optString(o, "name"), o.get("runs_played").getAsInt(), o.get("deaths").getAsInt(),
				o.get("total_playtime_millis").getAsLong(), o.get("longest_run_millis").getAsLong(), optInt(o, "longest_run"),
				o.get("furthest_day").getAsLong(), optInt(o, "furthest_day_run")));
		}
		return new PlayerStats(map, json.get("last_run").getAsInt());
	}

	private static @Nullable String optString(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static int optInt(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? 0 : e.getAsInt();
	}
}
