package net.hardcoretogether.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The skin snapshot in death records, and the upgrade from older file formats. */
class RunDeathSkinTest {
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final RunDeath.SkinTextures SKIN = new RunDeath.SkinTextures("ewogICJ0aW1lc3RhbXAiIDog", "c2lnbmF0dXJl");

	@TempDir
	Path folder;

	private static RunDeath death(RunDeath.SkinTextures skin) {
		return new RunDeath(ALEX, "Alex", "Alex fell", "minecraft:overworld", 1, 64, 2, "minecraft:fall", null, skin);
	}

	private static RunHistoryEntry entry(int run, RunDeath death) {
		return new RunHistoryEntry(run, 7, 0, 1, 5000, 3, "death", death, Map.of(ALEX, 5000L), Map.of(ALEX, "Alex"));
	}

	@Test
	void skinSurvivesSaveAndLoad() throws IOException {
		RunStore store = new RunStore(folder);
		store.saveHistory(List.of(entry(1, death(SKIN)), entry(2, death(new RunDeath.SkinTextures("dmFsdWU=", null)))));
		List<RunHistoryEntry> loaded = store.loadHistory();
		assertEquals(SKIN, loaded.get(0).death().skin());
		assertEquals(new RunDeath.SkinTextures("dmFsdWU=", null), loaded.get(1).death().skin());

		CurrentRun run = CurrentRun.start(3, 1, 7, 0, 0);
		run.end(1, 2, "death", death(SKIN));
		store.saveCurrent(run);
		assertEquals(SKIN, store.loadCurrent().death().skin());
	}

	@Test
	void oldFilesLoadWithoutSkinAndAreUpgraded() throws IOException {
		RunStore store = new RunStore(folder);
		store.saveHistory(List.of(entry(1, death(null))));
		Path history = store.historyFile();
		// Rewrite it as a format 1 file: no skin_textures key at all.
		String v1 = Files.readString(history).replace("\"format\": " + RunStore.HISTORY_FORMAT, "\"format\": 1")
			.replace("\"skin_textures\": null,", "").replace(",\n      \"skin_textures\": null", "");
		Files.writeString(history, v1);
		assertTrue(!v1.contains("skin_textures"));

		RunHistoryEntry loaded = store.loadHistory().get(0);
		assertNull(loaded.death().skin());
		assertEquals("Alex fell", loaded.death().message());
		assertTrue(Files.exists(history.resolveSibling("run-history.json.v1.bak")));
		assertTrue(Files.readString(history).contains("\"format\": " + RunStore.HISTORY_FORMAT));

		CurrentRun run = CurrentRun.start(3, 1, 7, 0, 0);
		run.end(1, 2, "death", death(null));
		store.saveCurrent(run);
		Path current = store.currentFile();
		Files.writeString(current, Files.readString(current).replace("\"format\": " + CurrentRun.FORMAT, "\"format\": 2"));
		assertNull(store.loadCurrent().death().skin());
		assertTrue(Files.exists(current.resolveSibling("current-run.json.v2.bak")));
		assertTrue(Files.readString(current).contains("\"format\": " + CurrentRun.FORMAT));
	}
}
