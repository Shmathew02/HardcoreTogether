package net.hardcoretogether.hall;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.HtWorldData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the upper hall's redstone lamps lit without redstone: every lamp in the hall region from the hall spawn's
 * block y + {@code always_lit_offset} up to the top of the region, except lamps inside an arrow box (those belong to
 * {@link ArrowLights}). Lit is set directly without neighbour or shape updates, and pending block ticks on those lamps
 * are cleared: placing a structure sends neighbour updates, and an unpowered lit lamp then schedules a tick that
 * turns it off (saved with the chunk, it fires when the chunk ticks again). {@code RedstoneLampBlockMixin} also
 * refuses that tick for these lamps. The lamp set is found by a full pass right after the hall is loaded or placed
 * (server start, fresh install), on /ht hall lamps rescan and when the spawn, the arrow boxes or the hall blocks
 * (restore) change; no polling. When a hall chunk loads or starts ticking, its lamps are lit again on the next tick.
 */
public final class AlwaysLitLamps {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	/** Always-lit lamps by chunk (ChunkPos.toLong). */
	private static Map<Long, List<BlockPos>> byChunk = Map.of();
	private static Set<BlockPos> all = Set.of();
	private static int zoneMinY, zoneMaxY;
	private static final Set<Long> loadedChunks = new HashSet<>();

	private AlwaysLitLamps() {
	}

	static void register() {
		// The start pass is run by DeathHall once the hall is placed and its spawn is known.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			byChunk = Map.of();
			all = Set.of();
			loadedChunks.clear();
		});
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (level.dimension() == DeathHall.KEY && byChunk.containsKey(chunk.getPos().pack())) {
				loadedChunks.add(chunk.getPos().pack()); // lit on the next tick, not inside chunk loading
			}
		});
		// Ticks saved with a chunk are only unpacked when it starts ticking, so check again then.
		ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register((level, chunk, oldStatus, newStatus) -> {
			if (level.dimension() == DeathHall.KEY && newStatus.isOrAfter(FullChunkStatus.BLOCK_TICKING)
				&& byChunk.containsKey(chunk.getPos().pack())) {
				loadedChunks.add(chunk.getPos().pack());
			}
		});
	}

	public static int count() {
		return all.size();
	}

	public static int zoneMinY() {
		return zoneMinY;
	}

	public static int zoneMaxY() {
		return zoneMaxY;
	}

	/** True if the mod keeps this hall lamp lit (used by export so the shipped hall has them lit). */
	public static boolean contains(BlockPos pos) {
		return all.contains(pos);
	}

	static void tick(MinecraftServer server) {
		if (loadedChunks.isEmpty()) {
			return;
		}
		ServerLevel hall = DeathHall.level(server);
		if (hall != null) {
			int relit = 0;
			for (long chunk : loadedChunks) {
				if (hall.getChunkSource().hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
					relit += light(hall, byChunk.getOrDefault(chunk, List.of()));
				}
			}
			if (relit > 0) {
				HardcoreTogether.LOGGER.info("[HT lamps] chunk load: {} always-lit lamp(s) relit", relit);
			}
		}
		loadedChunks.clear();
	}

	public record ScanResult(int lamps, int lit, int released, int skippedInArrows, int minY, int maxY) {
	}

	/**
	 * Finds every always-lit lamp again and lights it. Lamps that were always-lit before but are no longer in the
	 * zone (spawn moved up) go back to unlit, unless an arrow box now controls them.
	 */
	public static ScanResult rescan(MinecraftServer server, String why) {
		ServerLevel hall = DeathHall.level(server);
		HtWorldData data = HardcoreTogether.worldData();
		if (hall == null || data == null) {
			return new ScanResult(0, 0, 0, 0, 0, 0);
		}
		List<BlockPos[]> arrowBoxes = new ArrayList<>();
		try {
			HallLayout layout = HallLayout.load(data.folder());
			for (int arrow = 1; arrow <= HallLayout.ARROWS; arrow++) {
				ArrowLights.box(layout, arrow).ifPresent(arrowBoxes::add);
			}
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("[HT lamps] could not read the hall layout; arrow boxes not excluded", e);
		}
		BlockPos lo = HallBuilder.regionMin(), hi = HallBuilder.regionMax();
		int minY = Math.max((int) Math.floor(DeathHall.spawnPoint(hall).pos().y) + HardcoreTogether.config().alwaysLitOffset(), lo.getY());
		int maxY = Math.min(hi.getY(), hall.getMaxY());
		// Ticket every hall chunk for the pass so none unloads half way; released at the end.
		List<ChunkPos> ticketed = new ArrayList<>();
		for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) {
			for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) {
				ChunkPos cp = new ChunkPos(cx, cz);
				hall.getChunkSource().addTicketWithRadius(TicketType.UNKNOWN, cp, 0);
				ticketed.add(cp);
			}
		}
		Map<Long, List<BlockPos>> found = new HashMap<>();
		Set<BlockPos> foundAll = new HashSet<>();
		int skipped = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4 && minY <= maxY; cx++) {
			for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) {
				LevelChunk chunk = hall.getChunk(cx, cz);
				for (int sy = hall.getSectionIndex(minY); sy <= hall.getSectionIndex(maxY); sy++) {
					LevelChunkSection section = chunk.getSection(sy);
					if (section.hasOnlyAir() || !section.maybeHas(s -> s.is(Blocks.REDSTONE_LAMP))) {
						continue;
					}
					int baseY = hall.getSectionYFromSectionIndex(sy) << 4;
					for (int y = Math.max(baseY, minY); y <= Math.min(baseY + 15, maxY); y++) {
						for (int x = Math.max(cx << 4, lo.getX()); x <= Math.min((cx << 4) + 15, hi.getX()); x++) {
							for (int z = Math.max(cz << 4, lo.getZ()); z <= Math.min((cz << 4) + 15, hi.getZ()); z++) {
								if (!section.getBlockState(x & 15, y & 15, z & 15).is(Blocks.REDSTONE_LAMP)) {
									continue;
								}
								pos.set(x, y, z);
								if (inAny(arrowBoxes, pos)) {
									skipped++;
									continue;
								}
								BlockPos p = pos.immutable();
								found.computeIfAbsent(ChunkPos.pack(cx, cz), k -> new ArrayList<>()).add(p);
								foundAll.add(p);
							}
						}
					}
				}
			}
		}
		List<BlockPos> released = new ArrayList<>();
		for (BlockPos old : all) {
			if (!foundAll.contains(old) && !inAny(arrowBoxes, old)) {
				released.add(old);
			}
		}
		int unlit = set(hall, released, false);
		byChunk = found;
		all = Set.copyOf(foundAll);
		zoneMinY = minY;
		zoneMaxY = maxY;
		int lit = light(hall, foundAll);
		int nowLit = 0;
		for (BlockPos p : foundAll) {
			BlockState state = hall.getBlockState(p);
			if (state.is(Blocks.REDSTONE_LAMP) && state.getValue(RedstoneLampBlock.LIT)) {
				nowLit++;
			}
		}
		ticketed.forEach(cp -> hall.getChunkSource().removeTicketWithRadius(TicketType.UNKNOWN, cp, 0));
		HardcoreTogether.LOGGER.info("[HT lamps] rescan ({}): {} always-lit lamp(s) in y {}..{}, {} newly lit, {} released, {} in arrow boxes",
			why, all.size(), minY, maxY, lit, unlit, skipped);
		if (nowLit == all.size()) {
			HardcoreTogether.LOGGER.info("[HT lamps] {} of {} always-lit lamps lit", nowLit, all.size());
		} else {
			HardcoreTogether.LOGGER.warn("[HT lamps] {} of {} always-lit lamps lit", nowLit, all.size());
		}
		return new ScanResult(all.size(), lit, unlit, skipped, minY, maxY);
	}

	private static boolean inAny(List<BlockPos[]> boxes, BlockPos p) {
		for (BlockPos[] b : boxes) {
			if (p.getX() >= Math.min(b[0].getX(), b[1].getX()) && p.getX() <= Math.max(b[0].getX(), b[1].getX())
				&& p.getY() >= Math.min(b[0].getY(), b[1].getY()) && p.getY() <= Math.max(b[0].getY(), b[1].getY())
				&& p.getZ() >= Math.min(b[0].getZ(), b[1].getZ()) && p.getZ() <= Math.max(b[0].getZ(), b[1].getZ())) {
				return true;
			}
		}
		return false;
	}

	private static int light(ServerLevel hall, Iterable<BlockPos> lamps) {
		return set(hall, lamps, true);
	}

	private static int set(ServerLevel hall, Iterable<BlockPos> lamps, boolean lit) {
		int changed = 0;
		for (BlockPos p : lamps) {
			// A pending lamp tick would turn a lit lamp off again a few ticks later.
			hall.getBlockTicks().clearArea(new BoundingBox(p));
			BlockState state = hall.getBlockState(p);
			if (state.is(Blocks.REDSTONE_LAMP) && state.getValue(RedstoneLampBlock.LIT) != lit) {
				hall.setBlock(p, state.setValue(RedstoneLampBlock.LIT, lit), FLAGS);
				changed++;
			}
		}
		return changed;
	}
}
