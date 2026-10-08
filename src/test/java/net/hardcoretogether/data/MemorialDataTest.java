package net.hardcoretogether.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorialDataTest {
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID ZOE = UUID.fromString("00000000-0000-0000-0000-000000000003");

	private static RunDeath death(UUID uuid, String name, String damage, String killer) {
		return new RunDeath(uuid, name, name + " died", "minecraft:overworld", 0, 64, 0, damage, killer);
	}

	private static RunHistoryEntry run(int n, long millis, long day, RunDeath death, Map<UUID, Long> participants) {
		Map<UUID, String> names = new java.util.HashMap<>();
		participants.keySet().forEach(u -> names.put(u, u.equals(ALEX) ? "Alex" : u.equals(STEVE) ? "Steve" : "Zoe"));
		return new RunHistoryEntry(n, n, 0, 0, millis, day, death == null ? "command" : "death", death, participants, names);
	}

	private static List<Integer> numbers(List<RunHistoryEntry> runs) {
		return runs.stream().map(RunHistoryEntry::run).toList();
	}

	private static MemorialData data(List<RunHistoryEntry> history, int currentRun) {
		return MemorialData.build(history, PlayerStats.rebuild(history), currentRun);
	}

	private static final List<RunHistoryEntry> HISTORY = List.of(
		run(1, 5_000, 1, death(ALEX, "Alex", "minecraft:mob_attack", "minecraft:zombie"), Map.of(ALEX, 5_000L)),
		run(2, 9_000, 3, death(STEVE, "Steve", "minecraft:fall", null), Map.of(ALEX, 9_000L, STEVE, 9_000L)),
		run(3, 9_000, 2, null, Map.of(STEVE, 4_000L)),
		run(4, 2_000, 3, death(ALEX, "Alex", "minecraft:arrow", "minecraft:skeleton"), Map.of(ALEX, 2_000L)),
		run(5, 1_000, 1, death(STEVE, "Steve", "minecraft:mob_attack", "minecraft:zombie"), Map.of(STEVE, 1_000L)),
		run(6, 3_000, 1, death(ALEX, "Alex", null, null), Map.of(ALEX, 3_000L)), // older record: no cause
		run(8, 4_000, 1, death(STEVE, "Steve", "minecraft:fall", null), Map.of(STEVE, 4_000L))); // #7 deleted

	@Test
	void emptyDataAnswersEveryQuery() {
		MemorialData d = data(List.of(), 0);
		assertTrue(d.recentRuns(10).isEmpty());
		assertTrue(d.topRunsByDuration(10).isEmpty());
		assertTrue(d.topRunsByDay(10).isEmpty());
		assertTrue(d.run(1).isEmpty());
		for (MemorialData.Metric m : MemorialData.Metric.values()) {
			assertTrue(d.playerLeaderboard(m, 10).isEmpty());
		}
		assertEquals(new MemorialData.Totals(0, 0, 0, 0), d.totals());
		assertTrue(d.deadliestCauses(10).isEmpty());
		assertEquals(new MemorialData.Totals(0, 0, 0, 0), MemorialData.EMPTY.totals());
	}

	@Test
	void recentRunsNewestFirstWithLimit() {
		MemorialData d = data(HISTORY, 9);
		assertEquals(List.of(8, 6, 5, 4, 3, 2, 1), numbers(d.recentRuns(100)));
		assertEquals(List.of(8, 6), numbers(d.recentRuns(2)));
		assertTrue(d.recentRuns(0).isEmpty());
	}

	@Test
	void topRunsByDurationAndDayKeepEarlierRunOnTies() {
		MemorialData d = data(HISTORY, 9);
		assertEquals(List.of(2, 3, 1, 8, 6), numbers(d.topRunsByDuration(5))); // 2 and 3 tie at 9 s
		assertEquals(List.of(2, 4, 3, 1, 5), numbers(d.topRunsByDay(5))); // 2 and 4 tie at Day 3
	}

	@Test
	void runByNumber() {
		MemorialData d = data(HISTORY, 9);
		assertEquals(HISTORY.get(3), d.run(4).orElseThrow());
		assertTrue(d.run(7).isEmpty());
		assertTrue(d.run(9).isEmpty()); // the current run is not finished
	}

	@Test
	void playerLeaderboards() {
		MemorialData d = data(HISTORY, 9);
		// Alex: runs 1,2,4,6 = 19 s, 3 deaths, longest 9 s (Run #2), Day 3 (Run #2).
		// Steve: runs 2,3,5,8 = 18 s, 3 deaths, longest 9 s (Run #2), Day 3 (Run #2).
		assertEquals(List.of(new MemorialData.PlayerEntry(ALEX, "Alex", 19_000, 0), new MemorialData.PlayerEntry(STEVE, "Steve", 18_000, 0)),
			d.playerLeaderboard(MemorialData.Metric.PLAYTIME, 10));
		// Ties: same value and record run, so by name.
		assertEquals(List.of(ALEX, STEVE), d.playerLeaderboard(MemorialData.Metric.RUNS_PLAYED, 10).stream().map(MemorialData.PlayerEntry::uuid).toList());
		assertEquals(3, d.playerLeaderboard(MemorialData.Metric.DEATHS, 10).get(1).value());
		assertEquals(new MemorialData.PlayerEntry(ALEX, "Alex", 9_000, 2), d.playerLeaderboard(MemorialData.Metric.LONGEST_RUN, 1).get(0));
		assertEquals(new MemorialData.PlayerEntry(ALEX, "Alex", 3, 2), d.playerLeaderboard(MemorialData.Metric.FURTHEST_DAY, 1).get(0));
		assertEquals(1, d.playerLeaderboard(MemorialData.Metric.DEATHS, 1).size());
	}

	@Test
	void leaderboardTiesPreferEarlierRecordAndSkipZeros() {
		List<RunHistoryEntry> h = List.of(
			run(1, 7_000, 2, null, Map.of(ZOE, 7_000L)),
			run(2, 7_000, 2, death(ALEX, "Alex", "minecraft:fall", null), Map.of(ALEX, 7_000L)));
		MemorialData d = data(h, 0);
		// Same 7 s, Zoe's record is from the earlier run, so she ranks first even though "Alex" sorts first.
		assertEquals(List.of(ZOE, ALEX), d.playerLeaderboard(MemorialData.Metric.LONGEST_RUN, 10).stream().map(MemorialData.PlayerEntry::uuid).toList());
		// Zoe has no deaths: left out.
		assertEquals(List.of(ALEX), d.playerLeaderboard(MemorialData.Metric.DEATHS, 10).stream().map(MemorialData.PlayerEntry::uuid).toList());
	}

	@Test
	void totals() {
		MemorialData d = data(HISTORY, 9);
		assertEquals(new MemorialData.Totals(7, 6, 37_000, 9), d.totals());
	}

	@Test
	void deadliestCausesGroupByKillerThenDamageAndSkipMissing() {
		MemorialData d = data(HISTORY, 9);
		assertEquals(List.of(
			new MemorialData.Cause("minecraft:fall", false, 2),
			new MemorialData.Cause("minecraft:zombie", true, 2),
			new MemorialData.Cause("minecraft:skeleton", true, 1)), d.deadliestCauses(10));
		assertEquals(1, d.deadliestCauses(1).size());
	}

	@Test
	void killerWinsOverDamageType() {
		List<RunHistoryEntry> h = List.of(
			run(1, 1, 1, death(ALEX, "Alex", "minecraft:fall", "minecraft:phantom"), Map.of(ALEX, 1L)),
			run(2, 1, 1, death(ALEX, "Alex", "minecraft:mob_attack", "minecraft:phantom"), Map.of(ALEX, 1L)),
			run(3, 1, 1, death(ALEX, "Alex", "minecraft:fall", null), Map.of(ALEX, 1L)));
		assertEquals(List.of(new MemorialData.Cause("minecraft:phantom", true, 2), new MemorialData.Cause("minecraft:fall", false, 1)),
			data(h, 0).deadliestCauses(10));
	}
}
