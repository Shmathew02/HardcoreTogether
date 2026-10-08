package net.hardcoretogether.reset;

import org.junit.jupiter.api.Test;

import static net.hardcoretogether.reset.StuckSpectator.Fix.ADVENTURE;
import static net.hardcoretogether.reset.StuckSpectator.Fix.NONE;
import static net.hardcoretogether.reset.StuckSpectator.Fix.SURVIVAL;
import static net.hardcoretogether.reset.StuckSpectator.onJoin;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StuckSpectatorTest {
	@Test
	void spectatorWithNoResetOnItsWayIsFixed() {
		assertEquals(SURVIVAL, onJoin(true, false, false, false));
		assertEquals(ADVENTURE, onJoin(true, true, false, false));
	}

	@Test
	void deathSpectatorsWaitForTheReset() {
		// A pending reset that restarts its countdown, or a countdown or reset already running.
		assertEquals(NONE, onJoin(true, false, true, false));
		assertEquals(NONE, onJoin(true, false, false, true));
		assertEquals(NONE, onJoin(true, true, false, true));
	}

	@Test
	void otherModesAreLeftAlone() {
		assertEquals(NONE, onJoin(false, false, false, false));
		assertEquals(NONE, onJoin(false, true, false, false));
	}
}
