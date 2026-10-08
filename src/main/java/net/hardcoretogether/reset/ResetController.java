package net.hardcoretogether.reset;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.config.DifficultyControl;
import net.hardcoretogether.config.HtConfig;
import net.hardcoretogether.data.CurrentRun;
import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.data.PlayerRecords;
import net.hardcoretogether.data.ResetState;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.hall.DeathHall;
import net.hardcoretogether.run.RunTimer;
import net.hardcoretogether.run.RunTracker;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The world reset state machine, driven from END_SERVER_TICK on the server thread.
 * Each phase moves on only when its exit condition holds (with a timeout where one could hang). One INFO line
 * per phase change: "[HT reset #N] A -> B after T ticks".
 *
 * A real reset runs COUNTDOWN, COMMIT, GATHER, DRAIN, SWAP, WORLD_STATE, SPAWN, RETURN, DONE. A dry run swaps
 * DRAIN..SPAWN for DRY_RUN, only teleports players back in RETURN, and never changes the reset counter or run state.
 */
public final class ResetController {
	private static final int TICKS_PER_SECOND = 20;
	private static final int DRY_RUN_WAIT_TICKS = 5 * TICKS_PER_SECOND;
	private static final int GATHER_TIMEOUT_TICKS = 30 * TICKS_PER_SECOND;
	private static final int DRAIN_TIMEOUT_TICKS = 60 * TICKS_PER_SECOND;
	private static final int SPAWN_TIMEOUT_TICKS = 60 * TICKS_PER_SECOND;
	private static final int SPAWN_CHUNK_RADIUS = 2;
	private static final int RETURN_PER_TICK = 4;

	private static ResetState phase = ResetState.IDLE;
	private static int ticksInPhase;
	private static boolean dryRun;
	/** False for a death reset: a death is final, so /ht reset cancel refuses it. */
	private static boolean cancellable = true;
	/** Set when the SWAP guard fails: players are returned to the untouched old world. */
	private static boolean aborted;
	private static @Nullable String requestedSeed;
	private static @Nullable Long seed;
	private static int runNumber;
	private static long startedAtMillis;
	private static int returnedCount;
	private static int pendingCount;
	/** How long SWAP held the server thread, or -1 if this reset had no swap (dry run, aborted). */
	private static long swapMillis = -1;
	/** DRAIN's chunk write flush per level, started once everything has unloaded; null until then. */
	private static @Nullable Map<ResourceKey<Level>, CompletableFuture<Long>> flushes;
	/** How long each level's flush took (dimension path -> ms, -1 if it timed out). */
	private static final Map<String, Long> flushMillis = new LinkedHashMap<>();
	private static @Nullable CompletableFuture<?> spawnChunks;
	private static @Nullable ChunkPos spawnChunk;
	/** Players held in the hall by this reset, by UUID only (player objects change on respawn and rejoin). */
	private static final Set<UUID> held = new LinkedHashSet<>();
	/**
	 * Players who joined since the last tick. Fabric's JOIN event fires inside PlayerList.placeNewPlayer before
	 * the player is added to their level, so teleporting them there leaves a stale copy of the player behind.
	 * They are handled on the next tick instead, once fully placed.
	 */
	private static final Set<UUID> joinedSinceLastTick = new LinkedHashSet<>();
	/** Set by startup recovery when the world was reset offline and WORLD_STATE still has to run. */
	private static @Nullable ResetJournal recoveredJournal;

	/** A death reset whose countdown was cut short by a stop; it starts again once a player is online. */
	private static @Nullable PendingReset pendingRestart;

	public enum RequestResult { STARTED, ALREADY_RUNNING, UNAVAILABLE, GUARD_FAILED }

	public enum CancelResult { CANCELLED, TOO_LATE, NOT_RUNNING, NOT_CANCELLABLE }

	private ResetController() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(ResetController::recoverBeforeLevelsLoad);
		ServerLifecycleEvents.SERVER_STARTED.register(ResetController::recoverAfterStartup);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (HardcoreTogether.worldData() != null) {
				TrashBin.cleanLeftovers(server);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			DrainDiagnostics.stop(server, runNumber, false);
			clear();
			joinedSinceLastTick.clear();
			recoveredJournal = null;
			pendingRestart = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(ResetController::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> joinedSinceLastTick.add(handler.getPlayer().getUUID()));
	}

	/** An op's reset (/ht reset): can be cancelled until COMMIT. */
	public static RequestResult request(MinecraftServer server, String reason, boolean dry, @Nullable String seedText) {
		return request(server, reason, dry, seedText, true);
	}

	/**
	 * The single entry point for starting a reset. The death trigger passes cancellable = false. A real reset
	 * ends a still-ACTIVE run as a command reset at COMMIT; a dry run never touches run state.
	 */
	public static RequestResult request(MinecraftServer server, String reason, boolean dry, @Nullable String seedText, boolean cancellable) {
		if (phase != ResetState.IDLE) {
			return RequestResult.ALREADY_RUNNING;
		}
		HtWorldData data = HardcoreTogether.worldData();
		if (data == null || HardcoreTogether.playerRecords() == null || DeathHall.level(server) == null) {
			return RequestResult.UNAVAILABLE;
		}
		if (!dry) {
			try {
				WorldSwapper.verifiedTargets(server);
			} catch (WorldSwapper.GuardException e) {
				HardcoreTogether.LOGGER.error("[HT reset] delete guard failed, reset refused: {}", e.getMessage());
				return RequestResult.GUARD_FAILED;
			}
		}

		clear();
		dryRun = dry;
		ResetController.cancellable = cancellable;
		requestedSeed = seedText == null || seedText.isBlank() ? null : seedText.trim();
		runNumber = dry ? data.runNumber() : data.runNumber() + 1;
		startedAtMillis = System.currentTimeMillis();
		log("requested by {} ({}{})", reason, dry ? "dry run" : "real reset", requestedSeed == null ? "" : ", seed " + requestedSeed);

		int seconds = HardcoreTogether.config().countdownSeconds();
		String text = dry
			? "Dry-run world reset in " + seconds + " seconds. Nothing in the world will change."
			: "The world resets in " + seconds + " seconds. Everything will be lost.";
		server.getPlayerList().broadcastSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD), false);
		moveTo(server, ResetState.COUNTDOWN, "");
		return RequestResult.STARTED;
	}

	/**
	 * The reset for a death (not cancellable). Once its countdown has started, the death is saved as a pending reset
	 * so a stop before COMMIT doesn't lose the reset.
	 */
	public static RequestResult requestForDeath(MinecraftServer server, RunDeath death) {
		RequestResult result = request(server, "death of " + death.name(), false, null, false);
		if (result == RequestResult.STARTED) {
			HtWorldData data = HardcoreTogether.worldData();
			RunTracker tracker = RunTimer.tracker();
			CurrentRun run = tracker == null ? null : tracker.current();
			PendingReset pending = new PendingReset(data.runNumber(), run == null ? 0 : run.run(), death, Instant.now().toString());
			try {
				PendingReset.write(data.folder(), pending);
				log("pending reset saved (death of {}, run #{})", death.name(), pending.run());
			} catch (IOException e) {
				HardcoreTogether.LOGGER.error("[HT reset #{}] could not save the pending reset; a stop before COMMIT would lose it", runNumber, e);
			}
		}
		return result;
	}

	public static CancelResult cancel(MinecraftServer server) {
		if (phase == ResetState.IDLE) {
			return CancelResult.NOT_RUNNING;
		}
		if (phase.isCommitted()) {
			return CancelResult.TOO_LATE;
		}
		if (!cancellable) {
			return CancelResult.NOT_CANCELLABLE;
		}
		server.getPlayerList().broadcastSystemMessage(Component.literal("World reset cancelled.").withStyle(ChatFormatting.GREEN), false);
		moveTo(server, ResetState.IDLE, "(cancelled)");
		clear();
		return CancelResult.CANCELLED;
	}

	private static void tick(MinecraftServer server) {
		handleJoins(server);
		resumePendingReset(server);
		if (phase == ResetState.IDLE) {
			return;
		}
		ticksInPhase++;
		switch (phase) {
			case COUNTDOWN -> tickCountdown(server);
			case COMMIT -> commit(server);
			case GATHER -> tickGather(server);
			case DRAIN -> tickDrain(server);
			case SWAP -> swap(server);
			case WORLD_STATE -> worldState(server);
			case SPAWN -> tickSpawn(server);
			case DRY_RUN -> tickDryRun(server);
			case RETURN -> tickReturn(server);
			case DONE -> finish(server);
			case IDLE -> {
			}
		}
	}

	/** Restarts a death reset's countdown from the start, once a player is online after the stop. */
	private static void resumePendingReset(MinecraftServer server) {
		RunTracker tracker = RunTimer.tracker();
		if (pendingRestart == null || tracker == null
				|| !PendingReset.startNow(phase == ResetState.IDLE, server.getPlayerList().getPlayerCount())) {
			return;
		}
		PendingReset pending = pendingRestart;
		pendingRestart = null;
		if (PendingReset.recordIfMissing(tracker, pending, RunTimer.dayTime(server))) {
			HardcoreTogether.LOGGER.warn("[HT reset] the death of {} was missing from run #{}; recorded now", pending.death().name(), pending.run());
		}
		RequestResult result = request(server, "death of " + pending.death().name() + ", resumed after a stop", false, null, false);
		if (result == RequestResult.STARTED) {
			log("countdown restarted for the death of {} (run #{})", pending.death().name(), pending.run());
		} else {
			HardcoreTogether.LOGGER.error("[HT reset] could not restart the reset for the death of {}: {}; use /ht reset",
				pending.death().name(), result);
		}
	}

	// ---- Phases ----

	private static void tickCountdown(MinecraftServer server) {
		int total = HardcoreTogether.config().countdownSeconds() * TICKS_PER_SECOND;
		int remainingTicks = total - ticksInPhase;
		if (remainingTicks <= 0) {
			moveTo(server, ResetState.COMMIT, "");
			return;
		}
		if ((ticksInPhase - 1) % TICKS_PER_SECOND == 0) {
			int seconds = (remainingTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
			server.getPlayerList().broadcastSystemMessage(
				Component.literal("World reset in " + seconds + "...").withStyle(ChatFormatting.RED), true);
		}
	}

	private static void commit(MinecraftServer server) {
		HtWorldData data = HardcoreTogether.worldData();
		seed = chooseSeed();
		try {
			if (!dryRun) {
				PlayerRecords records = HardcoreTogether.playerRecords();
				// Everyone who has played so far belongs to the run that is ending; without a record they would
				// count as current and skip the fresh-player treatment.
				knownPlayers(server).forEach(uuid -> records.setIfAbsent(uuid, runNumber - 1));
				records.save();
				data.setRunNumber(runNumber);
			}
			data.setResetState(ResetState.COMMIT);
			data.save();
			writeJournal(data);
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("[HT reset #{}] could not write the journal; aborting before any player is moved", runNumber, e);
			if (!dryRun) {
				data.setRunNumber(runNumber - 1);
			}
			server.getPlayerList().broadcastSystemMessage(Component.literal("World reset failed to start (see server log).").withStyle(ChatFormatting.RED), false);
			moveTo(server, ResetState.IDLE, "(aborted)");
			clear();
			return;
		}
		// Past the point of no return: an op's real reset ends the run here (a death already ended it).
		RunTimer.resetCommitted(dryRun);
		moveTo(server, ResetState.GATHER, "(seed " + seed + ")");
	}

	private static long chooseSeed() {
		OptionalLong parsed = requestedSeed != null ? WorldOptions.parseSeed(requestedSeed) : OptionalLong.empty();
		if (parsed.isPresent()) {
			return parsed.getAsLong();
		}
		HtConfig config = HardcoreTogether.config();
		if (config.hasFixedSeed()) {
			return WorldOptions.parseSeed(config.fixedSeed()).orElse(WorldOptions.randomSeed());
		}
		return WorldOptions.randomSeed();
	}

	private static void tickGather(MinecraftServer server) {
		gatherOnlinePlayers(server);
		ResetState next = dryRun ? ResetState.DRY_RUN : ResetState.DRAIN;
		boolean allInHall = server.getPlayerList().getPlayers().stream().allMatch(DeathHall::contains);
		if (allInHall) {
			moveTo(server, next, "(" + held.size() + " held)");
		} else if (ticksInPhase >= GATHER_TIMEOUT_TICKS) {
			HardcoreTogether.LOGGER.warn("[HT reset #{}] GATHER timed out with players outside the hall; continuing", runNumber);
			moveTo(server, next, "(timeout, " + held.size() + " held)");
		}
	}

	/**
	 * DRAIN runs in two steps under one timeout: unload everything (drainTick), then wait until each level's
	 * chunk I/O worker has written and flushed what the unloads queued, so close() in SWAP has nothing left.
	 */
	private static void tickDrain(MinecraftServer server) {
		if (ticksInPhase == 1) {
			DrainDiagnostics.start();
			WorldSwapper.beginDrain(server);
		}
		gatherOnlinePlayers(server);
		boolean drained = WorldSwapper.drainTick(server);
		if (drained && flushes == null) {
			flushes = WorldSwapper.beginFlush(server);
			log("DRAIN: chunks unloaded after {} ticks; flushing chunk writes", ticksInPhase);
		}
		if (drained && flushes.values().stream().allMatch(CompletableFuture::isDone)) {
			recordFlushTimes();
			DrainDiagnostics.stop(server, runNumber, false);
			moveTo(server, ResetState.SWAP, "(" + chunkSummary(server) + "; flush " + flushSummary() + ")");
		} else if (ticksInPhase >= DRAIN_TIMEOUT_TICKS) {
			if (flushes == null) {
				HardcoreTogether.LOGGER.warn("[HT reset #{}] DRAIN timed out ({}, pending joins {}); closing anyway",
					runNumber, chunkSummary(server), JoinRouting.pendingVanillaSpawns());
			} else {
				recordFlushTimes();
				String writing = flushes.entrySet().stream().filter(e -> !e.getValue().isDone())
					.map(e -> e.getKey().identifier().getPath()).collect(Collectors.joining(", "));
				HardcoreTogether.LOGGER.warn("[HT reset #{}] DRAIN timed out waiting for chunk writes (still writing: {}; {}); closing anyway",
					runNumber, writing.isEmpty() ? "none" : writing, chunkSummary(server));
			}
			DrainDiagnostics.stop(server, runNumber, true);
			moveTo(server, ResetState.SWAP, "(timeout" + (flushes == null ? "" : "; flush " + flushSummary()) + ")");
		}
	}

	/** Copies finished flush times into flushMillis; a level still writing gets -1. */
	private static void recordFlushTimes() {
		flushMillis.clear();
		flushes.forEach((key, future) -> flushMillis.put(key.identifier().getPath(), future.isDone() ? future.join() : -1L));
	}

	private static String flushSummary() {
		return formatFlushTimes(flushMillis);
	}

	/** "overworld 812 ms, the_nether 0 ms, the_end 0 ms"; -1 shows as "still writing". */
	public static String formatFlushTimes(Map<String, Long> times) {
		return times.entrySet().stream()
			.map(e -> e.getKey() + " " + (e.getValue() < 0 ? "still writing" : e.getValue() + " ms"))
			.collect(Collectors.joining(", "));
	}

	private static void swap(MinecraftServer server) {
		try {
			WorldSwapper.verifiedTargets(server);
		} catch (WorldSwapper.GuardException e) {
			HardcoreTogether.LOGGER.error("[HT reset #{}] delete guard failed before SWAP: {}. Reset aborted; nothing was deleted", runNumber, e.getMessage());
			abort(server);
			return;
		}
		WorldSwapper.SwapResult result = WorldSwapper.swap(server, seed, runNumber);
		swapMillis = result.millis();
		log("swapped overworld, nether and end in {} ms (seed {}); {} old folder(s) moved to the trash", swapMillis, seed, result.trash().size());
		log("swap timing: {}", result.breakdown());
		result.trash().forEach(folder -> TrashBin.deleteLater(server, folder));
		moveTo(server, ResetState.WORLD_STATE, "");
	}

	private static void worldState(MinecraftServer server) {
		WorldSwapper.resetWorldState(server);
		moveTo(server, ResetState.SPAWN, "(weather and clocks reset)");
	}

	private static void tickSpawn(MinecraftServer server) {
		ServerChunkCache chunks = server.overworld().getChunkSource();
		if (spawnChunks == null) {
			spawnChunk = chunks.getGenerator().getOrigin(chunks.randomState());
			spawnChunks = chunks.addTicketAndLoadWithRadius(TicketType.FORCED, spawnChunk, SPAWN_CHUNK_RADIUS);
		}
		boolean ready = spawnChunks.isDone();
		if (!ready && ticksInPhase < SPAWN_TIMEOUT_TICKS) {
			return;
		}
		if (!ready) {
			HardcoreTogether.LOGGER.warn("[HT reset #{}] spawn chunks not ready after {} s; finding spawn anyway", runNumber, SPAWN_TIMEOUT_TICKS / TICKS_PER_SECOND);
		}
		WorldSwapper.placeWorldSpawn(server);
		chunks.removeTicketWithRadius(TicketType.FORCED, spawnChunk, SPAWN_CHUNK_RADIUS);
		moveTo(server, ResetState.RETURN, "(spawn " + server.getRespawnData().pos().toShortString() + ")");
	}

	private static void tickDryRun(MinecraftServer server) {
		gatherOnlinePlayers(server);
		if (ticksInPhase >= DRY_RUN_WAIT_TICKS) {
			log("DRY RUN: world swap skipped");
			moveTo(server, ResetState.RETURN, "");
		}
	}

	/**
	 * Real reset: a fresh player object at the new spawn, everything cleared, lastRunId recorded. Offline held
	 * players get the same treatment when they next join (their lastRunId is older than the run).
	 * Dry run or aborted reset: teleport back to the world spawn in Survival only.
	 */
	private static void tickReturn(MinecraftServer server) {
		HtWorldData data = HardcoreTogether.worldData();
		PlayerRecords records = HardcoreTogether.playerRecords();
		boolean fresh = !dryRun && !aborted;
		// Portal start: a real reset leaves everyone in the hall; they start by walking through the portal.
		boolean portal = fresh && HardcoreTogether.config().portalStart();
		int done = 0;
		Iterator<UUID> it = held.iterator();
		while (it.hasNext() && done < RETURN_PER_TICK) {
			UUID uuid = it.next();
			it.remove();
			done++;
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null && DeathHall.isEditing(player)) {
				// Editors stay in the hall; a real reset's fresh start waits until their edit mode ends.
				if (fresh) {
					DeathHall.markMissedReset(player);
				}
				HardcoreTogether.LOGGER.info("[HT reset #{}] {} is in hall edit mode; left in the hall{}", runNumber,
					player.getGameProfile().name(), fresh ? " (fresh start when edit mode ends)" : "");
			} else if (player != null && portal) {
				HardcoreTogether.LOGGER.info("[HT reset #{}] {} waits in the Death Hall for the portal", runNumber, player.getGameProfile().name());
			} else if (player != null) {
				String name = player.getGameProfile().name();
				if (fresh) {
					// The run start hook: the first player sent into the new world starts the next run.
					RunTimer.startRunInNewWorld(server, "players sent to the new world");
					PlayerReset.makeFresh(player);
					records.setLastRunId(uuid, runNumber);
				} else {
					sendBack(player);
				}
				returnedCount++;
				HardcoreTogether.LOGGER.info("[HT reset #{}] returned {}{}", runNumber, name, fresh ? " (fresh)" : "");
			} else {
				if (!fresh) {
					data.addPendingReturn(uuid);
				}
				pendingCount++;
				HardcoreTogether.LOGGER.info("[HT reset #{}] {} is offline; will be returned on next join", runNumber, uuid);
			}
		}
		if (done > 0 && fresh) {
			records.trySave();
		}
		if (held.isEmpty()) {
			if (portal) {
				data.setAwaitingStart(true);
				data.trySave();
			}
			moveTo(server, ResetState.DONE, "(" + returnedCount + " returned, " + pendingCount + " pending" + (portal ? ", the rest wait for the portal" : "") + ")");
		} else if (done > 0) {
			updateJournal(data);
		}
	}

	private static void finish(MinecraftServer server) {
		HtWorldData data = HardcoreTogether.worldData();
		try {
			ResetJournal.delete(data);
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("[HT reset #{}] could not delete the journal", runNumber, e);
		}
		double seconds = (System.currentTimeMillis() - startedAtMillis) / 1000.0;
		String kind = aborted ? "aborted reset" : dryRun ? "dry run" : "reset";
		if (!dryRun && !aborted) {
			data.setLastResetSeconds(seconds);
			data.setLastSwapMillis(swapMillis);
			data.setLastFlushMillis(flushMillis);
		}
		log("summary: {} complete in {} s, swap {}, seed {}, reset {}, returned {}, pending {}",
			kind, String.format("%.1f", seconds), swapMillis < 0 ? "skipped" : swapMillis + " ms",
			aborted ? "unchanged" : seed, data.runNumber(), returnedCount, pendingCount);
		boolean newWorld = !dryRun && !aborted;
		if (newWorld) {
			deletePendingReset(data);
		}
		moveTo(server, ResetState.IDLE, "");
		clear();
		// The run counter is RunTimer's own. If nobody was online to send, the run starts on the first join.
		CurrentRun current = RunTimer.tracker() == null ? null : RunTimer.tracker().current();
		boolean started = newWorld && current != null && current.isActive() && current.worldReset() == data.runNumber();
		String text = aborted ? "World reset aborted; the world was not changed."
			: dryRun ? "Dry-run reset complete." : data.awaitingStart() ? "A new world is ready. Step into the portal to begin."
			: started ? "A new world has begun. Run #" + current.run() + "." : "A new world has begun.";
		server.getPlayerList().broadcastSystemMessage(Component.literal(text).withStyle(aborted ? ChatFormatting.RED : ChatFormatting.GREEN), false);
		DifficultyControl.apply(server, "after reset");
	}

	/** The SWAP guard failed while the old levels were still open: undo the commit and send everyone back. */
	private static void abort(MinecraftServer server) {
		aborted = true;
		// DRAIN's flush set noSave on the old levels, which stay in use now; give them back normal saving.
		WorldSwapper.RESET_LEVELS.forEach(key -> {
			ServerLevel level = server.getLevel(key);
			if (level != null) {
				level.noSave = false;
			}
		});
		HtWorldData data = HardcoreTogether.worldData();
		data.setRunNumber(runNumber - 1);
		data.trySave();
		server.getPlayerList().broadcastSystemMessage(Component.literal("World reset aborted (see server log).").withStyle(ChatFormatting.RED), false);
		moveTo(server, ResetState.RETURN, "(aborted)");
	}

	// ---- Players ----

	/** Moves every online player who isn't held yet into the hall. Also catches anyone who left it mid-run. */
	private static void gatherOnlinePlayers(MinecraftServer server) {
		ServerLevel hall = DeathHall.level(server);
		boolean changed = false;
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			if (!DeathHall.contains(player) || !held.contains(player.getUUID())) {
				hold(player, hall);
				changed = true;
			}
		}
		if (changed) {
			updateJournal(HardcoreTogether.worldData());
		}
	}

	private static void hold(ServerPlayer player, ServerLevel hall) {
		if (player.isDeadOrDying()) {
			// Dead (a death ignored during the countdown, or a dead player rejoining): respawn instead of
			// teleporting a dead player object, which is not a vanilla path.
			player = DeathHall.respawnInHall(player, hall);
		}
		if (!DeathHall.contains(player)) {
			PlayerReset.discardEnderPearls(player);
			DeathHall.teleportToSpawn(player, hall);
		}
		// An editor is already in the hall (edit mode ends on leaving it) and keeps edit mode through the reset.
		if (!DeathHall.isEditing(player)) {
			player.setGameMode(GameType.ADVENTURE);
		}
		if (held.add(player.getUUID())) {
			HardcoreTogether.LOGGER.info("[HT reset #{}] holding {} in the Death Hall", runNumber, player.getGameProfile().name());
		}
	}

	/**
	 * Edit mode ended for a player a real reset left in the hall: give them the fresh start RETURN skipped. If
	 * another reset is already holding players, they just join it instead.
	 */
	public static void sendEditorToNewWorld(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		HtWorldData data = HardcoreTogether.worldData();
		PlayerRecords records = HardcoreTogether.playerRecords();
		if (data == null || records == null) {
			return;
		}
		if (holdsJoiners()) {
			hold(player, DeathHall.level(server));
			updateJournal(data);
			return;
		}
		if (data.awaitingStart()) {
			// The fresh start happens at the portal with everyone else.
			waitInHall(player);
			return;
		}
		RunTimer.startRunInNewWorld(server, player.getGameProfile().name() + " finished editing the hall");
		PlayerReset.makeFresh(player);
		records.setLastRunId(player.getUUID(), data.runNumber());
		records.trySave();
		HardcoreTogether.LOGGER.info("[HT reset] {} finished hall editing; fresh start in reset {}", player.getGameProfile().name(), data.runNumber());
	}

	/** Portal start: puts a player in the hall (respawning a dead one), in Adventure unless they are editing. */
	private static void waitInHall(ServerPlayer player) {
		ServerLevel hall = DeathHall.level(player.level().getServer());
		if (hall == null) {
			return;
		}
		if (player.isDeadOrDying()) {
			player = DeathHall.respawnInHall(player, hall);
		}
		if (!DeathHall.contains(player)) {
			PlayerReset.discardEnderPearls(player);
			DeathHall.teleportToSpawn(player, hall);
		}
		if (!DeathHall.isEditing(player)) {
			player.setGameMode(GameType.ADVENTURE);
		}
	}

	/** True while a finished reset waits for the players to go through the portal (survives restarts). */
	public static boolean awaitingStart() {
		HtWorldData data = HardcoreTogether.worldData();
		return data != null && data.awaitingStart() && !isRunning();
	}

	/** Portal start: brings a player who is somewhere else back to the hall while the portal waits. */
	public static void keepInHall(ServerPlayer player) {
		waitInHall(player);
	}

	/**
	 * Starts the next run for everyone online (the portal, or /ht run start): the same fresh start RETURN gives in
	 * auto mode. Editors get it when their edit mode ends; offline players on their next join. Returns how many
	 * players were sent, or -1 if no new world is waiting.
	 */
	public static int startNewRun(MinecraftServer server, String why) {
		HtWorldData data = HardcoreTogether.worldData();
		PlayerRecords records = HardcoreTogether.playerRecords();
		if (data == null || records == null || !awaitingStart()) {
			return -1;
		}
		data.setAwaitingStart(false);
		data.trySave();
		int sent = 0;
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			if (DeathHall.isEditing(player)) {
				DeathHall.markMissedReset(player);
				HardcoreTogether.LOGGER.info("[HT portal] {} is in hall edit mode; fresh start when edit mode ends", player.getGameProfile().name());
				continue;
			}
			RunTimer.startRunInNewWorld(server, why);
			PlayerReset.makeFresh(player);
			records.setLastRunId(player.getUUID(), data.runNumber());
			sent++;
			HardcoreTogether.LOGGER.info("[HT portal] sent {} to the new world", player.getGameProfile().name());
		}
		records.trySave();
		CurrentRun current = RunTimer.tracker() == null ? null : RunTimer.tracker().current();
		HardcoreTogether.LOGGER.info("[HT portal] run start ({}): {} player(s) sent, run #{}", why, sent, current == null ? "?" : current.run());
		server.getPlayerList().broadcastSystemMessage(Component.literal(current != null && current.isActive()
			? "A new world has begun. Run #" + current.run() + "." : "A new world has begun.").withStyle(ChatFormatting.GREEN), false);
		return sent;
	}

	private static void sendBack(ServerPlayer player) {
		DeathHall.sendToWorldSpawn(player);
		player.setGameMode(GameType.SURVIVAL);
	}

	private static void handleJoins(MinecraftServer server) {
		if (joinedSinceLastTick.isEmpty()) {
			return;
		}
		List<UUID> joined = List.copyOf(joinedSinceLastTick);
		joinedSinceLastTick.clear();
		for (UUID uuid : joined) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) {
				onJoin(server, player);
			}
		}
	}

	private static void onJoin(MinecraftServer server, ServerPlayer player) {
		HtWorldData data = HardcoreTogether.worldData();
		PlayerRecords records = HardcoreTogether.playerRecords();
		if (data == null || records == null) {
			return;
		}
		UUID uuid = player.getUUID();
		String name = player.getGameProfile().name();
		if (records.recordJoin(uuid, name)) {
			HardcoreTogether.LOGGER.info("[HT players] registered {} ({})", name, uuid);
			net.hardcoretogether.hall.StatueEntities.request(server, "player " + name + " registered", null); // deaths board
		}
		records.trySave();
		boolean wasPending = data.removePendingReturn(uuid);

		if (holdsJoiners()) {
			// Joined mid-reset: wait in the hall and come back with everyone else.
			hold(player, DeathHall.level(server));
			updateJournal(data);
		} else if (data.awaitingStart()) {
			// Portal start: the new world is ready but nobody has gone through the portal yet. Wait in the hall.
			waitInHall(player);
			HardcoreTogether.LOGGER.info("[HT portal] {} joined while the portal waits; in the Death Hall", name);
		} else {
			// The run start hook for joins: starts the run if this is a new world without one (reset recovered on
			// startup, or nobody online during RETURN). Does nothing in a world whose run has already ended.
			RunTimer.startRunInNewWorld(server, name + " joined the new world");
			OptionalInt last = records.lastRunId(uuid);
			if (last.isPresent() && last.getAsInt() < data.runNumber()) {
				PlayerReset.makeFresh(player);
				records.setLastRunId(uuid, data.runNumber());
				records.trySave();
				HardcoreTogether.LOGGER.info("[HT reset] {} last played in reset {}; fresh start in reset {}", name, last.getAsInt(), data.runNumber());
			} else {
				if (last.isEmpty()) {
					records.setLastRunId(uuid, data.runNumber());
					records.trySave();
				}
				if (wasPending) {
					sendBack(player);
					HardcoreTogether.LOGGER.info("[HT reset] returned {} on join (held by an earlier dry run)", name);
				}
			}
		}
		if (wasPending) {
			data.trySave();
		}
		ServerPlayer current = server.getPlayerList().getPlayer(uuid);
		if (current != null) {
			StuckSpectator.Fix fix = StuckSpectator.onJoin(current.gameMode() == GameType.SPECTATOR, DeathHall.contains(current),
				pendingRestart != null, isRunning());
			if (fix != StuckSpectator.Fix.NONE) {
				current.setGameMode(fix == StuckSpectator.Fix.ADVENTURE ? GameType.ADVENTURE : GameType.SURVIVAL);
				HardcoreTogether.LOGGER.warn("[HT reset] {} joined in Spectator with no reset on its way; now in {}", name, fix);
			}
		}
	}

	/** UUIDs of every player who has a saved player file, plus everyone online. */
	private static Set<UUID> knownPlayers(MinecraftServer server) {
		Set<UUID> uuids = new LinkedHashSet<>();
		server.getPlayerList().getPlayers().forEach(p -> uuids.add(p.getUUID()));
		Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
		if (Files.isDirectory(dir)) {
			try (Stream<Path> files = Files.list(dir)) {
				files.map(f -> f.getFileName().toString())
					.filter(n -> n.endsWith(".dat"))
					.map(n -> n.substring(0, n.length() - 4))
					.forEach(n -> {
						try {
							uuids.add(UUID.fromString(n));
						} catch (IllegalArgumentException ignored) {
							// not a player file
						}
					});
			} catch (IOException e) {
				HardcoreTogether.LOGGER.warn("[HT reset] could not list {}", dir, e);
			}
		}
		return uuids;
	}

	// ---- Shutdown recovery ----

	/**
	 * Runs before vanilla creates the levels. A real-reset journal from COMMIT up to SPAWN means the server
	 * stopped mid-reset: delete the three folders (same guard), install the journal's seed and mark the world
	 * uninitialised, so vanilla's normal startup generates the fresh world and spawn.
	 */
	private static void recoverBeforeLevelsLoad(MinecraftServer server) {
		HtWorldData data = HardcoreTogether.worldData();
		if (data == null) {
			return;
		}
		try {
			Optional<ResetJournal> journal = ResetJournal.read(data);
			if (journal.isEmpty() || journal.get().dryRun()) {
				return;
			}
			ResetJournal j = journal.get();
			recoveredJournal = j;
			if (j.phase().ordinal() < ResetState.RETURN.ordinal()) {
				WorldSwapper.verifiedTargets(server);
				for (var key : WorldSwapper.RESET_LEVELS) {
					// Only renamed here; cleanLeftovers deletes the trash in the background once the server has started.
					TrashBin.moveToTrash(server, key, j.runNumber());
				}
				WorldSwapper.installSeed(server, j.seed());
				server.getWorldData().overworldData().setInitialized(false);
				HardcoreTogether.LOGGER.warn("[HT reset #{}] server stopped during {}; finishing the reset on startup (seed {})",
					j.runNumber(), j.phase(), j.seed());
			}
		} catch (WorldSwapper.GuardException e) {
			recoveredJournal = null;
			HardcoreTogether.LOGGER.error("[HT reset] delete guard failed during recovery: {}. Nothing deleted; journal left in place", e.getMessage());
		} catch (IOException e) {
			recoveredJournal = null;
			HardcoreTogether.LOGGER.error("[HT reset] recovery failed; journal left in place", e);
		}
	}

	/** Runs once the levels exist: finishes a recovered reset, or recovers an interrupted dry run. */
	private static void recoverAfterStartup(MinecraftServer server) {
		HtWorldData data = HardcoreTogether.worldData();
		if (data == null) {
			return;
		}
		try {
			if (recoveredJournal != null) {
				ResetJournal j = recoveredJournal;
				recoveredJournal = null;
				if (j.phase().ordinal() < ResetState.RETURN.ordinal()) {
					WorldSwapper.resetWorldState(server);
				}
				data.setRunNumber(Math.max(data.runNumber(), j.runNumber()));
				if (!j.dryRun() && HardcoreTogether.config().portalStart()) {
					data.setAwaitingStart(true); // portal start: everyone waits in the hall for the portal
				}
				ResetJournal.delete(data);
				if (!j.dryRun()) {
					deletePendingReset(data);
				}
				HardcoreTogether.LOGGER.info("[HT reset #{}] recovered: new world ready (seed {}); {} held players get a fresh start on join",
					j.runNumber(), server.getWorldGenSettings().options().seed(), j.held().size());
			} else {
				Optional<ResetJournal> journal = ResetJournal.read(data);
				if (journal.isPresent() && journal.get().dryRun()) {
					ResetJournal j = journal.get();
					j.held().forEach(data::addPendingReturn);
					ResetJournal.delete(data);
					HardcoreTogether.LOGGER.warn("[HT reset] dry run interrupted during {}; {} held players will be returned on join",
						j.phase(), j.held().size());
				} else if (journal.isPresent()) {
					HardcoreTogether.LOGGER.error("[HT reset] a reset journal is still present after startup; see earlier errors");
					return;
				} else {
					resumeAfterStop(data);
				}
			}
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("Could not read the reset journal; leaving it in place", e);
			return;
		}
		data.setResetState(ResetState.IDLE);
		data.trySave();
	}

	/** No journal: a death reset stopped during its countdown is restarted once a player is online. */
	private static void resumeAfterStop(HtWorldData data) {
		Optional<PendingReset> pending;
		try {
			pending = PendingReset.read(data.folder());
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("[HT reset] could not read {}; use /ht reset if a death reset was cut short", PendingReset.FILE_NAME, e);
			return;
		}
		switch (PendingReset.onLoad(pending, false, data.runNumber())) {
			case RESUME -> {
				pendingRestart = pending.get();
				HardcoreTogether.LOGGER.warn("[HT reset] server stopped during the countdown for the death of {} (run #{}); the countdown"
					+ " restarts when a player is online", pendingRestart.death().name(), pendingRestart.run());
			}
			case STALE -> {
				deletePendingReset(data);
				HardcoreTogether.LOGGER.info("[HT reset] removed a pending reset for reset #{}; the world has moved on", pending.get().worldReset());
			}
			case NONE -> {
				if (data.resetState() != ResetState.IDLE) {
					HardcoreTogether.LOGGER.warn("[HT reset] server stopped during {} (before COMMIT); nothing to recover", data.resetState());
				}
			}
		}
	}

	private static void deletePendingReset(HtWorldData data) {
		try {
			PendingReset.delete(data.folder());
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("[HT reset] could not delete {}", PendingReset.FILE_NAME, e);
		}
	}

	// ---- State, journal, logging ----

	private static void moveTo(MinecraftServer server, ResetState next, String detail) {
		ResetState from = phase;
		log("{} -> {} after {} ticks{}", from, next, ticksInPhase, detail.isEmpty() ? "" : " " + detail);
		phase = next;
		ticksInPhase = 0;
		HtWorldData data = HardcoreTogether.worldData();
		if (data != null) {
			data.setResetState(next);
			data.trySave();
			if (next.isCommitted() && from.isCommitted()) {
				updateJournal(data);
			}
		}
	}

	private static void writeJournal(HtWorldData data) throws IOException {
		ResetJournal.write(data, runNumber, seed, dryRun, phase, held, startedAtMillis);
	}

	private static void updateJournal(@Nullable HtWorldData data) {
		if (data == null || seed == null || !phase.isCommitted() || phase == ResetState.DONE) {
			return;
		}
		try {
			writeJournal(data);
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("[HT reset #{}] could not update the journal", runNumber, e);
		}
	}

	private static void log(String message, Object... args) {
		Object[] all = new Object[args.length + 1];
		all[0] = dryRun ? runNumber + " dry" : runNumber;
		System.arraycopy(args, 0, all, 1, args.length);
		HardcoreTogether.LOGGER.info("[HT reset #{}] " + message, all);
	}

	private static String chunkSummary(MinecraftServer server) {
		StringBuilder out = new StringBuilder();
		for (var key : WorldSwapper.RESET_LEVELS) {
			ServerLevel level = server.getLevel(key);
			if (level != null) {
				if (!out.isEmpty()) {
					out.append(", ");
				}
				out.append(key.identifier().getPath()).append(" chunks=").append(level.getChunkSource().getLoadedChunksCount());
			}
		}
		return out.toString();
	}

	private static void clear() {
		phase = ResetState.IDLE;
		ticksInPhase = 0;
		dryRun = false;
		cancellable = true;
		aborted = false;
		requestedSeed = null;
		seed = null;
		returnedCount = 0;
		pendingCount = 0;
		swapMillis = -1;
		flushes = null;
		flushMillis.clear();
		spawnChunks = null;
		spawnChunk = null;
		held.clear();
	}

	// ---- Queries (status, mixins) ----

	public static boolean isRunning() {
		return phase != ResetState.IDLE;
	}

	/** From COMMIT until RETURN has finished, joining players are sent to the Death Hall. */
	public static boolean holdsJoiners() {
		return phase.isCommitted() && phase != ResetState.DONE;
	}

	public static ResetState phase() {
		return phase;
	}

	public static int ticksInPhase() {
		return ticksInPhase;
	}

	public static boolean isDryRun() {
		return dryRun;
	}

	public static @Nullable Long seed() {
		return seed;
	}

	public static @Nullable String requestedSeed() {
		return requestedSeed;
	}

	public static int runNumber() {
		return runNumber;
	}

	public static Set<UUID> heldPlayers() {
		return Set.copyOf(held);
	}
}
