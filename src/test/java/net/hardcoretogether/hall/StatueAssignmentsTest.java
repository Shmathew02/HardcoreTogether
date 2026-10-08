package net.hardcoretogether.hall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatueAssignmentsTest {
	@TempDir
	Path folder;

	private static HallLayout withStatues(int... numbers) {
		HallLayout layout = new HallLayout();
		for (int n : numbers) {
			layout.addStatue(n, n, 64, 0, 0, 64, 0, 0);
		}
		return layout;
	}

	@Test
	void fillsOldestRunFirstInAscendingNumberSkippingGaps() {
		HallLayout layout = withStatues(5, 1, 3);
		StatueAssignments.Result r = StatueAssignments.sync(layout, List.of(9, 2, 4, 7));
		assertEquals(Map.of(2, 1, 4, 3, 7, 5), layout.statueAssignments());
		assertEquals(List.of(9), r.unplaced());
		assertEquals(3, r.placed());
	}

	@Test
	void assignmentsArePermanentWhenAnchorsAreAdded() {
		HallLayout layout = withStatues(2);
		StatueAssignments.sync(layout, List.of(1, 2));
		assertEquals(Map.of(1, 2), layout.statueAssignments());
		layout.addStatue(1, 0, 64, 0, 0, 64, 0, 0);
		StatueAssignments.Result r = StatueAssignments.sync(layout, List.of(1, 2));
		// Run 1 stays on anchor 2; the unplaced run 2 fills the new anchor 1.
		assertEquals(Map.of(1, 2, 2, 1), layout.statueAssignments());
		assertEquals(List.of(), r.unplaced());
	}

	@Test
	void removedAnchorMakesItsDeathUnplacedThenItTakesTheNextFree() {
		HallLayout layout = withStatues(1, 2);
		StatueAssignments.sync(layout, List.of(1, 2, 3));
		layout.removeStatue(1);
		StatueAssignments.Result r = StatueAssignments.sync(layout, List.of(1, 2, 3));
		assertEquals(Map.of(2, 2), layout.statueAssignments());
		assertEquals(List.of(1, 3), r.unplaced());
		layout.addStatue(7, 0, 64, 0, 0, 64, 0, 0);
		StatueAssignments.sync(layout, List.of(1, 2, 3));
		assertEquals(Map.of(1, 7, 2, 2), layout.statueAssignments());
	}

	@Test
	void deletedRunFreesItsAnchor() {
		HallLayout layout = withStatues(1);
		StatueAssignments.sync(layout, List.of(1, 2));
		StatueAssignments.Result r = StatueAssignments.sync(layout, List.of(2));
		assertEquals(Map.of(2, 1), layout.statueAssignments());
		assertEquals(1, r.released());
	}

	@Test
	void relayoutReassignsEveryDeathInRunOrder() {
		HallLayout layout = withStatues(2);
		StatueAssignments.sync(layout, List.of(5));
		layout.addStatue(1, 0, 64, 0, 0, 64, 0, 0);
		StatueAssignments.sync(layout, List.of(3, 5));
		assertEquals(Map.of(5, 2, 3, 1), layout.statueAssignments());
		StatueAssignments.relayout(layout, List.of(3, 5));
		assertEquals(Map.of(3, 1, 5, 2), layout.statueAssignments());
	}

	@Test
	void rejectsDuplicateNumbers() {
		HallLayout layout = withStatues(1);
		assertThrows(IllegalArgumentException.class, () -> layout.addStatue(1, 0, 64, 0, 0, 64, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> layout.addStatue(0, 0, 64, 0, 0, 64, 0, 0));
	}

	@Test
	void storesBlockCentreAndEightWayFacingAndSurvivesSaveAndParse() throws IOException {
		HallLayout layout = new HallLayout();
		HallLayout.Statue s = layout.addStatue(4, 10, 65, -3, 0, 64, 0, 130);
		assertEquals(10.5, s.dx());
		assertEquals(1.0, s.dy());
		assertEquals(-2.5, s.dz());
		assertEquals(HallLayout.Facing8.NORTH_WEST, s.facing());
		StatueAssignments.sync(layout, List.of(6));
		layout.save(folder);
		HallLayout loaded = HallLayout.load(folder);
		assertEquals(layout.statues(), loaded.statues());
		assertEquals(Map.of(6, 4), loaded.statueAssignments());
		assertEquals(layout.statues(), HallLayout.parse(layout.toJsonString()).statues());
	}

	@Test
	void exportShipsStatueAnchorsButNoAssignments(@TempDir Path exportFolder, @TempDir Path freshFolder) throws IOException {
		HallLayout layout = withStatues(1, 2, 5);
		StatueAssignments.sync(layout, List.of(3, 4));
		layout.save(folder);
		HallLayout.load(folder).forExport().save(exportFolder);
		// A fresh install: the shipped layout is its starting state, with no run history yet.
		java.nio.file.Files.copy(exportFolder.resolve(HallLayout.FILE_NAME), freshFolder.resolve(HallLayout.FILE_NAME));
		HallLayout fresh = HallLayout.load(freshFolder);
		assertEquals(layout.statues(), fresh.statues());
		assertEquals(Map.of(), fresh.statueAssignments());
		assertEquals(List.of(), StatueAssignments.sync(fresh, List.of()).unplaced());
		assertEquals(Map.of(), fresh.statueAssignments());
		// The exporting world's own layout keeps its assignments.
		assertEquals(Map.of(3, 1, 4, 2), HallLayout.load(folder).statueAssignments());
	}

	@Test
	void storesTheBlockCentreNeverTheExactPosition() {
		// Callers pass the block the executor stands in; any block gives x.5, the block's floor y, z.5.
		HallLayout.Statue s = new HallLayout().addStatue(1, -7, 64, 12, 0, 64, 0, 0);
		assertEquals(-6.5, s.dx());
		assertEquals(0.0, s.dy());
		assertEquals(12.5, s.dz());
	}

	@Test
	void snapsYawToEightFacings() {
		assertEquals(HallLayout.Facing8.SOUTH, HallLayout.snapFacing8(0));
		assertEquals(HallLayout.Facing8.SOUTH, HallLayout.snapFacing8(22.4));
		assertEquals(HallLayout.Facing8.SOUTH_WEST, HallLayout.snapFacing8(22.5));
		assertEquals(HallLayout.Facing8.WEST, HallLayout.snapFacing8(90));
		assertEquals(HallLayout.Facing8.NORTH, HallLayout.snapFacing8(-180));
		assertEquals(HallLayout.Facing8.EAST, HallLayout.snapFacing8(-90));
		assertEquals(HallLayout.Facing8.SOUTH_EAST, HallLayout.snapFacing8(-45));
		assertEquals(HallLayout.Facing8.SOUTH, HallLayout.snapFacing8(359));
	}
}
