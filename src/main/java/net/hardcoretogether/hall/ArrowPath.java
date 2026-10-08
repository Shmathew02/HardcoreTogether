package net.hardcoretogether.hall;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The floor arrows' light pattern: lamp order along the path and which lamps are lit at a given tick. The path is
 * every lamp of arrow 1, then arrow 2, then arrow 3; inside an arrow, farthest from the target first. No Minecraft
 * classes, so tests can use it.
 */
public final class ArrowPath {
	public enum Mode {
		/** A lit band runs along the whole path towards the target, then pauses and starts again. */
		WAVE,
		/** Every lamp on and off together. */
		BLINK,
		/** Every lamp always on. */
		SOLID;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Optional<Mode> byId(String id) {
			for (Mode m : values()) {
				if (m.id().equals(id.trim().toLowerCase(Locale.ROOT))) {
					return Optional.of(m);
				}
			}
			return Optional.empty();
		}
	}

	/** Timings from the config; every value is at least 1 (pause at least 0). */
	public record Settings(Mode mode, int bandLength, int stepTicks, int pauseTicks, int blinkTicks) {
		public static final Settings DEFAULT = new Settings(Mode.WAVE, 3, 2, 10, 10);

		public Settings withMode(Mode m) {
			return new Settings(m, bandLength, stepTicks, pauseTicks, blinkTicks);
		}
	}

	public record Lamp(int x, int y, int z) {
		long distanceSq(int[] target) {
			long dx = x - target[0], dy = y - target[1], dz = z - target[2];
			return dx * dx + dy * dy + dz * dz;
		}
	}

	private ArrowPath() {
	}

	/**
	 * Orders the lamps of one arrow: farthest from the target first, ties by x, y, z so the order never changes
	 * between rebuilds. Without a target the lamps are ordered by x, y, z only.
	 */
	public static List<Lamp> orderArrow(List<Lamp> lamps, int[] target) {
		Comparator<Lamp> byPos = Comparator.comparingInt(Lamp::x).thenComparingInt(Lamp::y).thenComparingInt(Lamp::z);
		Comparator<Lamp> order = target == null ? byPos
			: Comparator.<Lamp>comparingLong(l -> l.distanceSq(target)).reversed().thenComparing(byPos);
		List<Lamp> sorted = new ArrayList<>(lamps);
		sorted.sort(order);
		return sorted;
	}

	/** Which of the {@code n} path lamps are lit {@code tick} ticks after the arrows were switched on. */
	public static BitSet frame(Settings s, int n, long tick) {
		BitSet lit = new BitSet(n);
		if (n == 0) {
			return lit;
		}
		switch (s.mode()) {
			case SOLID -> lit.set(0, n);
			case BLINK -> {
				if ((tick / s.blinkTicks()) % 2 == 0) {
					lit.set(0, n);
				}
			}
			case WAVE -> {
				// The band's head moves one lamp per step from the first lamp until the band has left the last lamp.
				long steps = n + s.bandLength() - 1L;
				long moving = steps * s.stepTicks();
				long phase = tick % (moving + s.pauseTicks());
				if (phase < moving) {
					long head = phase / s.stepTicks();
					int from = (int) Math.max(0, head - s.bandLength() + 1);
					int to = (int) Math.min(n - 1, head);
					if (from <= to) {
						lit.set(from, to + 1);
					}
				}
			}
		}
		return lit;
	}
}
