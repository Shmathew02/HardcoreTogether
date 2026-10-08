package net.hardcoretogether.hall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Temporary particle markers shown to one player in the hall: the region outline (/ht hall bounds show), the
 * anchors and the statue anchors (/ht hall anchor show). Redrawn every 10 ticks for 30 seconds; stops early if
 * the player leaves the hall or disconnects. Particles are sent to that player only, with the long-distance flag set.
 */
public final class HallMarkers {
	public static final int SECONDS = 30;
	private static final int REDRAW_TICKS = 10;
	private static final int BOUNDS_COLOR = 0xFFAA00;
	private static final double BOUNDS_STEP = 3.0;

	private record Marker(UUID player, String kind, long endTick, BiConsumer<ServerPlayer, ServerLevel> draw) {
	}

	private static final List<Marker> markers = new ArrayList<>();

	private HallMarkers() {
	}

	/** Shows the hall region outline for {@link #SECONDS}; replaces an outline already showing for this player. */
	public static void showBounds(ServerPlayer player) {
		BlockPos lo = HallBuilder.regionMin(), hi = HallBuilder.regionMax();
		// Outline on the outer faces of the boundary blocks.
		double x0 = lo.getX(), y0 = lo.getY(), z0 = lo.getZ();
		double x1 = hi.getX() + 1, y1 = hi.getY() + 1, z1 = hi.getZ() + 1;
		DustParticleOptions dust = new DustParticleOptions(BOUNDS_COLOR, 2.0F);
		add(player, "bounds", (p, hall) -> {
			for (double y : new double[] {y0, y1}) {
				for (double z : new double[] {z0, z1}) {
					line(p, hall, dust, x0, y, z, x1, y, z);
				}
				for (double x : new double[] {x0, x1}) {
					line(p, hall, dust, x, y, z0, x, y, z1);
				}
			}
			for (double x : new double[] {x0, x1}) {
				for (double z : new double[] {z0, z1}) {
					line(p, hall, dust, x, y0, z, x, y1, z);
				}
			}
		});
	}

	/** Shows every anchor for {@link #SECONDS}: a coloured column and an arrow pointing the way it faces. */
	public static void showAnchors(ServerPlayer player, List<HallLayout.Anchor> anchors) {
		List<HallLayout.Anchor> copy = List.copyOf(anchors);
		add(player, "anchors", (p, hall) -> {
			for (HallLayout.Anchor a : copy) {
				DustParticleOptions dust = new DustParticleOptions(a.type().color(), 1.5F);
				double cx = DeathHall.ORIGIN.getX() + a.dx() + 0.5;
				double cy = DeathHall.ORIGIN.getY() + a.dy();
				double cz = DeathHall.ORIGIN.getZ() + a.dz() + 0.5;
				line(p, hall, dust, cx, cy + 0.1, cz, cx, cy + 2.1, cz);
				double[] dir = direction(a.facing());
				line(p, hall, dust, cx, cy + 1.2, cz, cx + dir[0] * 1.2, cy + 1.2, cz + dir[1] * 1.2);
			}
		});
	}

	private static final int STATUE_COLOR = 0xFFFFFF;
	private static final int STATUE_FREE_COLOR = 0x8888AA;
	/** Seven-segment digits: bits a (top), b (upper right), c (lower right), d (bottom), e, f, g (middle). */
	private static final int[] DIGIT_SEGMENTS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
	private static final double DIGIT_WIDTH = 0.4, DIGIT_HEIGHT = 0.8, DIGIT_GAP = 0.25;

	/**
	 * Shows every statue anchor for {@link #SECONDS}: a player-sized box (0.6 x 1.8 x 0.6), an arrow at chest height
	 * pointing the way the statue will face, and its number above, turned towards the viewer. White = a death is
	 * assigned, grey-blue = free.
	 */
	public static void showStatues(ServerPlayer player, List<HallLayout.Statue> statues, Set<Integer> assigned) {
		List<HallLayout.Statue> copy = List.copyOf(statues);
		Set<Integer> taken = Set.copyOf(assigned);
		add(player, "statues", (p, hall) -> {
			for (HallLayout.Statue s : copy) {
				double[] c = s.absolute(DeathHall.ORIGIN.getX(), DeathHall.ORIGIN.getY(), DeathHall.ORIGIN.getZ());
				DustParticleOptions dust = new DustParticleOptions(taken.contains(s.number()) ? STATUE_COLOR : STATUE_FREE_COLOR, 1.0F);
				box(p, hall, dust, c[0] - 0.3, c[1], c[2] - 0.3, c[0] + 0.3, c[1] + 1.8, c[2] + 0.3);
				double yaw = Math.toRadians(s.facing().yaw());
				double fx = -Math.sin(yaw), fz = Math.cos(yaw);
				line(p, hall, dust, c[0], c[1] + 1.3, c[2], c[0] + fx * 1.2, c[1] + 1.3, c[2] + fz * 1.2);
				line(p, hall, dust, c[0] + fx * 1.2, c[1] + 1.3, c[2] + fz * 1.2, c[0] + fx * 0.9 - fz * 0.2, c[1] + 1.3, c[2] + fz * 0.9 + fx * 0.2);
				line(p, hall, dust, c[0] + fx * 1.2, c[1] + 1.3, c[2] + fz * 1.2, c[0] + fx * 0.9 + fz * 0.2, c[1] + 1.3, c[2] + fz * 0.9 - fx * 0.2);
				number(p, hall, dust, s.number(), c[0], c[1] + 2.2, c[2]);
			}
		});
	}

	private static void box(ServerPlayer p, ServerLevel hall, DustParticleOptions dust, double x0, double y0, double z0, double x1, double y1, double z1) {
		for (double y : new double[] {y0, y1}) {
			line(p, hall, dust, x0, y, z0, x1, y, z0);
			line(p, hall, dust, x0, y, z1, x1, y, z1);
			line(p, hall, dust, x0, y, z0, x0, y, z1);
			line(p, hall, dust, x1, y, z0, x1, y, z1);
		}
		for (double x : new double[] {x0, x1}) {
			for (double z : new double[] {z0, z1}) {
				line(p, hall, dust, x, y0, z, x, y1, z);
			}
		}
	}

	/** Draws a number in seven-segment digits, bottom centred on (cx, y, cz), in the vertical plane facing the viewer. */
	private static void number(ServerPlayer p, ServerLevel hall, DustParticleOptions dust, int value, double cx, double y, double cz) {
		double vx = p.getX() - cx, vz = p.getZ() - cz;
		double len = Math.sqrt(vx * vx + vz * vz);
		if (len < 1.0E-3) {
			vx = 0;
			vz = 1;
			len = 1;
		}
		// The viewer looks along -v; their right-hand side is (vz, -vx).
		double rx = vz / len, rz = -vx / len;
		String digits = Integer.toString(value);
		double total = digits.length() * DIGIT_WIDTH + (digits.length() - 1) * DIGIT_GAP;
		for (int i = 0; i < digits.length(); i++) {
			double u0 = -total / 2 + i * (DIGIT_WIDTH + DIGIT_GAP);
			int mask = DIGIT_SEGMENTS[digits.charAt(i) - '0'];
			double u1 = u0 + DIGIT_WIDTH, hm = DIGIT_HEIGHT / 2, ht = DIGIT_HEIGHT;
			double[][] segments = {
				{u0, ht, u1, ht}, {u1, hm, u1, ht}, {u1, 0, u1, hm}, {u0, 0, u1, 0}, {u0, 0, u0, hm}, {u0, hm, u0, ht}, {u0, hm, u1, hm}};
			for (int seg = 0; seg < 7; seg++) {
				if ((mask & (1 << seg)) != 0) {
					double[] g = segments[seg];
					line(p, hall, dust, cx + rx * g[0], y + g[1], cz + rz * g[0], cx + rx * g[2], y + g[3], cz + rz * g[2], 0.1);
				}
			}
		}
	}

	private static double[] direction(HallLayout.Facing facing) {
		return switch (facing) {
			case SOUTH -> new double[] {0, 1};
			case NORTH -> new double[] {0, -1};
			case EAST -> new double[] {1, 0};
			case WEST -> new double[] {-1, 0};
		};
	}

	private static void add(ServerPlayer player, String kind, BiConsumer<ServerPlayer, ServerLevel> draw) {
		MinecraftServer server = player.level().getServer();
		markers.removeIf(m -> m.player().equals(player.getUUID()) && m.kind().equals(kind));
		markers.add(new Marker(player.getUUID(), kind, server.getTickCount() + SECONDS * 20L, draw));
		ServerLevel hall = DeathHall.level(server);
		if (hall != null) {
			draw.accept(player, hall); // first frame now, not 10 ticks later
		}
	}

	static void tick(MinecraftServer server) {
		if (markers.isEmpty()) {
			return;
		}
		ServerLevel hall = DeathHall.level(server);
		boolean redraw = server.getTickCount() % REDRAW_TICKS == 0;
		Iterator<Marker> it = markers.iterator();
		while (it.hasNext()) {
			Marker m = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(m.player());
			if (hall == null || player == null || !DeathHall.contains(player) || server.getTickCount() >= m.endTick()) {
				it.remove();
			} else if (redraw) {
				m.draw().accept(player, hall);
			}
		}
	}

	private static void line(ServerPlayer player, ServerLevel hall, DustParticleOptions dust,
			double ax, double ay, double az, double bx, double by, double bz) {
		double length = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
		line(player, hall, dust, ax, ay, az, bx, by, bz, length > 4 ? BOUNDS_STEP : 0.25);
	}

	private static void line(ServerPlayer player, ServerLevel hall, DustParticleOptions dust,
			double ax, double ay, double az, double bx, double by, double bz, double step) {
		double length = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
		int points = Math.max(1, (int) Math.ceil(length / step));
		for (int i = 0; i <= points; i++) {
			double t = (double) i / points;
			hall.sendParticles(player, dust, true, true, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, 1, 0, 0, 0, 0);
		}
	}
}
