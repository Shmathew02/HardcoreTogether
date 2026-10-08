package net.hardcoretogether.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunFormatTest {
	@Test
	void elapsedAtEachUnitBoundary() {
		assertEquals("0s", RunFormat.elapsed(0));
		assertEquals("0s", RunFormat.elapsed(999));
		assertEquals("1s", RunFormat.elapsed(1_000));
		assertEquals("59s", RunFormat.elapsed(59_999));
		assertEquals("1m 0s", RunFormat.elapsed(60_000));
		assertEquals("59m 59s", RunFormat.elapsed(3_599_999));
		assertEquals("1h 0m 0s", RunFormat.elapsed(3_600_000));
		assertEquals("2h 3m 4s", RunFormat.elapsed((2 * 3600 + 3 * 60 + 4) * 1000L));
		assertEquals("23h 59m 59s", RunFormat.elapsed(86_399_999));
		assertEquals("1d 0h 0m", RunFormat.elapsed(86_400_000));
		assertEquals("3d 4h 5m", RunFormat.elapsed(((3 * 24 + 4) * 3600 + 5 * 60 + 59) * 1000L));
	}

	@Test
	void elapsedNeverNegativeOrBroken() {
		assertEquals("0s", RunFormat.elapsed(-5_000));
		assertTrue(RunFormat.elapsed(Long.MAX_VALUE).endsWith("m"));
	}

	@Test
	void dayNormal() {
		assertEquals(1, RunFormat.day(0, 0));
		assertEquals(1, RunFormat.day(0, 23_999));
		assertEquals(2, RunFormat.day(0, 24_000));
		assertEquals(11, RunFormat.day(6_000, 6_000 + 240_000));
	}

	@Test
	void dayWhenTimeSetBackward() {
		assertEquals(1, RunFormat.day(100_000, 5_000));
		assertEquals(1, RunFormat.day(100_000, -1));
	}

	@Test
	void dayClampsVeryLargeValues() {
		assertEquals(RunFormat.MAX_DAY, RunFormat.day(Long.MIN_VALUE, Long.MAX_VALUE));
		assertEquals(1, RunFormat.day(Long.MAX_VALUE, Long.MIN_VALUE));
		long big = RunFormat.day(0, Long.MAX_VALUE);
		assertTrue(big > 0);
		assertEquals(Long.MAX_VALUE / RunFormat.TICKS_PER_DAY + 1, big);
		assertTrue(RunFormat.day(-5, Long.MAX_VALUE - 1) >= 1);
	}
}
