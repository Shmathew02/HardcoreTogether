package net.hardcoretogether.hall;

import java.util.Locale;
import java.util.Optional;

/** What a display anchor in the hall is for. The id is the name used in commands and in hall-layout.json. */
public enum AnchorType {
	RECORDS_TEXT(0xFFD700),
	LEADERBOARD_TEXT(0x55FFFF),
	CAUSES_TEXT(0xFF5555),
	RECENT_RUNS_TEXT(0x55FF55),
	HISTORY_LECTERN(0xAA7744),
	/** The run-start portal: without a portal zone set, the zone is built in front of this anchor. */
	RUN_START(0xFF55FF);

	/** Particle colour used by /ht hall anchor show. */
	private final int color;

	AnchorType(int color) {
		this.color = color;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public int color() {
		return color;
	}

	public static Optional<AnchorType> byId(String id) {
		for (AnchorType type : values()) {
			if (type.id().equals(id)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}
}
