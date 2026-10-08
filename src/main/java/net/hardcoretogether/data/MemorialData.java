package net.hardcoretogether.data;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only snapshot of run-history.json and player-stats.json for the hall displays. Lists are sorted once when
 * the snapshot is built, so queries never touch the disk.
 *
 * {@link #get()} is the current snapshot. RunTracker replaces it whenever the data changes (run start, run
 * recorded, run deleted, stats rebuild, fresh start) and then fires {@link MemorialEvents}. Snapshots are
 * immutable.
 *
 * Runs include command resets (end kind "command"), like the player stats; displays that only show deaths
 * filter on {@link RunHistoryEntry#endKind()}.
 */
public final class MemorialData {
	/** Player leaderboard metrics. LONGEST_RUN and FURTHEST_DAY come with the run that holds the record. */
	public enum Metric { PLAYTIME, RUNS_PLAYED, DEATHS, LONGEST_RUN, FURTHEST_DAY }

	/**
	 * One leaderboard row. value is milliseconds for PLAYTIME and LONGEST_RUN, a count or a day otherwise; run is
	 * the record's run for LONGEST_RUN and FURTHEST_DAY, 0 for the others. name is the newest name in the history,
	 * null if the history has none (look the UUID up in the registry then).
	 */
	public record PlayerEntry(UUID uuid, @Nullable String name, long value, int run) {
	}

	/** Finished runs only (the live run is not included). currentRun is 0 when there is no current run. */
	public record Totals(int totalRuns, int totalDeaths, long totalPlaytimeMillis, int currentRun) {
	}

	/** A cause of death: id is the killer's entity type (killer = true) or else the damage type. */
	public record Cause(String id, boolean killer, int count) {
	}

	public static final MemorialData EMPTY = build(List.of(), PlayerStats.empty(), 0);

	private static volatile MemorialData current = EMPTY;

	private final List<RunHistoryEntry> recent;
	private final List<RunHistoryEntry> byDuration;
	private final List<RunHistoryEntry> byDay;
	private final Map<Integer, RunHistoryEntry> byNumber;
	private final Map<Metric, List<PlayerEntry>> leaderboards;
	private final Totals totals;
	private final List<Cause> causes;

	private MemorialData(List<RunHistoryEntry> recent, List<RunHistoryEntry> byDuration, List<RunHistoryEntry> byDay,
			Map<Integer, RunHistoryEntry> byNumber, Map<Metric, List<PlayerEntry>> leaderboards, Totals totals, List<Cause> causes) {
		this.recent = recent;
		this.byDuration = byDuration;
		this.byDay = byDay;
		this.byNumber = byNumber;
		this.leaderboards = leaderboards;
		this.totals = totals;
		this.causes = causes;
	}

	/** The current snapshot (EMPTY while no world is loaded). */
	public static MemorialData get() {
		return current;
	}

	/** Replaces the current snapshot; the caller fires MemorialEvents afterwards if the data changed. */
	public static void set(MemorialData data) {
		current = data;
	}

	/**
	 * Builds a snapshot. Ties: runs with equal duration or day keep the earlier run first; leaderboard rows with
	 * equal values put the earlier record run first, then the name (case-insensitive), then the UUID.
	 */
	public static MemorialData build(List<RunHistoryEntry> history, PlayerStats stats, int currentRun) {
		Comparator<RunHistoryEntry> byRun = Comparator.comparingInt(RunHistoryEntry::run);
		List<RunHistoryEntry> recent = sorted(history, byRun.reversed());
		List<RunHistoryEntry> byDuration = sorted(history,
			Comparator.comparingLong(RunHistoryEntry::finalActiveMillis).reversed().thenComparing(byRun));
		List<RunHistoryEntry> byDay = sorted(history, Comparator.comparingLong(RunHistoryEntry::finalDay).reversed().thenComparing(byRun));
		Map<Integer, RunHistoryEntry> byNumber = new HashMap<>();
		history.forEach(r -> byNumber.put(r.run(), r));

		Map<Metric, List<PlayerEntry>> leaderboards = new EnumMap<>(Metric.class);
		for (Metric metric : Metric.values()) {
			List<PlayerEntry> rows = new ArrayList<>();
			stats.all().forEach((uuid, s) -> {
				PlayerEntry row = row(metric, uuid, s);
				if (row.value() > 0) {
					rows.add(row);
				}
			});
			rows.sort(Comparator.comparingLong(PlayerEntry::value).reversed()
				.thenComparingInt(PlayerEntry::run)
				.thenComparing(e -> e.name() == null ? "￿" : e.name().toLowerCase(Locale.ROOT))
				.thenComparing(PlayerEntry::uuid));
			leaderboards.put(metric, List.copyOf(rows));
		}

		int deaths = 0;
		Map<String, Cause> causeCounts = new HashMap<>();
		for (RunHistoryEntry r : history) {
			RunDeath d = r.death();
			if (!"death".equals(r.endKind()) || d == null) {
				continue;
			}
			deaths++;
			boolean killer = d.killerType() != null;
			String id = killer ? d.killerType() : d.damageType();
			if (id != null) {
				causeCounts.merge((killer ? "k:" : "d:") + id, new Cause(id, killer, 1), (a, b) -> new Cause(a.id(), a.killer(), a.count() + 1));
			}
		}
		List<Cause> causes = sorted(causeCounts.values(),
			Comparator.comparingInt(Cause::count).reversed().thenComparing(Cause::id).thenComparing(Cause::killer));
		long playtime = stats.all().values().stream().mapToLong(PlayerStats.Stats::totalPlaytimeMillis).sum();

		return new MemorialData(recent, byDuration, byDay, Map.copyOf(byNumber), leaderboards,
			new Totals(history.size(), deaths, playtime, currentRun), causes);
	}

	private static PlayerEntry row(Metric metric, UUID uuid, PlayerStats.Stats s) {
		return switch (metric) {
			case PLAYTIME -> new PlayerEntry(uuid, s.name(), s.totalPlaytimeMillis(), 0);
			case RUNS_PLAYED -> new PlayerEntry(uuid, s.name(), s.runsPlayed(), 0);
			case DEATHS -> new PlayerEntry(uuid, s.name(), s.deaths(), 0);
			case LONGEST_RUN -> new PlayerEntry(uuid, s.name(), s.longestRunMillis(), s.longestRun());
			case FURTHEST_DAY -> new PlayerEntry(uuid, s.name(), s.furthestDay(), s.furthestDayRun());
		};
	}

	private static <T> List<T> sorted(java.util.Collection<T> items, Comparator<? super T> order) {
		List<T> list = new ArrayList<>(items);
		list.sort(order);
		return List.copyOf(list);
	}

	private static <T> List<T> first(List<T> list, int limit) {
		return limit <= 0 ? List.of() : list.subList(0, Math.min(limit, list.size()));
	}

	// ---- Queries ----

	/** Finished runs, newest (highest run number) first. */
	public List<RunHistoryEntry> recentRuns(int limit) {
		return first(recent, limit);
	}

	/** Longest finished runs by active time. */
	public List<RunHistoryEntry> topRunsByDuration(int limit) {
		return first(byDuration, limit);
	}

	/** Finished runs that reached the highest day. */
	public List<RunHistoryEntry> topRunsByDay(int limit) {
		return first(byDay, limit);
	}

	/** One finished run, empty if it is not in the history (never filed, deleted, or the current run). */
	public Optional<RunHistoryEntry> run(int number) {
		return Optional.ofNullable(byNumber.get(number));
	}

	/** Players ranked by a lifetime stat (finished runs only); players with 0 for that stat are left out. */
	public List<PlayerEntry> playerLeaderboard(Metric metric, int limit) {
		return first(leaderboards.get(metric), limit);
	}

	public Totals totals() {
		return totals;
	}

	/**
	 * Deaths grouped by the killer's entity type, or by the damage type when there was no killer; deaths with no
	 * cause data (older records) and command resets are skipped. Most common first, ties by id.
	 */
	public List<Cause> deadliestCauses(int limit) {
		return first(causes, limit);
	}
}
