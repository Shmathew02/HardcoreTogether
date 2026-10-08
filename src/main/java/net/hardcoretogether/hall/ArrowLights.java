package net.hardcoretogether.hall;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.HtWorldData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;

/**
 * Lights the Death Hall floor arrows: the redstone lamps inside the three arrow boxes from hall-layout.json. The mod
 * sets each lamp's lit state directly without neighbour or shape updates, so no redstone is involved and nothing
 * flickers back. The lamp path is cached and rebuilt only when a box or the target changes (or after a restore).
 * Off by default; frames only advance while a player is in the hall. Switching off, and server stop, unlights every
 * controlled lamp.
 */
public final class ArrowLights {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private static boolean enabled;
	private static ArrowPath.Settings settings = ArrowPath.Settings.DEFAULT;
	/** Path lamps in order, or null until the next build. */
	private static List<BlockPos> path;
	/** Lamps per arrow in the current path (index 0 = arrow 1). */
	private static int[] perArrow = new int[HallLayout.ARROWS];
	private static long tick;

	private ArrowLights() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			settings = HardcoreTogether.config().arrows();
			enabled = false;
			path = null;
			apply(server, new BitSet(), "server start"); // a crash may have left lamps lit
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (enabled) {
				setEnabled(server, false);
			}
			path = null;
		});
	}

	public static boolean enabled() {
		return enabled;
	}

	public static ArrowPath.Mode mode() {
		return settings.mode();
	}

	public static ArrowPath.Settings settings() {
		return settings;
	}

	/** Switches the arrows on or off; off unlights every controlled lamp at once. */
	public static void setEnabled(MinecraftServer server, boolean on) {
		enabled = on;
		tick = 0;
		if (!on) {
			apply(server, new BitSet(), "arrows off");
		}
		HardcoreTogether.LOGGER.info("[HT arrows] {} (mode {})", on ? "on" : "off", settings.mode().id());
	}

	/** Changes the mode now and saves it in the config file. */
	public static void setMode(ArrowPath.Mode mode) throws IOException {
		settings = settings.withMode(mode);
		tick = 0;
		net.hardcoretogether.config.HtConfig.saveArrowsMode(mode);
		HardcoreTogether.LOGGER.info("[HT arrows] mode set to {}", mode.id());
	}

	/**
	 * Boxes, target or the hall blocks changed: unlights the old path, forgets it, and unlights the new one if the
	 * arrows are off (a restored backup may hold lit lamps).
	 */
	public static void invalidate(MinecraftServer server) {
		if (path != null) {
			unlight(server, path);
		}
		path = null;
		tick = 0;
		if (!enabled) {
			apply(server, new BitSet(), "path rebuilt");
		}
	}

	/** The cached path, building it if needed. Empty if the hall or the layout is unavailable. */
	public static List<BlockPos> path(MinecraftServer server) {
		if (path == null) {
			path = build(server);
		}
		return path;
	}

	public static int lampsInArrow(MinecraftServer server, int arrow) {
		path(server);
		return perArrow[arrow - 1];
	}

	static void tick(MinecraftServer server) {
		if (!enabled) {
			return;
		}
		ServerLevel hall = DeathHall.level(server);
		if (hall == null || hall.players().isEmpty()) {
			return;
		}
		List<BlockPos> lamps = path(server);
		apply(hall, lamps, ArrowPath.frame(settings, lamps.size(), tick++));
	}

	private static void apply(MinecraftServer server, BitSet lit, String why) {
		ServerLevel hall = DeathHall.level(server);
		if (hall == null) {
			return;
		}
		int changed = apply(hall, path(server), lit);
		if (changed > 0) {
			HardcoreTogether.LOGGER.info("[HT arrows] {}: {} lamp(s) unlit", why, changed);
		}
	}

	private static void unlight(MinecraftServer server, List<BlockPos> lamps) {
		ServerLevel hall = DeathHall.level(server);
		if (hall != null) {
			apply(hall, lamps, new BitSet());
		}
	}

	/** Sets each path lamp to its frame state where it differs; skips positions that are no longer lamps. */
	private static int apply(ServerLevel hall, List<BlockPos> lamps, BitSet lit) {
		int changed = 0;
		for (int i = 0; i < lamps.size(); i++) {
			BlockPos pos = lamps.get(i);
			BlockState state = hall.getBlockState(pos);
			boolean want = lit.get(i);
			if (state.is(Blocks.REDSTONE_LAMP) && state.getValue(RedstoneLampBlock.LIT) != want) {
				hall.setBlock(pos, state.setValue(RedstoneLampBlock.LIT, want), FLAGS);
				changed++;
			}
		}
		return changed;
	}

	private static List<BlockPos> build(MinecraftServer server) {
		perArrow = new int[HallLayout.ARROWS];
		ServerLevel hall = DeathHall.level(server);
		HtWorldData data = HardcoreTogether.worldData();
		if (hall == null || data == null) {
			return List.of();
		}
		HallLayout layout;
		try {
			layout = HallLayout.load(data.folder());
		} catch (IOException e) {
			HardcoreTogether.LOGGER.error("[HT arrows] could not read the hall layout; no arrow lamps", e);
			return List.of();
		}
		BlockPos o = DeathHall.ORIGIN;
		int[] target = layout.arrowTarget().map(t -> new int[] {o.getX() + t[0], o.getY() + t[1], o.getZ() + t[2]}).orElse(null);
		List<BlockPos> result = new ArrayList<>();
		for (int arrow = 1; arrow <= HallLayout.ARROWS; arrow++) {
			Optional<BlockPos[]> box = box(layout, arrow);
			if (box.isEmpty()) {
				continue;
			}
			List<ArrowPath.Lamp> lamps = new ArrayList<>();
			for (BlockPos pos : BlockPos.betweenClosed(box.get()[0], box.get()[1])) {
				if (hall.getBlockState(pos).is(Blocks.REDSTONE_LAMP)) {
					lamps.add(new ArrowPath.Lamp(pos.getX(), pos.getY(), pos.getZ()));
				}
			}
			for (ArrowPath.Lamp l : ArrowPath.orderArrow(lamps, target)) {
				result.add(new BlockPos(l.x(), l.y(), l.z()));
			}
			perArrow[arrow - 1] = lamps.size();
		}
		HardcoreTogether.LOGGER.info("[HT arrows] path built: {} lamp(s) (arrows {}, {}, {}){}", result.size(), perArrow[0], perArrow[1],
			perArrow[2], target == null ? "; no target set" : "");
		return List.copyOf(result);
	}

	/** An arrow's box as absolute corners, if both are set. */
	public static Optional<BlockPos[]> box(HallLayout layout, int arrow) {
		Optional<int[]> a = layout.arrowCorner(arrow, 1), b = layout.arrowCorner(arrow, 2);
		if (a.isEmpty() || b.isEmpty()) {
			return Optional.empty();
		}
		BlockPos o = DeathHall.ORIGIN;
		return Optional.of(new BlockPos[] {o.offset(a.get()[0], a.get()[1], a.get()[2]), o.offset(b.get()[0], b.get()[1], b.get()[2])});
	}
}
