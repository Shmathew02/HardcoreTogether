package net.hardcoretogether.hall;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the hall shipped in the jar: every lamp in the always-lit zone (spawn block y + the default offset and up,
 * outside the arrow boxes) is saved lit, every arrow lamp unlit, and the shipped layout has no statue assignments.
 * Placement itself needs a running server (see RedstoneLampBlockMixin and AlwaysLitLamps for that part).
 */
class ShippedHallTest {
	private static final Path DATA = Path.of("src/main/resources/data/hardcore_together");

	@Test
	void shippedLampsMatchTheirRole() throws IOException {
		HallLayout layout = HallLayout.parse(Files.readString(DATA.resolve("hall/hall-layout.json")));
		CompoundTag tag = NbtIo.readCompressed(DATA.resolve("structure/death_hall.nbt"), NbtAccounter.unlimitedHeap());
		int[] offset = tag.getIntArray("hardcore_together_offset").orElseThrow();
		double[] spawn = layout.spawn().orElseThrow().absolute(0, 64, 0);
		int zoneMinY = (int) Math.floor(spawn[1]) + 3;
		ListTag palette = tag.getListOrEmpty("palette");
		int alwaysLit = 0, arrows = 0;
		for (Tag t : tag.getListOrEmpty("blocks")) {
			CompoundTag block = (CompoundTag) t;
			CompoundTag state = palette.getCompoundOrEmpty(block.getIntOr("state", -1));
			if (!state.getStringOr("id", "").equals("minecraft:redstone_lamp")) {
				continue;
			}
			ListTag p = block.getListOrEmpty("pos");
			int x = offset[0] + p.getIntOr(0, 0), y = 64 + offset[1] + p.getIntOr(1, 0), z = offset[2] + p.getIntOr(2, 0);
			boolean lit = state.getCompoundOrEmpty("properties").getStringOr("lit", "false").equals("true");
			if (inArrowBox(layout, x, y, z)) {
				arrows++;
				assertTrue(!lit, "arrow lamp lit at " + x + " " + y + " " + z);
			} else if (y >= zoneMinY) {
				alwaysLit++;
				assertTrue(lit, "always-lit lamp unlit at " + x + " " + y + " " + z);
			}
		}
		// No statues or plaques (or any other entity) ship with the hall.
		assertTrue(tag.getListOrEmpty("entities").isEmpty(), "shipped hall has entities");
		assertEquals(40, alwaysLit);
		assertEquals(27, arrows);
		assertTrue(layout.statueAssignments().isEmpty());
		assertEquals(36, layout.statues().size());
	}

	private static boolean inArrowBox(HallLayout layout, int x, int y, int z) {
		for (int arrow = 1; arrow <= HallLayout.ARROWS; arrow++) {
			int[] a = layout.arrowCorner(arrow, 1).orElseThrow(), b = layout.arrowCorner(arrow, 2).orElseThrow();
			if (x >= Math.min(a[0], b[0]) && x <= Math.max(a[0], b[0]) && y - 64 >= Math.min(a[1], b[1]) && y - 64 <= Math.max(a[1], b[1])
				&& z >= Math.min(a[2], b[2]) && z <= Math.max(a[2], b[2])) {
				return true;
			}
		}
		return false;
	}
}
