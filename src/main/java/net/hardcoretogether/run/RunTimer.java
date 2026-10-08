package net.hardcoretogether.run;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.CurrentRun;
import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.data.MemorialData;
import net.hardcoretogether.data.ResetState;
import net.hardcoretogether.data.RunStore;
import net.hardcoretogether.reset.ResetController;
import net.hardcoretogether.reset.WorldSwapper;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Connects the RunTracker to the server: loads it on SERVER_STARTED, ticks it on END_SERVER_TICK, saves on
 * SERVER_STOPPING, and starts or ends runs for the reset controller and the death trigger. The run number is the tracker's own counter, not the reset counter.
 */
public final class RunTimer {
	private static @Nullable RunTracker tracker;

	private RunTimer() {
	}

	public static void register() {
		// Registered after ResetController, so its startup recovery has already updated the world reset counter.
		ServerLifecycleEvents.SERVER_STARTED.register(RunTimer::onStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (tracker != null) {
				tracker.shutdown();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			tracker = null;
			MemorialData.set(MemorialData.EMPTY);
		});
		ServerTickEvents.END_SERVER_TICK.register(RunTimer::tick);
	}

	private static void onStarted(MinecraftServer server) {
		HtWorldData data = HardcoreTogether.worldData();
		if (data == null) {
			return;
		}
		try {
			long interval = HardcoreTogether.config().timerSaveSeconds() * 1_000_000_000L;
			tracker = new RunTracker(new RunStore(data.folder()), System::nanoTime, System::currentTimeMillis, interval);
			CurrentRun run = tracker.current();
			if (run == null) {
				// First start with the mod (or after the run files were cleared): this world gets Run #1.
				startRunInNewWorld(server, "no saved run");
			} else if (run.worldReset() != data.runNumber()) {
				// A reset finished during startup (the server stopped mid-reset). The new run starts when the first
				// player is sent into the new world, not now.
				if (run.isActive()) {
					tracker.endRun("command", null, run.lastDayTime());
					HardcoreTogether.LOGGER.warn("[HT timer] run #{} was still ACTIVE for reset #{}; ended as command", run.run(), run.worldReset());
				}
				HardcoreTogether.LOGGER.info("[HT timer] run #{} ENDED; reset #{} finished during startup, the next run starts when a player"
					+ " enters the new world", run.run(), data.runNumber());
			} else {
				HardcoreTogether.LOGGER.info("[HT timer] run #{} {} at {}, Day {} ({} finished runs in history)", run.run(),
					run.isActive() ? "resumed" : "loaded (ENDED)", RunFormat.elapsed(run.activeMillis()), tracker.day(), tracker.history().size());
			}
		} catch (RuntimeException e) {
			tracker = null;
			HardcoreTogether.LOGGER.error("[HT timer] could not start the run timer; it is off until the next restart", e);
		}
	}

	/**
	 * The run start hook: called when a player is sent into the current world (ResetController's RETURN for
	 * every player leaving the Death Hall after a real reset, and every join outside a reset). Starts the next
	 * run only if this world has none yet, so a reset gives exactly one new run no matter how many players are
	 * sent. Returns the current run number, or -1 if the timer is off or there is no run.
	 */
	public static int startRunInNewWorld(MinecraftServer server, String why) {
		HtWorldData data = HardcoreTogether.worldData();
		if (tracker == null || data == null) {
			return -1;
		}
		CurrentRun run = tracker.startRunIfNeeded(data.runNumber(), server.getWorldGenSettings().options().seed(), dayTime(server));
		if (run != null) {
			HardcoreTogether.LOGGER.info("[HT timer] run #{} started at 0, Day 1 ({}; reset #{})", run.run(), why, data.runNumber());
		}
		return tracker.current() == null ? -1 : tracker.current().run();
	}

	/**
	 * Called at every reset's COMMIT: a real reset ends the run as a command reset if it is still ACTIVE (a death
	 * has already ended it at the moment of death); a dry run changes nothing.
	 */
	public static void resetCommitted(boolean dryRun) {
		if (tracker == null) {
			return;
		}
		CurrentRun run = tracker.current();
		if (tracker.resetCommitted(dryRun)) {
			HardcoreTogether.LOGGER.info("[HT timer] run #{} ended by command reset at {}, Day {}", run.run(),
				RunFormat.elapsed(run.activeMillis()), run.finalDay());
		}
	}

	private static void tick(MinecraftServer server) {
		if (tracker == null) {
			return;
		}
		HtWorldData data = HardcoreTogether.worldData();
		boolean resetBusy = data == null || ResetController.isRunning() || data.resetState() != ResetState.IDLE;
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (!resetBusy) {
			tracker.observeDayTime(dayTime(server));
		}
		boolean count = !resetBusy && !players.isEmpty() && !server.isPaused();
		Map<UUID, String> inRunWorlds = new HashMap<>();
		for (ServerPlayer p : players) {
			if (WorldSwapper.RESET_LEVELS.contains(p.level().dimension())) {
				inRunWorlds.put(p.getUUID(), p.getGameProfile().name());
			}
		}
		tracker.tick(count, inRunWorlds);
	}

	/** The overworld clock's total ticks (what /time query reports for minecraft:overworld). */
	public static long dayTime(MinecraftServer server) {
		return server.clockManager().getInstance(server.registryAccess().getOrThrow(WorldClocks.OVERWORLD)).totalTicks();
	}

	/** /ht reset-count set changed the reset counter without a reset; keep the run attached to this world. */
	public static void worldResetRenumbered(int worldReset) {
		if (tracker != null && tracker.current() != null) {
			tracker.current().setWorldReset(worldReset);
			tracker.saveNow("reset count renumbered");
		}
	}

	public static @Nullable RunTracker tracker() {
		return tracker;
	}
}
