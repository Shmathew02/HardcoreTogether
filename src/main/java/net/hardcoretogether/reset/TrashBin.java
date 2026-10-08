package net.hardcoretogether.reset;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.HtWorldData;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Old dimension folders are renamed into <world>/hardcore_together/trash/ during SWAP (an instant rename on the
 * same filesystem) and deleted later on one background thread, so the server thread never waits on thousands of
 * region file deletes.
 *
 * Two guards: a rename source must be one of the three vanilla dimension folders (WorldSwapper.verifiedTargets),
 * and the background delete only ever deletes a direct child of the trash folder whose name matches
 * <dimension>-run<N>-<timestamp>. Anything else is refused with an ERROR line.
 */
public final class TrashBin {
	private static final String TRASH = "trash";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'");
	private static final Pattern NAME = Pattern.compile("(overworld|the_nether|the_end)-run\\d{1,9}-\\d{8}T\\d{9}Z");

	/** Folders queued or being deleted, so startup cleanup never queues the same folder twice. */
	private static final Set<Path> queued = ConcurrentHashMap.newKeySet();
	private static @Nullable ExecutorService worker;

	private TrashBin() {
	}

	/** <world>/hardcore_together/trash, worked out from the world root rather than from anything on disk. */
	static Path trashRoot(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
			.resolve(HtWorldData.FOLDER.id()).resolve(TRASH);
	}

	/**
	 * Renames one reset dimension's folder into the trash. Returns the trash folder, or null if the dimension
	 * had no folder. Throws if the source fails the delete guard or the rename isn't possible; nothing moves then.
	 */
	static @Nullable Path moveToTrash(MinecraftServer server, ResourceKey<Level> key, int run) throws WorldSwapper.GuardException, IOException {
		Path source = WorldSwapper.verifiedTargets(server).get(key);
		if (source == null || Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		Path root = trashRoot(server);
		if (Files.isSymbolicLink(root)) {
			throw new WorldSwapper.GuardException(root + " is a symbolic link");
		}
		Files.createDirectories(root);
		String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(STAMP);
		Path target = root.resolve(key.identifier().getPath() + "-run" + run + "-" + stamp);
		// ATOMIC_MOVE: a plain rename or nothing; never a slow copy-and-delete across filesystems.
		Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		return target;
	}

	/** Queues one trash folder for deletion on the background thread. */
	static void deleteLater(MinecraftServer server, Path folder) {
		Path root = trashRoot(server);
		Path target = folder.toAbsolutePath().normalize();
		if (!queued.add(target)) {
			return;
		}
		executor().execute(() -> {
			try {
				delete(root, target);
			} finally {
				queued.remove(target);
			}
		});
	}

	/** Queues everything left in the trash, e.g. when the server stopped before a delete finished. */
	public static void cleanLeftovers(MinecraftServer server) {
		Path root = trashRoot(server);
		if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		List<Path> leftovers = new ArrayList<>();
		try (Stream<Path> children = Files.list(root)) {
			children.forEach(leftovers::add);
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("[HT trash] could not list {}", root, e);
			return;
		}
		if (!leftovers.isEmpty()) {
			HardcoreTogether.LOGGER.info("[HT trash] {} leftover folder(s) in {}; deleting in the background", leftovers.size(), root);
		}
		leftovers.forEach(folder -> deleteLater(server, folder));
	}

	/** Number of trash folders queued or being deleted right now. */
	public static int pending() {
		return queued.size();
	}

	private static synchronized ExecutorService executor() {
		if (worker == null) {
			worker = Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "HT trash cleaner");
				// A delete cut short by shutdown is finished by cleanLeftovers on the next start.
				thread.setDaemon(true);
				return thread;
			});
		}
		return worker;
	}

	// ---- Background thread only ----

	private static void delete(Path root, Path target) {
		String refusal = checkDeletable(root, target);
		if (refusal != null) {
			HardcoreTogether.LOGGER.error("[HT trash] refusing to delete {}: {}", target, refusal);
			return;
		}
		HardcoreTogether.LOGGER.info("[HT trash] deleting {}", target.getFileName());
		long start = System.nanoTime();
		long[] files = new long[1];
		try {
			Files.walkFileTree(target, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					files[0]++;
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					if (exc != null && !(exc instanceof NoSuchFileException)) {
						throw exc;
					}
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
			HardcoreTogether.LOGGER.info("[HT trash] deleted {} ({} files in {} ms)", target.getFileName(), files[0],
				(System.nanoTime() - start) / 1_000_000);
		} catch (NoSuchFileException e) {
			HardcoreTogether.LOGGER.info("[HT trash] {} was already gone", target.getFileName());
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("[HT trash] could not finish deleting {} ({} files deleted); retrying on next start",
				target.getFileName(), files[0], e);
		}
	}

	/** The delete guard. Returns why the folder may not be deleted, or null if it may. */
	private static @Nullable String checkDeletable(Path root, Path target) {
		if (!target.equals(target.toAbsolutePath().normalize())) {
			return "path is not absolute and normalized";
		}
		if (!root.equals(target.getParent())) {
			return "not directly inside " + root;
		}
		if (!NAME.matcher(target.getFileName().toString()).matches()) {
			return "name doesn't match <dimension>-run<N>-<timestamp>";
		}
		if (Files.isSymbolicLink(root) || Files.isSymbolicLink(root.getParent())) {
			return "the trash or data folder is a symbolic link";
		}
		if (Files.isSymbolicLink(target)) {
			return "it is a symbolic link";
		}
		if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
			return Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? "not a directory" : "it no longer exists";
		}
		try {
			// Compared with the trash folder's own real path, so a symlink above the world folder (a mount) is fine.
			Path expected = root.toRealPath().resolve(target.getFileName());
			if (!target.toRealPath().equals(expected)) {
				return "real path is " + target.toRealPath() + ", expected " + expected;
			}
		} catch (IOException e) {
			return "could not resolve its real path: " + e.getMessage();
		}
		return null;
	}
}
