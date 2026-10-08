package net.hardcoretogether.hall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which death stands on which statue anchor. Deaths are identified by their run number. Rules:
 * <ul>
 * <li>An assignment is permanent: adding or removing anchors never moves an existing statue.</li>
 * <li>Unplaced deaths (no anchor yet, or their anchor was removed) take the free anchors, oldest run first, in
 *     ascending anchor number; gaps in the numbering are skipped.</li>
 * <li>A death that is no longer in the run history (run deleted, fresh start) frees its anchor.</li>
 * <li>{@link #relayout} is the only thing that moves statues: it reassigns every death in run order.</li>
 * </ul>
 * No Minecraft classes, so tests can use it.
 */
public final class StatueAssignments {
	/** What a sync changed. unplaced: runs still without an anchor, oldest first. */
	public record Result(int placed, int released, List<Integer> unplaced) {
		public boolean changed() {
			return placed > 0 || released > 0;
		}
	}

	private StatueAssignments() {
	}

	/** Brings the layout's assignments up to date with the deaths (run numbers, any order), keeping existing ones. */
	public static Result sync(HallLayout layout, List<Integer> deathRuns) {
		Set<Integer> deaths = new TreeSet<>(deathRuns);
		int released = 0;
		for (Map.Entry<Integer, Integer> e : layout.statueAssignments().entrySet()) {
			if (!deaths.contains(e.getKey()) || layout.statue(e.getValue()).isEmpty()) {
				layout.unassignStatue(e.getKey());
				released++;
			}
		}
		Map<Integer, Integer> assigned = layout.statueAssignments();
		TreeSet<Integer> free = new TreeSet<>();
		layout.statues().forEach(s -> free.add(s.number()));
		free.removeAll(assigned.values());
		int placed = 0;
		List<Integer> unplaced = new ArrayList<>();
		for (int run : deaths) {
			if (assigned.containsKey(run)) {
				continue;
			}
			Integer number = free.pollFirst();
			if (number == null) {
				unplaced.add(run);
			} else {
				layout.assignStatue(run, number);
				placed++;
			}
		}
		return new Result(placed, released, List.copyOf(unplaced));
	}

	/** Clears every assignment and fills the anchors again from the oldest death. */
	public static Result relayout(HallLayout layout, List<Integer> deathRuns) {
		int before = layout.statueAssignments().size();
		layout.clearStatueAssignments();
		Result r = sync(layout, deathRuns);
		return new Result(r.placed(), before, r.unplaced());
	}
}
