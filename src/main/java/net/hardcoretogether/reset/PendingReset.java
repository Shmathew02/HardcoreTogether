package net.hardcoretogether.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.hardcoretogether.data.CurrentRun;
import net.hardcoretogether.data.JsonFiles;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.run.RunTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * <world>/hardcore_together/pending-reset.json: a death reset whose countdown has started but which hasn't
 * finished yet. Without it a save-and-quit, stop or crash during the countdown would lose the reset and leave the
 * dead players in Spectator. A stop after COMMIT is finished by the reset journal; this file only restarts the
 * countdown. The death is already recorded when the file is written, so a restart never records it again.
 * No Minecraft classes, so tests can use it.
 *
 * worldReset is the reset counter of the world the death happened in; run is the run that death ended.
 */
public record PendingReset(int worldReset, int run, RunDeath death, String savedAt) {
	public static final String FILE_NAME = "pending-reset.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** What to do with a pending reset found at startup. */
	public enum OnLoad {
		/** No pending reset. */
		NONE,
		/** Stopped during the countdown: run the countdown again once a player is online. */
		RESUME,
		/** The reset got past COMMIT (the journal finishes it) or already finished: just delete the file. */
		STALE
	}

	public static Path path(Path folder) {
		return folder.resolve(FILE_NAME);
	}

	public static void write(Path folder, PendingReset pending) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("world_reset", pending.worldReset);
		json.addProperty("run", pending.run);
		json.addProperty("saved_at", pending.savedAt);
		JsonObject death = new JsonObject();
		pending.death.writeTo(death);
		json.add("death", death);
		JsonFiles.writeAtomically(path(folder), GSON.toJson(json));
	}

	public static Optional<PendingReset> read(Path folder) throws IOException {
		Path path = path(folder);
		if (Files.notExists(path)) {
			return Optional.empty();
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			RunDeath death = RunDeath.readFrom(json.getAsJsonObject("death"));
			if (death == null) {
				throw new IllegalStateException("no death");
			}
			return Optional.of(new PendingReset(json.get("world_reset").getAsInt(), json.get("run").getAsInt(), death,
				json.get("saved_at").getAsString()));
		} catch (RuntimeException e) {
			throw new IOException("Unreadable pending reset " + path, e);
		}
	}

	public static void delete(Path folder) throws IOException {
		Files.deleteIfExists(path(folder));
	}

	/**
	 * A pending reset resumes only in the world it was made for and only if no journal exists: once the reset
	 * passed COMMIT the reset counter has moved on and the journal recovery owns it.
	 */
	public static OnLoad onLoad(Optional<PendingReset> pending, boolean journalPresent, int worldReset) {
		if (pending.isEmpty()) {
			return OnLoad.NONE;
		}
		return !journalPresent && pending.get().worldReset == worldReset ? OnLoad.RESUME : OnLoad.STALE;
	}

	/** The countdown restarts when no reset is running and at least one player is online. */
	public static boolean startNow(boolean resetIdle, int playersOnline) {
		return resetIdle && playersOnline > 0;
	}

	/**
	 * Records the death if the run it ended is still ACTIVE (its save was lost in the stop). Returns true if it
	 * recorded it now; false if it was already recorded, so a death is never recorded twice.
	 */
	public static boolean recordIfMissing(RunTracker tracker, PendingReset pending, long dayTime) {
		CurrentRun current = tracker.current();
		if (current == null || !current.isActive() || current.run() != pending.run) {
			return false;
		}
		return tracker.endRun("death", pending.death, dayTime);
	}
}
