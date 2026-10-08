package net.hardcoretogether.hall;

import net.hardcoretogether.data.DeathCause;
import net.hardcoretogether.data.RunHistoryEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The three record boards on the Death Hall text anchors: what each one says (pure, unit tested) and how it looks
 * (title line bold in the title colour, body white). The entities are kept by {@link StatueEntities}.
 */
public final class RecordBoards {
	/** Which anchor type shows which board. */
	public enum Board {
		DEATHS(AnchorType.LEADERBOARD_TEXT, "Deaths", true),
		RUNS(AnchorType.RECENT_RUNS_TEXT, "Runs", false),
		CAUSES(AnchorType.CAUSES_TEXT, "Causes", false);

		public final AnchorType anchor;
		public final String title;
		/** true: turns to face every viewer; false: fixed, facing the anchor's facing like a sign. */
		public final boolean vertical;

		Board(AnchorType anchor, String title, boolean vertical) {
			this.anchor = anchor;
			this.title = title;
			this.vertical = vertical;
		}

		public String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** A registered player for the deaths board; firstSeen is players.json's first_seen (ISO instant, may be null). */
	public record PlayerRow(UUID uuid, String name, String firstSeen) {
	}

	private RecordBoards() {
	}

	/** One line per registered player, most deaths first; ties in the order players first joined. Zero deaths included. */
	public static List<String> deaths(List<PlayerRow> players, List<RunHistoryEntry> history, int maxPlayers) {
		if (players.isEmpty()) {
			return List.of("No players yet");
		}
		Map<UUID, Integer> deaths = new HashMap<>();
		for (RunHistoryEntry r : history) {
			if ("death".equals(r.endKind()) && r.death() != null) {
				deaths.merge(r.death().uuid(), 1, Integer::sum);
			}
		}
		List<PlayerRow> sorted = new ArrayList<>(players);
		sorted.sort(Comparator.<PlayerRow>comparingInt(p -> -deaths.getOrDefault(p.uuid(), 0))
			.thenComparing(p -> joined(p.firstSeen()))
			.thenComparing(PlayerRow::name, String.CASE_INSENSITIVE_ORDER));
		List<String> lines = new ArrayList<>();
		for (PlayerRow p : sorted.subList(0, Math.min(Math.max(maxPlayers, 1), sorted.size()))) {
			lines.add(p.name() + ": " + deaths.getOrDefault(p.uuid(), 0));
		}
		return lines;
	}

	private static Instant joined(String firstSeen) {
		try {
			return firstSeen == null ? Instant.MAX : Instant.parse(firstSeen);
		} catch (RuntimeException e) {
			return Instant.MAX;
		}
	}

	/** Latest = the most recently ended run, Longest = the ended run with the greatest run length (earlier run on a tie). */
	public static List<String> runs(List<RunHistoryEntry> history) {
		if (history.isEmpty()) {
			return List.of("No runs yet");
		}
		RunHistoryEntry latest = history.stream()
			.max(Comparator.comparingLong(RunHistoryEntry::endedAtEpoch).thenComparingInt(RunHistoryEntry::run)).orElseThrow();
		RunHistoryEntry longest = history.stream()
			.max(Comparator.comparingLong(RunHistoryEntry::finalActiveMillis).thenComparing(Comparator.comparingInt(RunHistoryEntry::run).reversed()))
			.orElseThrow();
		return List.of("Latest: Run #" + latest.run() + " · " + StatuePlaque.runLength(latest.finalActiveMillis()),
			"Longest: Run #" + longest.run() + " · " + StatuePlaque.runLength(longest.finalActiveMillis()));
	}

	/** One line per distinct cause in the order each first happened (oldest on top); counts go up in place. */
	public static List<String> causes(List<RunHistoryEntry> history) {
		List<RunHistoryEntry> deaths = history.stream().filter(r -> "death".equals(r.endKind()) && r.death() != null)
			.sorted(Comparator.comparingLong(RunHistoryEntry::endedAtEpoch).thenComparingInt(RunHistoryEntry::run)).toList();
		if (deaths.isEmpty()) {
			return List.of("No deaths yet");
		}
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (RunHistoryEntry r : deaths) {
			counts.merge(DeathCause.label(r.death()), 1, Integer::sum);
		}
		List<String> lines = new ArrayList<>();
		counts.forEach((cause, n) -> lines.add(cause + ": " + n));
		return lines;
	}

	/**
	 * The causes board after fitting, at this text scale: one column (right empty), or two side by side under one
	 * centred title. body is the only (or left) column; a "+N more" last line can only end the right column.
	 */
	public record Fitted(List<String> body, List<String> right, double scale) {
		public boolean twoColumns() {
			return !right.isEmpty();
		}

		public int lines() {
			return body.size() + right.size();
		}
	}

	/** Space between the two causes columns, in font pixels. */
	public static final int COLUMN_GAP = 12;

	/**
	 * Causes board fit, hanging from its top edge within roomHeight below the top and roomWidth across. One column
	 * at the largest scale (never above maxScale) while that is at least minScale. Below that the text stays at
	 * minScale and the list splits into two columns: the left one filled top to bottom first, then the right one.
	 * Only when both are full the right column's last line is "+N more". Scales are rounded down to 0.01.
	 */
	public static Fitted fitCauses(List<RunHistoryEntry> history, double maxScale, double minScale, double roomHeight, double roomWidth) {
		return fitCauseLines(causes(history), maxScale, minScale, roomHeight, roomWidth);
	}

	/** {@link #fitCauses} for ready-made cause lines. */
	public static Fitted fitCauseLines(List<String> all, double maxScale, double minScale, double roomHeight, double roomWidth) {
		double min = Math.min(minScale, maxScale);
		double[] one = size(Board.CAUSES, all);
		double single = floor2(Math.min(maxScale, Math.min(roomHeight / one[1], roomWidth / one[0])));
		// Columns only help when height is the limit; a line too wide for the wall would not get narrower.
		if (single >= min - 1.0E-9 || all.size() < 2 || roomHeight / one[1] >= min - 1.0E-9) {
			return new Fitted(all, List.of(), single);
		}
		double scale = floor2(min);
		Fitted fitted = null;
		for (int attempt = 0; attempt < 4; attempt++) {
			int rows = Math.max(1, (int) Math.floor((roomHeight / (scale * PIXEL) - 1) / 10) - 1);
			fitted = split(all, rows, scale);
			double width = columnsSize(fitted)[0];
			if (roomWidth / width >= scale - 1.0E-9) {
				return fitted;
			}
			scale = floor2(roomWidth / width); // a column line too wide for its half of the wall: narrower text, more rows
		}
		return fitted;
	}

	/** The first rows causes on the left, the rest on the right; "+N more" ends the right column when it overflows. */
	static Fitted split(List<String> all, int rows, double scale) {
		List<String> left = List.copyOf(all.subList(0, Math.min(rows, all.size())));
		List<String> right = new ArrayList<>(all.subList(left.size(), Math.min(2 * rows, all.size())));
		if (all.size() > 2 * rows) {
			right.remove(right.size() - 1);
			right.add("+" + (all.size() - left.size() - right.size()) + " more");
		}
		return new Fitted(left, List.copyOf(right), scale);
	}

	/**
	 * Two-column board size in blocks at scale 1, background included: {width, height, left column width, right
	 * column width}. The columns sit COLUMN_GAP apart, centred under the title; height is the title plus the left
	 * (longer) column.
	 */
	public static double[] columnsSize(Fitted fitted) {
		int left = columnWidth(fitted.body()), right = columnWidth(fitted.right());
		int width = Math.max(textWidth(Board.CAUSES.title, true) + 2, left + COLUMN_GAP + right);
		return new double[] {width * PIXEL, ((fitted.body().size() + 1) * 10 + 1) * PIXEL, left * PIXEL, right * PIXEL};
	}

	private static int columnWidth(List<String> lines) {
		int w = 0;
		for (String line : lines) {
			w = Math.max(w, textWidth(line, false));
		}
		return w + 2;
	}

	/**
	 * The two-column board's backing: the title, then blank lines down to the bottom of the left column, the last
	 * one spaces as wide as both columns, so its background covers the whole board.
	 */
	public static List<String> columnsBacking(Fitted fitted) {
		int width = (int) Math.round(columnsSize(fitted)[0] / PIXEL) - 2;
		List<String> lines = new ArrayList<>();
		for (int i = 1; i < fitted.body().size(); i++) {
			lines.add("");
		}
		lines.add(" ".repeat(Math.max(1, (width + 3) / 4)));
		return lines;
	}

	/** One causes column: white lines, no title. */
	public static Component renderColumn(List<String> lines) {
		return Component.literal(String.join("\n", lines)).withStyle(Style.EMPTY.withBold(false).withColor(ChatFormatting.WHITE));
	}

	private static double floor2(double v) {
		return Math.floor(v * 100) / 100;
	}

	/** Vanilla text display units: 1 font pixel = 0.025 blocks at scale 1; a line is 10 px (9 + 1 spacing). */
	public static final double PIXEL = 0.025;

	/**
	 * Estimated board size in blocks at scale 1 (background included): the widest line (title bold) by the default
	 * font's advance widths, and 10 px per line. Close enough to fit boards to walls; the font has no server-side
	 * metrics.
	 */
	public static double[] size(Board board, List<String> body) {
		int width = textWidth(board.title, true);
		for (String line : body) {
			width = Math.max(width, textWidth(line, false));
		}
		int lines = body.size() + 1;
		return new double[] {(width + 2) * PIXEL, (lines * 10 + 1) * PIXEL};
	}

	/** Default font advance widths (glyph + 1 px), bold one more per character. */
	static int textWidth(String text, boolean bold) {
		int w = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			int a = switch (c) {
				case 'i', '!', '.', ',', ':', ';', '|', '\'', '·' -> 2;
				case 'l', '`' -> 3;
				case 'I', 't', '[', ']', ' ' -> 4;
				case 'f', 'k', '<', '>', '"', '(', ')', '{', '}', '*' -> 5;
				case '@', '~' -> 7;
				default -> 6;
			};
			w += a + (bold ? 1 : 0);
		}
		return w;
	}

	/** The board text: bold title in the title colour, then white body lines. */
	public static Component render(Board board, List<String> body, int titleRgb) {
		MutableComponent text = Component.literal(board.title).withStyle(Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(titleRgb)));
		for (String line : body) {
			text.append(Component.literal("\n" + line).withStyle(Style.EMPTY.withBold(false).withColor(ChatFormatting.WHITE)));
		}
		return text;
	}
}
