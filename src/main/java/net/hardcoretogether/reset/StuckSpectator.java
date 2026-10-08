package net.hardcoretogether.reset;

/**
 * The join safety net: a player in Spectator is only meant to be there while a death reset is on its way. If none
 * is (nothing pending, no reset running), they go to the mode for where they are. No Minecraft classes.
 */
public final class StuckSpectator {
	public enum Fix { NONE, ADVENTURE, SURVIVAL }

	private StuckSpectator() {
	}

	public static Fix onJoin(boolean spectator, boolean inDeathHall, boolean resetPending, boolean resetRunning) {
		if (!spectator || resetPending || resetRunning) {
			return Fix.NONE;
		}
		return inDeathHall ? Fix.ADVENTURE : Fix.SURVIVAL;
	}
}
