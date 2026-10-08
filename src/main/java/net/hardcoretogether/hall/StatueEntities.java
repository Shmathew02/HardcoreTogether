package net.hardcoretogether.hall;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.data.MemorialData;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunHistoryEntry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueOutput;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The statues in the Death Hall: for every death with a statue assignment, a mannequin with the player's skin at
 * the statue anchor and a text plaque in front of it. Assignments (hall-layout.json) are the source of truth; the
 * entities are only a view, rebuilt by {@link #request reconcile}. Both entities carry command tags with their role
 * and run number, which is how reconcile finds them.
 *
 * Reconcile tickets every hall chunk and waits until their entities are loaded before it compares anything, so it
 * never takes "not loaded yet" for "missing" and spawns a duplicate. It is idempotent: a second run changes nothing.
 */
public final class StatueEntities {
	public static final String TAG_STATUE = "hardcore_together.statue";
	public static final String TAG_PLAQUE = "hardcore_together.plaque";
	public static final String TAG_RUN = "hardcore_together.run.";
	public static final String TAG_RECORD = "hardcore_together.record";
	public static final String TAG_BOARD = "hardcore_together.board.";
	/** Keeps hall chunks (and their entities) loaded while a reconcile waits; never saved. */
	private static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, HardcoreTogether.id("hall_statues"),
		new TicketType(0L, TicketType.FLAG_LOADING));
	/** Give up waiting for entities after this many ticks (logged; the next trigger tries again). */
	private static final int WAIT_LIMIT_TICKS = 400;
	/** Plaque bottom above the statue's feet: the 1.8-block mannequin plus 0.3 clearance over its head. */
	private static final double PLAQUE_HEIGHT = 1.8 + 0.3;
	/** Wide enough that no plaque line ever wraps (vanilla wraps at line_width pixels). */
	private static final int PLAQUE_LINE_WIDTH = 1000;
	private static final float PLAQUE_SCALE = 0.5F;
	/** Statue keys compared with what the entity saves; anything else (motion, air, ...) is not ours. */
	private static final List<String> STATUE_KEYS = List.of("profile", "pose", "immovable", "hide_description", "Invulnerable", "Silent");
	private static final List<String> DISPLAY_KEYS = List.of("text", "billboard", "background", "line_width", "alignment", "shadow",
		"transformation");

	public record Result(int spawned, int updated, int removed) {
		public boolean changed() {
			return spawned + updated + removed > 0;
		}
	}

	private enum Role {
		STATUE(TAG_STATUE), PLAQUE(TAG_PLAQUE), RECORD(TAG_RECORD);

		final String tag;

		Role(String tag) {
			this.tag = tag;
		}
	}

	/** id: the run number for a statue or plaque, the board name for a record board. */
	private record Key(Role role, String id) {
	}

	/** What one entity should be: its spawn tag, where it stands and which saved keys must match. */
	private record Want(Role role, String id, CompoundTag tag, double x, double y, double z, float yaw) {
	}

	private static final Set<String> reasons = new LinkedHashSet<>();
	private static final List<Consumer<@Nullable Result>> waiting = new ArrayList<>();
	private static @Nullable List<ChunkPos> ticketed;
	private static long waitStart;

	private StatueEntities() {
	}

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(StatueEntities::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			release(server);
			reasons.clear();
			waiting.clear();
		});
	}

	/**
	 * Asks for a reconcile. It runs at once if the hall's entities are already loaded, otherwise as soon as they
	 * are (usually a few ticks). {@code done} gets the counts, or null if it could not run.
	 */
	public static void request(MinecraftServer server, String why, @Nullable Consumer<@Nullable Result> done) {
		reasons.add(why);
		if (done != null) {
			waiting.add(done);
		}
		if (ticketed == null) {
			ServerLevel hall = DeathHall.level(server);
			if (hall == null) {
				finish(null);
				return;
			}
			List<ChunkPos> chunks = new ArrayList<>();
			BlockPos lo = HallBuilder.regionMin(), hi = HallBuilder.regionMax();
			for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) {
				for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) {
					ChunkPos cp = new ChunkPos(cx, cz);
					hall.getChunkSource().addTicketWithRadius(TICKET, cp, 0);
					chunks.add(cp);
				}
			}
			ticketed = chunks;
			waitStart = server.getTickCount();
		}
		tryRun(server);
	}

	private static void tick(MinecraftServer server) {
		if (ticketed != null) {
			tryRun(server);
		}
	}

	private static void tryRun(MinecraftServer server) {
		ServerLevel hall = DeathHall.level(server);
		List<ChunkPos> chunks = ticketed;
		if (hall == null || chunks == null) {
			return;
		}
		for (ChunkPos cp : chunks) {
			if (!hall.areEntitiesLoaded(cp.pack())) {
				if (server.getTickCount() - waitStart > WAIT_LIMIT_TICKS) {
					HardcoreTogether.LOGGER.warn("[HT statues] reconcile ({}) gave up: hall entities not loaded after {} ticks",
						String.join(", ", reasons), WAIT_LIMIT_TICKS);
					release(server);
					finish(null);
				}
				return;
			}
		}
		String why = String.join(", ", reasons);
		Result result = null;
		try {
			result = reconcileNow(hall);
			long waited = server.getTickCount() - waitStart;
			HardcoreTogether.LOGGER.info("[HT statues] reconcile ({}): {} spawned, {} updated, {} removed (entities ready after {} tick(s))", why,
				result.spawned(), result.updated(), result.removed(), waited);
		} catch (IOException | RuntimeException e) {
			HardcoreTogether.LOGGER.error("[HT statues] reconcile ({}) failed", why, e);
		}
		release(server);
		finish(result);
	}

	private static void release(MinecraftServer server) {
		ServerLevel hall = DeathHall.level(server);
		if (ticketed != null && hall != null) {
			ticketed.forEach(cp -> hall.getChunkSource().removeTicketWithRadius(TICKET, cp, 0));
		}
		ticketed = null;
	}

	private static void finish(@Nullable Result result) {
		List<Consumer<@Nullable Result>> callbacks = List.copyOf(waiting);
		waiting.clear();
		reasons.clear();
		callbacks.forEach(c -> c.accept(result));
	}

	// ---- Reconcile ----

	private static Result reconcileNow(ServerLevel hall) throws IOException {
		HtWorldData data = HardcoreTogether.worldData();
		if (data == null) {
			throw new IOException("data folder unavailable");
		}
		HallLayout layout = HallLayout.load(data.folder());
		HallStatues.sync(data, layout, "statue reconcile");

		List<Want> wants = new ArrayList<>();
		BlockPos o = DeathHall.ORIGIN;
		layout.statueAssignments().forEach((run, number) -> {
			Optional<HallLayout.Statue> statue = layout.statue(number);
			Optional<RunHistoryEntry> entry = MemorialData.get().run(run);
			if (statue.isPresent() && entry.isPresent() && entry.get().death() != null) {
				double[] p = statue.get().absolute(o.getX(), o.getY(), o.getZ());
				float yaw = statue.get().facing().yaw();
				wants.add(new Want(Role.STATUE, String.valueOf(run), statueTag(hall, run, entry.get().death(), yaw), p[0], p[1], p[2], yaw));
				// Centred above the head; a vertical billboard turns it to every viewer, a fixed one faces the statue's way.
				wants.add(new Want(Role.PLAQUE, String.valueOf(run), displayTag(hall, Role.PLAQUE, String.valueOf(run), StatuePlaque.text(entry.get()),
					HardcoreTogether.config().plaqueBillboard(), PLAQUE_SCALE, yaw, HardcoreTogether.config().plaqueBackgroundArgb(), 0.0F),
					p[0], p[1] + PLAQUE_HEIGHT, p[2], yaw));
			}
		});
		wants.addAll(boardWants(hall, layout));

		Map<Key, List<Entity>> existing = new HashMap<>();
		for (Entity e : hall.getAllEntities()) {
			Key key = keyOf(e);
			if (key != null && !e.isRemoved()) {
				existing.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
			}
		}

		int spawned = 0, updated = 0, removed = 0;
		for (Want want : wants) {
			List<Entity> found = existing.remove(new Key(want.role(), want.id()));
			Entity keep = null;
			if (found != null) {
				CompoundTag expected = expectedSave(hall, want);
				for (Entity e : found) {
					if (keep == null && matches(hall, e, want, expected)) {
						keep = e;
					}
				}
			}
			if (keep == null) {
				spawn(hall, want);
				if (found == null || found.isEmpty()) {
					spawned++;
				} else {
					updated++; // the wrong one(s) go below; the first one counts as updated, the rest as removed
					removed--;
				}
			}
			if (found != null) {
				for (Entity e : found) {
					if (e != keep) {
						e.discard();
						removed++;
					}
				}
			}
		}
		for (List<Entity> orphans : existing.values()) {
			for (Entity e : orphans) {
				e.discard();
				removed++;
			}
		}
		return new Result(spawned, updated, removed);
	}

	private static @Nullable Key keyOf(Entity e) {
		Set<String> tags = e.entityTags();
		Role role = tags.contains(TAG_STATUE) ? Role.STATUE : tags.contains(TAG_PLAQUE) ? Role.PLAQUE
			: tags.contains(TAG_RECORD) ? Role.RECORD : null;
		if (role == null) {
			return null;
		}
		String prefix = role == Role.RECORD ? TAG_BOARD : TAG_RUN;
		for (String t : tags) {
			if (t.startsWith(prefix)) {
				return new Key(role, t.substring(prefix.length()));
			}
		}
		return new Key(role, ""); // malformed: no wanted entity has this key, so it is removed as an orphan
	}

	private static boolean matches(ServerLevel hall, Entity e, Want want, CompoundTag expected) {
		if (!e.isAlive() || (e instanceof LivingEntity living && living.isDeadOrDying())) {
			return false;
		}
		if (e.distanceToSqr(want.x(), want.y(), want.z()) > 1.0E-4 || Math.abs(wrap(e.getYRot() - want.yaw())) > 0.5F) {
			return false;
		}
		CompoundTag saved = save(hall, e);
		for (String key : want.role() == Role.STATUE ? STATUE_KEYS : DISPLAY_KEYS) {
			if (!java.util.Objects.equals(saved.get(key), expected.get(key))) {
				return false;
			}
		}
		return true;
	}

	private static float wrap(float degrees) {
		float d = degrees % 360.0F;
		return d > 180 ? d - 360 : d < -180 ? d + 360 : d;
	}

	/** What a freshly made entity from this tag saves, so comparisons use the game's own normalised form. */
	private static CompoundTag expectedSave(ServerLevel hall, Want want) {
		Entity probe = create(hall, want);
		return probe == null ? new CompoundTag() : save(hall, probe);
	}

	private static CompoundTag save(ServerLevel hall, Entity e) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, hall.registryAccess());
		e.saveWithoutId(out);
		return out.buildResult();
	}

	private static @Nullable Entity create(ServerLevel hall, Want want) {
		return EntityType.loadEntityRecursive(want.tag().copy(), hall, new EntitySpawnRequest(EntitySpawnReason.COMMAND, false), e -> {
			e.snapTo(want.x(), want.y(), want.z(), want.yaw(), 0.0F);
			if (e instanceof LivingEntity living) {
				living.setYHeadRot(want.yaw());
				living.setYBodyRot(want.yaw());
			}
			return e;
		});
	}

	private static void spawn(ServerLevel hall, Want want) {
		Entity e = create(hall, want);
		if (e == null || !hall.addFreshEntity(e)) {
			HardcoreTogether.LOGGER.error("[HT statues] could not spawn the {} for run #{}", want.role().name().toLowerCase(java.util.Locale.ROOT),
				want.id());
		}
	}

	// ---- Entity data ----

	private static ListTag tags(Role role, String id) {
		ListTag tags = new ListTag();
		tags.add(StringTag.valueOf(role.tag));
		tags.add(StringTag.valueOf((role == Role.RECORD ? TAG_BOARD : TAG_RUN) + id));
		return tags;
	}

	private static ListTag rotation(float yaw) {
		ListTag r = new ListTag();
		r.add(FloatTag.valueOf(yaw));
		r.add(FloatTag.valueOf(0.0F));
		return r;
	}

	/** The mannequin: the player's UUID, name and captured skin, or the UUID alone so the game looks the skin up. */
	private static CompoundTag statueTag(ServerLevel hall, int run, RunDeath death, float yaw) {
		ResolvableProfile profile;
		RunDeath.SkinTextures skin = death.skin();
		if (skin != null) {
			Property textures = skin.signature() == null ? new Property("textures", skin.value())
				: new Property("textures", skin.value(), skin.signature());
			profile = ResolvableProfile.createResolved(new GameProfile(death.uuid(), death.name(),
				new PropertyMap(ImmutableMultimap.of("textures", textures))));
		} else {
			profile = ResolvableProfile.createUnresolved(death.uuid());
		}
		CompoundTag tag = new CompoundTag();
		tag.putString("id", "minecraft:mannequin");
		tag.put("profile", ResolvableProfile.CODEC.encodeStart(hall.registryAccess().createSerializationContext(NbtOps.INSTANCE), profile)
			.getOrThrow());
		tag.putString("pose", "standing");
		tag.putBoolean("immovable", true);
		tag.putBoolean("hide_description", true);
		tag.putBoolean("Invulnerable", true);
		tag.putBoolean("Silent", true);
		tag.put("Rotation", rotation(yaw));
		tag.put("Tags", tags(Role.STATUE, String.valueOf(run)));
		return tag;
	}

	/**
	 * The record boards: one per board type, on the lowest-id anchor of its type, placed by the layout's board
	 * placement (made by autofit the first time). The entity stands at the board's TOP edge and the text hangs below
	 * it, so more lines grow downward. A fixed board faces the anchor's facing like a sign.
	 */
	private static List<Want> boardWants(ServerLevel hall, HallLayout layout) throws IOException {
		List<Want> wants = new ArrayList<>();
		net.hardcoretogether.config.HtConfig config = HardcoreTogether.config();
		List<RunHistoryEntry> history = MemorialData.get().recentRuns(Integer.MAX_VALUE);
		List<RecordBoards.PlayerRow> players = new ArrayList<>();
		net.hardcoretogether.data.PlayerRecords records = HardcoreTogether.playerRecords();
		if (records != null) {
			records.all().forEach((uuid, e) -> players.add(new RecordBoards.PlayerRow(uuid, e.name() == null ? uuid.toString() : e.name(), e.firstSeen())));
		}
		BlockPos o = DeathHall.ORIGIN;
		boolean fitted = false;
		for (RecordBoards.Board board : RecordBoards.Board.values()) {
			Optional<HallLayout.Anchor> anchor = layout.anchors().stream().filter(a -> a.type() == board.anchor)
				.min(java.util.Comparator.comparingInt(HallLayout.Anchor::id));
			if (anchor.isEmpty()) {
				continue;
			}
			List<String> body = switch (board) {
				case DEATHS -> RecordBoards.deaths(players, history, config.deathsBoardMaxPlayers());
				case RUNS -> RecordBoards.runs(history);
				case CAUSES -> RecordBoards.causes(history);
			};
			float yaw = facingYaw(anchor.get().facing());
			int[] p = HallLayout.absolute(anchor.get(), o.getX(), o.getY(), o.getZ());
			double[] size = RecordBoards.size(board, body);
			HallLayout.BoardPlacement place = layout.board(board.id()).orElse(null);
			if (place == null) {
				BoardFit.Fit fit = autofit(hall, board, new BlockPos(p[0], p[1], p[2]), anchor.get().facing(), size);
				place = fit.placement();
				layout.setBoard(board.id(), place);
				fitted = true;
				HardcoreTogether.LOGGER.info("[HT boards] {} autofit: {}; scale {}, top {} above the floor, forward {}, side {}", board.id(), fit.note(),
					place.scale(), place.height(), place.forward(), place.side());
			}
			if (!board.vertical && !place.hasFace()) {
				// Placed before the wall face was stored: measure it, keep the (possibly hand-tuned) placement.
				HallLayout.BoardPlacement measured = autofit(hall, board, new BlockPos(p[0], p[1], p[2]), anchor.get().facing(), size).placement();
				if (measured.hasFace()) {
					place = new HallLayout.BoardPlacement(place.scale(), place.height(), place.forward(), place.side(), measured.faceWidth(),
						measured.faceBottom());
					layout.setBoard(board.id(), place);
					fitted = true;
					HardcoreTogether.LOGGER.info("[HT boards] {} wall face measured: {} wide, bottom {} above the floor", board.id(),
						place.faceWidth(), place.faceBottom());
				}
			}
			int[] f = facingVector(anchor.get().facing());
			double x = p[0] + 0.5 + f[0] * place.forward() - f[1] * place.side();
			double z = p[2] + 0.5 + f[1] * place.forward() + f[0] * place.side();
			int background = board.vertical ? config.plaqueBackgroundArgb() : config.wallBoardBackgroundArgb();
			double scale = place.scale();
			if (board == RecordBoards.Board.CAUSES && place.hasFace()) {
				RecordBoards.Fitted shrunk = causesFit(place, history);
				if (shrunk.twoColumns()) {
					wants.addAll(causesColumnWants(hall, board, shrunk, place, p, anchor.get().facing(), yaw, background, config.recordsTitleColor()));
					continue;
				}
				body = shrunk.body();
				scale = shrunk.scale();
				size = RecordBoards.size(board, body);
			}
			CompoundTag tag = displayTag(hall, Role.RECORD, board.id(), RecordBoards.render(board, body, config.recordsTitleColor()),
				board.vertical ? "vertical" : "fixed", (float) scale, yaw, background, (float) -(scale * size[1]));
			wants.add(new Want(Role.RECORD, board.id(), tag, x, p[1] + place.height(), z, yaw));
		}
		if (fitted) {
			HtWorldData data = HardcoreTogether.worldData();
			if (data != null) {
				layout.save(data.folder());
			}
		}
		return wants;
	}

	/** The causes board's lines and effective scale for this placement (its scale is the maximum). */
	public static RecordBoards.Fitted causesFit(HallLayout.BoardPlacement place, List<RunHistoryEntry> history) {
		return RecordBoards.fitCauses(history, place.scale(), HardcoreTogether.config().causesBoardMinTextScale(),
			place.height() - place.faceBottom(), place.faceWidth());
	}

	/** Lift of the column text off the backing panel, so the two never z-fight. */
	private static final double COLUMN_LIFT = 0.01;

	/**
	 * A two-column causes board: the backing (title and the background for the whole board) under the board's own
	 * id, and one left-aligned, backgroundless display per column (ids causes.left and causes.right) starting one
	 * line below the top. The reader's left is the anchor's right, so the left column sits at +side.
	 */
	private static List<Want> causesColumnWants(ServerLevel hall, RecordBoards.Board board, RecordBoards.Fitted fitted,
			HallLayout.BoardPlacement place, int[] p, HallLayout.Facing facing, float yaw, int background, int titleRgb) {
		double scale = fitted.scale();
		double[] size = RecordBoards.columnsSize(fitted);
		int[] f = facingVector(facing);
		double top = p[1] + place.height();
		List<Want> out = new ArrayList<>();
		CompoundTag backing = displayTag(hall, Role.RECORD, board.id(), RecordBoards.render(board, RecordBoards.columnsBacking(fitted), titleRgb),
			"fixed", (float) scale, yaw, background, (float) -(scale * size[1]));
		out.add(boardWant(board.id(), backing, p, f, place.forward(), place.side(), top, yaw));
		double lineHeight = 10 * RecordBoards.PIXEL * scale;
		for (int column = 0; column < 2; column++) {
			List<String> lines = column == 0 ? fitted.body() : fitted.right();
			double width = size[2 + column];
			double side = place.side() + (column == 0 ? 1 : -1) * (size[0] - width) / 2 * scale;
			String id = board.id() + (column == 0 ? ".left" : ".right");
			CompoundTag tag = displayTag(hall, Role.RECORD, id, RecordBoards.renderColumn(lines), "fixed", (float) scale, yaw, 0,
				(float) -(scale * (lines.size() * 10 + 1) * RecordBoards.PIXEL), "left");
			out.add(boardWant(id, tag, p, f, place.forward() + COLUMN_LIFT, side, top - lineHeight, yaw));
		}
		return out;
	}

	private static Want boardWant(String id, CompoundTag tag, int[] p, int[] f, double forward, double side, double y, float yaw) {
		return new Want(Role.RECORD, id, tag, p[0] + 0.5 + f[0] * forward - f[1] * side, y, p[2] + 0.5 + f[1] * forward + f[0] * side, yaw);
	}

	/** Measures the space around a board's anchor; a wall board without a wall falls back to the free-standing fit. */
	static BoardFit.Fit autofit(ServerLevel hall, RecordBoards.Board board, BlockPos anchor, HallLayout.Facing facing, double[] size) {
		BoardFit.Fit fit = board.vertical ? null : BoardFit.wall(hall, anchor, facing, size[0], size[1]);
		return fit != null ? fit : BoardFit.freeStanding(hall, anchor, size[0], size[1]);
	}

	/** Autofit for /ht hall boards <board> autofit: measures now, with the board's current text. */
	public static BoardFitResult autofitNow(ServerLevel hall, RecordBoards.Board board, BlockPos anchor, HallLayout.Facing facing) {
		BoardFit.Fit fit = autofit(hall, board, anchor, facing, currentSize(board));
		return new BoardFitResult(fit.placement(), fit.note());
	}

	/** The current text size (blocks at scale 1) of a board, for autofit from a command. */
	static double[] currentSize(RecordBoards.Board board) {
		net.hardcoretogether.config.HtConfig config = HardcoreTogether.config();
		List<RunHistoryEntry> history = MemorialData.get().recentRuns(Integer.MAX_VALUE);
		List<RecordBoards.PlayerRow> players = new ArrayList<>();
		net.hardcoretogether.data.PlayerRecords records = HardcoreTogether.playerRecords();
		if (records != null) {
			records.all().forEach((uuid, e) -> players.add(new RecordBoards.PlayerRow(uuid, e.name() == null ? uuid.toString() : e.name(), e.firstSeen())));
		}
		List<String> body = switch (board) {
			case DEATHS -> RecordBoards.deaths(players, history, config.deathsBoardMaxPlayers());
			case RUNS -> RecordBoards.runs(history);
			case CAUSES -> RecordBoards.causes(history);
		};
		return RecordBoards.size(board, body);
	}

	/** Unit step (x, z) of a horizontal facing. */
	static int[] facingVector(HallLayout.Facing facing) {
		return switch (facing) {
			case SOUTH -> new int[] {0, 1};
			case NORTH -> new int[] {0, -1};
			case EAST -> new int[] {1, 0};
			case WEST -> new int[] {-1, 0};
		};
	}

	private static float facingYaw(HallLayout.Facing facing) {
		return switch (facing) {
			case SOUTH -> 0.0F;
			case WEST -> 90.0F;
			case NORTH -> 180.0F;
			case EAST -> 270.0F;
		};
	}

	/**
	 * A text display (statue plaque or record board): background from plaque_background_argb, text shadow, centred,
	 * no line wrapping.
	 */
	private static CompoundTag displayTag(ServerLevel hall, Role role, String id, net.minecraft.network.chat.Component content,
			String billboard, float scale, float yaw, int background, float translateY) {
		return displayTag(hall, role, id, content, billboard, scale, yaw, background, translateY, "center");
	}

	private static CompoundTag displayTag(ServerLevel hall, Role role, String id, net.minecraft.network.chat.Component content,
			String billboard, float scale, float yaw, int background, float translateY, String alignment) {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", "minecraft:text_display");
		Tag text = ComponentSerialization.CODEC.encodeStart(hall.registryAccess().createSerializationContext(NbtOps.INSTANCE),
			content).getOrThrow();
		tag.put("text", text);
		tag.putString("billboard", billboard);
		tag.putString("alignment", alignment);
		tag.putBoolean("shadow", true);
		tag.putInt("background", background);
		tag.putInt("line_width", PLAQUE_LINE_WIDTH);
		CompoundTag transformation = new CompoundTag();
		transformation.put("left_rotation", floats(0, 0, 0, 1));
		transformation.put("right_rotation", floats(0, 0, 0, 1));
		transformation.put("translation", floats(0, translateY, 0));
		transformation.put("scale", floats(scale, scale, scale));
		tag.put("transformation", transformation);
		tag.put("Rotation", rotation(yaw));
		tag.put("Tags", tags(role, id));
		return tag;
	}

	private static ListTag floats(float... values) {
		ListTag list = new ListTag();
		for (float v : values) {
			list.add(FloatTag.valueOf(v));
		}
		return list;
	}
}
