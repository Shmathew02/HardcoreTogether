package net.hardcoretogether.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** File helpers shared by every file in the data folder. No Minecraft classes, so unit tests can use it. */
public final class JsonFiles {
	private JsonFiles() {
	}

	/** Write to a temp file, then move it into place so a crash never leaves a half-written file. */
	public static void writeAtomically(Path target, String content) throws IOException {
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(temp, content + System.lineSeparator());
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Renames an unreadable file to "<name>.corrupt-<epoch millis>" so it is kept for inspection. */
	public static Path moveAside(Path file, long epochMillis) throws IOException {
		Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + epochMillis);
		Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
		return aside;
	}
}
