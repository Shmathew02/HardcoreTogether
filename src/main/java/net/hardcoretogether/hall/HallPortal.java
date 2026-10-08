package net.hardcoretogether.hall;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.reset.PlayerReset;
import net.hardcoretogether.reset.ResetController;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The return portal (start_mode portal). After a reset everyone waits in the Death Hall. The portal is READY
 * when the new world is ready (the reset finished and is waiting for the start) and every online player is
 * settled in the hall (not respawning or changing dimension). Then the floor arrows wave, the portal zone swirls
 * and hums, and the action bar says the way back is open. When every online player stands in the portal zone, a
 * 3-second countdown starts the next run (PortalGate) through ResetController.startNewRun.
 */
public final class HallPortal {
	private static final int ACTIONBAR_TICKS = 10, PARTICLE_TICKS = 4, HUM_TICKS = 80;

	private static final PortalGate gate = new PortalGate();
	/** The arrows were switched on by the portal (so it switches them off again). */
	private static boolean arrowsOn;
	/** Last tick's players inside the zone and online (names), for the countdown log lines. */
	private static java.util.Set<String> lastInside = java.util.Set.of(), lastOnline = java.util.Set.of();
	private static @Nullable AABB zoneCache;
	private static boolean zoneLoaded;

	private HallPortal() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			gate.reset();
			arrowsOn = false;
			invalidateZone();
		});
	}

	/** The portal zone changed (command, restore): read it again. */
	public static void invalidateZone() {
		zoneCache = null;
		zoneLoaded = false;
	}

	static void tick(MinecraftServer server) {
		if (!HardcoreTogether.config().portalStart()) {
			switchArrows(server, false, "start_mode auto");
			return;
		}
		ServerLevel hall = DeathHall.level(server);
		if (hall == null) {
			return;
		}
		long t = server.getTickCount();
		List<ServerPlayer> online = server.getPlayerList().getPlayers();
		if (!ResetController.awaitingStart()) {
			gate.reset();
			switchArrows(server, false, ResetController.isRunning() ? "the next world is being prepared" : "no new world is waiting");
			if (ResetController.holdsJoiners() && t % ACTIONBAR_TICKS == 0) {
				for (ServerPlayer p : hall.players()) {
					actionbar(p, Component.literal("Preparing the next world…").withStyle(ChatFormatting.GRAY));
				}
			}
			return;
		}
		// Waiting for the start: everyone belongs in the hall.
		String notReady = null;
		for (ServerPlayer p : List.copyOf(online)) {
			if (!DeathHall.contains(p) && !p.isChangingDimension() && p.isAlive()) {
				ResetController.keepInHall(p);
				HardcoreTogether.LOGGER.info("[HT portal] {} was outside the hall while the portal waits; brought back", p.getGameProfile().name());
			}
			String why = unsettled(p);
			if (why != null && notReady == null) {
				notReady = p.getGameProfile().name() + " " + why;
			}
		}
		AABB zone = zone();
		if (zone == null) {
			notReady = "no portal zone (no run_start anchor)";
		} else if (online.isEmpty()) {
			notReady = "nobody online";
		}
		java.util.Set<String> insideNames = new java.util.TreeSet<>(), onlineNames = new java.util.TreeSet<>();
		for (ServerPlayer p : online) {
			onlineNames.add(p.getGameProfile().name());
			if (zone != null && isSettled(p) && p.getBoundingBox().intersects(zone)) {
				insideNames.add(p.getGameProfile().name());
			}
		}
		int inside = insideNames.size();
		boolean ready = notReady == null;
		boolean wasCounting = gate.counting();
		PortalGate.Status status = gate.tick(ready, online.size(), inside);
		switchArrows(server, ready, notReady);
		if (ready && HardcoreTogether.config().portalEffects()) {
			effects(hall, zone, t);
		}
		if (status.cancelled()) {
			HardcoreTogether.LOGGER.info("[HT portal] countdown cancelled: {} ({}/{})", cancelReason(notReady, insideNames, onlineNames), inside, online.size());
		} else if (!wasCounting && status.phase() == PortalGate.Phase.COUNTDOWN) {
			HardcoreTogether.LOGGER.info("[HT portal] countdown started: everyone inside ({}/{}: {})", inside, online.size(), String.join(", ", insideNames));
		}
		lastInside = insideNames;
		lastOnline = onlineNames;
		if (status.phase() == PortalGate.Phase.START) {
			switchArrows(server, false, "run starting");
			ResetController.startNewRun(server, "everyone stepped into the portal");
			return;
		}
		if (t % ACTIONBAR_TICKS == 0 || status.phase() == PortalGate.Phase.COUNTDOWN || status.cancelled()) {
			for (ServerPlayer p : hall.players()) {
				actionbar(p, message(status, zone != null && isSettled(p) && p.getBoundingBox().intersects(zone), zone != null));
			}
		}
	}

	private static Component message(PortalGate.Status s, boolean inside, boolean hasZone) {
		if (!hasZone) {
			return Component.literal("The portal is not set up yet (no run_start anchor)").withStyle(ChatFormatting.GRAY);
		}
		return switch (s.phase()) {
			case NOT_READY -> Component.literal("Preparing the next world…").withStyle(ChatFormatting.GRAY);
			case COUNTDOWN -> Component.literal("Starting in " + s.secondsLeft() + "…").withStyle(ChatFormatting.GOLD);
			default -> inside
				? Component.literal("Waiting for others: " + s.inside() + "/" + s.online()).withStyle(ChatFormatting.AQUA)
				: Component.literal("The way back is open: all players step into the portal").withStyle(ChatFormatting.LIGHT_PURPLE);
		};
	}

	/** In the hall, alive, not changing dimension, not waiting for a respawn, client finished loading. */
	private static boolean isSettled(ServerPlayer p) {
		return unsettled(p) == null;
	}

	/** Why a player doesn't count as settled in the hall, or null if they do. */
	private static @Nullable String unsettled(ServerPlayer p) {
		if (!DeathHall.contains(p)) {
			return "is not in the Death Hall";
		}
		if (!p.isAlive()) {
			return "is dead";
		}
		if (p.isChangingDimension()) {
			return "is changing dimension";
		}
		if (PlayerReset.waitingForRespawn(p)) {
			return "is waiting to respawn";
		}
		return p.connection.hasClientLoaded() ? null : "is still loading";
	}

	/** Why a countdown stopped: the portal stopped being ready, someone left the zone, or someone joined. */
	private static String cancelReason(@Nullable String notReady, java.util.Set<String> inside, java.util.Set<String> online) {
		if (notReady != null) {
			return "portal not ready (" + notReady + ")";
		}
		java.util.Set<String> joined = new java.util.TreeSet<>(online);
		joined.removeAll(lastOnline);
		if (!joined.isEmpty()) {
			return String.join(", ", joined) + " joined";
		}
		java.util.Set<String> left = new java.util.TreeSet<>(lastInside);
		left.removeAll(inside);
		left.retainAll(online);
		return left.isEmpty() ? "not everyone is inside" : String.join(", ", left) + " left the zone";
	}

	private static void switchArrows(MinecraftServer server, boolean on, @Nullable String why) {
		if (on != arrowsOn) {
			arrowsOn = on;
			ArrowLights.setEnabled(server, on);
			HardcoreTogether.LOGGER.info("[HT portal] {}", on ? "READY: arrows on" : "not ready (" + why + "): arrows off");
		}
	}

	private static void actionbar(ServerPlayer p, Component text) {
		p.sendSystemMessage(text, true);
	}

	/** Swirling portal and soul particles rising through the zone, and a quiet hum every few seconds. */
	private static void effects(ServerLevel hall, AABB zone, long t) {
		if (t % PARTICLE_TICKS == 0) {
			double cx = (zone.minX + zone.maxX) / 2, cz = (zone.minZ + zone.maxZ) / 2;
			double rx = (zone.maxX - zone.minX) / 2 * 0.8, rz = (zone.maxZ - zone.minZ) / 2 * 0.8;
			double h = zone.maxY - zone.minY;
			for (int i = 0; i < 6; i++) {
				double a = t * 0.15 + i * Math.PI / 3;
				double y = zone.minY + ((t * 0.05 + i / 6.0) % 1.0) * h;
				hall.sendParticles(ParticleTypes.PORTAL, cx + Math.cos(a) * rx, y, cz + Math.sin(a) * rz, 2, 0.05, 0.05, 0.05, 0.2);
				if (i % 2 == 0) {
					hall.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, cx + Math.cos(a + Math.PI) * rx * 0.6, y, cz + Math.sin(a + Math.PI) * rz * 0.6,
						1, 0, 0.02, 0, 0.005);
				}
			}
		}
		if (t % HUM_TICKS == 0) {
			hall.playSound(null, (zone.minX + zone.maxX) / 2, zone.minY + 1, (zone.minZ + zone.maxZ) / 2, SoundEvents.PORTAL_AMBIENT, SoundSource.AMBIENT,
				0.25F, 0.8F);
		}
	}

	/** The portal zone (absolute, block-aligned box), or null if it can't be worked out. */
	public static @Nullable AABB zone() {
		if (!zoneLoaded) {
			zoneLoaded = true;
			zoneCache = null;
			HtWorldData data = HardcoreTogether.worldData();
			if (data != null) {
				try {
					zoneCache = zone(HallLayout.load(data.folder())).map(b -> new AABB(b[0].getX(), b[0].getY(), b[0].getZ(), b[1].getX() + 1,
						b[1].getY() + 1, b[1].getZ() + 1)).orElse(null);
				} catch (IOException e) {
					HardcoreTogether.LOGGER.error("[HT portal] could not read the hall layout", e);
				}
			}
		}
		return zoneCache;
	}

	/**
	 * The zone's min and max blocks: the two set corners, or by default 3 wide x 3 tall x 3 deep at the run_start
	 * anchor, centred across it, from its block upward, reaching 3 blocks in its facing direction.
	 */
	public static Optional<BlockPos[]> zone(HallLayout layout) {
		BlockPos o = DeathHall.ORIGIN;
		Optional<int[]> a = layout.portalCorner(1), b = layout.portalCorner(2);
		if (a.isPresent() && b.isPresent()) {
			return Optional.of(box(o.offset(a.get()[0], a.get()[1], a.get()[2]), o.offset(b.get()[0], b.get()[1], b.get()[2])));
		}
		Optional<HallLayout.Anchor> anchor = layout.anchors().stream().filter(x -> x.type() == AnchorType.RUN_START)
			.min(java.util.Comparator.comparingInt(HallLayout.Anchor::id));
		if (anchor.isEmpty()) {
			return Optional.empty();
		}
		int[] p = HallLayout.absolute(anchor.get(), o.getX(), o.getY(), o.getZ());
		int[] f = StatueEntities.facingVector(anchor.get().facing());
		int rx = -f[1], rz = f[0];
		BlockPos base = new BlockPos(p[0], p[1], p[2]);
		BlockPos c1 = base.offset(-rx, 0, -rz);
		BlockPos c2 = base.offset(rx + 2 * f[0], 2, rz + 2 * f[1]);
		return Optional.of(box(c1, c2));
	}

	private static BlockPos[] box(BlockPos a, BlockPos b) {
		return new BlockPos[] {new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
			new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))};
	}
}
