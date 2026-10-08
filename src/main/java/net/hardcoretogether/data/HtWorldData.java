package net.hardcoretogether.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.hardcoretogether.HardcoreTogether;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Our persistent data in <world>/hardcore_together/. The folder sits at the world root, outside every
 * dimension folder, so a reset never deletes it.
 */
public final class HtWorldData {
	public static final LevelResource FOLDER = new LevelResource(HardcoreTogether.MOD_ID);
	private static final String STATE_FILE = "state.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path folder;
	private int runNumber;
	private ResetState resetState;
	private boolean hallBuilt;
	/** Portal start mode: a reset has finished and the players wait in the Death Hall for the portal. */
	private boolean awaitingStart;
	/** Duration of the last completed real reset in seconds, or -1 if there was none. */
	private double lastResetSeconds;
	/** How long the last real reset's SWAP held the server thread, in ms, or -1 if not recorded. */
	private long lastSwapMillis;
	/** The last real reset's DRAIN chunk write flush per dimension, in ms (-1 = timed out). */
	private final Map<String, Long> lastFlushMillis = new LinkedHashMap<>();
	/** Players held in the hall by a reset who were offline when it returned everyone. */
	private final Set<UUID> pendingReturn;

	private HtWorldData(Path folder, int runNumber, ResetState resetState, boolean hallBuilt, double lastResetSeconds, long lastSwapMillis, Set<UUID> pendingReturn) {
		this.folder = folder;
		this.lastResetSeconds = lastResetSeconds;
		this.lastSwapMillis = lastSwapMillis;
		this.runNumber = runNumber;
		this.resetState = resetState;
		this.hallBuilt = hallBuilt;
		this.pendingReturn = pendingReturn;
	}

	public static HtWorldData load(MinecraftServer server) throws IOException {
		Path folder = server.getWorldPath(FOLDER).toAbsolutePath().normalize();
		Files.createDirectories(folder);
		Path stateFile = folder.resolve(STATE_FILE);

		if (Files.notExists(stateFile)) {
			HtWorldData data = new HtWorldData(folder, 1, ResetState.IDLE, false, -1, -1, new LinkedHashSet<>());
			data.save();
			HardcoreTogether.LOGGER.info("Created data folder {}", folder);
			return data;
		}

		try {
			JsonObject json = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
			int runNumber = Math.max(1, json.get("run_number").getAsInt());
			ResetState state = ResetState.valueOf(json.get("reset_state").getAsString());
			// State files from before hall_built existed belong to worlds whose platform was already placed.
			boolean hallBuilt = !json.has("hall_built") || json.get("hall_built").getAsBoolean();
			Set<UUID> pending = new LinkedHashSet<>();
			if (json.has("pending_return")) {
				for (JsonElement e : json.getAsJsonArray("pending_return")) {
					pending.add(UUID.fromString(e.getAsString()));
				}
			}
			double lastReset = json.has("last_reset_seconds") ? json.get("last_reset_seconds").getAsDouble() : -1;
			long lastSwap = json.has("last_swap_millis") ? json.get("last_swap_millis").getAsLong() : -1;
			HtWorldData data = new HtWorldData(folder, runNumber, state, hallBuilt, lastReset, lastSwap, pending);
			data.awaitingStart = json.has("awaiting_start") && json.get("awaiting_start").getAsBoolean();
			if (json.has("last_flush_millis")) {
				json.getAsJsonObject("last_flush_millis").entrySet().forEach(e -> data.lastFlushMillis.put(e.getKey(), e.getValue().getAsLong()));
			}
			return data;
		} catch (IllegalStateException | IllegalArgumentException | NullPointerException | JsonParseException | UnsupportedOperationException e) {
			// Never overwrite a state file we can't read; the run number in it matters.
			throw new IOException("Unreadable state file " + stateFile, e);
		}
	}

	/** Write to a temp file, then move it into place so a crash never leaves a half-written file. */
	public void save() throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("run_number", runNumber);
		json.addProperty("reset_state", resetState.name());
		json.addProperty("hall_built", hallBuilt);
		json.addProperty("awaiting_start", awaitingStart);
		json.addProperty("last_reset_seconds", lastResetSeconds);
		json.addProperty("last_swap_millis", lastSwapMillis);
		JsonObject flush = new JsonObject();
		lastFlushMillis.forEach(flush::addProperty);
		json.add("last_flush_millis", flush);
		JsonArray pending = new JsonArray();
		pendingReturn.forEach(uuid -> pending.add(uuid.toString()));
		json.add("pending_return", pending);
		writeAtomically(folder.resolve(STATE_FILE), GSON.toJson(json));
	}

	/** Saves, logging instead of throwing; for callers on the server tick that can't do anything better. */
	public void trySave() {
		try {
			save();
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("Could not write {}", folder.resolve(STATE_FILE), e);
		}
	}

	public static void writeAtomically(Path target, String content) throws IOException {
		JsonFiles.writeAtomically(target, content);
	}

	public Path folder() {
		return folder;
	}

	public int runNumber() {
		return runNumber;
	}

	public void setRunNumber(int runNumber) {
		this.runNumber = runNumber;
	}

	public ResetState resetState() {
		return resetState;
	}

	public void setResetState(ResetState resetState) {
		this.resetState = resetState;
	}

	/** True from the end of a reset (portal start mode) until everyone has gone through the portal. */
	public boolean awaitingStart() {
		return awaitingStart;
	}

	public void setAwaitingStart(boolean awaitingStart) {
		this.awaitingStart = awaitingStart;
	}

	public boolean hallBuilt() {
		return hallBuilt;
	}

	public void setHallBuilt(boolean hallBuilt) {
		this.hallBuilt = hallBuilt;
	}

	public double lastResetSeconds() {
		return lastResetSeconds;
	}

	public void setLastResetSeconds(double seconds) {
		this.lastResetSeconds = seconds;
	}

	public long lastSwapMillis() {
		return lastSwapMillis;
	}

	public void setLastSwapMillis(long millis) {
		this.lastSwapMillis = millis;
	}

	public Map<String, Long> lastFlushMillis() {
		return Collections.unmodifiableMap(lastFlushMillis);
	}

	public void setLastFlushMillis(Map<String, Long> millis) {
		lastFlushMillis.clear();
		lastFlushMillis.putAll(millis);
	}

	public Set<UUID> pendingReturn() {
		return Collections.unmodifiableSet(pendingReturn);
	}

	public void addPendingReturn(UUID uuid) {
		pendingReturn.add(uuid);
	}

	public boolean removePendingReturn(UUID uuid) {
		return pendingReturn.remove(uuid);
	}
}
