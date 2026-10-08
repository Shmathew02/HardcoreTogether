package net.hardcoretogether.hall;

/**
 * The buildable hall region as a box of offsets from the hall origin (the spawn floor block). Width runs
 * along x and depth along z, both centred on the origin; the box starts {@code belowFloor} blocks below the
 * floor and is {@code height} blocks tall. No Minecraft classes, so tests can use it.
 */
public record HallBounds(int width, int height, int depth, int belowFloor) {
	public static final int MAX_SIZE = 256;
	/** 192 wide x 96 tall x 192 deep, starting 12 blocks below the floor: x -96..95, y -12..83, z -96..95. */
	public static final HallBounds DEFAULT = new HallBounds(192, 96, 192, 12);

	public int minDx() {
		return -width / 2;
	}

	public int maxDx() {
		return minDx() + width - 1;
	}

	public int minDy() {
		return -belowFloor;
	}

	public int maxDy() {
		return minDy() + height - 1;
	}

	public int minDz() {
		return -depth / 2;
	}

	public int maxDz() {
		return minDz() + depth - 1;
	}

	public boolean containsOffset(int dx, int dy, int dz) {
		return dx >= minDx() && dx <= maxDx() && dy >= minDy() && dy <= maxDy() && dz >= minDz() && dz <= maxDz();
	}

	public long volume() {
		return (long) width * height * depth;
	}
}
