package net.hardcoretogether.hall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallLayoutTest {
	@TempDir
	Path folder;

	@Test
	void snapsYawToNearestOfFourFacings() {
		assertEquals(HallLayout.Facing.SOUTH, HallLayout.snapFacing(0));
		assertEquals(HallLayout.Facing.SOUTH, HallLayout.snapFacing(44.9));
		assertEquals(HallLayout.Facing.SOUTH, HallLayout.snapFacing(-44.9));
		assertEquals(HallLayout.Facing.WEST, HallLayout.snapFacing(90));
		assertEquals(HallLayout.Facing.WEST, HallLayout.snapFacing(45));
		assertEquals(HallLayout.Facing.NORTH, HallLayout.snapFacing(180));
		assertEquals(HallLayout.Facing.NORTH, HallLayout.snapFacing(-180));
		assertEquals(HallLayout.Facing.NORTH, HallLayout.snapFacing(-170));
		assertEquals(HallLayout.Facing.EAST, HallLayout.snapFacing(-90));
		assertEquals(HallLayout.Facing.EAST, HallLayout.snapFacing(270));
		assertEquals(HallLayout.Facing.SOUTH, HallLayout.snapFacing(359));
		assertEquals(HallLayout.Facing.WEST, HallLayout.snapFacing(720 + 100));
		assertEquals(HallLayout.Facing.EAST, HallLayout.snapFacing(-450));
	}

	@Test
	void storesPositionsRelativeToTheOrigin() {
		HallLayout layout = new HallLayout();
		HallLayout.Anchor a = layout.add(AnchorType.RECORDS_TEXT, " Records ", 5, 66, -3, 0, 64, 0, 180);
		assertEquals(5, a.dx());
		assertEquals(2, a.dy());
		assertEquals(-3, a.dz());
		assertEquals("Records", a.label());
		assertEquals(HallLayout.Facing.NORTH, a.facing());
		// The same anchor in a hall shipped at another origin moves with it.
		assertArrayEquals(new int[] {105, 52, 197}, HallLayout.absolute(a, 100, 50, 200));
		assertArrayEquals(new int[] {5, 66, -3}, HallLayout.absolute(a, 0, 64, 0));
	}

	@Test
	void savesAndLoadsAnchors() throws IOException {
		HallLayout layout = new HallLayout();
		layout.add(AnchorType.LEADERBOARD_TEXT, "Top", 1, 65, 2, 0, 64, 0, 90);
		layout.add(AnchorType.HISTORY_LECTERN, "", -7, 64, 7, 0, 64, 0, -90);
		layout.add(AnchorType.RUN_START, "Go", 0, 70, 0, 0, 64, 0, 0);
		layout.save(folder);

		HallLayout loaded = HallLayout.load(folder);
		assertEquals(layout.anchors(), loaded.anchors());
		assertEquals(4, loaded.nextId());
	}

	@Test
	void savesAndLoadsTheSpawnWithAnchors() throws IOException {
		HallLayout layout = new HallLayout();
		assertTrue(layout.spawn().isEmpty());
		layout.add(AnchorType.RUN_START, "Go", 0, 70, 0, 0, 64, 0, 0);
		layout.setSpawn(HallLayout.Spawn.at(18.5, 66, 0.5, 0, 64, 0, 12.5F, -3F));
		layout.save(folder);

		HallLayout loaded = HallLayout.load(folder);
		assertEquals(new HallLayout.Spawn(18.5, 2, 0.5, 12.5F, -3F), loaded.spawn().orElseThrow());
		assertArrayEquals(new double[] {18.5, 66, 0.5}, loaded.spawn().orElseThrow().absolute(0, 64, 0));
		assertEquals(layout.anchors(), loaded.anchors());

		// Anchor edits load and save the file, so the spawn must survive them.
		loaded.remove(1);
		loaded.save(folder);
		assertEquals(loaded.spawn(), HallLayout.load(folder).spawn());
	}

	@Test
	void idsAreNeverReused() throws IOException {
		HallLayout layout = new HallLayout();
		layout.add(AnchorType.CAUSES_TEXT, "", 0, 64, 0, 0, 64, 0, 0);
		HallLayout.Anchor second = layout.add(AnchorType.CAUSES_TEXT, "", 0, 64, 0, 0, 64, 0, 0);
		assertTrue(layout.remove(second.id()));
		assertFalse(layout.remove(second.id()));
		layout.save(folder);

		HallLayout loaded = HallLayout.load(folder);
		HallLayout.Anchor third = loaded.add(AnchorType.RECENT_RUNS_TEXT, "", 0, 64, 0, 0, 64, 0, 0);
		assertEquals(3, third.id());
		assertEquals(List.of(1, 3), loaded.anchors().stream().map(HallLayout.Anchor::id).toList());
	}

	@Test
	void missingFileIsEmptyAndBadFileThrows() throws IOException {
		assertTrue(HallLayout.load(folder).anchors().isEmpty());
		Files.writeString(folder.resolve(HallLayout.FILE_NAME), "{\"anchors\":[{\"id\":1,\"type\":\"nope\"}]}");
		assertThrows(IOException.class, () -> HallLayout.load(folder));
	}

	@Test
	void movedStatueKeepsItsNumberAndDeath() throws IOException {
		HallLayout layout = HallLayout.load(folder);
		layout.addStatue(1, 18, 65, -16, 0, 64, 0, 0);
		layout.addStatue(2, 22, 65, -16, 0, 64, 0, 90);
		layout.assignStatue(11, 2);
		HallLayout.Statue moved = layout.moveStatue(2, 30, 65, 4, 0, 64, 0, 180);
		assertEquals(new HallLayout.Statue(2, 30.5, 1, 4.5, HallLayout.Facing8.NORTH), moved);
		layout.save(folder);
		HallLayout again = HallLayout.load(folder);
		assertEquals(moved, again.statue(2).orElseThrow());
		assertEquals(new HallLayout.Statue(1, 18.5, 1, -15.5, HallLayout.Facing8.SOUTH), again.statue(1).orElseThrow());
		assertEquals(java.util.Map.of(11, 2), again.statueAssignments());
		assertEquals(2, again.statues().size());
		assertThrows(IllegalArgumentException.class, () -> layout.moveStatue(3, 0, 65, 0, 0, 64, 0, 0));
	}

	@Test
	void defaultBoundsAre192x96x192AroundThePlatform() {
		HallBounds b = HallBounds.DEFAULT;
		assertEquals(-96, b.minDx());
		assertEquals(95, b.maxDx());
		assertEquals(-12, b.minDy());
		assertEquals(83, b.maxDy());
		assertEquals(-96, b.minDz());
		assertEquals(95, b.maxDz());
		// The 15x15 platform (radius 7) is inside.
		assertTrue(b.containsOffset(-7, 0, 7));
		assertFalse(b.containsOffset(96, 0, 0));
	}
}
