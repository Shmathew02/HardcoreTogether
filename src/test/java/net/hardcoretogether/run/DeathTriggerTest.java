package net.hardcoretogether.run;

import net.hardcoretogether.data.CurrentRun;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunHistoryEntry;
import net.hardcoretogether.data.RunStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The death trigger, command and dry-run resets, and run start after a reset. */
class DeathTriggerTest {
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final RunDeath ALEX_DIES = new RunDeath(ALEX, "Alex", "Alex was slain by Phantom", "minecraft:overworld", 12, 64, -40);
	private static final RunDeath SAM_DIES = new RunDeath(SAM, "Sam", "Sam fell from a high place", "minecraft:the_nether", 1, 2, 3);
	private static final int WORLD = 5;

	@TempDir
	Path dir;

	private long nanos = 1_000_000_000_000L;

	private RunTracker tracker() {
		return new RunTracker(new RunStore(dir), () -> nanos, () -> 1_700_000_000_000L, 60_000_000_000L);
	}

	/** Counts requests; once one is accepted, the reset is running (like ResetController's COUNTDOWN). */
	private static class FakeReset implements DeathTrigger.Reset {
		boolean running;
		int requests;

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public boolean request(RunDeath death) {
			requests++;
			running = true;
			return true;
		}
	}

	@Test
	void sameTickDoubleDeathGivesOneHistoryEntryAndOneResetRequest() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		FakeReset reset = new FakeReset();

		assertEquals(DeathTrigger.Outcome.RESET_STARTED, DeathTrigger.onDeath(t, reset, ALEX_DIES, false, 100));
		assertEquals(DeathTrigger.Outcome.IGNORED_NO_ACTIVE_RUN, DeathTrigger.onDeath(t, reset, SAM_DIES, false, 100));

		assertEquals(1, reset.requests);
		assertEquals(1, t.history().size());
		RunHistoryEntry e = t.history().get(0);
		assertEquals("death", e.endKind());
		assertEquals(ALEX_DIES, e.death());
		assertEquals(ALEX_DIES, new RunStore(dir).loadHistory().get(0).death());
	}

	@Test
	void secondDeathIsIgnoredEvenIfTheResetWasNotYetRunning() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		FakeReset reset = new FakeReset() {
			@Override
			public boolean request(RunDeath death) {
				requests++;
				return true; // reset not marked running: the ENDED run alone must stop the second death
			}
		};
		DeathTrigger.onDeath(t, reset, ALEX_DIES, false, 0);
		DeathTrigger.onDeath(t, reset, SAM_DIES, false, 0);
		assertEquals(1, reset.requests);
		assertEquals(1, t.history().size());
	}

	@Test
	void deathWhileEndedIsIgnored() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		t.endRun("command", null, 0);
		FakeReset reset = new FakeReset();
		assertEquals(DeathTrigger.Outcome.IGNORED_NO_ACTIVE_RUN, DeathTrigger.onDeath(t, reset, ALEX_DIES, false, 0));
		assertEquals(0, reset.requests);
		assertEquals(1, t.history().size());
		assertEquals("command", t.current().endKind());
	}

	@Test
	void deathWithNoRunIsIgnored() {
		FakeReset reset = new FakeReset();
		assertEquals(DeathTrigger.Outcome.IGNORED_NO_ACTIVE_RUN, DeathTrigger.onDeath(tracker(), reset, ALEX_DIES, false, 0));
		assertEquals(0, reset.requests);
	}

	@Test
	void deathWhileAResetIsRunningIsIgnored() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		FakeReset reset = new FakeReset();
		reset.running = true;
		assertEquals(DeathTrigger.Outcome.IGNORED_RESET_RUNNING, DeathTrigger.onDeath(t, reset, ALEX_DIES, false, 0));
		assertEquals(0, reset.requests);
		assertTrue(t.current().isActive());
		assertTrue(t.history().isEmpty());
	}

	@Test
	void deathInTheDeathHallIsIgnored() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		FakeReset reset = new FakeReset();
		assertEquals(DeathTrigger.Outcome.IGNORED_IN_DEATH_HALL, DeathTrigger.onDeath(t, reset, ALEX_DIES, true, 0));
		assertEquals(0, reset.requests);
		assertTrue(t.current().isActive());
		assertTrue(t.history().isEmpty());
	}

	@Test
	void refusedResetStillEndsTheRunOnce() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		FakeReset refusing = new FakeReset() {
			@Override
			public boolean request(RunDeath death) {
				requests++;
				return false;
			}
		};
		assertEquals(DeathTrigger.Outcome.RESET_REFUSED, DeathTrigger.onDeath(t, refusing, ALEX_DIES, false, 0));
		assertFalse(t.current().isActive());
		assertEquals(1, t.history().size());
	}

	@Test
	void commandResetEndsTheRunWithReasonCommand() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		assertTrue(t.resetCommitted(false));
		CurrentRun run = t.current();
		assertEquals(CurrentRun.Status.ENDED, run.status());
		assertEquals("command", run.endKind());
		assertNull(run.death());
		assertEquals(1, t.history().size());
		assertEquals("command", t.history().get(0).endKind());
		assertNull(t.history().get(0).death());
	}

	@Test
	void commandResetAfterADeathAddsNothing() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		DeathTrigger.onDeath(t, new FakeReset(), ALEX_DIES, false, 0);
		assertFalse(t.resetCommitted(false));
		assertEquals(1, t.history().size());
		assertEquals("death", t.current().endKind());
	}

	@Test
	void dryRunDoesNotChangeRunState() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		assertFalse(t.resetCommitted(true));
		assertTrue(t.current().isActive());
		assertTrue(t.history().isEmpty());
		assertNull(t.startRunIfNeeded(WORLD, 1L, 0));
		assertEquals(1, t.current().run());
	}

	@Test
	void endThenStartGivesTheNextRunNumberOncePerReset() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		DeathTrigger.onDeath(t, new FakeReset(), ALEX_DIES, false, 0);

		// Same world (countdown, or the reset never swapped it): no new run.
		assertNull(t.startRunIfNeeded(WORLD, 1L, 0));
		// Players sent into the new world: exactly one new run, however many are sent.
		assertEquals(2, t.startRunIfNeeded(WORLD + 1, 2L, 0).run());
		assertNull(t.startRunIfNeeded(WORLD + 1, 2L, 0));
		assertNull(t.startRunIfNeeded(WORLD + 1, 2L, 0));
		assertEquals(2, t.current().run());
		assertTrue(t.current().isActive());

		// And again with a command reset: 3, no gaps, no doubles.
		t.resetCommitted(false);
		assertEquals(3, t.startRunIfNeeded(WORLD + 2, 3L, 0).run());
		assertEquals(2, t.history().size());
		assertEquals(1, t.history().get(0).run());
		assertEquals(2, t.history().get(1).run());
	}

	@Test
	void restartWhileEndedStaysEnded() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		DeathTrigger.onDeath(t, new FakeReset(), ALEX_DIES, false, 0);
		t.shutdown();

		// Server stopped before the world was swapped: same world after the restart.
		RunTracker restarted = tracker();
		assertEquals(CurrentRun.Status.ENDED, restarted.current().status());
		assertEquals(ALEX_DIES, restarted.current().death());
		assertNull(restarted.startRunIfNeeded(WORLD, 1L, 0));
		assertEquals(CurrentRun.Status.ENDED, restarted.current().status());
		assertEquals(1, restarted.history().size());

		// Journal recovery made the new world: the run starts only when a player is sent there.
		assertEquals(2, restarted.startRunIfNeeded(WORLD + 1, 2L, 0).run());
		assertEquals(1, restarted.history().size());
	}

	@Test
	void staleActiveRunFromAnOlderWorldIsEndedOnceThenTheNextStarts() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		assertEquals(2, t.startRunIfNeeded(WORLD + 1, 2L, 0).run());
		assertEquals(1, t.history().size());
		assertEquals("command", t.history().get(0).endKind());
	}

	@Test
	void recordedDeathMessageIsTheCapturedText() {
		RunTracker t = tracker();
		t.startRun(WORLD, 1L, 0);
		String text = DeathTrigger.messageText("Alex fell from a high place", "Alex");
		RunDeath death = new RunDeath(ALEX, "Alex", text, "minecraft:overworld", 0, 70, 0);

		DeathTrigger.onDeath(t, new FakeReset(), death, false, 0);

		assertEquals("Alex fell from a high place", t.history().get(0).death().message());
		assertEquals("Alex fell from a high place", new RunStore(dir).loadHistory().get(0).death().message());
	}

	@Test
	void missingDeathMessageFallsBackToNameDied() {
		assertEquals("Alex died", DeathTrigger.messageText(null, "Alex"));
		assertEquals("Alex died", DeathTrigger.messageText("", "Alex"));
		assertEquals("Alex died", DeathTrigger.messageText("  ", "Alex"));
		assertEquals("Alex was slain by Phantom", DeathTrigger.messageText("Alex was slain by Phantom", "Alex"));
	}
}
