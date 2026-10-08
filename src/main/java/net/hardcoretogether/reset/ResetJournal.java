package net.hardcoretogether.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.data.ResetState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <world>/hardcore_together/reset-journal.json. Exists only from COMMIT until DONE, so its presence at
 * startup means a committed reset was interrupted. Written atomically at COMMIT and after each phase.
 */
public record ResetJournal(int runNumber, long seed, boolean dryRun, ResetState phase, Set<UUID> held, long startedAtMillis) {
	public static final String FILE_NAME = "reset-journal.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static Path path(HtWorldData data) {
		return data.folder().resolve(FILE_NAME);
	}

	public static boolean exists(HtWorldData data) {
		return Files.exists(path(data));
	}

	public static void write(HtWorldData data, int runNumber, long seed, boolean dryRun, ResetState phase,
			Collection<UUID> held, long startedAtMillis) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("run_number", runNumber);
		json.addProperty("seed", seed);
		json.addProperty("dry_run", dryRun);
		json.addProperty("phase", phase.name());
		JsonArray players = new JsonArray();
		held.forEach(uuid -> players.add(uuid.toString()));
		json.add("held_players", players);
		json.addProperty("started_at", startedAtMillis);
		HtWorldData.writeAtomically(path(data), GSON.toJson(json));
	}

	public static Optional<ResetJournal> read(HtWorldData data) throws IOException {
		Path path = path(data);
		if (Files.notExists(path)) {
			return Optional.empty();
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			Set<UUID> held = new LinkedHashSet<>();
			for (JsonElement e : json.getAsJsonArray("held_players")) {
				held.add(UUID.fromString(e.getAsString()));
			}
			return Optional.of(new ResetJournal(json.get("run_number").getAsInt(), json.get("seed").getAsLong(),
				json.get("dry_run").getAsBoolean(), ResetState.valueOf(json.get("phase").getAsString()), held,
				json.get("started_at").getAsLong()));
		} catch (RuntimeException e) {
			throw new IOException("Unreadable reset journal " + path, e);
		}
	}

	public static void delete(HtWorldData data) throws IOException {
		Files.deleteIfExists(path(data));
	}
}
