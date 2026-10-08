package net.hardcoretogether.reset;

import com.google.common.collect.ImmutableList;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.mixin.MinecraftServerAccessor;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/**
 * Closes the three vanilla levels, moves their folders to the trash and builds fresh ones with a new seed, the
 * same way MinecraftServer.createLevels() does at startup (phases DRAIN to WORLD_STATE).
 */
public final class WorldSwapper {
	/** The only dimensions a reset ever touches. Fixed allowlist; the Death Hall is never on it. */
	public static final List<ResourceKey<Level>> RESET_LEVELS = List.of(Level.OVERWORLD, Level.NETHER, Level.END);

	private WorldSwapper() {
	}

	/** Thrown when a reset folder fails the guard; the reset must not move or delete anything. */
	public static final class GuardException extends Exception {
		GuardException(String message) {
			super(message);
		}
	}

	// ---- Delete guard ----

	/**
	 * Works out the three folders a reset may move to the trash and checks each one: it must be exactly
	 * <world>/dimensions/minecraft/<overworld|the_nether|the_end>, and if it exists it must be a real directory,
	 * not a symlink. Any mismatch throws, so nothing is moved.
	 */
	public static Map<ResourceKey<Level>, Path> verifiedTargets(MinecraftServer server) throws GuardException {
		LevelStorageSource.LevelStorageAccess storage = ((MinecraftServerAccessor) server).hardcoreTogether$storageSource();
		Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		Path allowedParent = worldRoot.resolve("dimensions").resolve("minecraft");

		Map<ResourceKey<Level>, Path> targets = new LinkedHashMap<>();
		for (ResourceKey<Level> key : RESET_LEVELS) {
			Path target = storage.getDimensionPath(key).toAbsolutePath().normalize();
			Path expected = allowedParent.resolve(key.identifier().getPath());
			if (!"minecraft".equals(key.identifier().getNamespace())) {
				throw new GuardException("not a vanilla dimension: " + key.identifier());
			}
			if (!target.equals(expected) || !target.getParent().equals(allowedParent)) {
				throw new GuardException(key.identifier() + " resolves to " + target + ", expected " + expected);
			}
			if (Files.isSymbolicLink(target)) {
				throw new GuardException(target + " is a symbolic link");
			}
			if (Files.exists(target) && !Files.isDirectory(target)) {
				throw new GuardException(target + " is not a directory");
			}
			targets.put(key, target);
		}
		if (targets.size() != 3 || targets.values().stream().distinct().count() != 3) {
			throw new GuardException("expected exactly three distinct dimension folders, got " + targets.values());
		}
		return targets;
	}

	// ---- DRAIN ----

	/**
	 * Removes every non-player entity from the reset levels. noSave is deliberately left off: ChunkMap.tick()
	 * skips chunk unloading entirely while noSave is set, so the levels could never drain (vanilla's
	 * stopServer() clears noSave before draining for the same reason). Unloading chunks are saved as usual;
	 * the folders go to the trash after close() anyway.
	 */
	static void beginDrain(MinecraftServer server) {
		for (ResourceKey<Level> key : RESET_LEVELS) {
			ServerLevel level = server.getLevel(key);
			if (level == null) {
				continue;
			}
			List<Entity> entities = new ArrayList<>();
			level.getAllEntities().forEach(entities::add);
			for (Entity entity : entities) {
				if (!(entity instanceof Player)) {
					entity.discard();
				}
			}
		}
	}

	/** One DRAIN tick. Returns true once the reset levels have no work, no players and no pending joiners. */
	static boolean drainTick(MinecraftServer server) {
		boolean drained = JoinRouting.pendingVanillaSpawns() == 0;
		for (ResourceKey<Level> key : RESET_LEVELS) {
			ServerLevel level = server.getLevel(key);
			if (level == null) {
				continue;
			}
			level.getChunkSource().deactivateTicketsOnClosing();
			if (level.getChunkSource().chunkMap.hasWork() || !level.players().isEmpty()) {
				drained = false;
			}
		}
		return drained;
	}

	/**
	 * Second half of DRAIN, once drainTick() is true: unloading only queues each chunk's write on the level's
	 * chunk I/O worker, and close() in SWAP would wait for all of them on the server thread. This asks each
	 * worker to finish and flush its queue (vanilla's public, non-blocking ChunkMap.synchronize) and returns,
	 * per level, a future that completes with the milliseconds it took.
	 *
	 * noSave is set first: with no chunks loaded it no longer blocks any unloading, and it keeps autosave from
	 * queueing new writes into levels that are about to be thrown away. close() ignores it.
	 */
	static Map<ResourceKey<Level>, CompletableFuture<Long>> beginFlush(MinecraftServer server) {
		Map<ResourceKey<Level>, CompletableFuture<Long>> flushes = new LinkedHashMap<>();
		for (ResourceKey<Level> key : RESET_LEVELS) {
			ServerLevel level = server.getLevel(key);
			if (level == null) {
				continue;
			}
			level.noSave = true;
			long start = System.nanoTime();
			flushes.put(key, level.getChunkSource().chunkMap.synchronize(true).handle((ignored, error) -> millisSince(start)));
		}
		return flushes;
	}

	// ---- SWAP ----

	/**
	 * Closes the three vanilla levels and renames their folders into the trash, installs the new seed and builds
	 * new levels in their place, all in one tick. The trash is deleted later off the server thread (TrashBin).
	 * The levels map keeps its original order. Rename errors are logged; the new levels are built regardless,
	 * because the server can't run without an overworld. Returns the trash folders to delete and how long
	 * each step took.
	 */
	static SwapResult swap(MinecraftServer server, long seed, int run) {
		long swapStart = System.nanoTime();
		MinecraftServerAccessor access = (MinecraftServerAccessor) server;
		Map<ResourceKey<Level>, ServerLevel> levels = access.hardcoreTogether$levels();
		List<ResourceKey<Level>> order = new ArrayList<>(levels.keySet());
		List<Path> trash = new ArrayList<>();
		Map<ResourceKey<Level>, long[]> timings = new LinkedHashMap<>();
		RESET_LEVELS.forEach(key -> timings.put(key, new long[3]));

		for (ResourceKey<Level> key : RESET_LEVELS) {
			ServerLevel old = levels.get(key);
			if (old == null) {
				continue;
			}
			ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, old);
			levels.remove(key);
			long t = System.nanoTime();
			try {
				old.close();
			} catch (IOException e) {
				HardcoreTogether.LOGGER.error("Error closing {}", key.identifier(), e);
			}
			timings.get(key)[0] = millisSince(t);
			t = System.nanoTime();
			try {
				Path moved = TrashBin.moveToTrash(server, key, run);
				if (moved != null) {
					trash.add(moved);
				}
			} catch (GuardException | IOException e) {
				HardcoreTogether.LOGGER.error("Could not move the {} folder to the trash; old data may remain", key.identifier(), e);
			}
			timings.get(key)[1] = millisSince(t);
		}

		installSeed(server, seed);
		// Before the new levels exist: ServerLevel's constructor copies the global weather (prepareWeather),
		// so building them while the old world was raining would start them at full rain and fade it out
		// over about 4 s, right when players return. WORLD_STATE clears it again as a second check.
		clearWeather(server);

		Map<ResourceKey<Level>, ServerLevel> fresh = createLevels(server, timings);
		Map<ResourceKey<Level>, ServerLevel> rebuilt = new LinkedHashMap<>();
		for (ResourceKey<Level> key : order) {
			rebuilt.put(key, fresh.containsKey(key) ? fresh.get(key) : levels.get(key));
		}
		levels.clear();
		levels.putAll(rebuilt);
		for (ServerLevel level : fresh.values()) {
			level.setRainLevel(0);
			level.setThunderLevel(0);
			ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);
		}
		server.updateMobSpawningFlags();
		server.getWorldData().overworldData().setInitialized(false);

		long total = millisSince(swapStart);
		long accounted = 0;
		StringBuilder breakdown = new StringBuilder();
		for (var entry : timings.entrySet()) {
			long[] ms = entry.getValue();
			accounted += ms[0] + ms[1] + ms[2];
			breakdown.append(entry.getKey().identifier().getPath()).append(" close ").append(ms[0])
				.append(" / rename ").append(ms[1]).append(" / create ").append(ms[2]).append(" ms; ");
		}
		breakdown.append("other ").append(total - accounted).append(" ms");
		return new SwapResult(trash, total, breakdown.toString());
	}

	/** What SWAP did: the trash folders to delete, its total duration and the per-dimension breakdown. */
	record SwapResult(List<Path> trash, long millis, String breakdown) {
	}

	private static long millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	/** Puts the new seed into the server's WorldGenSettings and its saved copy (data/minecraft/world_gen_settings.dat). */
	public static void installSeed(MinecraftServer server, long seed) {
		WorldGenSettings current = server.getWorldGenSettings();
		WorldOptions options = current.options().withSeed(OptionalLong.of(seed));
		WorldGenSettings updated = new WorldGenSettings(options, current.dimensions());
		((MinecraftServerAccessor) server).hardcoreTogether$setWorldGenSettings(updated);
		server.getDataStorage().set(WorldGenSettings.TYPE, updated);
		updated.setDirty();
	}

	/** Builds the three vanilla levels exactly as MinecraftServer.createLevels() does. */
	private static Map<ResourceKey<Level>, ServerLevel> createLevels(MinecraftServer server, Map<ResourceKey<Level>, long[]> timings) {
		MinecraftServerAccessor access = (MinecraftServerAccessor) server;
		WorldData worldData = server.getWorldData();
		ServerLevelData overworldData = worldData.overworldData();
		boolean isDebug = worldData.isDebugWorld();
		long biomeZoomSeed = BiomeManager.obfuscateSeed(server.getWorldGenSettings().options().seed());
		var stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);

		Map<ResourceKey<Level>, ServerLevel> created = new LinkedHashMap<>();
		for (ResourceKey<Level> key : RESET_LEVELS) {
			LevelStem stem = stems.getValue(ResourceKey.create(Registries.LEVEL_STEM, key.identifier()));
			if (stem == null) {
				continue;
			}
			long t = System.nanoTime();
			ServerLevel level;
			if (key == Level.OVERWORLD) {
				List<CustomSpawner> spawners = ImmutableList.of(
					new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(), new WanderingTraderSpawner(server.getDataStorage()));
				level = new ServerLevel(server, access.hardcoreTogether$executor(), access.hardcoreTogether$storageSource(),
					overworldData, key, stem, isDebug, biomeZoomSeed, spawners, true);
			} else {
				level = new ServerLevel(server, access.hardcoreTogether$executor(), access.hardcoreTogether$storageSource(),
					new DerivedLevelData(worldData, overworldData), key, stem, isDebug, biomeZoomSeed, ImmutableList.of(), false);
			}
			level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
			server.getPlayerList().addWorldborderListener(level);
			created.put(key, level);
			timings.get(key)[2] = millisSince(t);
		}
		return created;
	}

	// ---- WORLD_STATE ----

	/**
	 * Global weather as in a new world: clear, not raining or thundering, all timers at 0 (vanilla's
	 * WeatherData defaults), so the weather cycle picks fresh durations on its next tick.
	 */
	static void clearWeather(MinecraftServer server) {
		server.setWeatherParameters(0, 0, false, false);
	}

	/** Resets the global state that belongs to the old world: weather (again, see swap) and the clocks. */
	static void resetWorldState(MinecraftServer server) {
		clearWeather(server);
		var clocks = server.registryAccess();
		server.clockManager().setTotalTicks(clocks.getOrThrow(WorldClocks.OVERWORLD), 0L);
		server.clockManager().setTotalTicks(clocks.getOrThrow(WorldClocks.THE_END), 0L);
		server.forceGameTimeSynchronization();
	}

	// ---- SPAWN ----

	/** Picks the world spawn with vanilla's own rules (setInitialSpawn), then publishes it to the server. */
	static void placeWorldSpawn(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		ServerLevelData levelData = server.getWorldData().overworldData();
		MinecraftServerAccessor.hardcoreTogether$setInitialSpawn(overworld, levelData,
			server.getWorldGenSettings().options().generateBonusChest(), server.getWorldData().isDebugWorld(), server.getLevelLoadListener());
		levelData.setInitialized(true);
		((MinecraftServerAccessor) server).hardcoreTogether$updateEffectiveRespawnData();
	}
}
