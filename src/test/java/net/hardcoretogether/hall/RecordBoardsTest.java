package net.hardcoretogether.hall;

import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunHistoryEntry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordBoardsTest {
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID ZOE = UUID.fromString("00000000-0000-0000-0000-000000000003");
	private static final List<RecordBoards.PlayerRow> PLAYERS = List.of(
		new RecordBoards.PlayerRow(ZOE, "Zoe", "2026-10-04T10:00:00Z"),
		new RecordBoards.PlayerRow(ALEX, "Alex", "2026-10-03T10:00:00Z"),
		new RecordBoards.PlayerRow(SAM, "Sam", "2026-10-03T12:00:00Z"));

	private static RunHistoryEntry died(int run, UUID who, String name, String damage, String killer, long millis) {
		RunDeath d = new RunDeath(who, name, name + " died", "minecraft:overworld", 0, 64, 0, damage, killer);
		return new RunHistoryEntry(run, run, 0, 1000L * run, millis, 1, "death", d, Map.of(who, millis), Map.of(who, name));
	}

	private static RunHistoryEntry command(int run, long millis) {
		return new RunHistoryEntry(run, run, 0, 1000L * run, millis, 1, "command", null, Map.of(), Map.of());
	}

	@Test
	void deathsBoardSortsByDeathsThenJoinOrderAndKeepsZero() {
		assertEquals(List.of("Alex: 0", "Sam: 0", "Zoe: 0"), RecordBoards.deaths(PLAYERS, List.of(), 10));
		List<RunHistoryEntry> h = new ArrayList<>(List.of(died(1, ZOE, "Zoe", "minecraft:drown", null, 1000)));
		assertEquals(List.of("Zoe: 1", "Alex: 0", "Sam: 0"), RecordBoards.deaths(PLAYERS, h, 10));
		h.add(died(2, SAM, "Sam", "minecraft:fall", null, 1000));
		// Zoe and Sam tie on 1: Sam joined first.
		assertEquals(List.of("Sam: 1", "Zoe: 1", "Alex: 0"), RecordBoards.deaths(PLAYERS, h, 10));
		assertEquals(List.of("Sam: 1", "Zoe: 1"), RecordBoards.deaths(PLAYERS, h, 2));
		assertEquals(List.of("No players yet"), RecordBoards.deaths(List.of(), h, 10));
	}

	@Test
	void runsBoardLatestAndLongest() {
		assertEquals(List.of("No runs yet"), RecordBoards.runs(List.of()));
		List<RunHistoryEntry> h = List.of(command(1, 3_600_000 + 3 * 60_000 + 20_000), died(2, ALEX, "Alex", "minecraft:drown", null, 38_000),
			died(3, SAM, "Sam", "minecraft:fall", null, 12 * 60_000 + 5_000));
		assertEquals(List.of("Latest: Run #3 · 12m 5s", "Longest: Run #1 · 1h 3m 20s"), RecordBoards.runs(h));
	}

	@Test
	void causesBoardKeepsFirstSeenOrderAndCountsInPlace() {
		assertEquals(List.of("No deaths yet"), RecordBoards.causes(List.of(command(1, 5))));
		List<RunHistoryEntry> h = new ArrayList<>(List.of(died(1, ALEX, "Alex", "minecraft:drown", null, 1),
			died(2, SAM, "Sam", "minecraft:explosion", "minecraft:creeper", 1)));
		assertEquals(List.of("Drowning: 1", "Creeper: 1"), RecordBoards.causes(h));
		h.add(died(3, ZOE, "Zoe", "minecraft:explosion", "minecraft:creeper", 1));
		h.add(died(4, ZOE, "Zoe", "minecraft:fall", null, 1));
		h.add(died(5, ALEX, "Alex", "minecraft:drown", null, 1));
		// Creeper overtakes Drowning in count but keeps its place; Falling is added at the bottom.
		assertEquals(List.of("Drowning: 2", "Creeper: 2", "Falling: 1"), RecordBoards.causes(h));
	}

	// ---- Causes fit (a wall face 8 wide, top edge 4.82 above its bottom, max scale 4.69, min text scale 1.0) ----

	private static final double MAX = 4.69, MIN = 1.0, ROOM_H = 4.82, ROOM_W = 8;

	private static final String[] DAMAGE = {"minecraft:drown", "minecraft:fall", "minecraft:lava", "minecraft:starve", "minecraft:in_wall",
		"minecraft:explosion", "minecraft:in_fire", "minecraft:cactus", "minecraft:freeze", "minecraft:lightning_bolt", "minecraft:magic",
		"minecraft:wither", "minecraft:out_of_world", "minecraft:hot_floor", "minecraft:sweet_berry_bush", "minecraft:dragon_breath"};

	private static List<RunHistoryEntry> distinctCauses(int n) {
		List<RunHistoryEntry> h = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			h.add(died(i + 1, ALEX, "Alex", DAMAGE[i], null, 1000));
		}
		return h;
	}

	/** n cause lines as the board makes them, including the longest real labels and 16-letter player names. */
	private static List<String> lines(int n) {
		String[] labels = {"Wither Skeleton", "Dragon's breath", "PlayerOne_Long", "PlayerTwo1", "Drowning", "Falling", "Creeper", "Zombie"};
		List<String> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(labels[i % labels.length] + (i < labels.length ? "" : " " + i) + ": " + (i % 3 + 1));
		}
		return out;
	}

	private static RecordBoards.Fitted fit(int n) {
		return RecordBoards.fitCauseLines(lines(n), MAX, MIN, ROOM_H, ROOM_W);
	}

	private static void assertInsideWall(RecordBoards.Fitted f) {
		double[] size = f.twoColumns() ? RecordBoards.columnsSize(f) : RecordBoards.size(RecordBoards.Board.CAUSES, f.body());
		org.junit.jupiter.api.Assertions.assertTrue(f.scale() * size[1] <= ROOM_H + 1.0E-9, "too tall: " + f);
		org.junit.jupiter.api.Assertions.assertTrue(f.scale() * size[0] <= ROOM_W + 1.0E-9, "too wide: " + f);
	}

	@Test
	void causesKeepMaxScaleWhileTheyFit() {
		RecordBoards.Fitted f = RecordBoards.fitCauses(distinctCauses(2), MAX, MIN, ROOM_H, ROOM_W);
		assertEquals(4.69, f.scale());
		assertEquals(List.of("Drowning: 1", "Falling: 1"), f.body());
		assertInsideWall(f);
	}

	@Test
	void causesShrinkJustEnoughAndGrowBackWhenLinesGo() {
		RecordBoards.Fitted three = RecordBoards.fitCauses(distinctCauses(3), MAX, MIN, ROOM_H, ROOM_W);
		RecordBoards.Fitted four = RecordBoards.fitCauses(distinctCauses(4), MAX, MIN, ROOM_H, ROOM_W);
		org.junit.jupiter.api.Assertions.assertTrue(four.scale() < 4.69, "4 causes should shrink: " + four);
		org.junit.jupiter.api.Assertions.assertTrue(four.scale() >= MIN);
		assertEquals(4, four.body().size());
		assertInsideWall(three);
		assertInsideWall(four);
		// Just enough: 0.01 more would not fit.
		double[] size = RecordBoards.size(RecordBoards.Board.CAUSES, four.body());
		org.junit.jupiter.api.Assertions.assertTrue((four.scale() + 0.01) * size[1] > ROOM_H);
		// A run deleted: back up, never above the maximum.
		assertEquals(RecordBoards.fitCauses(distinctCauses(2), MAX, MIN, ROOM_H, ROOM_W).scale(), 4.69);
	}

	@Test
	void causesShrinkInOneColumnDownToTheMinimum() {
		// At 1.0 the wall holds the title and 18 lines: 18 causes is the most one column shows.
		for (int n = 1; n <= 18; n++) {
			RecordBoards.Fitted f = fit(n);
			org.junit.jupiter.api.Assertions.assertFalse(f.twoColumns(), n + ": " + f);
			assertEquals(lines(n), f.body());
			org.junit.jupiter.api.Assertions.assertTrue(f.scale() >= MIN, n + ": " + f);
			assertInsideWall(f);
		}
		assertEquals(MIN, fit(18).scale());
	}

	@Test
	void causesSplitIntoTwoColumnsAtTheMinimum() {
		for (int n = 19; n <= 36; n++) {
			RecordBoards.Fitted f = fit(n);
			org.junit.jupiter.api.Assertions.assertTrue(f.twoColumns(), n + ": " + f);
			assertEquals(MIN, f.scale());
			// Left column filled top to bottom first, then the right one, in the original order; nothing hidden.
			assertEquals(18, f.body().size());
			List<String> both = new ArrayList<>(f.body());
			both.addAll(f.right());
			assertEquals(lines(n), both);
			assertInsideWall(f);
		}
	}

	@Test
	void causesShowMoreOnlyWhenTwoFullColumnsOverflow() {
		RecordBoards.Fitted f = fit(50);
		assertInsideWall(f);
		assertEquals(MIN, f.scale());
		assertEquals(lines(50).subList(0, 18), f.body());
		assertEquals(lines(50).subList(18, 35), f.right().subList(0, 17));
		assertEquals("+15 more", f.right().getLast());
		assertEquals(18, f.right().size());
	}

	@Test
	void columnsBackingCoversBothColumns() {
		RecordBoards.Fitted f = fit(30);
		List<String> backing = RecordBoards.columnsBacking(f);
		assertEquals(f.body().size(), backing.size());
		double width = RecordBoards.size(RecordBoards.Board.CAUSES, backing)[0];
		org.junit.jupiter.api.Assertions.assertTrue(width >= RecordBoards.columnsSize(f)[0] - 1.0E-9, "backing too narrow");
		org.junit.jupiter.api.Assertions.assertTrue(width <= RecordBoards.columnsSize(f)[0] + 4 * RecordBoards.PIXEL, "backing too wide");
	}
}
