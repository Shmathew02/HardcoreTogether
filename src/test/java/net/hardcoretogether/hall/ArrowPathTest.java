package net.hardcoretogether.hall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrowPathTest {
	@TempDir
	Path folder;

	private static String lit(BitSet b, int n) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < n; i++) {
			s.append(b.get(i) ? '#' : '.');
		}
		return s.toString();
	}

	@Test
	void waveBandEntersTravelsLeavesThenPauses() {
		ArrowPath.Settings s = new ArrowPath.Settings(ArrowPath.Mode.WAVE, 2, 1, 2, 10);
		String[] expected = {"#...", "##..", ".##.", "..##", "...#", "....", "....", "#..."};
		for (int t = 0; t < expected.length; t++) {
			assertEquals(expected[t], lit(ArrowPath.frame(s, 4, t), 4), "tick " + t);
		}
	}

	@Test
	void waveStepTicksHoldEachFrame() {
		ArrowPath.Settings s = new ArrowPath.Settings(ArrowPath.Mode.WAVE, 3, 2, 10, 10);
		assertEquals("#.....", lit(ArrowPath.frame(s, 6, 1), 6));
		assertEquals("##....", lit(ArrowPath.frame(s, 6, 2), 6));
		assertEquals("..###.", lit(ArrowPath.frame(s, 6, 9), 6));
		// 8 steps x 2 ticks moving, then 10 ticks dark, then again.
		assertEquals("......", lit(ArrowPath.frame(s, 6, 16), 6));
		assertEquals("#.....", lit(ArrowPath.frame(s, 6, 26), 6));
	}

	@Test
	void blinkAndSolid() {
		ArrowPath.Settings blink = new ArrowPath.Settings(ArrowPath.Mode.BLINK, 3, 2, 10, 5);
		assertEquals("###", lit(ArrowPath.frame(blink, 3, 4), 3));
		assertEquals("...", lit(ArrowPath.frame(blink, 3, 5), 3));
		assertEquals("###", lit(ArrowPath.frame(blink, 3, 10), 3));
		ArrowPath.Settings solid = blink.withMode(ArrowPath.Mode.SOLID);
		assertEquals("###", lit(ArrowPath.frame(solid, 3, 123), 3));
		assertTrue(ArrowPath.frame(solid, 0, 0).isEmpty());
	}

	@Test
	void ordersEachArrowFarthestFromTheTargetFirst() {
		List<ArrowPath.Lamp> lamps = List.of(new ArrowPath.Lamp(1, 0, 0), new ArrowPath.Lamp(5, 0, 0), new ArrowPath.Lamp(3, 0, 1),
			new ArrowPath.Lamp(3, 0, -1));
		List<ArrowPath.Lamp> ordered = ArrowPath.orderArrow(lamps, new int[] {10, 0, 0});
		assertEquals(List.of(new ArrowPath.Lamp(1, 0, 0), new ArrowPath.Lamp(3, 0, -1), new ArrowPath.Lamp(3, 0, 1),
			new ArrowPath.Lamp(5, 0, 0)), ordered);
	}

	@Test
	void arrowsSurviveSaveParseAndExport() throws IOException {
		HallLayout layout = new HallLayout();
		layout.setArrowCorner(2, 1, 5, 64, 5, 0, 64, 0);
		layout.setArrowCorner(2, 2, 8, 64, 9, 0, 64, 0);
		layout.setArrowCorner(3, 1, 1, 64, 1, 0, 64, 0);
		layout.setArrowTarget(20, 65, 0, 0, 64, 0);
		layout.setArrowsMode("blink");
		layout.save(folder);
		HallLayout loaded = HallLayout.load(folder).forExport();
		assertArrayEquals(new int[] {8, 0, 9}, loaded.arrowCorner(2, 2).orElseThrow());
		assertTrue(loaded.arrowCorner(3, 2).isEmpty());
		assertTrue(loaded.arrowCorner(1, 1).isEmpty());
		assertArrayEquals(new int[] {20, 1, 0}, loaded.arrowTarget().orElseThrow());
		assertEquals("blink", loaded.arrowsMode().orElseThrow());
		HallLayout restored = new HallLayout();
		restored.replaceArrows(loaded);
		assertArrayEquals(new int[] {5, 0, 5}, restored.arrowCorner(2, 1).orElseThrow());
	}
}
