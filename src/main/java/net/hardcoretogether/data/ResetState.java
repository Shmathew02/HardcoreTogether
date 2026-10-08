package net.hardcoretogether.data;

/** Phases of a world reset, in order. A dry run goes through DRY_RUN instead of DRAIN, SWAP, WORLD_STATE and SPAWN. */
public enum ResetState {
	IDLE,
	COUNTDOWN,
	COMMIT,
	GATHER,
	DRAIN,
	SWAP,
	WORLD_STATE,
	SPAWN,
	DRY_RUN,
	RETURN,
	DONE;

	/** From COMMIT on, a reset always runs to the end; it can't be cancelled. */
	public boolean isCommitted() {
		return ordinal() >= COMMIT.ordinal() && this != IDLE;
	}

	/** Phases where every online player must be held in the Death Hall. */
	public boolean holdsPlayers() {
		return ordinal() >= GATHER.ordinal() && ordinal() < RETURN.ordinal();
	}
}
