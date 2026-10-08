package net.hardcoretogether.data;

import com.google.gson.JsonObject;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One finished run in run-history.json. endKind is "death" or "command" (an op's /ht reset); death is null for a
 * command reset. participantNames holds each participant's name at the time (empty in older runs).
 */
public record RunHistoryEntry(int run, long seed, long startedAtEpoch, long endedAtEpoch, long finalActiveMillis, long finalDay,
		String endKind, @Nullable RunDeath death, Map<UUID, Long> participantMillis, Map<UUID, String> participantNames) {

	public static RunHistoryEntry of(CurrentRun r) {
		Long ended = r.endedAtEpoch();
		return new RunHistoryEntry(r.run(), r.seed(), r.startedAtEpoch(), ended == null ? 0 : ended, r.activeMillis(), r.finalDay(),
			r.endKind() == null ? "command" : r.endKind(), r.death(), r.participantMillis(), r.participantNames());
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("run", run);
		o.addProperty("seed", seed);
		o.addProperty("started_at_epoch", startedAtEpoch);
		o.addProperty("ended_at_epoch", endedAtEpoch);
		o.addProperty("final_active_millis", finalActiveMillis);
		o.addProperty("final_day", finalDay);
		o.addProperty("end_kind", endKind);
		if (death != null) {
			death.writeTo(o);
		} else {
			RunDeath.writeNone(o);
		}
		JsonObject p = new JsonObject();
		participantMillis.forEach((uuid, millis) -> p.addProperty(uuid.toString(), millis));
		o.add("participants", p);
		JsonObject names = new JsonObject();
		participantNames.forEach((uuid, name) -> names.addProperty(uuid.toString(), name));
		o.add("participant_names", names);
		return o;
	}

	public static RunHistoryEntry fromJson(JsonObject o) {
		Map<UUID, Long> participants = new LinkedHashMap<>();
		JsonObject p = o.getAsJsonObject("participants");
		if (p != null) {
			p.keySet().forEach(k -> participants.put(UUID.fromString(k), p.get(k).getAsLong()));
		}
		Map<UUID, String> names = new LinkedHashMap<>();
		JsonObject n = o.getAsJsonObject("participant_names");
		if (n != null) {
			n.keySet().forEach(k -> names.put(UUID.fromString(k), n.get(k).getAsString()));
		}
		return new RunHistoryEntry(o.get("run").getAsInt(), o.get("seed").getAsLong(), o.get("started_at_epoch").getAsLong(),
			o.get("ended_at_epoch").getAsLong(), o.get("final_active_millis").getAsLong(), o.get("final_day").getAsLong(),
			o.get("end_kind").getAsString(), RunDeath.readFrom(o), participants, names);
	}
}
