package net.hardcoretogether.reset;

import net.hardcoretogether.data.PlayerStats;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunStore;
import net.hardcoretogether.run.RunTracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A death reset cut short by a stop: the pending file, the restarted countdown and recording the death once. */
class PendingResetTest {
	private static final long SEC = 1_000_000_000L;
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final RunDeath ALEX_DROWNED = new RunDeath(ALEX, "Alex", "Alex drowned", "minecraft:overworld", 10, 40, -3,
		"minecraft:drown", null, null, new RunDeath.SkinTextures("dGV4dHVyZXM=", "c2ln"));

	@TempDir
	Path dir;

	private long nanos = 1_000 * SEC;

	private RunTracker tracker() {
		return new RunTracker(new RunStore(dir), () -> nanos, () -> 1_700_000_000_000L, 60 * SEC);
	}

	/** Run #1 in world 7, ended by Alex's death, as at the start of a death countdown. */
	private RunTracker deathInWorld7() {
		RunTracker t = tracker();
		t.startRun(7, 1L, 0);
		t.tick(true, Map.of(ALEX, "Alex"));
		nanos += 5 * SEC;
		t.tick(true, Map.of(ALEX, "Alex"));
		assertTrue(t.endRun("death", ALEX_DROWNED, 0));
		return t;
	}

	@Test
	void savedAndLoaded() throws IOException {
		assertEquals(Optional.empty(), PendingReset.read(dir));
		PendingReset p = new PendingReset(7, 1, ALEX_DROWNED, "2026-10-06T00:00:00Z");
		PendingReset.write(dir, p);
		assertEquals(Optional.of(p), PendingReset.read(dir));
		PendingReset.delete(dir);
		assertEquals(Optional.empty(), PendingReset.read(dir));
		PendingReset.delete(dir);
	}

	@Test
	void unreadableFileIsAnError() throws IOException {
		Files.writeString(PendingReset.path(dir), "{ not json");
		assertThrows(IOException.class, () -> PendingReset.read(dir));
	}

	@Test
	void resumesOnlyInTheSameWorldWithoutAJournal() {
		Optional<PendingReset> p = Optional.of(new PendingReset(7, 1, ALEX_DROWNED, "t"));
		assertEquals(PendingReset.OnLoad.NONE, PendingReset.onLoad(Optional.empty(), false, 7));
		assertEquals(PendingReset.OnLoad.RESUME, PendingReset.onLoad(p, false, 7));
		// Past COMMIT: the journal finishes the reset.
		assertEquals(PendingReset.OnLoad.STALE, PendingReset.onLoad(p, true, 8));
		// The reset already finished (counter moved on) but the file was left behind.
		assertEquals(PendingReset.OnLoad.STALE, PendingReset.onLoad(p, false, 8));
	}

	@Test
	void countdownRestartsOnceAPlayerIsOnline() {
		assertFalse(PendingReset.startNow(true, 0));
		assertTrue(PendingReset.startNow(true, 1));
		assertFalse(PendingReset.startNow(false, 2));
	}

	@Test
	void deathIsNotRecordedAgainAfterARestart() {
		deathInWorld7();
		PendingReset p = new PendingReset(7, 1, ALEX_DROWNED, "t");
		RunTracker restarted = tracker();
		PlayerStats.Stats before = restarted.stats().get(ALEX);

		assertFalse(PendingReset.recordIfMissing(restarted, p, 0));
		// Joining the old world starts no new run, and the restarted reset's COMMIT records nothing.
		assertNull(restarted.startRunIfNeeded(7, 1L, 0));
		assertFalse(restarted.resetCommitted(false));

		assertEquals(1, restarted.history().size());
		assertEquals(ALEX_DROWNED, restarted.history().get(0).death());
		assertEquals(before, restarted.stats().get(ALEX));
		assertEquals(1, before.deaths());
	}

	@Test
	void deathLostInTheStopIsRecordedOnce() {
		RunTracker t = tracker();
		t.startRun(7, 1L, 0);
		t.tick(true, Map.of(ALEX, "Alex"));
		t.saveNow("test");
		// The run is still ACTIVE on disk: the death's save never happened.
		RunTracker restarted = tracker();
		PendingReset p = new PendingReset(7, 1, ALEX_DROWNED, "t");

		assertTrue(PendingReset.recordIfMissing(restarted, p, 0));
		assertFalse(PendingReset.recordIfMissing(restarted, p, 0));
		assertEquals(1, restarted.history().size());
		assertEquals(1, restarted.stats().get(ALEX).deaths());
	}

	@Test
	void anotherRunIsNeverEnded() {
		RunTracker t = tracker();
		t.startRun(7, 1L, 0);
		PendingReset forRunTwo = new PendingReset(7, 2, ALEX_DROWNED, "t");
		assertFalse(PendingReset.recordIfMissing(t, forRunTwo, 0));
		assertTrue(t.current().isActive());
	}
}
