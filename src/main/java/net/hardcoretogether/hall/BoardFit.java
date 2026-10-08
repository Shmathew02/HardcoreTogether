package net.hardcoretogether.hall;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Autofit for the record boards: measures the open space around a board's anchor in the hall's blocks and
 * returns a placement (scale, top height, forward and side offsets) that fills it. Any non-air block counts as an
 * obstacle (lanterns and chains too), so a board never clips into anything.
 */
final class BoardFit {
	/** Free-standing (vertical billboard) boards: centre this far above the anchor's floor, use 80% of the space. */
	static final double CENTRE_HEIGHT = 2.2, FREE_FILL = 0.80;
	/** Wall boards: 85% of the flat wall face, 0.05 blocks out from it. */
	static final double WALL_FILL = 0.85, WALL_GAP = 0.05;
	private static final int REACH = 16;

	/** The measured space (blocks) and the placement made from it. */
	record Fit(HallLayout.BoardPlacement placement, double spaceWidth, double spaceHeight, String note) {
	}

	private BoardFit() {
	}

	/**
	 * A board that turns to face the viewer: it sweeps a circle, so its half-width must stay within the nearest
	 * obstacle at its heights; vertically it is centred CENTRE_HEIGHT above the floor. Tries every half-height
	 * and keeps the one that allows the largest scale.
	 */
	static Fit freeStanding(ServerLevel hall, BlockPos anchor, double textWidth, double textHeight) {
		double cx = anchor.getX() + 0.5, cz = anchor.getZ() + 0.5, cy = anchor.getY() + CENTRE_HEIGHT;
		double best = 0, bestR = 0, bestHalf = 0;
		for (double half = 0.5; half <= CENTRE_HEIGHT + 1.0E-6; half += 0.1) {
			double r = clearRadius(hall, cx, cz, cy - half, cy + half);
			double s = FREE_FILL * Math.min(2 * r / textWidth, 2 * half / textHeight);
			if (s > best) {
				best = s;
				bestR = r;
				bestHalf = half;
			}
		}
		double scale = round(best);
		double top = CENTRE_HEIGHT + scale * textHeight / 2;
		return new Fit(new HallLayout.BoardPlacement(scale, round(top), 0, 0), 2 * bestR, 2 * bestHalf,
			String.format(java.util.Locale.ROOT, "clear %.1f wide (nearest obstacle %.2f from centre) x %.1f high around %.1f above the floor",
				2 * bestR, bestR, 2 * bestHalf, CENTRE_HEIGHT));
	}

	/** Horizontal distance from (cx, cz) to the nearest non-air block between y0 and y1. */
	private static double clearRadius(ServerLevel hall, double cx, double cz, double y0, double y1) {
		double r = REACH;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
		for (int y = (int) Math.floor(y0 + 1.0E-6); y <= (int) Math.floor(y1 - 1.0E-6); y++) {
			for (int x = bx - REACH; x <= bx + REACH; x++) {
				for (int z = bz - REACH; z <= bz + REACH; z++) {
					if (!hall.getBlockState(p.set(x, y, z)).isAir()) {
						double dx = Math.max(Math.max(x - cx, 0), cx - (x + 1));
						double dz = Math.max(Math.max(z - cz, 0), cz - (z + 1));
						r = Math.min(r, Math.sqrt(dx * dx + dz * dz));
					}
				}
			}
		}
		return r;
	}

	/**
	 * A board mounted on the wall behind the anchor (opposite its facing): finds the wall, then the flat face around
	 * the anchor column (wall block solid, the block in front of it open), bounded by side walls, trim and ceiling.
	 * The board fills WALL_FILL of that face, centred on it, WALL_GAP out from it.
	 */
	static Fit wall(ServerLevel hall, BlockPos anchor, HallLayout.Facing facing, double textWidth, double textHeight) {
		int fx = switch (facing) { case EAST -> 1; case WEST -> -1; default -> 0; };
		int fz = switch (facing) { case SOUTH -> 1; case NORTH -> -1; default -> 0; };
		int rx = -fz, rz = fx; // "side": to the right of the facing, for someone looking the way the anchor faces
		int yRef = anchor.getY() + 2;
		int depth = -1;
		for (int k = 0; k <= REACH; k++) {
			if (!hall.getBlockState(anchor.offset(-fx * k, yRef - anchor.getY(), -fz * k)).isAir()) {
				depth = k;
				break;
			}
		}
		if (depth < 1) {
			return null;
		}
		int lo = 0, hi = 0;
		while (lo > -REACH && isFace(hall, anchor, fx, fz, rx, rz, depth, lo - 1, yRef)) {
			lo--;
		}
		while (hi < REACH && isFace(hall, anchor, fx, fz, rx, rz, depth, hi + 1, yRef)) {
			hi++;
		}
		int bottom = Integer.MIN_VALUE, top = Integer.MAX_VALUE;
		for (int j = lo; j <= hi; j++) {
			int b = yRef, t = yRef;
			while (b > yRef - REACH && isFace(hall, anchor, fx, fz, rx, rz, depth, j, b - 1)) {
				b--;
			}
			while (t < yRef + REACH && isFace(hall, anchor, fx, fz, rx, rz, depth, j, t + 1)) {
				t++;
			}
			bottom = Math.max(bottom, b);
			top = Math.min(top, t);
		}
		double width = hi - lo + 1, height = top - bottom + 1;
		double scale = round(WALL_FILL * Math.min(width / textWidth, height / textHeight));
		double centreY = (bottom + top + 1) / 2.0;
		double boardTop = centreY + scale * textHeight / 2;
		// Forward: the wall face is depth - 0.5 behind the anchor block centre; side: the face centre along (rx, rz).
		double forward = -(depth - 0.5) + WALL_GAP;
		double side = (lo + hi) / 2.0;
		return new Fit(new HallLayout.BoardPlacement(scale, round(boardTop - anchor.getY()), round(forward), side, width, bottom - anchor.getY()), width, height,
			String.format(java.util.Locale.ROOT, "wall face %.0f wide x %.0f high (y %d..%d), %d block(s) behind the anchor", width, height,
				bottom, top, depth));
	}

	/** The wall block at this lateral step and height is solid and the block in front of it is open. */
	private static boolean isFace(ServerLevel hall, BlockPos anchor, int fx, int fz, int rx, int rz, int depth, int j, int y) {
		BlockPos wall = new BlockPos(anchor.getX() - fx * depth + rx * j, y, anchor.getZ() - fz * depth + rz * j);
		BlockPos front = wall.offset(fx, 0, fz);
		return !hall.getBlockState(wall).isAir() && hall.getBlockState(front).isAir();
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}
}
