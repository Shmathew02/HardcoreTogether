package net.hardcoretogether.hall;

import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunHistoryEntry;

import net.minecraft.network.chat.Component;

import java.util.List;

/** The text on a statue's plaque: all of its wording and style, kept apart from the entities. */
public final class StatuePlaque {
	private StatuePlaque() {
	}

	/** Exactly four lines: "Run #N", the player, the cause of death, "Survived <run length>". */
	public static List<String> lines(RunHistoryEntry run) {
		RunDeath death = run.death();
		String name = death == null ? "?" : death.name();
		String cause = death == null ? "" : cause(death.message(), name);
		return List.of("Run #" + run.run(), name, cause, "Survived " + runLength(run.finalActiveMillis()));
	}

	/** The formatted plaque text (one component, lines separated by newlines). */
	public static Component text(RunHistoryEntry run) {
		return Component.literal(String.join("\n", lines(run)));
	}

	/**
	 * The death message without the dying player's own name at the very start (and a "was " after it), first
	 * letter capitalised: "Alex was slain by Zombie" -> "Slain by Zombie". Other names stay. A message that does
	 * not start with the name is returned unchanged.
	 */
	static String cause(String message, String name) {
		if (name.isEmpty() || !message.startsWith(name + " ")) {
			return message;
		}
		String rest = message.substring(name.length() + 1).stripLeading();
		if (rest.startsWith("was ")) {
			rest = rest.substring(4).stripLeading();
		}
		return rest.isEmpty() ? message : Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
	}

	/** Run length without leading zero units: "38s", "12m 5s", "1h 3m 20s" (hours not wrapped into days). */
	static String runLength(long millis) {
		long s = Math.max(0, millis) / 1000;
		long h = s / 3600, m = s % 3600 / 60, sec = s % 60;
		if (h > 0) {
			return h + "h " + m + "m " + sec + "s";
		}
		return m > 0 ? m + "m " + sec + "s" : sec + "s";
	}
}
