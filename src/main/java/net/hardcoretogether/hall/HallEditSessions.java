package net.hardcoretogether.hall;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who is in hall edit mode. Memory only, never written to disk: a restart starts with nobody editing, and a
 * disconnect ends the player's session. Also remembers editors a real reset skipped in RETURN, so they get
 * their fresh start when edit mode ends. No Minecraft classes, so tests can use it.
 */
public final class HallEditSessions {
	private final Set<UUID> editing = new HashSet<>();
	private final Set<UUID> missedReset = new HashSet<>();

	/** Returns false if the player was already editing. */
	public boolean start(UUID player) {
		return editing.add(player);
	}

	/** Ends the session; returns false if the player wasn't editing. */
	public boolean stop(UUID player) {
		return editing.remove(player);
	}

	public boolean isEditing(UUID player) {
		return editing.contains(player);
	}

	public Set<UUID> editors() {
		return Set.copyOf(editing);
	}

	/** A real reset skipped this editor; they get a fresh start when edit mode ends. */
	public void markMissedReset(UUID player) {
		missedReset.add(player);
	}

	/** Returns and clears whether a reset skipped this player while editing. */
	public boolean takeMissedReset(UUID player) {
		return missedReset.remove(player);
	}

	/** Disconnect: the session ends. A missed reset is handled by the join path (the player's last reset is older). */
	public boolean disconnect(UUID player) {
		missedReset.remove(player);
		return editing.remove(player);
	}

	/** Server stop: nothing carries over to the next start. */
	public void clear() {
		editing.clear();
		missedReset.clear();
	}
}
