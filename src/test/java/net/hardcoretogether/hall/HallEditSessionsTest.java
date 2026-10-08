package net.hardcoretogether.hall;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallEditSessionsTest {
	private final UUID playerOne = UUID.randomUUID();
	private final UUID other = UUID.randomUUID();

	@Test
	void editModeIsPerPlayer() {
		HallEditSessions sessions = new HallEditSessions();
		assertTrue(sessions.start(playerOne));
		assertFalse(sessions.start(playerOne));
		assertTrue(sessions.isEditing(playerOne));
		assertFalse(sessions.isEditing(other));
		assertTrue(sessions.stop(playerOne));
		assertFalse(sessions.stop(playerOne));
	}

	@Test
	void disconnectEndsEditMode() {
		HallEditSessions sessions = new HallEditSessions();
		sessions.start(playerOne);
		sessions.markMissedReset(playerOne);
		assertTrue(sessions.disconnect(playerOne));
		assertFalse(sessions.isEditing(playerOne));
		// The join path handles the missed reset, so nothing is left over for a later session.
		assertFalse(sessions.takeMissedReset(playerOne));
	}

	@Test
	void restartStartsWithNobodyEditing() {
		HallEditSessions sessions = new HallEditSessions();
		sessions.start(playerOne);
		sessions.start(other);
		sessions.clear(); // server stop
		assertTrue(sessions.editors().isEmpty());
		// A fresh instance (next server start) never sees earlier sessions: nothing is read from disk.
		assertTrue(new HallEditSessions().editors().isEmpty());
	}

	@Test
	void missedResetIsTakenOnce() {
		HallEditSessions sessions = new HallEditSessions();
		sessions.start(playerOne);
		sessions.markMissedReset(playerOne);
		assertTrue(sessions.stop(playerOne));
		assertTrue(sessions.takeMissedReset(playerOne));
		assertFalse(sessions.takeMissedReset(playerOne));
	}
}
