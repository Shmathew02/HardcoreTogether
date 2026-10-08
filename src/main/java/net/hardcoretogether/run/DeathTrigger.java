package net.hardcoretogether.run;

import net.hardcoretogether.data.RunDeath;

import org.jspecify.annotations.Nullable;

/**
 * What a player's death does to the run, free of Minecraft classes so it is unit tested. The death is
 * ignored entirely when the run isn't ACTIVE, a reset is already running, or the player is in the Death Hall.
 * That makes it once per run: a second death in the same tick, a death during the countdown and a death in the
 * hall all change nothing. Otherwise the run is ended (one history entry) and then the reset is requested.
 */
public final class DeathTrigger {
	/** The reset controller, as the death trigger sees it. */
	public interface Reset {
		boolean isRunning();

		/** Starts a death reset (not cancellable); false if it was refused. */
		boolean request(RunDeath death);
	}

	public enum Outcome { RESET_STARTED, RESET_REFUSED, IGNORED_NO_ACTIVE_RUN, IGNORED_RESET_RUNNING, IGNORED_IN_DEATH_HALL }

	private DeathTrigger() {
	}

	/**
	 * The death message to record: vanilla's chat text captured at the start of die(), or "<name> died" when it
	 * is missing or blank (the caller logs a warning then).
	 */
	public static String messageText(@Nullable String captured, String name) {
		return captured == null || captured.isBlank() ? fallbackMessage(name) : captured;
	}

	public static String fallbackMessage(String name) {
		return name + " died";
	}

	public static Outcome onDeath(RunTracker tracker, Reset reset, RunDeath death, boolean inDeathHall, long dayTime) {
		if (tracker.current() == null || !tracker.current().isActive()) {
			return Outcome.IGNORED_NO_ACTIVE_RUN;
		}
		if (reset.isRunning()) {
			return Outcome.IGNORED_RESET_RUNNING;
		}
		if (inDeathHall) {
			return Outcome.IGNORED_IN_DEATH_HALL;
		}
		tracker.endRun("death", death, dayTime);
		return reset.request(death) ? Outcome.RESET_STARTED : Outcome.RESET_REFUSED;
	}
}
