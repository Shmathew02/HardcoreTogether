package net.hardcoretogether.hall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallBackupsTest {
	@TempDir
	Path data;

	private static String name(int minute) {
		return HallBackups.nameFor(LocalDateTime.of(2026, 10, 3, 12, minute, 0));
	}

	@Test
	void namesSortByTime() {
		assertEquals("hall-20261003-120500-000", name(5));
		assertTrue(name(5).compareTo(name(10)) < 0);
		assertTrue(HallBackups.isBackupName(name(5)));
	}

	@Test
	void keepsTheNewestTen() {
		List<String> names = new ArrayList<>();
		for (int m = 13; m >= 0; m--) {
			names.add(name(m)); // unsorted on purpose
		}
		assertEquals(List.of(name(0), name(1), name(2), name(3)), HallBackups.toDelete(names, 10));
		assertEquals(List.of(), HallBackups.toDelete(names.subList(0, 10), 10));
	}

	@Test
	void rotateDeletesOldFilesOnly() throws IOException {
		Path folder = Files.createDirectories(data.resolve(HallBackups.FOLDER));
		for (int m = 0; m < 12; m++) {
			Files.writeString(HallBackups.file(data, name(m)), "x");
		}
		Files.writeString(folder.resolve("notes.txt"), "keep me");
		Files.writeString(folder.resolve("hall-manual.nbt"), "keep me too");

		assertEquals(List.of(name(0), name(1)), HallBackups.rotate(data));
		List<String> left = HallBackups.list(data);
		assertEquals(10, left.size());
		assertEquals(name(2), left.getFirst());
		assertEquals(name(11), HallBackups.newest(data).orElseThrow());
		assertTrue(Files.exists(folder.resolve("notes.txt")));
		assertTrue(Files.exists(folder.resolve("hall-manual.nbt")));
	}
}
