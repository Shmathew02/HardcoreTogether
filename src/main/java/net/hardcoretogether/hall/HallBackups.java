package net.hardcoretogether.hall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Naming and rotation of hall backups in {@code <world>/hardcore_together/hall_backups/}. Names sort by time,
 * so the newest is last. No Minecraft classes, so tests can use it; HallBuilder writes the NBT itself.
 */
public final class HallBackups {
	public static final String FOLDER = "hall_backups";
	public static final int KEEP = 10;
	private static final String EXTENSION = ".nbt";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
	private static final Pattern NAME = Pattern.compile("hall-\\d{8}-\\d{6}-\\d{3}");

	private HallBackups() {
	}

	/** Backup name (without extension) for the given time, e.g. hall-20261003-201500-123. */
	public static String nameFor(LocalDateTime time) {
		return "hall-" + STAMP.format(time);
	}

	public static boolean isBackupName(String name) {
		return NAME.matcher(name).matches();
	}

	public static Path file(Path dataFolder, String name) {
		return dataFolder.resolve(FOLDER).resolve(name + EXTENSION);
	}

	/** Backup names in the folder, oldest first. */
	public static List<String> list(Path dataFolder) throws IOException {
		Path folder = dataFolder.resolve(FOLDER);
		if (Files.notExists(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.map(p -> p.getFileName().toString())
				.filter(n -> n.endsWith(EXTENSION))
				.map(n -> n.substring(0, n.length() - EXTENSION.length()))
				.filter(HallBackups::isBackupName)
				.sorted()
				.toList();
		}
	}

	public static Optional<String> newest(Path dataFolder) throws IOException {
		List<String> names = list(dataFolder);
		return names.isEmpty() ? Optional.empty() : Optional.of(names.getLast());
	}

	/** The names to delete so only the newest {@code keep} remain. */
	public static List<String> toDelete(List<String> names, int keep) {
		List<String> sorted = new ArrayList<>(names.stream().filter(HallBackups::isBackupName).toList());
		sorted.sort(Comparator.naturalOrder());
		int excess = sorted.size() - keep;
		return excess <= 0 ? List.of() : List.copyOf(sorted.subList(0, excess));
	}

	/** Deletes all but the newest {@link #KEEP} backups; returns the names deleted. */
	public static List<String> rotate(Path dataFolder) throws IOException {
		List<String> deleted = toDelete(list(dataFolder), KEEP);
		for (String name : deleted) {
			Files.deleteIfExists(file(dataFolder, name));
		}
		return deleted;
	}
}
