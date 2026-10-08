package net.hardcoretogether.hall;

/**
 * The portal start rule, without Minecraft classes: once the portal is READY and every online player is
 * inside the portal zone, a 3-second countdown runs; it cancels as soon as that stops being true (someone steps
 * out, a new player joins, the portal stops being ready) and START fires once when it reaches zero. A player
 * leaving the server just changes the counts; the rule is checked again on the next tick.
 */
public final class PortalGate {
	public static final int COUNTDOWN_TICKS = 60;

	public enum Phase { NOT_READY, WAITING, COUNTDOWN, START }

	/** What this tick means; secondsLeft is set during COUNTDOWN (3, 2, 1); cancelled when a countdown just stopped. */
	public record Status(Phase phase, int inside, int online, int secondsLeft, boolean cancelled) {
	}

	private int remaining = -1;

	public Status tick(boolean ready, int online, int inside) {
		boolean all = ready && online > 0 && inside >= online;
		if (!all) {
			boolean cancelled = remaining >= 0;
			remaining = -1;
			return new Status(ready ? Phase.WAITING : Phase.NOT_READY, inside, online, 0, cancelled);
		}
		if (remaining < 0) {
			remaining = COUNTDOWN_TICKS;
		}
		remaining--;
		if (remaining <= 0) {
			remaining = -1;
			return new Status(Phase.START, inside, online, 0, false);
		}
		return new Status(Phase.COUNTDOWN, inside, online, (remaining + 19) / 20, false);
	}

	public boolean counting() {
		return remaining >= 0;
	}

	public void reset() {
		remaining = -1;
	}
}
