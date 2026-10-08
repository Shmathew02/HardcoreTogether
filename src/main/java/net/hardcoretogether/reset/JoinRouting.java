package net.hardcoretogether.reset;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.data.PlayerRecords;

import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Where a joining player is placed. Used by the PrepareSpawnTask mixin before the player exists. */
public final class JoinRouting {
	public enum Route {
		/** Vanilla: the player's saved position. */
		VANILLA,
		/** A reset is between COMMIT and DONE, or the portal is waiting: wait in the Death Hall. */
		DEATH_HALL,
		/** The player last played in an older run: the new world spawn (fresh treatment follows on join). */
		NEW_WORLD_SPAWN
	}

	/** Spawn tasks that will place a player in a vanilla level; DRAIN waits until there are none. */
	private static final Set<Object> pendingVanillaSpawns = Collections.newSetFromMap(new WeakHashMap<>());

	private JoinRouting() {
	}

	public static Route route(MinecraftServer server, UUID uuid) {
		if (ResetController.holdsJoiners() || ResetController.awaitingStart()) {
			return Route.DEATH_HALL;
		}
		PlayerRecords records = HardcoreTogether.playerRecords();
		HtWorldData data = HardcoreTogether.worldData();
		if (records != null && data != null) {
			OptionalInt last = records.lastRunId(uuid);
			if (last.isPresent() && last.getAsInt() < data.runNumber()) {
				return Route.NEW_WORLD_SPAWN;
			}
		}
		return Route.VANILLA;
	}

	public static synchronized void trackPending(Object task) {
		pendingVanillaSpawns.add(task);
	}

	public static synchronized void untrack(Object task) {
		pendingVanillaSpawns.remove(task);
	}

	public static synchronized int pendingVanillaSpawns() {
		return pendingVanillaSpawns.size();
	}
}
