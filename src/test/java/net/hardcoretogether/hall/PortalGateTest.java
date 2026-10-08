package net.hardcoretogether.hall;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalGateTest {
	@Test
	void startsAfterThreeSecondsWithEveryoneInside() {
		PortalGate gate = new PortalGate();
		assertEquals(PortalGate.Phase.NOT_READY, gate.tick(false, 1, 1).phase());
		assertEquals(PortalGate.Phase.WAITING, gate.tick(true, 2, 1).phase());
		PortalGate.Status first = gate.tick(true, 2, 2);
		assertEquals(PortalGate.Phase.COUNTDOWN, first.phase());
		assertEquals(3, first.secondsLeft());
		int ticks = 1;
		PortalGate.Status s = first;
		while (s.phase() == PortalGate.Phase.COUNTDOWN) {
			s = gate.tick(true, 2, 2);
			ticks++;
		}
		assertEquals(PortalGate.Phase.START, s.phase());
		assertEquals(PortalGate.COUNTDOWN_TICKS, ticks);
	}

	@Test
	void cancelsWhenSomeoneStepsOutOrJoinsOrTheWorldIsNotReady() {
		PortalGate gate = new PortalGate();
		gate.tick(true, 1, 1);
		PortalGate.Status out = gate.tick(true, 1, 0);
		assertTrue(out.cancelled());
		assertEquals(PortalGate.Phase.WAITING, out.phase());
		assertEquals(3, gate.tick(true, 1, 1).secondsLeft(), "starts again from 3");
		assertTrue(gate.tick(true, 2, 1).cancelled(), "a new player joined");
		gate.tick(true, 2, 2);
		assertTrue(gate.tick(false, 2, 2).cancelled(), "not ready any more");
	}

	@Test
	void aPlayerLeavingIsJustARecount() {
		PortalGate gate = new PortalGate();
		gate.tick(true, 2, 1);
		// The player outside the portal disconnects: everyone left is inside, the countdown begins.
		PortalGate.Status s = gate.tick(true, 1, 1);
		assertEquals(PortalGate.Phase.COUNTDOWN, s.phase());
		// Everyone leaves: not ready (nobody online).
		assertTrue(gate.tick(true, 0, 0).cancelled());
	}
}
