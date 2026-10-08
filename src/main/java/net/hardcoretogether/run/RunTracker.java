package net.hardcoretogether.run;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.CurrentRun;
import net.hardcoretogether.data.MemorialData;
import net.hardcoretogether.data.MemorialEvents;
import net.hardcoretogether.data.PlayerStats;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunHistoryEntry;
import net.hardcoretogether.data.RunStore;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The run timer's logic, free of Minecraft classes so it can be unit tested with a fake clock.
 *
 * Active time is measured in open/close segments of a monotonic clock (System.nanoTime in game). A segment is
 * open while the caller says the run should count (ACTIVE, a player online, no reset, server not paused) and
 * is closed on run end and shutdown. Segments never survive a restart: only closed time is saved (a save
 * folds the open part in first). A gap between ticks longer than MAX_TICK_GAP_NANOS (a single-player pause,
 * which skips the tick event entirely, or a long stall) closes the segment at the last tick and reopens it
 * now, so the gap doesn't count.
 *
 * While a segment is open the run is saved every saveIntervalNanos. A failed save logs one warning per failure
 * streak, keeps the time in memory and is retried at the next interval; recovery is logged once. Saves for a
 * run start, run end, set-time and shutdown are always attempted.
 *
 * Every change to the finished runs or stats (run recorded, run deleted, stats rebuilt, fresh start) replaces
 * the MemorialData snapshot and fires MemorialEvents once; a run start replaces the snapshot without firing.
 */
public final class RunTracker {
	public static final long MAX_TICK_GAP_NANOS = 2_000_000_000L;
	private static final Logger LOGGER = LoggerFactory.getLogger(HardcoreTogether.MOD_ID);

	private final RunStore store;
	private final LongSupplier nanoClock;
	private final LongSupplier epochClock;
	private final long saveIntervalNanos;

	private @Nullable CurrentRun current;
	private final List<RunHistoryEntry> history;
	/** Start of the open segment, or -1 when closed. */
	private long segmentStart = -1;
	private long lastTickNanos = -1;
	private long lastCreditNanos = -1;
	private long lastSaveAttemptNanos;
	private long lastSaveEpoch = -1;
	private boolean saveFailing;
	private int failedSaves;
	private int saveAttempts;
	/** Set when appending to the history failed; the next run start or end tries again. */
	private boolean historyDirty;
	/** Lifetime player stats, updated in endRun together with the history. */
	private PlayerStats stats;
	private boolean statsDirty;
	/** Highest run number ever filed (kept in run-history.json), so a deleted run's number is never reused. */
	private int highestRun;

	public enum DeleteResult { DELETED, NOT_FOUND, ACTIVE_RUN }

	public RunTracker(RunStore store, LongSupplier nanoClock, LongSupplier epochClock, long saveIntervalNanos) {
		this.store = store;
		this.nanoClock = nanoClock;
		this.epochClock = epochClock;
		this.saveIntervalNanos = saveIntervalNanos;
		RunStore.History file = store.loadHistoryFile();
		this.history = new ArrayList<>(file.runs());
		this.highestRun = file.highestRun();
		this.current = store.loadCurrent();
		this.stats = PlayerStats.loadOrRebuild(store.folder(), history);
		this.lastSaveAttemptNanos = nanoClock.getAsLong();
		MemorialData.set(memorialSnapshot());
	}

	/**
	 * One past the highest run number in the current run, the history, or ever filed (deleted runs included);
	 * 1 if there are no runs yet.
	 */
	public int nextRunNumber() {
		int max = Math.max(highestRun, current == null ? 0 : current.run());
		for (RunHistoryEntry e : history) {
			max = Math.max(max, e.run());
		}
		return max + 1;
	}

	/** Starts the next run at 0 (status ACTIVE). An ACTIVE run must be ended first. */
	public CurrentRun startRun(int worldReset, long seed, long dayTime) {
		if (current != null && current.isActive()) {
			throw new IllegalStateException("run " + current.run() + " is still active");
		}
		if (historyDirty) {
			saveHistory();
		}
		if (statsDirty) {
			statsDirty = !stats.trySave(store.folder());
		}
		closeSegment(nanoClock.getAsLong());
		current = CurrentRun.start(nextRunNumber(), worldReset, seed, epochClock.getAsLong(), dayTime);
		saveNow("run started");
		MemorialData.set(memorialSnapshot());
		return current;
	}

	/**
	 * Starts the run for the world with this reset number, if that world has none yet: no run at all, or the
	 * current run belongs to an older world. An ACTIVE run from an older world (left by a version that didn't
	 * end runs at COMMIT) is ended as a command reset first. An ENDED run in this same world
	 * (a death whose reset hasn't swapped the world yet) never gets a new run. Returns the new run, or null.
	 */
	public @Nullable CurrentRun startRunIfNeeded(int worldReset, long seed, long dayTime) {
		if (current != null) {
			if (current.worldReset() == worldReset) {
				return null;
			}
			if (current.isActive()) {
				endRun("command", null, current.lastDayTime());
			}
		}
		return startRun(worldReset, seed, dayTime);
	}

	/**
	 * A reset passed COMMIT: a real reset ends a still-ACTIVE run as "command" (a death has already ended it);
	 * a dry run never touches run state. Returns true if a run was ended here.
	 */
	public boolean resetCommitted(boolean dryRun) {
		if (dryRun || current == null) {
			return false;
		}
		return endRun("command", null, current.lastDayTime());
	}

	/**
	 * Ends the ACTIVE run now: closes the segment, freezes time and day, sets ENDED, saves, appends one entry to
	 * the history and applies that entry to the player stats (the only stats update for this run). death is null
	 * for a command reset. Returns false if no run is active.
	 */
	public boolean endRun(String kind, @Nullable RunDeath death, long dayTime) {
		if (current == null || !current.isActive()) {
			return false;
		}
		closeSegment(nanoClock.getAsLong());
		current.setLastDayTime(dayTime);
		current.end(epochClock.getAsLong(), RunFormat.day(current.startDayTime(), dayTime), kind, death);
		saveNow("run ended");
		RunHistoryEntry entry = RunHistoryEntry.of(current);
		history.add(entry);
		highestRun = Math.max(highestRun, entry.run());
		saveHistory();
		stats.apply(entry);
		statsDirty = !stats.trySave(store.folder());
		MemorialEvents.publish(memorialSnapshot(), "run #" + entry.run() + " recorded");
		return true;
	}

	/**
	 * Recomputes the stats from the finished runs alone (/ht stats rebuild) and saves them. Returns true if the
	 * result differs from the stats it replaces.
	 */
	public boolean rebuildStats() {
		PlayerStats rebuilt = PlayerStats.rebuild(history);
		boolean changed = !rebuilt.all().equals(stats.all()) || rebuilt.lastRun() != stats.lastRun();
		stats = rebuilt;
		statsDirty = !stats.trySave(store.folder());
		MemorialEvents.publish(memorialSnapshot(), "stats rebuilt");
		return changed;
	}

	/**
	 * /ht runs delete: removes one finished run from the history, rebuilds the stats from what is left and fires
	 * the change hook. The ACTIVE run is refused. The number stays used (highest_run), so it is never reused.
	 */
	public DeleteResult deleteRun(int number) {
		if (current != null && current.isActive() && current.run() == number) {
			return DeleteResult.ACTIVE_RUN;
		}
		if (!history.removeIf(e -> e.run() == number)) {
			return DeleteResult.NOT_FOUND;
		}
		highestRun = Math.max(highestRun, number);
		saveHistory();
		stats = PlayerStats.rebuild(history);
		statsDirty = !stats.trySave(store.folder());
		MemorialEvents.publish(memorialSnapshot(), "run #" + number + " deleted");
		return DeleteResult.DELETED;
	}

	/**
	 * /ht fresh-start: deletes run-history.json, current-run.json and player-stats.json and forgets every run, so
	 * the next run is Run #1. The caller has already started the world reset; with no current run its COMMIT
	 * records nothing. Returns false (memory unchanged) if a file could not be deleted.
	 */
	public boolean freshStart() {
		closeSegment(nanoClock.getAsLong());
		try {
			store.deleteRunFiles();
		} catch (IOException e) {
			LOGGER.error("[HT timer] fresh start: could not delete the run files ({})", e.toString());
			return false;
		}
		current = null;
		history.clear();
		highestRun = 0;
		historyDirty = false;
		stats = PlayerStats.empty();
		statsDirty = false;
		MemorialEvents.publish(memorialSnapshot(), "fresh start");
		return true;
	}

	private MemorialData memorialSnapshot() {
		return MemorialData.build(history, stats, current == null ? 0 : current.run());
	}

	private void saveHistory() {
		try {
			store.saveHistory(history, highestRun);
			historyDirty = false;
		} catch (IOException e) {
			historyDirty = true;
			LOGGER.warn("[HT timer] could not write {} ({}); kept in memory, retried at the next run start",
				store.historyFile().getFileName(), e.toString());
		}
	}

	/**
	 * Called once per server tick. shouldCount: ACTIVE run, a player online, no reset, not paused (the caller
	 * checks everything but ACTIVE). inRunWorlds: players currently in the overworld, nether or end, with their
	 * names (recorded as the participant's name at the time).
	 */
	public void tick(boolean shouldCount, Map<UUID, String> inRunWorlds) {
		long now = nanoClock.getAsLong();
		if (segmentStart >= 0 && lastTickNanos >= 0 && now - lastTickNanos > MAX_TICK_GAP_NANOS) {
			closeSegment(lastTickNanos);
		}
		lastTickNanos = now;

		boolean count = shouldCount && current != null && current.isActive();
		if (count && segmentStart < 0) {
			segmentStart = now;
		} else if (!count && segmentStart >= 0) {
			closeSegment(now);
		}
		if (segmentStart < 0) {
			return;
		}

		long from = Math.max(lastCreditNanos, segmentStart);
		if (now > from) {
			long credit = now - from;
			inRunWorlds.forEach((uuid, name) -> current.addParticipant(uuid, name, credit));
		}
		lastCreditNanos = now;

		if (now - lastSaveAttemptNanos >= saveIntervalNanos) {
			save(null);
		}
	}

	public void observeDayTime(long dayTime) {
		if (current != null && current.isActive()) {
			current.setLastDayTime(dayTime);
		}
	}

	/** Closes the segment and saves; always attempted. */
	public void shutdown() {
		closeSegment(nanoClock.getAsLong());
		saveNow("on shutdown");
	}

	public boolean setActiveMillis(long millis) {
		if (current == null) {
			return false;
		}
		foldSegment(nanoClock.getAsLong());
		current.setActiveNanos(Math.multiplyExact(millis, 1_000_000L));
		saveNow("set-time");
		return true;
	}

	/** Always attempted; logs the reason on success. */
	public boolean saveNow(String reason) {
		return save(reason);
	}

	private boolean save(@Nullable String reason) {
		if (current == null) {
			return false;
		}
		long now = nanoClock.getAsLong();
		foldSegment(now);
		lastSaveAttemptNanos = now;
		saveAttempts++;
		try {
			store.saveCurrent(current);
		} catch (IOException e) {
			failedSaves++;
			if (!saveFailing || reason != null) {
				LOGGER.warn("[HT timer] could not save {} ({}{}); keeping run {} at {} in memory and retrying in {} s",
					store.currentFile().getFileName(), e.toString(), reason == null ? "" : ", " + reason, current.run(),
					RunFormat.elapsed(current.activeMillis()), saveIntervalNanos / 1_000_000_000L);
			}
			saveFailing = true;
			return false;
		}
		lastSaveEpoch = epochClock.getAsLong();
		if (saveFailing) {
			LOGGER.info("[HT timer] saving works again after {} failed attempt(s)", failedSaves);
			saveFailing = false;
			failedSaves = 0;
		}
		if (reason != null) {
			LOGGER.info("[HT timer] saved run #{} at {} ({})", current.run(), RunFormat.elapsed(current.activeMillis()), reason);
		}
		return true;
	}

	/** Moves the open part of the segment into the run's total and restarts the segment at now. */
	private void foldSegment(long now) {
		if (segmentStart >= 0 && current != null) {
			current.addActiveNanos(Math.max(0, now - segmentStart));
			segmentStart = now;
		}
	}

	private void closeSegment(long at) {
		foldSegment(at);
		segmentStart = -1;
	}

	// ---- Queries ----

	public @Nullable CurrentRun current() {
		return current;
	}

	public PlayerStats stats() {
		return stats;
	}

	public List<RunHistoryEntry> history() {
		return List.copyOf(history);
	}

	public boolean segmentOpen() {
		return segmentStart >= 0;
	}

	/** Active time including the open segment. */
	public long activeMillis() {
		if (current == null) {
			return 0;
		}
		long open = segmentStart >= 0 ? Math.max(0, nanoClock.getAsLong() - segmentStart) : 0;
		return (current.activeNanos() + open) / 1_000_000L;
	}

	/** Live day while ACTIVE (from the last observed day-time), the frozen final day once ENDED. */
	public long day() {
		if (current == null) {
			return 1;
		}
		return current.isActive() ? RunFormat.day(current.startDayTime(), current.lastDayTime()) : current.finalDay();
	}

	public long lastSaveEpoch() {
		return lastSaveEpoch;
	}

	public boolean saveFailing() {
		return saveFailing;
	}

	/** Save attempts since the server started, failed ones included (shown in /ht status). */
	public int saveAttempts() {
		return saveAttempts;
	}
}
