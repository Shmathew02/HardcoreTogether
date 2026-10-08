package net.hardcoretogether.run;

import net.hardcoretogether.data.CurrentRun;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunHistoryEntry;
import net.hardcoretogether.data.RunStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTrackerTest {
	private static final long SEC = 1_000_000_000L;
	private static final long TICK = SEC / 20;
	private static final long SAVE_INTERVAL = 60 * SEC;
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final RunDeath ALEX_DEATH = new RunDeath(ALEX, "Alex", "Alex fell from a high place", "minecraft:overworld", 12, 64, -40);

	@TempDir
	Path dir;

	private long nanos = 1_000 * SEC;
	private long epoch = 1_700_000_000_000L;

	private RunTracker tracker(RunStore store) {
		return new RunTracker(store, () -> nanos, () -> epoch, SAVE_INTERVAL);
	}

	/** Ticks every 50 ms for the given time. */
	private void play(RunTracker t, long duration, boolean count) {
		for (long done = 0; done < duration; done += TICK) {
			nanos += TICK;
			epoch += TICK / 1_000_000;
			t.tick(count, Map.of(ALEX, "Alex"));
		}
	}

	@Test
	void runNumbersStartAtOneAndCountUp() {
		RunTracker t = tracker(new RunStore(dir));
		assertNull(t.current());
		assertEquals(1, t.nextRunNumber());
		assertEquals(1, t.startRun(103, 42L, 0).run());
		assertTrue(t.endRun("command", null, 0));
		assertEquals(2, t.startRun(104, 43L, 0).run());

		RunTracker reloaded = tracker(new RunStore(dir));
		assertEquals(2, reloaded.current().run());
		assertEquals(1, reloaded.history().size());
		assertEquals(3, reloaded.nextRunNumber());
	}

	@Test
	void segmentsCountOnlyWhileOpen() {
		RunTracker t = tracker(new RunStore(dir));
		t.startRun(1, 1L, 0);
		t.tick(true, Map.of());
		assertTrue(t.segmentOpen());
		play(t, 10 * SEC, true);
		assertEquals(10_000, t.activeMillis());

		t.tick(false, Map.of()); // last player leaves: closes now
		assertFalse(t.segmentOpen());
		play(t, 5 * SEC, false);
		assertEquals(10_000, t.activeMillis());

		play(t, 2 * SEC, true);
		assertEquals(12_000, t.activeMillis(), 50);
		// Participants accrue only during open segments.
		assertEquals(12_000, t.current().participantMillis().get(ALEX), 100);
	}

	@Test
	void longTickGapIsNotCounted() {
		RunTracker t = tracker(new RunStore(dir));
		t.startRun(1, 1L, 0);
		play(t, 3 * SEC, true);
		long before = t.activeMillis();
		nanos += 30 * SEC; // e.g. single-player pause: no ticks at all
		t.tick(true, Map.of());
		assertEquals(before, t.activeMillis());
		play(t, SEC, true);
		assertEquals(before + 1_000, t.activeMillis(), 1);
	}

	@Test
	void endFreezesTimeAndDayAndAppendsHistory() {
		RunTracker t = tracker(new RunStore(dir));
		t.startRun(1, 7L, 1_000);
		play(t, 5 * SEC, true);
		t.observeDayTime(1_000 + 3 * 24_000 + 5);
		assertEquals(4, t.day());
		assertTrue(t.endRun("death", ALEX_DEATH, 1_000 + 3 * 24_000 + 10));
		assertFalse(t.segmentOpen());
		long frozen = t.activeMillis();
		play(t, 5 * SEC, true);
		assertEquals(frozen, t.activeMillis());
		assertEquals(4, t.day());
		assertFalse(t.endRun("death", ALEX_DEATH, 0));

		RunHistoryEntry e = t.history().getFirst();
		assertEquals(1, e.run());
		assertEquals(frozen, e.finalActiveMillis());
		assertEquals(4, e.finalDay());
		assertEquals("death", e.endKind());
		assertEquals(ALEX_DEATH, e.death());
	}

	@Test
	void shutdownSavesAndNeverResumesAnOpenSegment() {
		RunTracker t = tracker(new RunStore(dir));
		t.startRun(1, 1L, 0);
		play(t, 3 * SEC, true);
		t.shutdown();
		assertFalse(t.segmentOpen());
		long saved = t.activeMillis();
		assertEquals(3_000, saved, 50);

		nanos += 600 * SEC; // downtime
		RunTracker next = tracker(new RunStore(dir));
		assertEquals(saved, next.activeMillis());
		assertFalse(next.segmentOpen());
		nanos += 10 * SEC;
		assertEquals(saved, next.activeMillis());
	}

	@Test
	void saveLoadRoundTrip() throws IOException {
		RunStore store = new RunStore(dir);
		RunTracker t = tracker(store);
		t.startRun(5, -123456789L, 48_000);
		play(t, 4 * SEC, true);
		t.observeDayTime(100_000);
		t.saveNow("test");

		CurrentRun a = t.current();
		CurrentRun b = new RunStore(dir).loadCurrent();
		assertNotNull(b);
		assertEquals(a.toJson(), b.toJson());
		assertEquals(CurrentRun.Status.ACTIVE, b.status());
		assertEquals(5, b.worldReset());
		assertEquals(48_000, b.startDayTime());
		assertEquals(100_000, b.lastDayTime());

		t.endRun("death", ALEX_DEATH, 100_000);
		CurrentRun ended = new RunStore(dir).loadCurrent();
		assertEquals(CurrentRun.Status.ENDED, ended.status());
		assertEquals(t.current().toJson(), ended.toJson());
		assertEquals(t.history(), new RunStore(dir).loadHistory());
	}

	@Test
	void missingFilesStartClean() {
		RunStore store = new RunStore(dir);
		assertNull(store.loadCurrent());
		assertTrue(store.loadHistory().isEmpty());
	}

	@Test
	void corruptFilesStartCleanAndAreKept() throws IOException {
		RunStore store = new RunStore(dir);
		RunTracker t = tracker(store);
		t.startRun(1, 1L, 0);
		t.endRun("command", null, 0);
		t.startRun(2, 1L, 0);

		Files.writeString(store.currentFile(), "{ not json");
		RunTracker reloaded = tracker(store);
		assertNull(reloaded.current());
		assertEquals(2, reloaded.nextRunNumber(), "numbering continues from the history");
		assertTrue(Files.notExists(store.currentFile()));
		try (var files = Files.list(dir)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("current-run.json.corrupt-")));
		}

		Files.writeString(store.historyFile(), "{\"format\": 1, \"runs\": [{\"run\": \"x\"}]}");
		assertTrue(store.loadHistory().isEmpty());
		assertTrue(Files.notExists(store.historyFile()));
	}

	@Test
	void oldFormatTestDataStartsAtRunOne() throws IOException {
		Files.writeString(dir.resolve(CurrentRun.FILE_NAME),
			"{\"format\": 1, \"run\": 103, \"seed\": 1, \"started_at\": \"x\", \"live_millis\": 5000, \"participants\": {}}");
		RunTracker t = tracker(new RunStore(dir));
		assertNull(t.current());
		assertEquals(1, t.startRun(103, 1L, 0).run());
	}

	@Test
	void unwritableFileIsRetriedOncePerIntervalNotPerTick() throws IOException {
		Path notAFolder = dir.resolve("blocked");
		Files.writeString(notAFolder, "a file where the data folder should be");
		CountingStore store = new CountingStore(notAFolder);
		RunTracker t = tracker(store);
		t.startRun(1, 1L, 0); // forced save: 1 attempt, fails
		assertEquals(1, store.attempts);
		assertTrue(t.saveFailing());

		play(t, 180 * SEC, true); // 3600 ticks
		assertEquals(1 + 3, store.attempts, "one retry per 60 s interval");
		assertEquals(180_000, t.activeMillis(), 50);
		assertTrue(t.saveFailing());

		t.shutdown(); // always attempted
		assertEquals(5, store.attempts);
	}

	@Test
	void saveRecoversAfterFailures() {
		CountingStore store = new CountingStore(dir);
		RunTracker t = tracker(store);
		t.startRun(1, 1L, 0);
		store.fail = true;
		play(t, 61 * SEC, true);
		assertTrue(t.saveFailing());
		store.fail = false;
		play(t, 60 * SEC, true);
		assertFalse(t.saveFailing());
		assertEquals(t.current().activeMillis(), new RunStore(dir).loadCurrent().activeMillis());
	}

	private static final class CountingStore extends RunStore {
		int attempts;
		boolean fail;

		CountingStore(Path folder) {
			super(folder);
		}

		@Override
		public void saveCurrent(CurrentRun run) throws IOException {
			attempts++;
			if (fail) {
				throw new IOException("simulated write failure");
			}
			super.saveCurrent(run);
		}
	}
}
