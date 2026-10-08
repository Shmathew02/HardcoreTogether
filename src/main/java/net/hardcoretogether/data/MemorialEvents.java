package net.hardcoretogether.data;

import net.hardcoretogether.HardcoreTogether;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The memorial change hook. Fires once after a run is recorded, a run is deleted, the stats are
 * rebuilt, or a fresh start; {@link MemorialData#get()} already holds the new snapshot when it fires. Listeners
 * run on the server thread; one that throws is logged and the others still run.
 */
public final class MemorialEvents {
	public interface Listener {
		void onDataChanged(MemorialData data, String reason);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(HardcoreTogether.MOD_ID);
	private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

	private MemorialEvents() {
	}

	/** Registers a listener for every later change. */
	public static void onDataChanged(Listener listener) {
		listeners.add(listener);
	}

	public static void unregister(Listener listener) {
		listeners.remove(listener);
	}

	/** Sets the new snapshot, then notifies every listener. reason: e.g. "run #7 recorded". */
	public static void publish(MemorialData data, String reason) {
		MemorialData.set(data);
		LOGGER.info("[HT memorial] data changed ({}): {} run(s), {} death(s); {} listener(s) notified", reason,
			data.totals().totalRuns(), data.totals().totalDeaths(), listeners.size());
		for (Listener listener : listeners) {
			try {
				listener.onDataChanged(data, reason);
			} catch (RuntimeException e) {
				LOGGER.warn("[HT memorial] a change listener failed ({})", reason, e);
			}
		}
	}
}
