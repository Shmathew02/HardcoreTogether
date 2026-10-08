package net.hardcoretogether.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.hall.AlwaysLitLamps;
import net.hardcoretogether.hall.AnchorType;
import net.hardcoretogether.hall.ArrowLights;
import net.hardcoretogether.hall.ArrowPath;
import net.hardcoretogether.hall.DeathHall;
import net.hardcoretogether.hall.HallBackups;
import net.hardcoretogether.hall.HallBounds;
import net.hardcoretogether.hall.HallBuilder;
import net.hardcoretogether.hall.HallLayout;
import net.hardcoretogether.hall.HallMarkers;
import net.hardcoretogether.hall.HallStatues;
import net.hardcoretogether.hall.StatueAssignments;
import net.hardcoretogether.hall.StatueEntities;
import net.hardcoretogether.hall.RecordBoards;
import net.hardcoretogether.data.RunHistoryEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Death Hall tools under /ht hall: edit mode, anchors, floor arrows, record boards, portal zone, export and
 * backups. Statue anchors are numbered places for the death statues; deaths fill them in run order (see
 * StatueAssignments).
 */
final class HallCommand {
	private static final SuggestionProvider<CommandSourceStack> BACKUP_NAMES = (ctx, builder) -> {
		HtWorldData data = HardcoreTogether.worldData();
		List<String> names = List.of();
		if (data != null) {
			try {
				names = HallBackups.list(data.folder()).reversed();
			} catch (IOException ignored) {
				// no suggestions
			}
		}
		return SharedSuggestionProvider.suggest(names, builder);
	};

	private HallCommand() {
	}

	/** The /ht hall node; {@code teleport} runs for plain /ht hall. */
	static LiteralArgumentBuilder<CommandSourceStack> node(Command<CommandSourceStack> teleport) {
		LiteralArgumentBuilder<CommandSourceStack> add = Commands.literal("add");
		for (AnchorType type : AnchorType.values()) {
			add.then(Commands.literal(type.id()).executes(ctx -> anchorAdd(ctx, type, ""))
				.then(Commands.argument("label", StringArgumentType.greedyString())
					.executes(ctx -> anchorAdd(ctx, type, StringArgumentType.getString(ctx, "label")))));
		}
		add.then(Commands.literal("statue").executes(HallCommand::statueNumberMissing)
			.then(Commands.argument("number", IntegerArgumentType.integer(1))
				.executes(ctx -> statueAdd(ctx, IntegerArgumentType.getInteger(ctx, "number")))));
		return Commands.literal("hall").executes(teleport)
			.then(Commands.literal("export").executes(HallCommand::export))
			.then(Commands.literal("setspawn").executes(HallCommand::setSpawn))
			.then(Commands.literal("edit")
				.then(Commands.literal("on").executes(HallCommand::editOn))
				.then(Commands.literal("off").executes(HallCommand::editOff)))
			.then(Commands.literal("anchor")
				.then(add)
				.then(Commands.literal("list").executes(HallCommand::anchorList))
				.then(Commands.literal("nearest").executes(HallCommand::anchorNearest))
				.then(Commands.literal("move")
					.then(Commands.literal("statue").then(Commands.argument("number", IntegerArgumentType.integer(1))
						.executes(ctx -> statueMove(ctx, IntegerArgumentType.getInteger(ctx, "number"))))))
				.then(Commands.literal("remove")
					.then(Commands.literal("statue").then(Commands.argument("number", IntegerArgumentType.integer(1))
						.executes(ctx -> statueRemove(ctx, IntegerArgumentType.getInteger(ctx, "number")))))
					.then(Commands.argument("id", IntegerArgumentType.integer(1))
						.executes(ctx -> anchorRemove(ctx, IntegerArgumentType.getInteger(ctx, "id")))))
				.then(Commands.literal("show").executes(HallCommand::anchorShow)))
			.then(arrowsNode())
			.then(boardsNode())
			.then(Commands.literal("portal")
				.then(Commands.literal("pos1").executes(ctx -> portalCorner(ctx, 1)))
				.then(Commands.literal("pos2").executes(ctx -> portalCorner(ctx, 2)))
				.then(Commands.literal("info").executes(HallCommand::portalInfo)))
			.then(Commands.literal("lamps")
				.then(Commands.literal("rescan").executes(HallCommand::lampsRescan))
				.then(Commands.literal("info").executes(HallCommand::lampsInfo)))
			.then(Commands.literal("statues")
				.then(Commands.literal("relayout").executes(HallCommand::statuesRelayout))
				.then(Commands.literal("reconcile").executes(HallCommand::statuesReconcile)))
			.then(Commands.literal("bounds").executes(HallCommand::boundsInfo)
				.then(Commands.literal("show").executes(HallCommand::boundsShow)))
			.then(Commands.literal("backup").executes(HallCommand::backup))
			.then(Commands.literal("restore").executes(HallCommand::listBackups)
				.then(Commands.argument("backup", StringArgumentType.word()).suggests(BACKUP_NAMES)
					.executes(ctx -> restore(ctx, false))
					.then(Commands.literal("confirm").executes(ctx -> restore(ctx, true)))));
	}

	// ---- Edit mode ----

	private static int editOn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!DeathHall.contains(player)) {
			ctx.getSource().sendFailure(Component.literal("Edit mode only works in the Death Hall. Use /ht hall first."));
			return 0;
		}
		if (!DeathHall.startEditing(player)) {
			ctx.getSource().sendFailure(Component.literal("Edit mode is already on."));
			return 0;
		}
		HardcoreTogether.LOGGER.info("[HT hall] edit mode on for {}", player.getGameProfile().name());
		ctx.getSource().sendSuccess(() -> Component.literal("Edit mode on: Creative, hall rules off for you. "
			+ "Ends with /ht hall edit off, on leaving the hall or on disconnect; the hall is backed up then."), true);
		return 1;
	}

	private static int editOff(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Optional<String> before = newestBackup();
		if (!DeathHall.stopEditing(player, "/ht hall edit off")) {
			ctx.getSource().sendFailure(Component.literal("Edit mode was not on."));
			return 0;
		}
		Optional<String> after = newestBackup();
		String backup = after.isPresent() && !after.equals(before)
			? " Backup saved: " + after.get() + "." : " Automatic backup FAILED, see the server log.";
		ctx.getSource().sendSuccess(() -> Component.literal("Edit mode off: Adventure, hall rules back on." + backup), true);
		return 1;
	}

	// ---- Anchors ----

	private static int anchorAdd(CommandContext<CommandSourceStack> ctx, AnchorType type, String label) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!DeathHall.contains(player)) {
			ctx.getSource().sendFailure(Component.literal("Anchors can only be added in the Death Hall."));
			return 0;
		}
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		BlockPos pos = player.blockPosition();
		BlockPos origin = DeathHall.ORIGIN;
		try {
			HallLayout layout = HallLayout.load(data.folder());
			HallLayout.Anchor a = layout.add(type, label, pos.getX(), pos.getY(), pos.getZ(),
				origin.getX(), origin.getY(), origin.getZ(), player.getYRot());
			layout.save(data.folder());
			HallBounds bounds = HardcoreTogether.config().hallBounds();
			String outside = bounds.containsOffset(a.dx(), a.dy(), a.dz()) ? "" : " WARNING: outside the hall region.";
			HardcoreTogether.LOGGER.info("[HT hall] anchor #{} {} added at {} facing {}", a.id(), type.id(), pos.toShortString(), a.facing().id());
			ctx.getSource().sendSuccess(() -> Component.literal("Anchor #" + a.id() + " added: " + describe(a) + outside), false);
			return 1;
		} catch (IOException e) {
			return fail(ctx, "Could not save the hall layout: " + e.getMessage(), e);
		}
	}

	private static int anchorList(CommandContext<CommandSourceStack> ctx) {
		HallLayout layout = loadLayout(ctx);
		if (layout == null) {
			return 0;
		}
		List<HallLayout.Anchor> anchors = layout.anchors();
		StringBuilder out = new StringBuilder("[HT hall] " + anchors.size() + " anchor(s)");
		anchors.forEach(a -> out.append("\n #").append(a.id()).append(' ').append(describe(a)));
		StatueAssignments.Result r = syncStatues(ctx, layout);
		if (r == null) {
			return 0;
		}
		out.append("\n").append(layout.statues().size()).append(" statue anchor(s), ").append(r.unplaced().size())
			.append(" unplaced death(s)");
		layout.statues().forEach(st -> out.append("\n statue ").append(describe(layout, st)));
		if (!r.unplaced().isEmpty()) {
			out.append("\n unplaced runs: ").append(r.unplaced().stream().map(n -> "#" + n).reduce((a, b) -> a + ", " + b).orElse(""));
		}
		ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
		return anchors.size() + layout.statues().size();
	}

	private static int anchorRemove(CommandContext<CommandSourceStack> ctx, int id) {
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		try {
			HallLayout layout = HallLayout.load(data.folder());
			Optional<HallLayout.Anchor> anchor = layout.get(id);
			if (anchor.isEmpty()) {
				ctx.getSource().sendFailure(Component.literal("No anchor #" + id + ". See /ht hall anchor list."));
				return 0;
			}
			layout.remove(id);
			layout.save(data.folder());
			HardcoreTogether.LOGGER.info("[HT hall] anchor #{} removed", id);
			ctx.getSource().sendSuccess(() -> Component.literal("Anchor #" + id + " removed: " + describe(anchor.get())), false);
			return 1;
		} catch (IOException e) {
			return fail(ctx, "Could not update the hall layout: " + e.getMessage(), e);
		}
	}

	private static int anchorShow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!DeathHall.contains(player)) {
			ctx.getSource().sendFailure(Component.literal("Go to the Death Hall to see the anchors."));
			return 0;
		}
		HallLayout layout = loadLayout(ctx);
		if (layout == null) {
			return 0;
		}
		List<HallLayout.Anchor> anchors = layout.anchors();
		HallMarkers.showAnchors(player, anchors);
		if (syncStatues(ctx, layout) == null) {
			return 0;
		}
		List<HallLayout.Statue> statues = layout.statues();
		HallMarkers.showStatues(player, statues, new HashSet<>(layout.statueAssignments().values()));
		StringBuilder out = new StringBuilder("[HT hall] showing " + anchors.size() + " anchor(s) and " + statues.size()
			+ " statue anchor(s) for " + HallMarkers.SECONDS + " s (column or player box at the anchor, arrow = facing, "
			+ "statue number above; white = death assigned)");
		anchors.forEach(a -> out.append("\n #").append(a.id()).append(' ').append(describe(a)));
		statues.forEach(st -> out.append("\n statue ").append(describe(layout, st)));
		ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
		return anchors.size() + statues.size();
	}

	private static String describe(HallLayout.Anchor a) {
		BlockPos o = DeathHall.ORIGIN;
		String label = a.label().isEmpty() ? "" : " \"" + a.label() + "\"";
		return a.type().id() + label + " at " + (o.getX() + a.dx()) + ", " + (o.getY() + a.dy()) + ", " + (o.getZ() + a.dz())
			+ " facing " + a.facing().id();
	}

	// ---- Statue anchors ----

	private static int statueNumberMissing(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendFailure(Component.literal("A statue anchor needs its number (the fill order): /ht hall anchor add statue <number>"));
		return 0;
	}

	/** Adds a statue anchor at the centre of the executor's block, facing their yaw snapped to 8 directions. */
	private static int statueAdd(CommandContext<CommandSourceStack> ctx, int number) {
		CommandSourceStack source = ctx.getSource();
		if (source.getLevel().dimension() != DeathHall.KEY) {
			source.sendFailure(Component.literal("Statue anchors can only be added in the Death Hall."));
			return 0;
		}
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		BlockPos pos = BlockPos.containing(source.getPosition());
		BlockPos origin = DeathHall.ORIGIN;
		try {
			HallLayout layout = HallLayout.load(data.folder());
			if (layout.statue(number).isPresent()) {
				source.sendFailure(Component.literal("Statue anchor " + number + " already exists: " + describe(layout, layout.statue(number).get())
					+ ". Remove it first with /ht hall anchor remove statue " + number + "."));
				return 0;
			}
			HallLayout.Statue st = layout.addStatue(number, pos.getX(), pos.getY(), pos.getZ(), origin.getX(), origin.getY(), origin.getZ(),
				source.getRotation().y);
			layout.save(data.folder());
			HallStatues.sync(data, layout, "statue anchor " + number + " added");
			StatueEntities.request(source.getServer(), "statue anchor " + number + " added", null);
			HallBounds bounds = HardcoreTogether.config().hallBounds();
			String outside = bounds.containsOffset(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ())
				? "" : " WARNING: outside the hall region.";
			HardcoreTogether.LOGGER.info("[HT hall] statue anchor {} added at {} facing {}", number, pos.toShortString(), st.facing().id());
			source.sendSuccess(() -> Component.literal("Statue anchor added: " + describe(layout, st) + outside), false);
			return 1;
		} catch (IOException e) {
			return fail(ctx, "Could not save the hall layout: " + e.getMessage(), e);
		}
	}

	private static int statueRemove(CommandContext<CommandSourceStack> ctx, int number) {
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		try {
			HallLayout layout = HallLayout.load(data.folder());
			Optional<HallLayout.Statue> st = layout.statue(number);
			if (st.isEmpty()) {
				ctx.getSource().sendFailure(Component.literal("No statue anchor " + number + ". See /ht hall anchor list."));
				return 0;
			}
			String before = describe(layout, st.get());
			layout.removeStatue(number);
			layout.save(data.folder());
			StatueAssignments.Result r = HallStatues.sync(data, layout, "statue anchor " + number + " removed");
			StatueEntities.request(ctx.getSource().getServer(), "statue anchor " + number + " removed", null);
			HardcoreTogether.LOGGER.info("[HT hall] statue anchor {} removed", number);
			ctx.getSource().sendSuccess(() -> Component.literal("Statue anchor removed: " + before + ". Unplaced deaths now: "
				+ r.unplaced().size() + "."), false);
			return 1;
		} catch (IOException e) {
			return fail(ctx, "Could not update the hall layout: " + e.getMessage(), e);
		}
	}

	/**
	 * Re-places statue anchor {@code number} at the centre of the executor's block, facing their yaw snapped to 8
	 * directions. Number and assigned death stay, so the statue and plaque follow it (unlike remove + add).
	 */
	private static int statueMove(CommandContext<CommandSourceStack> ctx, int number) {
		CommandSourceStack source = ctx.getSource();
		if (source.getLevel().dimension() != DeathHall.KEY) {
			source.sendFailure(Component.literal("Statue anchors can only be moved in the Death Hall."));
			return 0;
		}
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		BlockPos pos = BlockPos.containing(source.getPosition());
		BlockPos origin = DeathHall.ORIGIN;
		try {
			HallLayout layout = HallLayout.load(data.folder());
			Optional<HallLayout.Statue> old = layout.statue(number);
			if (old.isEmpty()) {
				source.sendFailure(Component.literal("No statue anchor " + number + ". See /ht hall anchor list."));
				return 0;
			}
			String before = describe(layout, old.get());
			HallLayout.Statue st = layout.moveStatue(number, pos.getX(), pos.getY(), pos.getZ(), origin.getX(), origin.getY(), origin.getZ(),
				source.getRotation().y);
			layout.save(data.folder());
			HallStatues.sync(data, layout, "statue anchor " + number + " moved");
			StatueEntities.request(source.getServer(), "statue anchor " + number + " moved", null);
			HallBounds bounds = HardcoreTogether.config().hallBounds();
			String outside = bounds.containsOffset(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ())
				? "" : " WARNING: outside the hall region.";
			HardcoreTogether.LOGGER.info("[HT hall] statue anchor {} moved: {} -> {} facing {}", number, before, pos.toShortString(), st.facing().id());
			source.sendSuccess(() -> Component.literal("Statue anchor moved: was " + before + "; now " + describe(layout, st) + outside), false);
			return 1;
		} catch (IOException e) {
			return fail(ctx, "Could not save the hall layout: " + e.getMessage(), e);
		}
	}

	/** The statue anchors and display anchors nearest the executor (3 of each), with distances. */
	private static int anchorNearest(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		if (source.getLevel().dimension() != DeathHall.KEY) {
			source.sendFailure(Component.literal("Go to the Death Hall to find the nearest anchor."));
			return 0;
		}
		HallLayout layout = loadLayout(ctx);
		if (layout == null) {
			return 0;
		}
		Vec3 at = source.getPosition();
		BlockPos o = DeathHall.ORIGIN;
		List<HallLayout.Statue> statues = layout.statues().stream()
			.sorted(java.util.Comparator.comparingDouble(st -> distance(at, st.absolute(o.getX(), o.getY(), o.getZ())))).limit(3).toList();
		List<HallLayout.Anchor> anchors = layout.anchors().stream()
			.sorted(java.util.Comparator.comparingDouble(a -> distance(at, anchorCentre(a)))).limit(3).toList();
		if (statues.isEmpty() && anchors.isEmpty()) {
			source.sendFailure(Component.literal("No anchors yet. See /ht hall anchor list."));
			return 0;
		}
		StringBuilder out = new StringBuilder(String.format(java.util.Locale.ROOT, "[HT hall] nearest to you (%.1f, %.1f, %.1f):",
			at.x, at.y, at.z));
		statues.forEach(st -> out.append(String.format(java.util.Locale.ROOT, "\n statue %s (%.1f blocks)", describe(layout, st),
			distance(at, st.absolute(o.getX(), o.getY(), o.getZ())))));
		anchors.forEach(a -> out.append(String.format(java.util.Locale.ROOT, "\n #%d %s (%.1f blocks)", a.id(), describe(a),
			distance(at, anchorCentre(a)))));
		source.sendSuccess(() -> Component.literal(out.toString()), false);
		return statues.isEmpty() ? 0 : statues.getFirst().number();
	}

	private static double[] anchorCentre(HallLayout.Anchor a) {
		BlockPos o = DeathHall.ORIGIN;
		return new double[] {o.getX() + a.dx() + 0.5, o.getY() + a.dy(), o.getZ() + a.dz() + 0.5};
	}

	private static double distance(Vec3 at, double[] p) {
		return Math.sqrt(at.distanceToSqr(p[0], p[1], p[2]));
	}

	private static int statuesRelayout(CommandContext<CommandSourceStack> ctx) {
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		try {
			HallLayout layout = HallLayout.load(data.folder());
			StatueAssignments.Result r = HallStatues.relayout(data, layout);
			StringBuilder out = new StringBuilder("[HT hall] statues relaid out in run order: " + r.placed() + " placed, "
				+ r.unplaced().size() + " unplaced");
			layout.statues().forEach(st -> out.append("\n statue ").append(describe(layout, st)));
			ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), true);
			StatueEntities.request(ctx.getSource().getServer(), "relayout", null);
			return r.placed();
		} catch (IOException e) {
			return fail(ctx, "Could not update the hall layout: " + e.getMessage(), e);
		}
	}

	/** Brings the statue and plaque entities in line with the assignments; reports when done (may take a few ticks). */
	private static int statuesReconcile(CommandContext<CommandSourceStack> ctx) {
		if (dataOrFail(ctx) == null) {
			return 0;
		}
		CommandSourceStack source = ctx.getSource();
		StatueEntities.request(source.getServer(), "/ht hall statues reconcile", r -> {
			if (r == null) {
				source.sendFailure(Component.literal("Statue reconcile could not run; see the server log."));
			} else {
				source.sendSuccess(() -> Component.literal("[HT statues] reconcile: " + r.spawned() + " spawned, " + r.updated() + " updated, "
					+ r.removed() + " removed"), true);
			}
		});
		return 1;
	}

	/** Syncs statue assignments before showing them; null (with a chat error) if the layout could not be saved. */
	private static StatueAssignments.Result syncStatues(CommandContext<CommandSourceStack> ctx, HallLayout layout) {
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return null;
		}
		try {
			return HallStatues.sync(data, layout, "statue check");
		} catch (IOException e) {
			fail(ctx, "Could not update the hall layout: " + e.getMessage(), e);
			return null;
		}
	}

	private static String describe(HallLayout layout, HallLayout.Statue st) {
		BlockPos o = DeathHall.ORIGIN;
		double[] p = st.absolute(o.getX(), o.getY(), o.getZ());
		String who = layout.runOnStatue(st.number())
			.map(run -> "run #" + run + HallStatues.death(run).map(RunHistoryEntry::death).map(d -> " " + d.name()).orElse(""))
			.orElse("free");
		return String.format(java.util.Locale.ROOT, "%d at %.1f, %.0f, %.1f facing %s: %s", st.number(), p[0], p[1], p[2],
			st.facing().id(), who);
	}

	// ---- Floor arrows ----

	private static LiteralArgumentBuilder<CommandSourceStack> arrowsNode() {
		LiteralArgumentBuilder<CommandSourceStack> mode = Commands.literal("mode");
		for (ArrowPath.Mode m : ArrowPath.Mode.values()) {
			mode.then(Commands.literal(m.id()).executes(ctx -> arrowsMode(ctx, m)));
		}
		return Commands.literal("arrows")
			.then(Commands.argument("arrow", IntegerArgumentType.integer(1, HallLayout.ARROWS))
				.then(Commands.literal("pos1").executes(ctx -> arrowCorner(ctx, 1)))
				.then(Commands.literal("pos2").executes(ctx -> arrowCorner(ctx, 2))))
			.then(Commands.literal("target").executes(HallCommand::arrowTarget))
			.then(Commands.literal("on").executes(ctx -> arrowsSwitch(ctx, true)))
			.then(Commands.literal("off").executes(ctx -> arrowsSwitch(ctx, false)))
			.then(Commands.literal("info").executes(HallCommand::arrowsInfo))
			.then(mode);
	}

	/** The executor's block, if they are in the Death Hall; otherwise a chat error and null. */
	private static BlockPos hallBlock(CommandContext<CommandSourceStack> ctx) {
		if (ctx.getSource().getLevel().dimension() != DeathHall.KEY) {
			ctx.getSource().sendFailure(Component.literal("Floor arrows can only be set up in the Death Hall."));
			return null;
		}
		return BlockPos.containing(ctx.getSource().getPosition());
	}

	private static int arrowCorner(CommandContext<CommandSourceStack> ctx, int corner) {
		int arrow = IntegerArgumentType.getInteger(ctx, "arrow");
		BlockPos pos = hallBlock(ctx);
		HtWorldData data = pos == null ? null : dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		BlockPos o = DeathHall.ORIGIN;
		try {
			HallLayout layout = HallLayout.load(data.folder());
			layout.setArrowCorner(arrow, corner, pos.getX(), pos.getY(), pos.getZ(), o.getX(), o.getY(), o.getZ());
			layout.save(data.folder());
			ArrowLights.invalidate(ctx.getSource().getServer());
			AlwaysLitLamps.rescan(ctx.getSource().getServer(), "arrow " + arrow + " box changed");
			HardcoreTogether.LOGGER.info("[HT arrows] arrow {} pos{} set to {}", arrow, corner, pos.toShortString());
			String lamps = ArrowLights.box(layout, arrow).isPresent()
				? " Box complete: " + ArrowLights.lampsInArrow(ctx.getSource().getServer(), arrow) + " lamp(s)."
				: " Now set pos" + (3 - corner) + ".";
			ctx.getSource().sendSuccess(() -> Component.literal("Arrow " + arrow + " pos" + corner + " set to " + pos.toShortString() + "." + lamps), false);
			return 1;
		} catch (IOException e) {
			return fail(ctx, "Could not save the hall layout: " + e.getMessage(), e);
		}
	}

	private static int arrowTarget(CommandContext<CommandSourceStack> ctx) {
		BlockPos pos = hallBlock(ctx);
		HtWorldData data = pos == null ? null : dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		BlockPos o = DeathHall.ORIGIN;
		try {
			HallLayout layout = HallLayout.load(data.folder());
			layout.setArrowTarget(pos.getX(), pos.getY(), pos.getZ(), o.getX(), o.getY(), o.getZ());
			layout.save(data.folder());
			ArrowLights.invalidate(ctx.getSource().getServer());
			HardcoreTogether.LOGGER.info("[HT arrows] target set to {}", pos.toShortString());
			ctx.getSource().sendSuccess(() -> Component.literal("Arrow target set to " + pos.toShortString() + "."), false);
			return 1;
		} catch (IOException e) {
			return fail(ctx, "Could not save the hall layout: " + e.getMessage(), e);
		}
	}

	private static int arrowsSwitch(CommandContext<CommandSourceStack> ctx, boolean on) {
		if (dataOrFail(ctx) == null) {
			return 0;
		}
		int lamps = ArrowLights.path(ctx.getSource().getServer()).size();
		ArrowLights.setEnabled(ctx.getSource().getServer(), on);
		String empty = on && lamps == 0 ? " WARNING: no lamps in the arrow boxes; see /ht hall arrows info." : "";
		ctx.getSource().sendSuccess(() -> Component.literal("Floor arrows " + (on ? "ON (" + ArrowLights.mode().id() + ")" : "OFF, all lamps unlit")
			+ ", " + lamps + " lamp(s)." + empty), true);
		return 1;
	}

	private static int arrowsMode(CommandContext<CommandSourceStack> ctx, ArrowPath.Mode mode) {
		try {
			ArrowLights.setMode(mode);
		} catch (IOException e) {
			return fail(ctx, "Mode changed for now, but saving it in the config failed: " + e.getMessage(), e);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Floor arrow mode: " + mode.id() + " (saved in the config)"
			+ (ArrowLights.enabled() ? "." : ". Arrows are OFF; /ht hall arrows on to preview.")), true);
		return 1;
	}

	private static int arrowsInfo(CommandContext<CommandSourceStack> ctx) {
		HallLayout layout = loadLayout(ctx);
		if (layout == null) {
			return 0;
		}
		MinecraftServer server = ctx.getSource().getServer();
		BlockPos o = DeathHall.ORIGIN;
		ArrowPath.Settings s = ArrowLights.settings();
		StringBuilder out = new StringBuilder("[HT arrows] " + (ArrowLights.enabled() ? "ON" : "OFF") + ", mode " + s.mode().id()
			+ String.format(" (wave: band %d, %d ticks/step, pause %d; blink %d ticks)", s.bandLength(), s.stepTicks(), s.pauseTicks(), s.blinkTicks())
			+ ", " + ArrowLights.path(server).size() + " lamp(s) in the path");
		for (int arrow = 1; arrow <= HallLayout.ARROWS; arrow++) {
			out.append("\n arrow ").append(arrow).append(": ");
			Optional<BlockPos[]> box = ArrowLights.box(layout, arrow);
			if (box.isPresent()) {
				out.append(box.get()[0].toShortString()).append(" to ").append(box.get()[1].toShortString()).append(", ")
					.append(ArrowLights.lampsInArrow(server, arrow)).append(" lamp(s)");
			} else {
				int a = arrow;
				String p1 = layout.arrowCorner(a, 1).map(c -> o.offset(c[0], c[1], c[2]).toShortString()).orElse("not set");
				String p2 = layout.arrowCorner(a, 2).map(c -> o.offset(c[0], c[1], c[2]).toShortString()).orElse("not set");
				out.append("incomplete (pos1 ").append(p1).append(", pos2 ").append(p2).append(")");
			}
		}
		out.append("\n target: ").append(layout.arrowTarget().map(t -> o.offset(t[0], t[1], t[2]).toShortString())
			.orElse("not set (lamps ordered by position)"));
		ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
		return 1;
	}

	// ---- Portal zone ----

	private static int portalCorner(CommandContext<CommandSourceStack> ctx, int corner) {
		if (ctx.getSource().getLevel().dimension() != DeathHall.KEY) {
			ctx.getSource().sendFailure(Component.literal("Set the portal zone from inside the Death Hall."));
			return 0;
		}
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
		BlockPos o = DeathHall.ORIGIN;
		try {
			HallLayout layout = HallLayout.load(data.folder());
			layout.setPortalCorner(corner, pos.getX(), pos.getY(), pos.getZ(), o.getX(), o.getY(), o.getZ());
			layout.save(data.folder());
			net.hardcoretogether.hall.HallPortal.invalidateZone();
			HardcoreTogether.LOGGER.info("[HT portal] zone pos{} set to {} by {}", corner, pos.toShortString(), ctx.getSource().getTextName());
			String other = layout.portalCorner(3 - corner).isPresent() ? "" : " Set pos" + (3 - corner) + " too; until then the default zone is used.";
			ctx.getSource().sendSuccess(() -> Component.literal("Portal zone pos" + corner + " set to " + pos.toShortString() + "." + other), false);
			return 1;
		} catch (IOException e) {
			return fail(ctx, "Could not save the hall layout: " + e.getMessage(), e);
		}
	}

	private static int portalInfo(CommandContext<CommandSourceStack> ctx) {
		HallLayout layout = loadLayout(ctx);
		if (layout == null) {
			return 0;
		}
		boolean set = layout.portalCorner(1).isPresent() && layout.portalCorner(2).isPresent();
		Optional<BlockPos[]> zone = net.hardcoretogether.hall.HallPortal.zone(layout);
		String box = zone.map(b -> b[0].toShortString() + " to " + b[1].toShortString() + String.format(" (%dx%dx%d)",
			b[1].getX() - b[0].getX() + 1, b[1].getY() - b[0].getY() + 1, b[1].getZ() - b[0].getZ() + 1)).orElse("none (no run_start anchor)");
		String text = "[HT portal] zone " + box + (set ? " (set with pos1/pos2)" : " (default at the run_start anchor)")
			+ "; start_mode " + (HardcoreTogether.config().portalStart() ? "portal" : "auto") + ", effects "
			+ (HardcoreTogether.config().portalEffects() ? "on" : "off") + ", waiting for start: "
			+ (net.hardcoretogether.reset.ResetController.awaitingStart() ? "yes" : "no");
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	// ---- Record boards ----

	private static LiteralArgumentBuilder<CommandSourceStack> boardsNode() {
		LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("boards").then(Commands.literal("info").executes(HallCommand::boardsInfo));
		for (RecordBoards.Board board : RecordBoards.Board.values()) {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(board.id())
				.then(Commands.literal("autofit").executes(ctx -> boardAutofit(ctx, board)));
			for (String field : List.of("scale", "height", "forward", "side")) {
				b.then(Commands.literal(field).then(Commands.argument("value", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-64, 64))
					.executes(ctx -> boardSet(ctx, board, field, com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "value")))));
			}
			node.then(b);
		}
		return node;
	}

	private static Optional<HallLayout.Anchor> boardAnchor(HallLayout layout, RecordBoards.Board board) {
		return layout.anchors().stream().filter(a -> a.type() == board.anchor).min(java.util.Comparator.comparingInt(HallLayout.Anchor::id));
	}

	private static int boardSet(CommandContext<CommandSourceStack> ctx, RecordBoards.Board board, String field, double value) {
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		if (field.equals("scale") && value <= 0) {
			ctx.getSource().sendFailure(Component.literal("Scale must be more than 0."));
			return 0;
		}
		try {
			HallLayout layout = HallLayout.load(data.folder());
			Optional<HallLayout.BoardPlacement> current = layout.board(board.id());
			if (current.isEmpty()) {
				ctx.getSource().sendFailure(Component.literal("The " + board.id() + " board has no placement yet; run /ht hall boards " + board.id() + " autofit."));
				return 0;
			}
			HallLayout.BoardPlacement p = current.get();
			HallLayout.BoardPlacement next = switch (field) {
				case "scale" -> p.with(value, p.height(), p.forward(), p.side());
				case "height" -> p.with(p.scale(), value, p.forward(), p.side());
				case "forward" -> p.with(p.scale(), p.height(), value, p.side());
				default -> p.with(p.scale(), p.height(), p.forward(), value);
			};
			layout.setBoard(board.id(), next);
			layout.save(data.folder());
			HardcoreTogether.LOGGER.info("[HT boards] {} {} set to {} by {}", board.id(), field, value, ctx.getSource().getTextName());
			StatueEntities.request(ctx.getSource().getServer(), "board " + board.id() + " " + field, null);
			ctx.getSource().sendSuccess(() -> Component.literal("[HT boards] " + describe(board, next)), false);
			return 1;
		} catch (IOException e) {
			return fail(ctx, "Could not update the hall layout: " + e.getMessage(), e);
		}
	}

	private static int boardAutofit(CommandContext<CommandSourceStack> ctx, RecordBoards.Board board) {
		HtWorldData data = dataOrFail(ctx);
		ServerLevel hall = DeathHall.level(ctx.getSource().getServer());
		if (data == null || hall == null) {
			return 0;
		}
		try {
			HallLayout layout = HallLayout.load(data.folder());
			Optional<HallLayout.Anchor> anchor = boardAnchor(layout, board);
			if (anchor.isEmpty()) {
				ctx.getSource().sendFailure(Component.literal("No " + board.anchor.id() + " anchor for the " + board.id() + " board."));
				return 0;
			}
			BlockPos o = DeathHall.ORIGIN;
			int[] p = HallLayout.absolute(anchor.get(), o.getX(), o.getY(), o.getZ());
			net.hardcoretogether.hall.BoardFitResult fit = StatueEntities.autofitNow(hall, board, new BlockPos(p[0], p[1], p[2]), anchor.get().facing());
			layout.setBoard(board.id(), fit.placement());
			layout.save(data.folder());
			HardcoreTogether.LOGGER.info("[HT boards] {} autofit: {}; {}", board.id(), fit.note(), describe(board, fit.placement()));
			StatueEntities.request(ctx.getSource().getServer(), "board " + board.id() + " autofit", null);
			ctx.getSource().sendSuccess(() -> Component.literal("[HT boards] " + board.id() + " autofit: " + fit.note() + ". " + describe(board, fit.placement())), false);
			return 1;
		} catch (IOException e) {
			return fail(ctx, "Could not update the hall layout: " + e.getMessage(), e);
		}
	}

	private static int boardsInfo(CommandContext<CommandSourceStack> ctx) {
		HallLayout layout = loadLayout(ctx);
		if (layout == null) {
			return 0;
		}
		StringBuilder out = new StringBuilder("[HT boards] placement per board (height = top edge above the anchor floor, forward = along "
			+ "the anchor facing, side = to the right of it):");
		for (RecordBoards.Board board : RecordBoards.Board.values()) {
			String anchor = boardAnchor(layout, board).map(a -> "anchor #" + a.id() + " facing " + a.facing().id()).orElse("no anchor");
			out.append("\n ").append(layout.board(board.id()).map(p -> describe(board, p)).orElse(board.id() + ": not placed yet")).append(" (")
				.append(anchor).append(", ").append(board.vertical ? "vertical" : "fixed").append(")");
			if (board == RecordBoards.Board.CAUSES) {
				layout.board(board.id()).filter(HallLayout.BoardPlacement::hasFace).ifPresent(p -> {
					RecordBoards.Fitted f = StatueEntities.causesFit(p, net.hardcoretogether.data.MemorialData.get().recentRuns(Integer.MAX_VALUE));
					out.append(String.format(java.util.Locale.ROOT, "\n   causes: max scale %.2f, effective %.2f (min %.2f), %d line(s) in %d column(s); wall face %.1f wide, bottom %.2f",
						p.scale(), f.scale(), HardcoreTogether.config().causesBoardMinTextScale(), f.lines(), f.twoColumns() ? 2 : 1, p.faceWidth(), p.faceBottom()));
				});
			}
		}
		ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
		return 1;
	}

	private static String describe(RecordBoards.Board board, HallLayout.BoardPlacement p) {
		return String.format(java.util.Locale.ROOT, "%s: scale %.2f, height %.2f, forward %.2f, side %.2f", board.id(), p.scale(), p.height(),
			p.forward(), p.side());
	}

	// ---- Always-lit lamps ----

	private static int lampsRescan(CommandContext<CommandSourceStack> ctx) {
		if (dataOrFail(ctx) == null) {
			return 0;
		}
		AlwaysLitLamps.ScanResult r = AlwaysLitLamps.rescan(ctx.getSource().getServer(), "/ht hall lamps rescan");
		ctx.getSource().sendSuccess(() -> Component.literal("[HT lamps] " + r.lamps() + " always-lit lamp(s) in y " + r.minY() + ".." + r.maxY()
			+ ": " + r.lit() + " newly lit, " + r.released() + " left the zone (unlit), " + r.skippedInArrows() + " in arrow boxes (arrow-controlled)."), true);
		return r.lamps();
	}

	private static int lampsInfo(CommandContext<CommandSourceStack> ctx) {
		int offset = HardcoreTogether.config().alwaysLitOffset();
		ctx.getSource().sendSuccess(() -> Component.literal("[HT lamps] always-lit zone y " + AlwaysLitLamps.zoneMinY() + ".."
			+ AlwaysLitLamps.zoneMaxY() + " (hall spawn block y + always_lit_offset " + offset + ", to the top of the hall region), "
			+ AlwaysLitLamps.count() + " lamp(s). Lamps in arrow boxes are left to the arrows. New lamps: /ht hall lamps rescan."), false);
		return AlwaysLitLamps.count();
	}

	// ---- Bounds ----

	private static String boundsText() {
		BlockPos lo = HallBuilder.regionMin(), hi = HallBuilder.regionMax();
		HallBounds b = HardcoreTogether.config().hallBounds();
		return String.format("Hall region %dx%dx%d (w x h x d): %s to %s", b.width(), b.height(), b.depth(),
			lo.toShortString(), hi.toShortString());
	}

	private static int boundsInfo(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.literal(boundsText()), false);
		return 1;
	}

	private static int boundsShow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!DeathHall.contains(player)) {
			ctx.getSource().sendFailure(Component.literal("Go to the Death Hall to see the region."));
			return 0;
		}
		HallMarkers.showBounds(player);
		ctx.getSource().sendSuccess(() -> Component.literal(boundsText() + ". Outline shown for " + HallMarkers.SECONDS + " s."), false);
		return 1;
	}

	// ---- Spawn ----

	/** Saves the executor's exact position and facing as the hall spawn (works with /execute from the console). */
	private static int setSpawn(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		if (source.getLevel().dimension() != DeathHall.KEY) {
			source.sendFailure(Component.literal("Set the hall spawn from inside the Death Hall. Use /ht hall first."));
			return 0;
		}
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		Vec3 pos = source.getPosition();
		Vec2 rot = source.getRotation();
		BlockPos o = DeathHall.ORIGIN;
		try {
			DeathHall.saveSpawn(data, HallLayout.Spawn.at(pos.x, pos.y, pos.z, o.getX(), o.getY(), o.getZ(), rot.y, rot.x));
		} catch (IOException e) {
			return fail(ctx, "Could not save the hall spawn: " + e.getMessage(), e);
		}
		AlwaysLitLamps.rescan(source.getServer(), "hall spawn moved");
		String unsafe = DeathHall.unsafeReason(source.getLevel(), pos);
		String text = String.format(java.util.Locale.ROOT, "Hall spawn set to %s facing yaw %.1f pitch %.1f by %s%s",
			DeathHall.describe(pos), rot.y, rot.x, source.getTextName(),
			unsafe == null ? "" : ". WARNING: not safe here (" + unsafe + "), arrivals use the default "
				+ DeathHall.describe(DeathHall.DEFAULT_SPAWN.pos()) + " until fixed");
		HardcoreTogether.LOGGER.info("[HT hall] {}", text);
		source.sendSuccess(() -> Component.literal(text), true);
		return 1;
	}

	// ---- Export, backup, restore ----

	private static int export(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerLevel hall = DeathHall.level(source.getServer());
		HtWorldData data = HardcoreTogether.worldData();
		if (hall == null || data == null) {
			source.sendFailure(Component.literal("The Death Hall or the data folder is not available."));
			return 0;
		}
		Optional<HallBuilder.ExportResult> result;
		try {
			result = HallBuilder.export(hall, data);
		} catch (IOException e) {
			return fail(ctx, "Hall export failed: " + e.getMessage(), e);
		}
		if (result.isEmpty()) {
			source.sendFailure(Component.literal("Nothing to export: no blocks in the hall region."));
			return 0;
		}
		HallBuilder.ExportResult r = result.get();
		String text = String.format("Hall exported: box %dx%dx%d from %s, %d blocks, saved to %s (with hall-layout.json: anchors, spawn, statue anchors; assignments not shipped)",
			r.size().getX(), r.size().getY(), r.size().getZ(), r.min().toShortString(), r.blockCount(), r.file());
		HardcoreTogether.LOGGER.info(text);
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	private static int backup(CommandContext<CommandSourceStack> ctx) {
		ServerLevel hall = DeathHall.level(ctx.getSource().getServer());
		HtWorldData data = dataOrFail(ctx);
		if (hall == null || data == null) {
			return 0;
		}
		try {
			HallBuilder.BackupResult r = HallBuilder.backup(hall, data);
			String rotated = r.rotatedOut().isEmpty() ? "" : " Removed " + r.rotatedOut().size() + " old backup(s); keeping " + HallBackups.KEEP + ".";
			ctx.getSource().sendSuccess(() -> Component.literal("Hall backup saved: " + r.name() + "." + rotated), true);
			return 1;
		} catch (IOException | RuntimeException e) {
			return fail(ctx, "Hall backup failed: " + e.getMessage(), e);
		}
	}

	private static int listBackups(CommandContext<CommandSourceStack> ctx) {
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return 0;
		}
		try {
			List<String> names = HallBackups.list(data.folder()).reversed();
			StringBuilder out = new StringBuilder("[HT hall] " + names.size() + " backup(s), newest first:");
			names.forEach(n -> out.append("\n ").append(n));
			out.append("\nUse /ht hall restore <backup> to see what it holds, then add confirm.");
			ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
			return names.size();
		} catch (IOException e) {
			return fail(ctx, "Could not list backups: " + e.getMessage(), e);
		}
	}

	private static int restore(CommandContext<CommandSourceStack> ctx, boolean confirm) {
		String name = StringArgumentType.getString(ctx, "backup");
		ServerLevel hall = DeathHall.level(ctx.getSource().getServer());
		HtWorldData data = dataOrFail(ctx);
		if (hall == null || data == null) {
			return 0;
		}
		try {
			HallBuilder.BackupInfo info = HallBuilder.describeBackup(ctx.getSource().getServer(), data, name);
			String what = String.format("%s: %dx%dx%d box from %s, %d blocks, %d entities, %s", info.name(), info.size().getX(),
				info.size().getY(), info.size().getZ(), info.min().toShortString(), info.nonAirBlocks(), info.entities(),
				info.statueAnchors() < 0 ? "no statue data (statue anchors kept)" : info.statueAnchors() + " statue anchor(s)");
			if (!confirm) {
				ctx.getSource().sendSuccess(() -> Component.literal("Would restore " + what + ". Everything in that box is replaced "
					+ "(the current hall is backed up first). Run /ht hall restore " + name + " confirm to do it."), false);
				return 1;
			}
			HallBuilder.BackupResult safety = HallBuilder.backup(hall, data);
			HallBuilder.restore(hall, data, name);
			ctx.getSource().sendSuccess(() -> Component.literal("Restored " + what + ". The hall before the restore is in backup "
				+ safety.name() + ".").withStyle(ChatFormatting.GREEN), true);
			return 1;
		} catch (IOException | RuntimeException e) {
			return fail(ctx, "Restore failed: " + e.getMessage(), e);
		}
	}

	// ---- Helpers ----

	private static Optional<String> newestBackup() {
		HtWorldData data = HardcoreTogether.worldData();
		try {
			return data == null ? Optional.empty() : HallBackups.newest(data.folder());
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	private static HtWorldData dataOrFail(CommandContext<CommandSourceStack> ctx) {
		HtWorldData data = HardcoreTogether.worldData();
		if (data == null || DeathHall.level(ctx.getSource().getServer()) == null) {
			ctx.getSource().sendFailure(Component.literal("The Death Hall or the data folder is not available."));
			return null;
		}
		return data;
	}

	private static HallLayout loadLayout(CommandContext<CommandSourceStack> ctx) {
		HtWorldData data = dataOrFail(ctx);
		if (data == null) {
			return null;
		}
		try {
			return HallLayout.load(data.folder());
		} catch (IOException e) {
			fail(ctx, "Could not read the hall layout: " + e.getMessage(), e);
			return null;
		}
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String message, Exception e) {
		HardcoreTogether.LOGGER.error("[HT hall] {}", message, e);
		ctx.getSource().sendFailure(Component.literal(message));
		return 0;
	}
}
