package net.hardcoretogether.hall;

import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunHistoryEntry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatuePlaqueTest {
	@Test
	void causeDropsTheDyingPlayersNameAndWas() {
		assertEquals("Drowned", StatuePlaque.cause("PlayerOne drowned", "PlayerOne"));
		assertEquals("Blown up by Creeper", StatuePlaque.cause("X was blown up by Creeper", "X"));
		assertEquals("Slain by Zombie", StatuePlaque.cause("X was slain by Zombie", "X"));
		assertEquals("Fell from a high place", StatuePlaque.cause("X fell from a high place", "X"));
	}

	@Test
	void causeKeepsOtherNamesAndOtherMessages() {
		assertEquals("Slain by Steve using [Sword]", StatuePlaque.cause("Alex was slain by Steve using [Sword]", "Alex"));
		assertEquals("Shot by Alex", StatuePlaque.cause("Alex was shot by Alex", "Alex"));
		// Only the name at the very start, as a whole word.
		assertEquals("Steve was slain by Alex", StatuePlaque.cause("Steve was slain by Alex", "Alex"));
		assertEquals("Alexander drowned", StatuePlaque.cause("Alexander drowned", "Alex"));
		assertEquals("Died (test death)", StatuePlaque.cause("TestDummyA died (test death)", "TestDummyA"));
		assertEquals("Alex", StatuePlaque.cause("Alex", "Alex"));
	}

	@Test
	void runLengthDropsLeadingZeroUnits() {
		assertEquals("0s", StatuePlaque.runLength(0));
		assertEquals("38s", StatuePlaque.runLength(38_999));
		assertEquals("12m 5s", StatuePlaque.runLength((12 * 60 + 5) * 1000L));
		assertEquals("1h 3m 20s", StatuePlaque.runLength((3600 + 3 * 60 + 20) * 1000L));
		assertEquals("1h 0m 0s", StatuePlaque.runLength(3600_000L));
		assertEquals("27h 0m 1s", StatuePlaque.runLength((27 * 3600 + 1) * 1000L));
	}

	@Test
	void exactlyFourLinesWithoutTheDay() {
		UUID id = UUID.randomUUID();
		RunDeath death = new RunDeath(id, "PlayerOne", "PlayerOne drowned", "minecraft:overworld", 0, 64, 0);
		RunHistoryEntry run = new RunHistoryEntry(2, 1, 0, 0, 38_000, 1, "death", death, Map.of(id, 38_000L), Map.of(id, "PlayerOne"));
		assertEquals(List.of("Run #2", "PlayerOne", "Drowned", "Survived 38s"), StatuePlaque.lines(run));
	}
}
