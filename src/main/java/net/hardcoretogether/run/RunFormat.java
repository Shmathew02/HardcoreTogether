package net.hardcoretogether.run;

/** Display math for /run: elapsed time and the in-game day. Pure functions, unit tested. */
public final class RunFormat {
	public static final long TICKS_PER_DAY = 24000L;
	/** The day for the largest possible tick difference; anything beyond it is shown as this. */
	public static final long MAX_DAY = Long.MAX_VALUE / TICKS_PER_DAY + 1;

	private RunFormat() {
	}

	/** "45s", "3m 5s", "2h 0m 5s", "1d 4h 12m": leading zero units skipped, seconds dropped from a day on. */
	public static String elapsed(long millis) {
		long s = Math.max(0, millis) / 1000;
		long days = s / 86400;
		long hours = s % 86400 / 3600;
		long minutes = s % 3600 / 60;
		long seconds = s % 60;
		if (days > 0) {
			return days + "d " + hours + "h " + minutes + "m";
		}
		if (hours > 0) {
			return hours + "h " + minutes + "m " + seconds + "s";
		}
		if (minutes > 0) {
			return minutes + "m " + seconds + "s";
		}
		return seconds + "s";
	}

	/**
	 * Day = floorDiv(now - start, 24000) + 1. A negative difference (time set backwards) is Day 1, and an
	 * overflowing one is clamped, so the result is always at least 1.
	 */
	public static long day(long startDayTime, long currentDayTime) {
		long diff;
		try {
			diff = Math.subtractExact(currentDayTime, startDayTime);
		} catch (ArithmeticException e) {
			return currentDayTime > startDayTime ? MAX_DAY : 1;
		}
		if (diff < 0) {
			return 1;
		}
		return Math.floorDiv(diff, TICKS_PER_DAY) + 1;
	}
}
