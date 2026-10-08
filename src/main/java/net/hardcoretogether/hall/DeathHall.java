package net.hardcoretogether.hall;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.reset.PlayerReset;
import net.hardcoretogether.reset.ResetController;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Death Hall: a datapack dimension shipped in the mod jar (data/hardcore_together/dimension/) where
 * players wait between runs. The reset code only ever touches the three vanilla dimensions, never this one.
 *
 * Hall rules, applied to every player in this dimension: Adventure mode, no damage (so no PvP or void
 * deaths), no hunger loss, no item dropping, and falling off the platform sends you back to the spawn point.
 * Hunger and item dropping are handled by mixins that call {@link #contains(Player)} and {@link #rulesApply}.
 *
 * Edit mode (/ht hall edit) exempts one player from Adventure enforcement, the fall teleport and drop blocking
 * so ops can build the hall in Creative. Damage, hunger and attack blocking stay on for everyone. Edit mode is
 * memory only ({@link HallEditSessions}); it survives resets and deaths but ends on disconnect, on leaving the
 * hall and on server stop, and the hall is backed up every time it ends.
 */
public final class DeathHall {
	public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, HardcoreTogether.id("death_hall"));

	public static final int PLATFORM_Y = 64;
	public static final int PLATFORM_RADIUS = 7;
	/** The built-in spawn on the starter platform; used when no spawn is set or the set one is unsafe. */
	public static final SpawnPoint DEFAULT_SPAWN = new SpawnPoint(new Vec3(0.5, PLATFORM_Y + 1, 0.5), 0.0F, 0.0F);
	/** The hall origin: the spawn floor block. Anchors, exports and backups are stored relative to it. */
	public static final BlockPos ORIGIN = new BlockPos(0, PLATFORM_Y, 0);
	/** Anyone this far below the platform is returned to the spawn point. */
	private static final int FALL_LIMIT_Y = PLATFORM_Y - 16;

	private static final HallEditSessions editSessions = new HallEditSessions();

	/** Where players arrive in the hall: position and facing. */
	public record SpawnPoint(Vec3 pos, float yaw, float pitch) {
	}

	/** The spawn from hall-layout.json (/ht hall setspawn), or null for the default. */
	private static volatile HallLayout.Spawn setSpawn;
	/** The unsafe set spawn already warned about, so a fall back is logged once, not on every arrival. */
	private static HallLayout.Spawn warnedSpawn;

	private DeathHall() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(DeathHall::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> editSessions.clear());
		ServerTickEvents.END_SERVER_TICK.register(DeathHall::tick);
		HallStatues.register();
		ArrowLights.register();
		AlwaysLitLamps.register();
		StatueEntities.register();
		HallPortal.register();
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();
			if (editSessions.disconnect(player.getUUID())) {
				HardcoreTogether.LOGGER.info("[HT hall] {} disconnected; edit mode ended", player.getGameProfile().name());
				HallBuilder.autoBackup(server, player.getGameProfile().name() + " disconnected while editing");
			}
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !(entity instanceof ServerPlayer player && contains(player)));
		AttackEntityCallback.EVENT.register((player, level, hand, target, hit) ->
			level.dimension() == KEY ? InteractionResult.FAIL : InteractionResult.PASS);
	}

	public static ServerLevel level(MinecraftServer server) {
		return server.getLevel(KEY);
	}

	public static boolean contains(Player player) {
		return player.level().dimension() == KEY;
	}

	/** True when the player is in the hall and not exempted by edit mode. */
	public static boolean rulesApply(Player player) {
		return contains(player) && !editSessions.isEditing(player.getUUID());
	}

	public static List<ServerPlayer> playersInside(MinecraftServer server) {
		ServerLevel hall = level(server);
		return hall == null ? List.of() : List.copyOf(hall.players());
	}

	/**
	 * The one spawn source for every arrival in the hall (reset gather and hold, death respawn, joins routed to
	 * the hall, falling off, /ht hall). Uses the set spawn if it is safe, else logs a warning and falls back to
	 * {@link #DEFAULT_SPAWN}.
	 */
	public static SpawnPoint spawnPoint(ServerLevel hall) {
		HallLayout.Spawn set = setSpawn;
		if (set == null) {
			return DEFAULT_SPAWN;
		}
		double[] p = set.absolute(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ());
		Vec3 pos = new Vec3(p[0], p[1], p[2]);
		String reason = unsafeReason(hall, pos);
		if (reason == null) {
			warnedSpawn = null;
			return new SpawnPoint(pos, set.yaw(), set.pitch());
		}
		if (!set.equals(warnedSpawn)) {
			warnedSpawn = set;
			HardcoreTogether.LOGGER.warn("[HT hall] Hall spawn {} is not safe ({}); using the default {}",
				describe(pos), reason, describe(DEFAULT_SPAWN.pos()));
		}
		return DEFAULT_SPAWN;
	}

	/** True when a player fits at the position (no blocks or liquid in the way) and stands on something solid. */
	public static boolean isSafe(ServerLevel hall, Vec3 pos) {
		return unsafeReason(hall, pos) == null;
	}

	/**
	 * Why a player can't stand at the position, or null if they can. Reads the blocks directly (ServerLevel
	 * loads the chunk if needed) instead of the level's collision checks, which skip chunks that aren't loaded.
	 */
	public static String unsafeReason(ServerLevel hall, Vec3 pos) {
		AABB body = EntityTypes.PLAYER.getDimensions().makeBoundingBox(pos);
		VoxelShape bodyShape = Shapes.create(body);
		for (BlockPos p : BlockPos.betweenClosed(Mth.floor(body.minX), Mth.floor(body.minY), Mth.floor(body.minZ),
				Mth.floor(body.maxX - 1.0E-7), Mth.floor(body.maxY - 1.0E-7), Mth.floor(body.maxZ - 1.0E-7))) {
			BlockState state = hall.getBlockState(p);
			if (!state.getFluidState().isEmpty()) {
				return "liquid " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + p.toShortString();
			}
			if (Shapes.joinIsNotEmpty(state.getCollisionShape(hall, p).move(p), bodyShape, BooleanOp.AND)) {
				return "blocked by " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + p.toShortString();
			}
		}
		VoxelShape underFeet = Shapes.create(new AABB(body.minX, pos.y - 0.1, body.minZ, body.maxX, pos.y, body.maxZ));
		for (BlockPos p : BlockPos.betweenClosed(Mth.floor(body.minX), Mth.floor(pos.y - 0.1), Mth.floor(body.minZ),
				Mth.floor(body.maxX - 1.0E-7), Mth.floor(pos.y - 1.0E-7), Mth.floor(body.maxZ - 1.0E-7))) {
			BlockState state = hall.getBlockState(p);
			if (Shapes.joinIsNotEmpty(state.getCollisionShape(hall, p).move(p), underFeet, BooleanOp.AND)) {
				return null;
			}
		}
		return "nothing solid below " + BlockPos.containing(pos).toShortString();
	}

	/** Saves the hall spawn to hall-layout.json and uses it from now on. */
	public static void saveSpawn(HtWorldData data, HallLayout.Spawn spawn) throws IOException {
		HallLayout layout = HallLayout.load(data.folder());
		layout.setSpawn(spawn);
		layout.save(data.folder());
		setSpawn = spawn;
		warnedSpawn = null;
	}

	public static String describe(Vec3 pos) {
		return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", pos.x, pos.y, pos.z);
	}

	/** Moves the player to the hall spawn point. Inventory and game mode are left alone. */
	public static void teleportToSpawn(ServerPlayer player, ServerLevel hall) {
		SpawnPoint spawn = spawnPoint(hall);
		player.stopRiding();
		player.teleport(new TeleportTransition(hall, spawn.pos(), Vec3.ZERO, spawn.yaw(), spawn.pitch(), TeleportTransition.DO_NOTHING));
		player.resetFallDistance();
	}

	/**
	 * Respawns a dead player through vanilla's respawn path (as the Respawn button would) and moves the new
	 * player object to the hall spawn. Returns the new player; the dead one must not be used afterwards.
	 */
	public static ServerPlayer respawnInHall(ServerPlayer dead, ServerLevel hall) {
		ServerPlayer alive = PlayerReset.attachRespawned(dead.level().getServer().getPlayerList().respawn(dead, false, Entity.RemovalReason.KILLED));
		teleportToSpawn(alive, hall);
		return alive;
	}

	/** Teleports the player to the world spawn (normally in the overworld), standing on the surface. */
	public static void sendToWorldSpawn(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		LevelData.RespawnData spawn = server.getRespawnData();
		ServerLevel target = server.getLevel(spawn.dimension());
		if (target == null) {
			target = server.overworld();
		}
		BlockPos pos = spawn.pos();
		int surface = target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
		Vec3 destination = new Vec3(pos.getX() + 0.5, Math.max(pos.getY(), surface), pos.getZ() + 0.5);

		player.stopRiding();
		player.teleport(new TeleportTransition(target, destination, Vec3.ZERO, spawn.yaw(), spawn.pitch(), TeleportTransition.DO_NOTHING));
		player.resetFallDistance();
	}

	public static boolean isEditing(ServerPlayer player) {
		return editSessions.isEditing(player.getUUID());
	}

	public static Set<UUID> editors() {
		return editSessions.editors();
	}

	/** Starts edit mode: Creative, hall rules off for this player only. Returns false if already editing. */
	public static boolean startEditing(ServerPlayer player) {
		boolean started = editSessions.start(player.getUUID());
		player.setGameMode(GameType.CREATIVE);
		return started;
	}

	/**
	 * Ends edit mode: back to Adventure if still in the hall, then an automatic hall backup. A player a real
	 * reset skipped while editing then gets that fresh start. Returns false if the player wasn't editing.
	 */
	public static boolean stopEditing(ServerPlayer player, String why) {
		if (!editSessions.stop(player.getUUID())) {
			return false;
		}
		String name = player.getGameProfile().name();
		HardcoreTogether.LOGGER.info("[HT hall] edit mode off for {} ({})", name, why);
		if (contains(player)) {
			player.setGameMode(GameType.ADVENTURE);
		}
		HallBuilder.autoBackup(player.level().getServer(), "edit mode off for " + name);
		if (editSessions.takeMissedReset(player.getUUID())) {
			ResetController.sendEditorToNewWorld(player);
		}
		return true;
	}

	/** RETURN skipped this editor in a real reset; they get the fresh start when edit mode ends. */
	public static void markMissedReset(ServerPlayer player) {
		editSessions.markMissedReset(player.getUUID());
	}

	private static void onServerStarted(MinecraftServer server) {
		ServerLevel hall = level(server);
		if (hall == null) {
			HardcoreTogether.LOGGER.error("Death Hall dimension {} is not loaded; check the mod jar's data files", KEY.identifier());
			return;
		}
		HtWorldData data = HardcoreTogether.worldData();
		if (data == null) {
			HardcoreTogether.LOGGER.error("Death Hall loaded, but the data folder is unavailable; hall placement skipped");
			return;
		}
		boolean placed = HallBuilder.placeOnce(server, hall, data);
		try {
			setSpawn = HallLayout.load(data.folder()).spawn().orElse(null);
		} catch (IOException e) {
			setSpawn = null;
			HardcoreTogether.LOGGER.error("[HT hall] Could not read the hall spawn; using the default", e);
		}
		SpawnPoint spawn = spawnPoint(hall);
		HardcoreTogether.LOGGER.info("[HT hall] Hall spawn {} facing yaw {} pitch {}{}", describe(spawn.pos()), spawn.yaw(), spawn.pitch(),
			setSpawn == null ? " (default, none set)" : spawn == DEFAULT_SPAWN ? " (default, set spawn unsafe)" : " (set)");
		// Right after placement (fresh install) or load, once the spawn that sets the zone is known.
		AlwaysLitLamps.rescan(server, placed ? "hall placed" : "hall loaded");
		StatueEntities.request(server, placed ? "hall placed" : "server start", null);
	}

	private static void tick(MinecraftServer server) {
		// Edit mode ends as soon as the player is no longer in the hall.
		for (UUID uuid : editSessions.editors()) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player == null) {
				editSessions.disconnect(uuid);
			} else if (!contains(player)) {
				stopEditing(player, "left the hall");
			}
		}
		HallMarkers.tick(server);
		ArrowLights.tick(server);
		AlwaysLitLamps.tick(server);
		HallPortal.tick(server);

		ServerLevel hall = level(server);
		if (hall == null) {
			return;
		}
		for (ServerPlayer player : List.copyOf(hall.players())) {
			if (!rulesApply(player)) {
				continue;
			}
			if (player.gameMode() != GameType.ADVENTURE && player.gameMode() != GameType.SPECTATOR) {
				player.setGameMode(GameType.ADVENTURE);
			}
			if (player.getY() < FALL_LIMIT_Y) {
				teleportToSpawn(player, hall);
			}
		}
	}
}
