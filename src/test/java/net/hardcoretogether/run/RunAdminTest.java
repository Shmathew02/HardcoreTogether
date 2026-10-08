package net.hardcoretogether.run;

import net.hardcoretogether.data.MemorialData;
import net.hardcoretogether.data.MemorialEvents;
import net.hardcoretogether.data.PlayerStats;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deleting runs, fresh start, death cause fields and the memorial change hook, through the RunTracker. */
class RunAdminTest {
	private static final long SEC = 1_000_000_000L;
	private static final long TICK = SEC / 20;
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final RunDeath ALEX_SLAIN = new RunDeath(ALEX, "Alex", "Alex was slain by Zombie", "minecraft:overworld", 1, 64, 2,
		"minecraft:mob_attack", "minecraft:zombie");

	@TempDir
	Path dir;

	private long nanos = 1_000 * SEC;
	private final List<String> fired = new ArrayList<>();
	private final MemorialEvents.Listener listener = (data, reason) -> {
		// The snapshot is rebuilt before the hook fires.
		assertTrue(data == MemorialData.get());
		fired.add(reason);
	};

	@BeforeEach
	void listen() {
		MemorialEvents.onDataChanged(listener);
	}

	@AfterEach
	void stopListening() {
		MemorialEvents.unregister(listener);
	}

	private RunTracker tracker() {
		return new RunTracker(new RunStore(dir), () -> nanos, () -> 1_700_000_000_000L, 60 * SEC);
	}

	/** Plays one run of the given seconds and ends it (a death by Alex, or a command reset). */
	private void playRun(RunTracker t, int worldReset, int seconds, boolean death) {
		t.startRun(worldReset, 1L, 0);
		t.tick(true, Map.of(ALEX, "Alex")); // opens the segment
		for (int i = 0; i < seconds * 20; i++) {
			nanos += TICK;
			t.tick(true, Map.of(ALEX, "Alex"));
		}
		t.endRun(death ? "death" : "command", death ? ALEX_SLAIN : null, 0);
	}

	@Test
	void deathCauseIsSavedAndReloaded() {
		RunTracker t = tracker();
		playRun(t, 1, 1, true);
		RunDeath saved = new RunStore(dir).loadHistory().get(0).death();
		assertEquals("minecraft:mob_attack", saved.damageType());
		assertEquals("minecraft:zombie", saved.killerType());
		assertEquals(ALEX_SLAIN, saved);
	}

	@Test
	void oldEntriesWithoutCauseLoadAsNull() throws IOException {
		Files.writeString(dir.resolve(RunStore.HISTORY_FILE), """
			{"format": 1, "runs": [{"run": 1, "seed": 1, "started_at_epoch": 0, "ended_at_epoch": 0, "final_active_millis": 5,
			"final_day": 1, "end_kind": "death", "died_uuid": "00000000-0000-0000-0000-000000000001", "died_name": "Alex",
			"death_message": "Alex died", "death_dimension": "minecraft:overworld", "death_pos": [0, 64, 0],
			"participants": {"00000000-0000-0000-0000-000000000001": 5}}]}""");
		RunDeath d = new RunStore(dir).loadHistory().get(0).death();
		assertEquals(null, d.damageType());
		assertEquals(null, d.killerType());
		assertTrue(tracker().deleteRun(1) == RunTracker.DeleteResult.DELETED);
		assertTrue(MemorialData.get().deadliestCauses(10).isEmpty());
	}

	@Test
	void deleteRefusesTheActiveRun() {
		RunTracker t = tracker();
		playRun(t, 1, 1, true);
		t.startRun(2, 1L, 0);
		fired.clear();
		assertEquals(RunTracker.DeleteResult.ACTIVE_RUN, t.deleteRun(2));
		assertEquals(RunTracker.DeleteResult.NOT_FOUND, t.deleteRun(5));
		assertEquals(1, t.history().size());
		assertTrue(t.current().isActive());
		assertTrue(fired.isEmpty(), "a refused delete changes nothing");
	}

	@Test
	void deleteRemovesTheEntryAndRebuildsStats() {
		RunTracker t = tracker();
		playRun(t, 1, 10, true);
		playRun(t, 2, 20, false);
		playRun(t, 3, 5, true);
		assertEquals(new PlayerStats.Stats("Alex", 3, 2, 35_000, 20_000, 2, 1, 1), t.stats().get(ALEX));

		assertEquals(RunTracker.DeleteResult.DELETED, t.deleteRun(2));
		assertEquals(List.of(1, 3), t.history().stream().map(e -> e.run()).toList());
		PlayerStats.Stats expected = new PlayerStats.Stats("Alex", 2, 2, 15_000, 10_000, 1, 1, 1);
		assertEquals(expected, t.stats().get(ALEX));
		// Saved: the files on disk agree after a reload.
		RunTracker reloaded = tracker();
		assertEquals(List.of(1, 3), reloaded.history().stream().map(e -> e.run()).toList());
		assertEquals(expected, reloaded.stats().get(ALEX));
		assertEquals(PlayerStats.rebuild(new RunStore(dir).loadHistory()).all(), reloaded.stats().all());
		assertTrue(MemorialData.get().run(2).isEmpty());
	}

	@Test
	void runNumbersAreNeverReusedAfterADelete() {
		RunTracker t = tracker();
		playRun(t, 1, 1, true);
		playRun(t, 2, 1, true);
		// Delete the newest run (current-run.json still holds it as ENDED).
		assertEquals(RunTracker.DeleteResult.DELETED, t.deleteRun(2));
		assertEquals(3, t.nextRunNumber());

		// Even with current-run.json gone (it would otherwise remember Run #2), the history keeps highest_run.
		try {
			Files.delete(new RunStore(dir).currentFile());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		RunTracker reloaded = tracker();
		assertEquals(3, reloaded.nextRunNumber());
		assertEquals(3, reloaded.startRun(3, 1L, 0).run());

		// Deleting every run still never goes back.
		reloaded.endRun("command", null, 0);
		reloaded.deleteRun(1);
		reloaded.deleteRun(3);
		assertTrue(reloaded.history().isEmpty());
		assertEquals(4, tracker().nextRunNumber());
	}

	@Test
	void freshStartWipesRunFilesAndKeepsRegistryAndConfig() throws IOException {
		RunTracker t = tracker();
		playRun(t, 1, 1, true);
		playRun(t, 2, 1, true);
		t.deleteRun(1);
		t.startRun(3, 1L, 0);
		Path players = dir.resolve("players.json");
		Path state = dir.resolve("state.json");
		Path backup = dir.resolve("players.json.v2.bak");
		Path config = dir.resolve("config").resolve("hardcore_together.json");
		Files.createDirectories(config.getParent());
		Files.writeString(players, "{\"format\": 3, \"players\": {}}");
		Files.writeString(state, "{\"run_number\": 109}");
		Files.writeString(backup, "old");
		Files.writeString(config, "{\"countdown_seconds\": 10}");
		RunStore store = new RunStore(dir);
		assertTrue(Files.exists(store.historyFile()) && Files.exists(store.currentFile()) && Files.exists(dir.resolve(PlayerStats.FILE_NAME)));

		assertTrue(t.freshStart());
		assertTrue(Files.notExists(store.historyFile()));
		assertTrue(Files.notExists(store.currentFile()));
		assertTrue(Files.notExists(dir.resolve(PlayerStats.FILE_NAME)));
		assertEquals("{\"format\": 3, \"players\": {}}", Files.readString(players));
		assertEquals("{\"run_number\": 109}", Files.readString(state));
		assertEquals("old", Files.readString(backup));
		assertEquals("{\"countdown_seconds\": 10}", Files.readString(config));

		assertEquals(null, t.current());
		assertTrue(t.history().isEmpty());
		assertTrue(t.stats().all().isEmpty());
		assertEquals(new MemorialData.Totals(0, 0, 0, 0), MemorialData.get().totals());
		// The reset's COMMIT records nothing, and the next run is Run #1, also after a restart.
		assertTrue(!t.resetCommitted(false));
		assertEquals(1, tracker().nextRunNumber());
		assertEquals(1, t.startRun(4, 1L, 0).run());
		assertTrue(new RunStore(dir).loadHistory().isEmpty());
	}

	@Test
	void changeHookFiresOncePerChange() {
		RunTracker t = tracker();
		assertTrue(fired.isEmpty(), "loading fires nothing");
		t.startRun(1, 1L, 0);
		assertTrue(fired.isEmpty(), "a run start fires nothing");
		assertEquals(1, MemorialData.get().totals().currentRun(), "but the snapshot knows the current run");

		t.endRun("death", ALEX_SLAIN, 0);
		assertEquals(List.of("run #1 recorded"), fired);
		assertEquals(1, MemorialData.get().totals().totalRuns());
		t.endRun("death", ALEX_SLAIN, 0); // already ENDED: no second record
		assertEquals(1, fired.size());

		t.startRun(2, 1L, 0);
		t.resetCommitted(false); // ends Run #2 as a command reset
		assertEquals(List.of("run #1 recorded", "run #2 recorded"), fired);

		t.rebuildStats();
		assertEquals("stats rebuilt", fired.get(2));
		t.deleteRun(1);
		assertEquals("run #1 deleted", fired.get(3));
		assertEquals(1, MemorialData.get().totals().totalRuns());
		t.freshStart();
		assertEquals(List.of("run #1 recorded", "run #2 recorded", "stats rebuilt", "run #1 deleted", "fresh start"), fired);
		assertEquals(0, MemorialData.get().totals().totalRuns());
	}
}
