package net.hardcoretogether.run;

import net.hardcoretogether.data.CurrentRun;
import net.hardcoretogether.data.PlayerStats;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunHistoryEntry;
import net.hardcoretogether.data.RunStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStatsTest {
	private static final long SEC = 1_000_000_000L;
	private static final long TICK = SEC / 20;
	private static final long DAY = 24_000;
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID NOBODY = UUID.fromString("00000000-0000-0000-0000-00000000000f");

	@TempDir
	Path dir;

	private long nanos = 1_000 * SEC;
	private long epoch = 1_700_000_000_000L;

	private RunTracker tracker() {
		return new RunTracker(new RunStore(dir), () -> nanos, () -> epoch, 60 * SEC);
	}

	private void play(RunTracker t, long duration, Map<UUID, String> online) {
		for (long done = 0; done < duration; done += TICK) {
			nanos += TICK;
			epoch += TICK / 1_000_000;
			t.tick(true, online);
		}
	}

	private static RunDeath death(UUID uuid, String name) {
		return new RunDeath(uuid, name, name + " fell from a high place", "minecraft:overworld", 0, 64, 0);
	}

	private static RunHistoryEntry entry(int run, long millis, long day, String kind, RunDeath death, Map<UUID, Long> participants) {
		Map<UUID, String> names = new LinkedHashMap<>();
		participants.keySet().forEach(u -> names.put(u, u.equals(ALEX) ? "Alex" : "Bob"));
		return new RunHistoryEntry(run, 1L, 0, 0, millis, day, kind, death, participants, names);
	}

	@Test
	void aggregationSumsTimeKeepsRecordsAndCountsOnlyRunEndingDeaths() {
		PlayerStats s = PlayerStats.rebuild(List.of(
			entry(1, 600_000, 2, "death", death(ALEX, "Alex"), Map.of(ALEX, 600_000L)),
			entry(2, 900_000, 1, "command", null, Map.of(ALEX, 500_000L, BOB, 900_000L)),
			entry(3, 900_000, 4, "death", death(BOB, "Bob"), Map.of(ALEX, 100_000L, BOB, 900_000L))));

		PlayerStats.Stats alex = s.get(ALEX);
		assertEquals(3, alex.runsPlayed());
		assertEquals(1, alex.deaths());
		assertEquals(1_200_000, alex.totalPlaytimeMillis());
		assertEquals(900_000, alex.longestRunMillis());
		assertEquals(2, alex.longestRun()); // tie with run 3: the earlier run keeps the record
		assertEquals(4, alex.furthestDay());
		assertEquals(3, alex.furthestDayRun());

		PlayerStats.Stats bob = s.get(BOB);
		assertEquals(2, bob.runsPlayed());
		assertEquals(1, bob.deaths()); // the command reset counts against nobody
		assertEquals(1_800_000, bob.totalPlaytimeMillis());
		assertEquals(2, bob.longestRun());
		assertEquals(3, bob.furthestDayRun());
		assertEquals("Bob", bob.name());
		assertEquals(3, s.lastRun());
	}

	/** Several runs through the real tracker: a solo death, a two-player command reset, a two-player death. */
	private RunTracker playThreeRuns() {
		RunTracker t = tracker();
		t.startRun(1, 1L, 0);
		play(t, 30 * SEC, Map.of(ALEX, "Alex"));
		t.endRun("death", death(ALEX, "Alex"), DAY + 100);

		t.startRun(2, 2L, 0);
		play(t, 20 * SEC, Map.of(ALEX, "Alex", BOB, "Bob"));
		play(t, 40 * SEC, Map.of(BOB, "Bob"));
		assertTrue(t.resetCommitted(false));

		t.startRun(3, 3L, 0);
		play(t, 10 * SEC, Map.of(ALEX, "Alex2", BOB, "Bob"));
		t.endRun("death", death(BOB, "Bob"), 3 * DAY);
		return t;
	}

	@Test
	void rebuildEqualsIncrementalAcrossRuns() {
		RunTracker t = playThreeRuns();
		Map<UUID, PlayerStats.Stats> incremental = t.stats().all();
		assertEquals(PlayerStats.rebuild(t.history()).all(), incremental);
		assertEquals(PlayerStats.rebuild(new RunStore(dir).loadHistory()).all(), incremental); // from run-history.json alone
		assertFalse(t.rebuildStats());
		assertEquals(incremental, t.stats().all());

		PlayerStats.Stats alex = incremental.get(ALEX);
		assertEquals(3, alex.runsPlayed());
		assertEquals(1, alex.deaths());
		assertEquals(60_000, alex.totalPlaytimeMillis(), 200);
		assertEquals(2, alex.longestRun());
		assertEquals(4, alex.furthestDay());
		assertEquals(3, alex.furthestDayRun());
		assertEquals("Alex2", alex.name());
		assertEquals(1, incremental.get(BOB).deaths());
		assertEquals(2, incremental.get(BOB).runsPlayed());

		// The saved file loads to the same values after a restart.
		assertEquals(incremental, tracker().stats().all());
	}

	@Test
	void historyEntriesKeepParticipantNamesAndMillis() {
		RunTracker t = playThreeRuns();
		RunHistoryEntry run2 = new RunStore(dir).loadHistory().get(1);
		assertEquals(Map.of(ALEX, "Alex", BOB, "Bob"), run2.participantNames());
		assertEquals(60_000, run2.participantMillis().get(BOB), 200);
		assertEquals(t.history().size(), 3);
	}

	@Test
	void currentRunAddsLivePlaytimeButNotARun() {
		RunTracker t = playThreeRuns();
		PlayerStats.Stats before = t.stats().get(ALEX);
		t.startRun(4, 4L, 0);
		play(t, 15 * SEC, Map.of(ALEX, "Alex2"));

		PlayerStats.View v = t.stats().view(ALEX, t.current(), t.activeMillis(), t.day());
		assertEquals(before.totalPlaytimeMillis() + 15_000, v.totalPlaytimeMillis(), 200);
		assertEquals(before.runsPlayed(), v.runsPlayed());
		assertEquals(before.longestRun(), v.longestRun());
		assertFalse(v.longestInProgress());

		// Bob hasn't been in run 4: no live time for him.
		assertEquals(t.stats().get(BOB).totalPlaytimeMillis(), t.stats().view(BOB, t.current(), t.activeMillis(), t.day()).totalPlaytimeMillis());
	}

	@Test
	void inProgressRunCanHoldTheRecords() {
		RunTracker t = playThreeRuns();
		t.startRun(4, 4L, 0);
		play(t, 70 * SEC, Map.of(ALEX, "Alex2"));
		t.observeDayTime(9 * DAY);
		CurrentRun run = t.current();

		PlayerStats.View v = t.stats().view(ALEX, run, t.activeMillis(), t.day());
		assertTrue(v.longestInProgress());
		assertEquals(4, v.longestRun());
		assertEquals(t.activeMillis(), v.longestRunMillis());
		assertTrue(v.furthestInProgress());
		assertEquals(10, v.furthestDay());
		assertEquals(4, v.furthestDayRun());

		// A player with no finished runs who is in the current run gets it as an in-progress record.
		UUID carl = UUID.fromString("00000000-0000-0000-0000-000000000003");
		play(t, SEC, Map.of(carl, "Carl"));
		PlayerStats.View c = t.stats().view(carl, run, t.activeMillis(), t.day());
		assertEquals(0, c.runsPlayed());
		assertEquals(1_000, c.totalPlaytimeMillis(), 100);
		assertTrue(c.longestInProgress());
		assertTrue(c.furthestInProgress());

		// Once the run is ENDED (a death, before the next run starts) the live part is gone until the stats apply it.
		t.endRun("death", death(ALEX, "Alex2"), 9 * DAY);
		PlayerStats.View after = t.stats().view(ALEX, t.current(), t.activeMillis(), t.day());
		assertFalse(after.longestInProgress());
		assertEquals(4, after.longestRun());
		assertEquals(4, after.runsPlayed());
		assertEquals(2, after.deaths());
	}

	@Test
	void unknownPlayerHasNoStats() {
		RunTracker t = playThreeRuns();
		assertFalse(t.stats().has(NOBODY));
		assertTrue(t.stats().findByName("NotARealPlayer").isEmpty());
		assertEquals(ALEX, t.stats().findByName("alex2").orElseThrow());
		PlayerStats.View v = t.stats().view(NOBODY, t.current(), t.activeMillis(), t.day());
		assertEquals(new PlayerStats.View(0, 0, 0, 0, 0, false, 0, 0, false), v);
	}

	@Test
	void corruptStatsFileIsKeptAsideAndRebuilt() throws IOException {
		Map<UUID, PlayerStats.Stats> expected = playThreeRuns().stats().all();
		Path file = dir.resolve(PlayerStats.FILE_NAME);
		Files.writeString(file, "{\"format\": 1, \"players\": {not json");

		RunTracker reloaded = tracker();
		assertEquals(expected, reloaded.stats().all());
		try (Stream<Path> files = Files.list(dir)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith(PlayerStats.FILE_NAME + ".corrupt-")));
		}
		assertEquals(expected, tracker().stats().all()); // the rebuilt file was saved and loads cleanly
	}

	@Test
	void missingStatsFileIsRebuiltOnFirstLoad() throws IOException {
		Map<UUID, PlayerStats.Stats> expected = playThreeRuns().stats().all();
		Files.delete(dir.resolve(PlayerStats.FILE_NAME));
		assertEquals(expected, tracker().stats().all());
		assertTrue(Files.exists(dir.resolve(PlayerStats.FILE_NAME)));
	}

	@Test
	void runsMissingFromTheStatsFileAreAppliedOnLoad() throws IOException {
		RunTracker t = playThreeRuns();
		Path file = dir.resolve(PlayerStats.FILE_NAME);
		String afterThree = Files.readString(file);
		t.startRun(4, 4L, 0);
		play(t, 5 * SEC, Map.of(ALEX, "Alex2"));
		t.endRun("command", null, 0);
		Map<UUID, PlayerStats.Stats> expected = t.stats().all();
		Files.writeString(file, afterThree); // as if the stats write for run 4 was lost

		assertEquals(expected, tracker().stats().all());
	}
}
