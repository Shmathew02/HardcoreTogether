package net.hardcoretogether.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The live (or just ended) run, saved as <world>/hardcore_together/current-run.json (format 4; 3 added the death's
 * skin_textures, 4 its killer_name; formats 2 and 3 load without them and are upgraded).
 *
 * The run number is its own counter, separate from the world reset counter in state.json; worldReset records
 * which reset's world this run is played in, so a world reset the timer didn't see (server stopped mid-reset)
 * is detected on startup. Time is kept in nanoseconds in memory and saved as milliseconds.
 */
public final class CurrentRun {
	public static final String FILE_NAME = "current-run.json";
	public static final int FORMAT = 4;
	/** Oldest format still read; format 1 was pre-release test data. */
	public static final int OLDEST_FORMAT = 2;

	public enum Status { ACTIVE, ENDED }

	private final int run;
	private int worldReset;
	private final long seed;
	private final long startedAtEpoch;
	private final long startDayTime;
	private Status status;
	private long activeNanos;
	private long lastDayTime;
	private @Nullable Long endedAtEpoch;
	private long finalDay;
	private @Nullable String endKind;
	private @Nullable RunDeath death;
	private final Map<UUID, Long> participantNanos;
	/** Each participant's newest name while in the run. */
	private final Map<UUID, String> participantNames = new LinkedHashMap<>();

	private CurrentRun(int run, int worldReset, long seed, long startedAtEpoch, long startDayTime, Map<UUID, Long> participantNanos) {
		this.run = run;
		this.worldReset = worldReset;
		this.seed = seed;
		this.startedAtEpoch = startedAtEpoch;
		this.startDayTime = startDayTime;
		this.lastDayTime = startDayTime;
		this.status = Status.ACTIVE;
		this.participantNanos = participantNanos;
	}

	public static CurrentRun start(int run, int worldReset, long seed, long startedAtEpoch, long startDayTime) {
		return new CurrentRun(run, worldReset, seed, startedAtEpoch, startDayTime, new LinkedHashMap<>());
	}

	/** Marks the run ended; time and day are frozen from here on. */
	public void end(long endedAtEpoch, long finalDay, String kind, @Nullable RunDeath death) {
		this.status = Status.ENDED;
		this.endedAtEpoch = endedAtEpoch;
		this.finalDay = finalDay;
		this.endKind = kind;
		this.death = death;
	}

	// ---- JSON ----

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("format", FORMAT);
		json.addProperty("run", run);
		json.addProperty("status", status.name());
		json.addProperty("world_reset", worldReset);
		json.addProperty("seed", seed);
		json.addProperty("started_at_epoch", startedAtEpoch);
		json.addProperty("ended_at_epoch", endedAtEpoch);
		json.addProperty("active_millis", activeMillis());
		json.addProperty("start_day_time", startDayTime);
		json.addProperty("last_day_time", lastDayTime);
		json.addProperty("final_day", status == Status.ENDED ? finalDay : null);
		json.addProperty("end_kind", endKind);
		if (death != null) {
			death.writeTo(json);
		} else {
			RunDeath.writeNone(json);
		}
		JsonObject p = new JsonObject();
		participantNanos.forEach((uuid, nanos) -> p.addProperty(uuid.toString(), nanos / 1_000_000L));
		json.add("participants", p);
		JsonObject names = new JsonObject();
		participantNames.forEach((uuid, name) -> names.addProperty(uuid.toString(), name));
		json.add("participant_names", names);
		return json;
	}

	/** Throws a RuntimeException (from Gson or valueOf) if a required field is missing or malformed. */
	public static CurrentRun fromJson(JsonObject json) {
		Map<UUID, Long> participants = new LinkedHashMap<>();
		JsonObject p = json.getAsJsonObject("participants");
		if (p != null) {
			for (String key : p.keySet()) {
				participants.put(UUID.fromString(key), p.get(key).getAsLong() * 1_000_000L);
			}
		}
		CurrentRun r = new CurrentRun(json.get("run").getAsInt(), json.get("world_reset").getAsInt(), json.get("seed").getAsLong(),
			json.get("started_at_epoch").getAsLong(), json.get("start_day_time").getAsLong(), participants);
		JsonObject names = json.getAsJsonObject("participant_names");
		if (names != null) {
			names.keySet().forEach(key -> r.participantNames.put(UUID.fromString(key), names.get(key).getAsString()));
		}
		r.status = Status.valueOf(json.get("status").getAsString());
		r.activeNanos = Math.max(0, json.get("active_millis").getAsLong()) * 1_000_000L;
		r.lastDayTime = json.has("last_day_time") ? json.get("last_day_time").getAsLong() : r.startDayTime;
		if (r.status == Status.ENDED) {
			r.endedAtEpoch = optLong(json, "ended_at_epoch");
			Long day = optLong(json, "final_day");
			r.finalDay = day == null ? 1 : day;
			r.endKind = optString(json, "end_kind");
			r.death = RunDeath.readFrom(json);
		}
		return r;
	}

	private static @Nullable Long optLong(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e instanceof JsonNull ? null : e.getAsLong();
	}

	private static @Nullable String optString(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e instanceof JsonNull ? null : e.getAsString();
	}

	// ---- Time ----

	public void addActiveNanos(long nanos) {
		activeNanos += nanos;
	}

	public void setActiveNanos(long nanos) {
		activeNanos = Math.max(0, nanos);
	}

	public long activeNanos() {
		return activeNanos;
	}

	public long activeMillis() {
		return activeNanos / 1_000_000L;
	}

	public void addParticipant(UUID uuid, @Nullable String name, long nanos) {
		participantNanos.merge(uuid, nanos, Long::sum);
		if (name != null) {
			participantNames.put(uuid, name);
		}
	}

	public Map<UUID, String> participantNames() {
		return new LinkedHashMap<>(participantNames);
	}

	public Map<UUID, Long> participantMillis() {
		Map<UUID, Long> out = new LinkedHashMap<>();
		participantNanos.forEach((uuid, nanos) -> out.put(uuid, nanos / 1_000_000L));
		return out;
	}

	public void setLastDayTime(long dayTime) {
		lastDayTime = dayTime;
	}

	// ---- Accessors ----

	public int run() {
		return run;
	}

	public int worldReset() {
		return worldReset;
	}

	/** Follows /ht reset-count set, which renumbers the reset counter without resetting the world. */
	public void setWorldReset(int worldReset) {
		this.worldReset = worldReset;
	}

	public long seed() {
		return seed;
	}

	public Status status() {
		return status;
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	public long startedAtEpoch() {
		return startedAtEpoch;
	}

	public @Nullable Long endedAtEpoch() {
		return endedAtEpoch;
	}

	public long startDayTime() {
		return startDayTime;
	}

	public long lastDayTime() {
		return lastDayTime;
	}

	/** Only meaningful once ENDED. */
	public long finalDay() {
		return finalDay;
	}

	public @Nullable String endKind() {
		return endKind;
	}

	/** The death that ended the run; null while ACTIVE and for a command reset. */
	public @Nullable RunDeath death() {
		return death;
	}

	public @Nullable String diedName() {
		return death == null ? null : death.name();
	}
}
