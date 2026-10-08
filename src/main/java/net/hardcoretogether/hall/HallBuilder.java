package net.hardcoretogether.hall;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.HtWorldData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Places the hall once per world, exports a hall build as a structure template, and backs up and restores
 * the hall region. All of it goes through StructureTemplate in code, so the structure block's 48-block size
 * limit doesn't apply.
 *
 * Exports and backups record the build's offset from the spawn floor block under {@link #OFFSET_TAG}, so a shipped export
 * is placed exactly where it was built. Vanilla ignores the extra tag.
 */
public final class HallBuilder {
	/** Template shipped in the mod jar: data/hardcore_together/structure/death_hall.nbt. */
	private static final Identifier SHIPPED_TEMPLATE = HardcoreTogether.id("structure/death_hall.nbt");
	private static final String OFFSET_TAG = "hardcore_together_offset";
	private static final BlockPos SPAWN_FLOOR = DeathHall.ORIGIN;

	public static final String EXPORT_FOLDER = "hall_export";
	public static final String EXPORT_FILE = "death_hall.nbt";
	/** Air kept around the built blocks in an export, so placing the hall clears a little space around it. */
	public static final int EXPORT_MARGIN = 2;
	/** Layout shipped in the mod jar next to the template; copied to a fresh world's data folder. */
	private static final Identifier SHIPPED_LAYOUT = HardcoreTogether.id("hall/hall-layout.json");

	private HallBuilder() {
	}

	/** Places the hall the first time the world starts; never again after that. True if it was placed now. */
	public static boolean placeOnce(MinecraftServer server, ServerLevel hall, HtWorldData data) {
		if (data.hallBuilt()) {
			HardcoreTogether.LOGGER.info("Death Hall loaded (already built, left untouched)");
			return false;
		}

		Optional<CompoundTag> shipped = readShippedTemplate(server);
		if (shipped.isPresent()) {
			placeTemplate(server, hall, shipped.get());
			seedShippedLayout(server, data);
		} else {
			int placed = placePlatform(hall);
			HardcoreTogether.LOGGER.info("Death Hall built: no template shipped, placed the {}-block platform", placed);
		}
		data.setHallBuilt(true);
		data.trySave();
		return true;
	}

	/**
	 * Copies the shipped hall-layout.json (anchors, spawn, statue anchors, arrows; never statue assignments) into a
	 * world that has no layout yet. An existing layout is never replaced.
	 */
	private static void seedShippedLayout(MinecraftServer server, HtWorldData data) {
		if (Files.exists(data.folder().resolve(HallLayout.FILE_NAME))) {
			HardcoreTogether.LOGGER.info("[HT hall] hall layout already present; shipped layout not copied");
			return;
		}
		Optional<Resource> resource = server.getResourceManager().getResource(SHIPPED_LAYOUT);
		if (resource.isEmpty()) {
			HardcoreTogether.LOGGER.warn("[HT hall] no shipped hall layout {}; anchors and spawn start empty", SHIPPED_LAYOUT);
			return;
		}
		try (InputStream in = resource.get().open()) {
			HallLayout layout = HallLayout.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).forExport();
			layout.save(data.folder());
			HardcoreTogether.LOGGER.info("[HT hall] shipped hall layout copied: {} anchor(s), {} statue anchor(s), spawn {}", layout.anchors().size(),
				layout.statues().size(), layout.spawn().isPresent() ? "set" : "default");
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("[HT hall] could not copy the shipped hall layout {}", SHIPPED_LAYOUT, e);
		}
	}

	private static Optional<CompoundTag> readShippedTemplate(MinecraftServer server) {
		Optional<Resource> resource = server.getResourceManager().getResource(SHIPPED_TEMPLATE);
		if (resource.isEmpty()) {
			return Optional.empty();
		}
		try (InputStream in = resource.get().open()) {
			return Optional.of(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()));
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("Could not read the shipped hall template {}; using the platform", SHIPPED_TEMPLATE, e);
			return Optional.empty();
		}
	}

	private static void placeTemplate(MinecraftServer server, ServerLevel hall, CompoundTag tag) {
		StructureTemplate template = new StructureTemplate();
		template.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
		Vec3i size = template.getSize();

		BlockPos origin;
		Optional<int[]> offset = tag.getIntArray(OFFSET_TAG);
		if (offset.isPresent() && offset.get().length == 3) {
			int[] o = offset.get();
			origin = SPAWN_FLOOR.offset(o[0], o[1], o[2]);
		} else {
			origin = new BlockPos(SPAWN_FLOOR.getX() - size.getX() / 2, DeathHall.PLATFORM_Y, SPAWN_FLOOR.getZ() - size.getZ() / 2);
		}

		template.placeInWorld(hall, origin, origin, new StructurePlaceSettings(), hall.getRandom(), Block.UPDATE_CLIENTS);
		HardcoreTogether.LOGGER.info("Death Hall built from the shipped template: {}x{}x{} at {}",
			size.getX(), size.getY(), size.getZ(), origin.toShortString());
	}

	private static int placePlatform(ServerLevel hall) {
		BlockState floor = Blocks.STONE_BRICKS.defaultBlockState();
		int placed = 0;
		for (int x = -DeathHall.PLATFORM_RADIUS; x <= DeathHall.PLATFORM_RADIUS; x++) {
			for (int z = -DeathHall.PLATFORM_RADIUS; z <= DeathHall.PLATFORM_RADIUS; z++) {
				hall.setBlock(new BlockPos(x, DeathHall.PLATFORM_Y, z), floor, Block.UPDATE_CLIENTS);
				placed++;
			}
		}
		return placed;
	}

	public record ExportResult(Path file, Vec3i size, BlockPos min, int blockCount) {
	}

	/** The hall region in world coordinates, from the configured bounds. Inclusive corners. */
	public static BlockPos regionMin() {
		HallBounds b = HardcoreTogether.config().hallBounds();
		return SPAWN_FLOOR.offset(b.minDx(), b.minDy(), b.minDz());
	}

	public static BlockPos regionMax() {
		HallBounds b = HardcoreTogether.config().hallBounds();
		return SPAWN_FLOOR.offset(b.maxDx(), b.maxDy(), b.maxDz());
	}

	public static Vec3i regionSize() {
		HallBounds b = HardcoreTogether.config().hallBounds();
		return new Vec3i(b.width(), b.height(), b.depth());
	}

	/**
	 * Saves every block (and block entity, but no entities) in the hall region, cropped to the bounding box of the
	 * non-air blocks plus {@link #EXPORT_MARGIN} blocks of air on every side (kept inside the region), plus a copy
	 * of hall-layout.json (anchors, spawn, statue anchors, arrows; no statue assignments). Returns empty if there is
	 * nothing to save.
	 */
	public static Optional<ExportResult> export(ServerLevel hall, HtWorldData data) throws IOException {
		AlwaysLitLamps.rescan(hall.getServer(), "export"); // lamps added since the last scan ship lit too
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		int count = 0;

		BlockPos lo = regionMin(), hi = regionMax();
		int x0 = lo.getX(), x1 = hi.getX(), z0 = lo.getZ(), z1 = hi.getZ();
		int y0 = Math.max(lo.getY(), hall.getMinY()), y1 = Math.min(hi.getY(), hall.getMaxY());
		if (y0 > y1) {
			return Optional.empty();
		}

		for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
			for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
				LevelChunk chunk = hall.getChunk(cx, cz);
				for (int sy = hall.getSectionIndex(y0); sy <= hall.getSectionIndex(y1); sy++) {
					LevelChunkSection section = chunk.getSection(sy);
					if (section.hasOnlyAir()) {
						continue; // skip empty 16x16x16 sections; almost all of the void
					}
					int baseY = hall.getSectionYFromSectionIndex(sy) << 4;
					for (int ly = 0; ly < 16; ly++) {
						int y = baseY + ly;
						if (y < y0 || y > y1) {
							continue;
						}
						for (int lx = 0; lx < 16; lx++) {
							int x = (cx << 4) + lx;
							if (x < x0 || x > x1) {
								continue;
							}
							for (int lz = 0; lz < 16; lz++) {
								int z = (cz << 4) + lz;
								if (z < z0 || z > z1 || section.getBlockState(lx, ly, lz).isAir()) {
									continue;
								}
								count++;
								minX = Math.min(minX, x); maxX = Math.max(maxX, x);
								minY = Math.min(minY, y); maxY = Math.max(maxY, y);
								minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
							}
						}
					}
				}
			}
		}

		if (count == 0) {
			return Optional.empty();
		}

		BlockPos min = new BlockPos(Math.max(minX - EXPORT_MARGIN, x0), Math.max(minY - EXPORT_MARGIN, y0), Math.max(minZ - EXPORT_MARGIN, z0));
		BlockPos max = new BlockPos(Math.min(maxX + EXPORT_MARGIN, x1), Math.min(maxY + EXPORT_MARGIN, y1), Math.min(maxZ + EXPORT_MARGIN, z1));
		Vec3i size = new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
		Path folder = data.folder().resolve(EXPORT_FOLDER);
		Files.createDirectories(folder);
		Path file = folder.resolve(EXPORT_FILE);
		// Air is included so placing the template clears what was there; no entities.
		writeTemplate(hall, min, size, false, List.of(), null, file);
		// The layout (anchors, the hall spawn, statue anchors, arrows and their mode) ships with the build; statue
		// assignments stay here.
		HallLayout shipped = HallLayout.load(data.folder()).forExport();
		shipped.setArrowsMode(ArrowLights.mode().id());
		shipped.save(folder);
		return Optional.of(new ExportResult(file, size, min, count));
	}

	/** Air variants left out of backups; restore clears the box first instead. */
	private static final List<Block> AIR = List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR);

	/**
	 * Captures a box of the hall into a template with the hall-origin offset tag and writes it atomically.
	 * {@code ignore} lists blocks left out (air for backups, so a large mostly empty region stays small).
	 * {@code layoutJson}, if given, is stored under {@link #LAYOUT_TAG} (backups keep the statue anchors this way).
	 */
	private static void writeTemplate(ServerLevel hall, BlockPos min, Vec3i size, boolean entities, List<Block> ignore,
			String layoutJson, Path file) throws IOException {
		StructureTemplate template = new StructureTemplate();
		template.fillFromWorld(hall, min, size, entities, ignore);
		CompoundTag tag = template.save(new CompoundTag());
		if (layoutJson == null) {
			setShippedLamps(hall, tag, min); // always-lit lamps ship lit, every other lamp (arrows too) unlit
		}
		BlockPos offset = min.subtract(SPAWN_FLOOR);
		tag.put(OFFSET_TAG, new IntArrayTag(new int[] {offset.getX(), offset.getY(), offset.getZ()}));
		if (layoutJson != null) {
			tag.putString(LAYOUT_TAG, layoutJson);
		}

		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		NbtIo.writeCompressed(tag, temp);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Sets each redstone lamp in a saved template to its shipped state: lit if {@link AlwaysLitLamps} keeps it lit,
	 * otherwise unlit (arrow lamps and any other lamp). Palette entries are read and written with the game's own
	 * block state codec, so the NBT key names don't matter. Returns {lit, unlit} lamp counts.
	 */
	private static int[] setShippedLamps(ServerLevel hall, CompoundTag tag, BlockPos min) {
		net.minecraft.core.HolderGetter<Block> blocks = hall.getServer().registryAccess().lookupOrThrow(Registries.BLOCK);
		net.minecraft.nbt.ListTag palette = tag.getListOrEmpty("palette");
		List<BlockState> states = new java.util.ArrayList<>();
		for (int i = 0; i < palette.size(); i++) {
			states.add(net.minecraft.nbt.NbtUtils.readBlockState(blocks, palette.getCompoundOrEmpty(i)));
		}
		int[] lampIndex = new int[2]; // [unlit, lit]
		for (int lit = 0; lit < 2; lit++) {
			BlockState want = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, lit == 1);
			int index = states.indexOf(want);
			if (index < 0) {
				index = states.size();
				states.add(want);
				palette.add(net.minecraft.nbt.NbtUtils.writeBlockState(want));
			}
			lampIndex[lit] = index;
		}
		int[] counts = new int[2];
		for (net.minecraft.nbt.Tag t : tag.getListOrEmpty("blocks")) {
			if (!(t instanceof CompoundTag block)) {
				continue;
			}
			int state = block.getIntOr("state", -1);
			if (state < 0 || state >= states.size() || !states.get(state).is(Blocks.REDSTONE_LAMP)) {
				continue;
			}
			net.minecraft.nbt.ListTag p = block.getListOrEmpty("pos");
			BlockPos abs = min.offset(p.getIntOr(0, 0), p.getIntOr(1, 0), p.getIntOr(2, 0));
			int lit = AlwaysLitLamps.contains(abs) ? 1 : 0;
			block.putInt("state", lampIndex[lit]);
			counts[lit]++;
		}
		HardcoreTogether.LOGGER.info("[HT hall] export lamps: {} lit (always-lit), {} unlit", counts[1], counts[0]);
		return counts;
	}

	// ---- Backups ----

	public record BackupResult(String name, Path file, Vec3i size, BlockPos min, List<String> rotatedOut) {
	}

	/** Backup tag holding hall-layout.json as it was; restore takes its statue anchors and assignments. */
	private static final String LAYOUT_TAG = "ht_layout";

	/**
	 * Saves the whole hall region (blocks except air, block entities, non-player entities) as a new backup, with the
	 * hall layout (statue anchors and assignments) inside it.
	 */
	public static BackupResult backup(ServerLevel hall, HtWorldData data) throws IOException {
		String name = HallBackups.nameFor(LocalDateTime.now());
		Path file = HallBackups.file(data.folder(), name);
		Files.createDirectories(file.getParent());
		BlockPos min = regionMin();
		Vec3i size = regionSize();
		HallLayout layout = HallLayout.load(data.folder());
		layout.setArrowsMode(ArrowLights.mode().id());
		writeTemplate(hall, min, size, true, AIR, layout.toJsonString(), file);
		List<String> rotated = HallBackups.rotate(data.folder());
		HardcoreTogether.LOGGER.info("[HT hall] backup {} saved ({}x{}x{} from {}){}", name, size.getX(), size.getY(), size.getZ(),
			min.toShortString(), rotated.isEmpty() ? "" : "; removed old " + String.join(", ", rotated));
		return new BackupResult(name, file, size, min, rotated);
	}

	/** Backup after edit mode ends. Never throws; failures are logged. */
	public static void autoBackup(MinecraftServer server, String why) {
		ServerLevel hall = DeathHall.level(server);
		HtWorldData data = HardcoreTogether.worldData();
		if (hall == null || data == null) {
			HardcoreTogether.LOGGER.warn("[HT hall] automatic backup skipped ({}): hall or data folder unavailable", why);
			return;
		}
		try {
			BackupResult r = backup(hall, data);
			HardcoreTogether.LOGGER.info("[HT hall] automatic backup {} ({})", r.name(), why);
		} catch (IOException | RuntimeException e) {
			HardcoreTogether.LOGGER.error("[HT hall] automatic backup failed ({})", why, e);
		}
	}

	/** statueAnchors is -1 for an older backup without statue anchors. */
	public record BackupInfo(String name, Vec3i size, BlockPos min, int nonAirBlocks, int entities, int statueAnchors) {
	}

	private static Optional<HallLayout> backupLayout(CompoundTag tag) throws IOException {
		Optional<String> json = tag.getString(LAYOUT_TAG);
		return json.isPresent() ? Optional.of(HallLayout.parse(json.get())) : Optional.empty();
	}

	private static CompoundTag readBackup(HtWorldData data, String name) throws IOException {
		if (!HallBackups.isBackupName(name)) {
			throw new IOException("not a backup name: " + name);
		}
		Path file = HallBackups.file(data.folder(), name);
		if (Files.notExists(file)) {
			throw new IOException("no backup named " + name);
		}
		return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
	}

	private static StructureTemplate loadTemplate(MinecraftServer server, CompoundTag tag) {
		StructureTemplate template = new StructureTemplate();
		template.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
		return template;
	}

	private static BlockPos backupOrigin(CompoundTag tag) throws IOException {
		int[] o = tag.getIntArray(OFFSET_TAG).filter(a -> a.length == 3).orElseThrow(() -> new IOException("backup has no hall offset"));
		return SPAWN_FLOOR.offset(o[0], o[1], o[2]);
	}

	/** What a restore would place, without changing anything. */
	public static BackupInfo describeBackup(MinecraftServer server, HtWorldData data, String name) throws IOException {
		CompoundTag tag = readBackup(data, name);
		StructureTemplate template = loadTemplate(server, tag);
		// Palette entries are decoded with the block state codec (26.3 names the keys id/properties, not Name).
		net.minecraft.core.HolderGetter<Block> lookup = server.registryAccess().lookupOrThrow(Registries.BLOCK);
		net.minecraft.nbt.ListTag palette = tag.getListOrEmpty("palette");
		boolean[] air = new boolean[palette.size()];
		for (int i = 0; i < palette.size(); i++) {
			air[i] = net.minecraft.nbt.NbtUtils.readBlockState(lookup, palette.getCompoundOrEmpty(i)).isAir();
		}
		int blocks = 0;
		for (net.minecraft.nbt.Tag b : tag.getListOrEmpty("blocks")) {
			if (b instanceof CompoundTag block) {
				int state = block.getIntOr("state", -1);
				if (state >= 0 && state < air.length && !air[state]) {
					blocks++;
				}
			}
		}
		int statues = backupLayout(tag).map(l -> l.statues().size()).orElse(-1);
		return new BackupInfo(name, template.getSize(), backupOrigin(tag), blocks, tag.getListOrEmpty("entities").size(), statues);
	}

	/**
	 * Restores a backup: removes the non-player entities in its box, clears every block in the box to air
	 * (backups don't store air), then places it. Statue anchors and assignments are taken from the backup (if it
	 * has them) and synced with the current deaths, and so are the arrow boxes, target and mode; other anchors and
	 * the spawn are left as they are. The caller takes a safety backup first.
	 */
	public static BackupInfo restore(ServerLevel hall, HtWorldData data, String name) throws IOException {
		BackupInfo info = describeBackup(hall.getServer(), data, name);
		CompoundTag tag = readBackup(data, name);
		StructureTemplate template = loadTemplate(hall.getServer(), tag);
		BlockPos min = info.min();
		Vec3i size = template.getSize();
		AABB box = new AABB(min.getX(), min.getY(), min.getZ(), min.getX() + size.getX(), min.getY() + size.getY(), min.getZ() + size.getZ());
		for (Entity entity : hall.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player))) {
			entity.discard();
		}
		int cleared = clearBox(hall, min, min.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
		template.placeInWorld(hall, min, min, new StructurePlaceSettings(), hall.getRandom(), Block.UPDATE_CLIENTS);
		Optional<HallLayout> saved = backupLayout(tag);
		if (saved.isPresent()) {
			HallLayout layout = HallLayout.load(data.folder());
			layout.replaceStatues(saved.get());
			layout.replaceArrows(saved.get());
			layout.replaceBoards(saved.get());
			layout.replacePortal(saved.get());
			HallPortal.invalidateZone();
			layout.save(data.folder());
			HallStatues.sync(data, layout, "restore " + name);
			Optional<ArrowPath.Mode> mode = saved.get().arrowsMode().flatMap(ArrowPath.Mode::byId);
			if (mode.isPresent() && mode.get() != ArrowLights.mode()) {
				ArrowLights.setMode(mode.get());
			}
		}
		ArrowLights.invalidate(hall.getServer()); // new lamps and boxes; lit lamps from the backup go dark if off
		AlwaysLitLamps.rescan(hall.getServer(), "restore " + name);
		StatueEntities.request(hall.getServer(), "restore " + name, null);
		HardcoreTogether.LOGGER.info("[HT hall] restored backup {} ({}x{}x{} at {}, {} blocks cleared first, statues and arrows {})", name, size.getX(),
			size.getY(), size.getZ(), min.toShortString(), cleared, saved.isPresent() ? "restored" : "not in backup, kept");
		return info;
	}

	/**
	 * Sets every non-air block in the box to air, skipping empty sections. Containers are emptied first and no
	 * neighbour updates or drops happen, so nothing falls, breaks or spills.
	 */
	private static int clearBox(ServerLevel hall, BlockPos lo, BlockPos hi) {
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
		int y0 = Math.max(lo.getY(), hall.getMinY()), y1 = Math.min(hi.getY(), hall.getMaxY());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState();
		int cleared = 0;
		for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) {
			for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) {
				LevelChunk chunk = hall.getChunk(cx, cz);
				for (int sy = hall.getSectionIndex(y0); sy <= hall.getSectionIndex(y1); sy++) {
					if (chunk.getSection(sy).hasOnlyAir()) {
						continue;
					}
					int baseY = hall.getSectionYFromSectionIndex(sy) << 4;
					for (int y = Math.max(baseY, y0); y <= Math.min(baseY + 15, y1); y++) {
						for (int x = Math.max(cx << 4, lo.getX()); x <= Math.min((cx << 4) + 15, hi.getX()); x++) {
							for (int z = Math.max(cz << 4, lo.getZ()); z <= Math.min((cz << 4) + 15, hi.getZ()); z++) {
								pos.set(x, y, z);
								if (hall.getBlockState(pos).isAir()) {
									continue;
								}
								if (hall.getBlockEntity(pos) instanceof Clearable container) {
									container.clearContent();
								}
								hall.setBlock(pos, air, flags);
								cleared++;
							}
						}
					}
				}
			}
		}
		return cleared;
	}
}
