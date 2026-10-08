package net.hardcoretogether.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.hardcoretogether.HardcoreTogether;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * <world>/hardcore_together/players.json: the player registry (format 3).
 *
 * Per player: current name, every name seen, first and last join, and lastRunId (the run they last played in;
 * older than the current run means a fresh start on their next join). Lifetime stats live in player-stats.json
 * ({@link PlayerStats}), not here.
 *
 * A player with no lastRunId has never been in an earlier run (lastRunId is filled in for every known player
 * when a reset commits), so they count as current. Older files are upgraded on load, after copying the original
 * to players.json.v<format>.bak: format 1 (UUID -> last_run_id only) and format 2 (also had runs_played and
 * deaths placeholders, which are dropped).
 */
public final class PlayerRecords {
	private static final String FILE_NAME = "players.json";
	private static final int FORMAT = 3;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

	/** One registry entry. Name and timestamps are null for players only known from a run backfill. */
	public static final class Entry {
		private @Nullable String name;
		private final List<String> namesSeen = new ArrayList<>();
		private @Nullable String firstSeen;
		private @Nullable String lastSeen;
		private @Nullable Integer lastRunId;

		public @Nullable String name() {
			return name;
		}

		public List<String> namesSeen() {
			return List.copyOf(namesSeen);
		}

		public @Nullable String firstSeen() {
			return firstSeen;
		}

		public @Nullable String lastSeen() {
			return lastSeen;
		}

		public OptionalInt lastRunId() {
			return lastRunId == null ? OptionalInt.empty() : OptionalInt.of(lastRunId);
		}
	}

	private final Path file;
	private final Map<UUID, Entry> entries;

	private PlayerRecords(Path file, Map<UUID, Entry> entries) {
		this.file = file;
		this.entries = entries;
	}

	public static PlayerRecords load(Path folder) throws IOException {
		Path file = folder.resolve(FILE_NAME);
		Map<UUID, Entry> map = new HashMap<>();
		if (Files.notExists(file)) {
			return new PlayerRecords(file, map);
		}

		int format;
		try {
			JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			format = json.has("format") ? json.get("format").getAsInt() : 1;
			JsonObject players = json.getAsJsonObject("players");
			for (String key : players.keySet()) {
				JsonObject p = players.getAsJsonObject(key);
				Entry e = new Entry();
				if (p.has("last_run_id") && !p.get("last_run_id").isJsonNull()) {
					e.lastRunId = p.get("last_run_id").getAsInt();
				}
				if (format >= 2) {
					e.name = optString(p, "name");
					if (p.has("names_seen")) {
						for (JsonElement n : p.getAsJsonArray("names_seen")) {
							e.namesSeen.add(n.getAsString());
						}
					}
					e.firstSeen = optString(p, "first_seen");
					e.lastSeen = optString(p, "last_seen");
				}
				map.put(UUID.fromString(key), e);
			}
		} catch (IllegalStateException | IllegalArgumentException | NullPointerException | JsonParseException | UnsupportedOperationException e) {
			// Never overwrite a registry we can't read.
			throw new IOException("Unreadable " + file, e);
		}

		PlayerRecords records = new PlayerRecords(file, map);
		if (format < FORMAT) {
			Path backup = folder.resolve(FILE_NAME + ".v" + format + ".bak");
			if (Files.notExists(backup)) {
				Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
			}
			records.save();
			HardcoreTogether.LOGGER.info("Upgraded {} from format {} to {} ({} players, last_run_id kept, stats placeholders dropped; backup {})",
				file.getFileName(), format, FORMAT, map.size(), backup.getFileName());
		} else if (format > FORMAT) {
			HardcoreTogether.LOGGER.warn("{} has format {}, newer than this mod understands ({}); unknown fields will be dropped on save",
				file.getFileName(), format, FORMAT);
		}
		return records;
	}

	private static @Nullable String optString(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	// ---- Registry ----

	/**
	 * Records a join: creates the entry if new, updates the current name and last_seen, and adds the name to
	 * names_seen if it hasn't been seen before. Returns true if the name changed (or the player is new).
	 */
	public boolean recordJoin(UUID uuid, String name) {
		String now = Instant.now().toString();
		Entry e = entries.computeIfAbsent(uuid, k -> new Entry());
		boolean changed = !name.equals(e.name);
		if (e.firstSeen == null) {
			e.firstSeen = now;
		}
		e.lastSeen = now;
		e.name = name;
		if (!e.namesSeen.contains(name)) {
			e.namesSeen.add(name);
		}
		return changed;
	}

	/** Every registered player (read-only view). */
	public Map<UUID, Entry> all() {
		return java.util.Collections.unmodifiableMap(entries);
	}

	/** Finds a player by current name, or by any name they have used (case-insensitive). */
	public Optional<Map.Entry<UUID, Entry>> findByName(String name) {
		String wanted = name.toLowerCase(Locale.ROOT);
		Optional<Map.Entry<UUID, Entry>> current = entries.entrySet().stream()
			.filter(e -> e.getValue().name != null && e.getValue().name.toLowerCase(Locale.ROOT).equals(wanted))
			.findFirst();
		if (current.isPresent()) {
			return current;
		}
		return entries.entrySet().stream()
			.filter(e -> e.getValue().namesSeen.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).equals(wanted)))
			.findFirst();
	}

	public @Nullable Entry get(UUID uuid) {
		return entries.get(uuid);
	}

	/** Every player's current name, sorted (for tab completion). */
	public List<String> currentNames() {
		return entries.values().stream().map(Entry::name).filter(n -> n != null).sorted(String.CASE_INSENSITIVE_ORDER).toList();
	}

	// ---- lastRunId ----

	public OptionalInt lastRunId(UUID uuid) {
		Entry e = entries.get(uuid);
		return e == null ? OptionalInt.empty() : e.lastRunId();
	}

	public void setLastRunId(UUID uuid, int run) {
		entries.computeIfAbsent(uuid, k -> new Entry()).lastRunId = run;
	}

	/** Records the run only for players that have no lastRunId yet. */
	public void setIfAbsent(UUID uuid, int run) {
		Entry e = entries.computeIfAbsent(uuid, k -> new Entry());
		if (e.lastRunId == null) {
			e.lastRunId = run;
		}
	}

	// ---- Persistence ----

	public void save() throws IOException {
		JsonObject players = new JsonObject();
		entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(en -> {
			Entry e = en.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("name", e.name);
			JsonArray seen = new JsonArray();
			e.namesSeen.forEach(seen::add);
			o.add("names_seen", seen);
			o.addProperty("first_seen", e.firstSeen);
			o.addProperty("last_seen", e.lastSeen);
			o.addProperty("last_run_id", e.lastRunId);
			players.add(en.getKey().toString(), o);
		});
		JsonObject json = new JsonObject();
		json.addProperty("format", FORMAT);
		json.add("players", players);
		HtWorldData.writeAtomically(file, GSON.toJson(json));
	}

	public void trySave() {
		try {
			save();
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("Could not write {}", file, e);
		}
	}
}
