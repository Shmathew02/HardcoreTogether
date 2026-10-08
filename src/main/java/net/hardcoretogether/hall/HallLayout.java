package net.hardcoretogether.hall;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.hardcoretogether.data.JsonFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The hall layout file, {@code <world>/hardcore_together/hall-layout.json}: display anchors, statue anchors, floor
 * arrows, portal zone, board placements and the hall spawn. Positions are offsets from the hall origin (the spawn
 * floor block), so everything moves with the hall when it ships in the jar. Anchor ids are never reused.
 *
 * Statue anchors are kept apart from the display anchors: they are keyed by their number (the fill order), and
 * the file also holds which death (run number) has which statue anchor (see {@link StatueAssignments}). The
 * statue and plaque entities are placed from these, tagged with the run number.
 */
public final class HallLayout {
	public static final String FILE_NAME = "hall-layout.json";
	private static final int FORMAT = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** The four horizontal facings, named as Minecraft names them. */
	public enum Facing {
		SOUTH, WEST, NORTH, EAST;

		public String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** The eight facings of a statue, clockwise from south, each 45 degrees of Minecraft yaw apart. */
	public enum Facing8 {
		SOUTH, SOUTH_WEST, WEST, NORTH_WEST, NORTH, NORTH_EAST, EAST, SOUTH_EAST;

		public String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		/** Minecraft yaw for this facing: 0 = south, 90 = west, 180 = north, 270 = east. */
		public float yaw() {
			return ordinal() * 45.0F;
		}
	}

	/**
	 * A statue anchor: number is the fill order (unique, gaps allowed); dx/dy/dz is the exact offset of the centre of
	 * the block's floor (x and z end in .5) from the hall origin block's corner.
	 */
	public record Statue(int number, double dx, double dy, double dz, Facing8 facing) {
		public double[] absolute(int originX, int originY, int originZ) {
			return new double[] {originX + dx, originY + dy, originZ + dz};
		}
	}

	/**
	 * Where a record board sits relative to its anchor block: height = the board's TOP edge above the anchor's
	 * floor (the anchor block's y), forward = along the anchor's facing from the block centre, side = to the right
	 * of the facing; scale = the text scale (for the causes board the MAXIMUM; it shrinks to stay on its wall).
	 * faceWidth / faceBottom: the measured wall face (width, and its bottom edge above the anchor floor) for wall
	 * boards, NaN when not measured. Set by autofit or /ht hall boards.
	 */
	public record BoardPlacement(double scale, double height, double forward, double side, double faceWidth, double faceBottom) {
		public BoardPlacement(double scale, double height, double forward, double side) {
			this(scale, height, forward, side, Double.NaN, Double.NaN);
		}

		public BoardPlacement with(double scale, double height, double forward, double side) {
			return new BoardPlacement(scale, height, forward, side, faceWidth, faceBottom);
		}

		public boolean hasFace() {
			return !Double.isNaN(faceWidth) && !Double.isNaN(faceBottom);
		}
	}

	/** One anchor; dx/dy/dz are the block offset from the hall origin. */
	public record Anchor(int id, AnchorType type, String label, int dx, int dy, int dz, Facing facing) {
	}

	/** The hall spawn point; dx/dy/dz are the exact offset from the hall origin block's corner. */
	public record Spawn(double dx, double dy, double dz, float yaw, float pitch) {
		public static Spawn at(double x, double y, double z, int originX, int originY, int originZ, float yaw, float pitch) {
			return new Spawn(x - originX, y - originY, z - originZ, yaw, pitch);
		}

		public double[] absolute(int originX, int originY, int originZ) {
			return new double[] {originX + dx, originY + dy, originZ + dz};
		}
	}

	private final List<Anchor> anchors = new ArrayList<>();
	private int nextId = 1;
	private Spawn spawn;
	private final TreeMap<Integer, Statue> statues = new TreeMap<>();
	/** Arrow number (1..3) to its box corners pos1/pos2 (block offsets, either may be null). */
	private final TreeMap<Integer, int[][]> arrowBoxes = new TreeMap<>();
	private int[] arrowTarget;
	/** Portal zone corners (block offsets from the origin), null until set with /ht hall portal pos1|pos2. */
	private int[] portalPos1, portalPos2;
	/** Record board placements by board name (deaths, runs, causes). */
	private final TreeMap<String, BoardPlacement> boards = new TreeMap<>();
	/** Arrow light mode recorded when a backup or export was written (the live mode is in the config). */
	private String arrowsMode;
	/** Run number of a death to the statue anchor number it was given. */
	private final TreeMap<Integer, Integer> statueRuns = new TreeMap<>();

	/**
	 * Snaps a Minecraft yaw (0 = south, 90 = west, 180 = north, 270 or -90 = east; any multiple of 360 added)
	 * to the nearest of the four facings. Exact halfway angles go to the next facing clockwise.
	 */
	public static Facing snapFacing(double yaw) {
		double normalized = ((yaw % 360.0) + 360.0) % 360.0;
		int quarter = (int) Math.floor((normalized + 45.0) / 90.0) & 3;
		return Facing.values()[quarter];
	}

	/** Snaps a Minecraft yaw to the nearest of the eight statue facings; exact halfway angles go clockwise. */
	public static Facing8 snapFacing8(double yaw) {
		double normalized = ((yaw % 360.0) + 360.0) % 360.0;
		int eighth = (int) Math.floor((normalized + 22.5) / 45.0) & 7;
		return Facing8.values()[eighth];
	}

	/** Adds an anchor at an absolute block position, stored relative to the given hall origin. */
	public Anchor add(AnchorType type, String label, int x, int y, int z, int originX, int originY, int originZ, double yaw) {
		Anchor anchor = new Anchor(nextId++, type, label == null ? "" : label.trim(), x - originX, y - originY, z - originZ, snapFacing(yaw));
		anchors.add(anchor);
		return anchor;
	}

	public boolean remove(int id) {
		return anchors.removeIf(a -> a.id() == id);
	}

	public Optional<Anchor> get(int id) {
		return anchors.stream().filter(a -> a.id() == id).findFirst();
	}

	public List<Anchor> anchors() {
		return List.copyOf(anchors);
	}

	public int nextId() {
		return nextId;
	}

	/** The spawn set with /ht hall setspawn, or empty to use the built-in one. */
	public Optional<Spawn> spawn() {
		return Optional.ofNullable(spawn);
	}

	public void setSpawn(Spawn spawn) {
		this.spawn = spawn;
	}

	// ---- Statues ----

	/**
	 * Adds statue anchor {@code number} at the centre of the given block, facing the yaw snapped to eight directions.
	 * Throws IllegalArgumentException if the number is not positive or already used. Does not assign deaths.
	 */
	public Statue addStatue(int number, int x, int y, int z, int originX, int originY, int originZ, double yaw) {
		if (number < 1) {
			throw new IllegalArgumentException("statue number must be 1 or more");
		}
		if (statues.containsKey(number)) {
			throw new IllegalArgumentException("statue anchor " + number + " already exists");
		}
		Statue statue = new Statue(number, x - originX + 0.5, y - originY, z - originZ + 0.5, snapFacing8(yaw));
		statues.put(number, statue);
		return statue;
	}

	/**
	 * Re-places an existing statue anchor at the centre of block x, y, z with a new facing. It keeps its number and
	 * the death assigned to it, so the statue follows the anchor.
	 */
	public Statue moveStatue(int number, int x, int y, int z, int originX, int originY, int originZ, double yaw) {
		if (!statues.containsKey(number)) {
			throw new IllegalArgumentException("no statue anchor " + number);
		}
		Statue statue = new Statue(number, x - originX + 0.5, y - originY, z - originZ + 0.5, snapFacing8(yaw));
		statues.put(number, statue);
		return statue;
	}

	/** Removes a statue anchor; the death on it (if any) becomes unplaced. Does not refill. */
	public boolean removeStatue(int number) {
		if (statues.remove(number) == null) {
			return false;
		}
		statueRuns.values().removeIf(n -> n == number);
		return true;
	}

	public Optional<Statue> statue(int number) {
		return Optional.ofNullable(statues.get(number));
	}

	/** Statue anchors in ascending number. */
	public List<Statue> statues() {
		return List.copyOf(statues.values());
	}

	/** Run number to statue anchor number, in ascending run order. */
	public Map<Integer, Integer> statueAssignments() {
		return new LinkedHashMap<>(statueRuns);
	}

	/** The run whose death stands on this statue anchor, if any. */
	public Optional<Integer> runOnStatue(int number) {
		return statueRuns.entrySet().stream().filter(e -> e.getValue() == number).map(Map.Entry::getKey).findFirst();
	}

	void assignStatue(int run, int number) {
		statueRuns.put(run, number);
	}

	void unassignStatue(int run) {
		statueRuns.remove(run);
	}

	void clearStatueAssignments() {
		statueRuns.clear();
	}

	/**
	 * A copy for shipping in the mod (hall export): anchors, spawn, statue anchors and arrows, but no statue assignments,
	 * so a fresh install starts with every statue anchor free.
	 */
	public HallLayout forExport() throws IOException {
		HallLayout copy = parse(toJsonString());
		copy.clearStatueAssignments();
		return copy;
	}

	// ---- Floor arrows ----

	public static final int ARROWS = 3;

	/** Sets corner 1 or 2 of an arrow's box at an absolute block position. */
	public void setArrowCorner(int arrow, int corner, int x, int y, int z, int originX, int originY, int originZ) {
		if (arrow < 1 || arrow > ARROWS || corner < 1 || corner > 2) {
			throw new IllegalArgumentException("arrow 1-" + ARROWS + ", corner 1-2");
		}
		arrowBoxes.computeIfAbsent(arrow, a -> new int[2][]);
		arrowBoxes.get(arrow)[corner - 1] = new int[] {x - originX, y - originY, z - originZ};
	}

	/** Corner 1 or 2 of an arrow's box as an offset from the hall origin, or empty if not set. */
	public Optional<int[]> arrowCorner(int arrow, int corner) {
		int[][] box = arrowBoxes.get(arrow);
		return box == null || box[corner - 1] == null ? Optional.empty() : Optional.of(box[corner - 1].clone());
	}

	public void setArrowTarget(int x, int y, int z, int originX, int originY, int originZ) {
		arrowTarget = new int[] {x - originX, y - originY, z - originZ};
	}

	/** The block the arrows lead to, as an offset from the hall origin. */
	public Optional<int[]> arrowTarget() {
		return Optional.ofNullable(arrowTarget).map(int[]::clone);
	}

	public Optional<String> arrowsMode() {
		return Optional.ofNullable(arrowsMode);
	}

	public void setArrowsMode(String mode) {
		arrowsMode = mode;
	}

	// ---- Portal zone ----

	public void setPortalCorner(int corner, int x, int y, int z, int originX, int originY, int originZ) {
		int[] p = {x - originX, y - originY, z - originZ};
		if (corner == 1) {
			portalPos1 = p;
		} else {
			portalPos2 = p;
		}
	}

	/** Portal zone corner 1 or 2 (offset from the origin), if set. */
	public Optional<int[]> portalCorner(int corner) {
		int[] p = corner == 1 ? portalPos1 : portalPos2;
		return Optional.ofNullable(p).map(int[]::clone);
	}

	/** Replaces this layout's portal zone with the other layout's (hall restore). */
	public void replacePortal(HallLayout other) {
		portalPos1 = other.portalPos1;
		portalPos2 = other.portalPos2;
	}

	// ---- Record boards ----

	public Optional<BoardPlacement> board(String name) {
		return Optional.ofNullable(boards.get(name));
	}

	public void setBoard(String name, BoardPlacement placement) {
		boards.put(name, placement);
	}

	public Map<String, BoardPlacement> boards() {
		return new LinkedHashMap<>(boards);
	}

	/** Replaces this layout's board placements with the other layout's (hall restore). */
	public void replaceBoards(HallLayout other) {
		boards.clear();
		boards.putAll(other.boards);
	}

	/** Replaces this layout's arrow boxes, target and recorded mode with the other layout's (hall restore). */
	public void replaceArrows(HallLayout other) {
		arrowBoxes.clear();
		other.arrowBoxes.forEach((n, box) -> arrowBoxes.put(n, new int[][] {box[0], box[1]}));
		arrowTarget = other.arrowTarget;
		arrowsMode = other.arrowsMode;
	}

	/** Replaces this layout's statue anchors and assignments with the other layout's (hall restore). */
	public void replaceStatues(HallLayout other) {
		statues.clear();
		statues.putAll(other.statues);
		statueRuns.clear();
		statueRuns.putAll(other.statueRuns);
	}

	/** Absolute position of an anchor for a hall whose origin is at the given block. */
	public static int[] absolute(Anchor anchor, int originX, int originY, int originZ) {
		return new int[] {originX + anchor.dx(), originY + anchor.dy(), originZ + anchor.dz()};
	}

	// ---- File ----

	/** Loads the layout, or an empty one if the file doesn't exist. Throws if it exists but can't be read. */
	public static HallLayout load(Path folder) throws IOException {
		Path file = folder.resolve(FILE_NAME);
		if (Files.notExists(file)) {
			return new HallLayout();
		}
		return parse(Files.readString(file));
	}

	/** Reads a layout from its JSON text (the file, or the copy stored in a hall backup). */
	public static HallLayout parse(String text) throws IOException {
		HallLayout layout = new HallLayout();
		try {
			JsonObject json = JsonParser.parseString(text).getAsJsonObject();
			for (JsonElement e : json.getAsJsonArray("anchors")) {
				JsonObject a = e.getAsJsonObject();
				String typeId = a.get("type").getAsString();
				AnchorType type = AnchorType.byId(typeId).orElseThrow(() -> new IOException("unknown anchor type " + typeId));
				Facing facing = Facing.valueOf(a.get("facing").getAsString().toUpperCase(java.util.Locale.ROOT));
				layout.anchors.add(new Anchor(a.get("id").getAsInt(), type, a.get("label").getAsString(),
					a.get("dx").getAsInt(), a.get("dy").getAsInt(), a.get("dz").getAsInt(), facing));
			}
			int maxId = layout.anchors.stream().mapToInt(Anchor::id).max().orElse(0);
			if (json.has("spawn")) {
				JsonObject sp = json.getAsJsonObject("spawn");
				layout.spawn = new Spawn(sp.get("dx").getAsDouble(), sp.get("dy").getAsDouble(), sp.get("dz").getAsDouble(),
					sp.get("yaw").getAsFloat(), sp.get("pitch").getAsFloat());
			}
			if (json.has("statues")) {
				for (JsonElement e : json.getAsJsonArray("statues")) {
					JsonObject s = e.getAsJsonObject();
					Statue statue = new Statue(s.get("number").getAsInt(), s.get("dx").getAsDouble(), s.get("dy").getAsDouble(),
						s.get("dz").getAsDouble(), Facing8.valueOf(s.get("facing").getAsString().toUpperCase(java.util.Locale.ROOT)));
					if (layout.statues.put(statue.number(), statue) != null) {
						throw new IOException("duplicate statue anchor " + statue.number());
					}
				}
			}
			if (json.has("statue_assignments")) {
				for (JsonElement e : json.getAsJsonArray("statue_assignments")) {
					JsonObject s = e.getAsJsonObject();
					int number = s.get("statue").getAsInt();
					if (layout.statues.containsKey(number) && !layout.statueRuns.containsValue(number)) {
						layout.statueRuns.put(s.get("run").getAsInt(), number);
					}
				}
			}
			if (json.has("arrows")) {
				JsonObject arrows = json.getAsJsonObject("arrows");
				for (JsonElement e : arrows.getAsJsonArray("boxes")) {
					JsonObject b = e.getAsJsonObject();
					int arrow = b.get("arrow").getAsInt();
					if (arrow < 1 || arrow > ARROWS) {
						throw new IOException("arrow number " + arrow + " out of range");
					}
					layout.arrowBoxes.put(arrow, new int[][] {readPos(b, "pos1"), readPos(b, "pos2")});
				}
				layout.arrowTarget = readPos(arrows, "target");
				layout.arrowsMode = arrows.has("mode") ? arrows.get("mode").getAsString() : null;
			}
			if (json.has("portal")) {
				JsonObject portal = json.getAsJsonObject("portal");
				layout.portalPos1 = readPos(portal, "pos1");
				layout.portalPos2 = readPos(portal, "pos2");
			}
			if (json.has("boards")) {
				JsonObject b = json.getAsJsonObject("boards");
				for (String name : b.keySet()) {
					JsonObject p = b.getAsJsonObject(name);
					layout.boards.put(name, new BoardPlacement(p.get("scale").getAsDouble(), p.get("height").getAsDouble(),
						p.get("forward").getAsDouble(), p.has("side") ? p.get("side").getAsDouble() : 0.0,
						p.has("face_width") ? p.get("face_width").getAsDouble() : Double.NaN,
						p.has("face_bottom") ? p.get("face_bottom").getAsDouble() : Double.NaN));
				}
			}
			layout.nextId = Math.max(json.has("next_id") ? json.get("next_id").getAsInt() : 1, maxId + 1);
			return layout;
		} catch (RuntimeException e) {
			throw new IOException("unreadable " + FILE_NAME + ": " + e.getMessage(), e);
		}
	}

	public void save(Path folder) throws IOException {
		Files.createDirectories(folder);
		JsonFiles.writeAtomically(folder.resolve(FILE_NAME), toJsonString());
	}

	public String toJsonString() {
		JsonObject json = new JsonObject();
		json.addProperty("format", FORMAT);
		json.addProperty("origin", "offsets from the hall spawn floor block (0, 64, 0)");
		json.addProperty("next_id", nextId);
		JsonArray list = new JsonArray();
		for (Anchor a : anchors) {
			JsonObject o = new JsonObject();
			o.addProperty("id", a.id());
			o.addProperty("type", a.type().id());
			o.addProperty("label", a.label());
			o.addProperty("dx", a.dx());
			o.addProperty("dy", a.dy());
			o.addProperty("dz", a.dz());
			o.addProperty("facing", a.facing().id());
			list.add(o);
		}
		json.add("anchors", list);
		if (spawn != null) {
			JsonObject sp = new JsonObject();
			sp.addProperty("dx", spawn.dx());
			sp.addProperty("dy", spawn.dy());
			sp.addProperty("dz", spawn.dz());
			sp.addProperty("yaw", spawn.yaw());
			sp.addProperty("pitch", spawn.pitch());
			json.add("spawn", sp);
		}
		JsonArray statueList = new JsonArray();
		for (Statue s : statues.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("number", s.number());
			o.addProperty("dx", s.dx());
			o.addProperty("dy", s.dy());
			o.addProperty("dz", s.dz());
			o.addProperty("facing", s.facing().id());
			statueList.add(o);
		}
		json.add("statues", statueList);
		JsonArray assigned = new JsonArray();
		statueRuns.forEach((run, number) -> {
			JsonObject o = new JsonObject();
			o.addProperty("run", run);
			o.addProperty("statue", number);
			assigned.add(o);
		});
		json.add("statue_assignments", assigned);
		JsonObject arrows = new JsonObject();
		JsonArray boxes = new JsonArray();
		arrowBoxes.forEach((n, box) -> {
			JsonObject b = new JsonObject();
			b.addProperty("arrow", n);
			writePos(b, "pos1", box[0]);
			writePos(b, "pos2", box[1]);
			boxes.add(b);
		});
		arrows.add("boxes", boxes);
		writePos(arrows, "target", arrowTarget);
		if (arrowsMode != null) {
			arrows.addProperty("mode", arrowsMode);
		}
		json.add("arrows", arrows);
		JsonObject boardJson = new JsonObject();
		boards.forEach((name, p) -> {
			JsonObject o = new JsonObject();
			o.addProperty("scale", p.scale());
			o.addProperty("height", p.height());
			o.addProperty("forward", p.forward());
			o.addProperty("side", p.side());
			if (p.hasFace()) {
				o.addProperty("face_width", p.faceWidth());
				o.addProperty("face_bottom", p.faceBottom());
			}
			boardJson.add(name, o);
		});
		json.add("boards", boardJson);
		JsonObject portal = new JsonObject();
		writePos(portal, "pos1", portalPos1);
		writePos(portal, "pos2", portalPos2);
		json.add("portal", portal);
		return GSON.toJson(json);
	}

	private static int[] readPos(JsonObject o, String key) {
		if (!o.has(key)) {
			return null;
		}
		JsonArray a = o.getAsJsonArray(key);
		return new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
	}

	private static void writePos(JsonObject o, String key, int[] pos) {
		if (pos != null) {
			JsonArray a = new JsonArray();
			for (int v : pos) {
				a.add(v);
			}
			o.add(key, a);
		}
	}
}
