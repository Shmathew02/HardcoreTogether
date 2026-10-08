package net.hardcoretogether.hall;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.data.MemorialData;
import net.hardcoretogether.data.MemorialEvents;
import net.hardcoretogether.data.RunHistoryEntry;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Keeps the statue assignments in hall-layout.json in step with the run history: every memorial change (a death
 * recorded, a run deleted, a fresh start) places new deaths on free statue anchors, then asks
 * {@link StatueEntities} to bring the statues and plaques in line (at a death: before the countdown, or as soon as
 * the hall's entities are loaded).
 */
public final class HallStatues {
	private HallStatues() {
	}

	private static MinecraftServer server;

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
		MemorialEvents.onDataChanged((data, reason) -> {
			HtWorldData world = HardcoreTogether.worldData();
			if (world == null) {
				return;
			}
			try {
				HallLayout layout = HallLayout.load(world.folder());
				sync(world, layout, reason);
			} catch (IOException e) {
				HardcoreTogether.LOGGER.error("[HT hall] statue sync failed ({})", reason, e);
			}
			if (server != null) {
				StatueEntities.request(server, reason, null);
			}
		});
	}

	/** Run numbers of every finished run that ended in a death, oldest first. */
	public static List<Integer> deathRuns(MemorialData data) {
		return data.recentRuns(Integer.MAX_VALUE).stream().filter(HallStatues::isDeath).map(RunHistoryEntry::run).sorted().toList();
	}

	/** The death that ended this run, for display ("run #3 Steve"). */
	public static Optional<RunHistoryEntry> death(int run) {
		return MemorialData.get().run(run).filter(HallStatues::isDeath);
	}

	private static boolean isDeath(RunHistoryEntry r) {
		return "death".equals(r.endKind()) && r.death() != null;
	}

	/** Syncs the given layout with the current deaths and saves it if anything changed. */
	public static StatueAssignments.Result sync(HtWorldData world, HallLayout layout, String why) throws IOException {
		StatueAssignments.Result r = StatueAssignments.sync(layout, deathRuns(MemorialData.get()));
		if (r.changed()) {
			layout.save(world.folder());
			HardcoreTogether.LOGGER.info("[HT hall] statues synced ({}): {} placed, {} released, {} unplaced", why, r.placed(),
				r.released(), r.unplaced().size());
		}
		return r;
	}

	/** Reassigns every death in run order to the current statue anchors and saves. */
	public static StatueAssignments.Result relayout(HtWorldData world, HallLayout layout) throws IOException {
		StatueAssignments.Result r = StatueAssignments.relayout(layout, deathRuns(MemorialData.get()));
		layout.save(world.folder());
		HardcoreTogether.LOGGER.info("[HT hall] statues relayout: {} cleared, {} placed, {} unplaced", r.released(), r.placed(),
			r.unplaced().size());
		return r;
	}
}
