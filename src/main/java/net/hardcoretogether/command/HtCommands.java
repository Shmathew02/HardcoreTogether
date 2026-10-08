package net.hardcoretogether.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.sun.management.UnixOperatingSystemMXBean;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.run.DeathTrigger;
import net.hardcoretogether.run.DeathListener;
import net.hardcoretogether.config.HtConfig;
import net.hardcoretogether.data.CurrentRun;
import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.data.PlayerRecords;
import net.hardcoretogether.data.ResetState;
import net.hardcoretogether.hall.DeathHall;
import net.hardcoretogether.hall.HallBuilder;
import net.hardcoretogether.reset.PlayerReset;
import net.hardcoretogether.reset.ResetController;
import net.hardcoretogether.reset.ResetJournal;
import net.hardcoretogether.reset.TrashBin;
import net.hardcoretogether.reset.WorldSwapper;
import net.hardcoretogether.run.RunFormat;
import net.hardcoretogether.run.RunTimer;
import net.hardcoretogether.run.RunTracker;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** The /ht admin commands (op only) and /run. */
public final class HtCommands {
	/** Game mode each player had before /ht hall, so /ht leave can restore it. */
	private static final Map<UUID, GameType> modeBeforeHall = new HashMap<>();

	private HtCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> register(dispatcher));
	}

	/** Upper bound for set-time, so seconds * 1e9 can never overflow. */
	private static final long MAX_SET_TIME_SECONDS = 1_000_000_000L;

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		// /run and /stats are open to everyone.
		dispatcher.register(Commands.literal("run").executes(HtCommands::runInfo));
		StatsCommand.register(dispatcher);

		dispatcher.register(Commands.literal("ht")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("status").executes(ctx -> status(ctx, false))
				.then(Commands.literal("gc").executes(ctx -> status(ctx, true))))
			.then(HallCommand.node(HtCommands::hall))
			.then(Commands.literal("leave").executes(HtCommands::leave))
			.then(Commands.literal("reset").executes(ctx -> reset(ctx, false, null))
				.then(Commands.literal("dryrun").executes(ctx -> reset(ctx, true, null))
					.then(Commands.argument("seed", StringArgumentType.greedyString())
						.executes(ctx -> reset(ctx, true, StringArgumentType.getString(ctx, "seed")))))
				.then(Commands.literal("cancel").executes(HtCommands::cancelReset))
				.then(Commands.argument("seed", StringArgumentType.greedyString())
					.executes(ctx -> reset(ctx, false, StringArgumentType.getString(ctx, "seed")))))
			.then(Commands.literal("reset-count")
				.then(Commands.literal("set")
					.then(Commands.argument("n", IntegerArgumentType.integer(1))
						.executes(ctx -> setResetCount(ctx, IntegerArgumentType.getInteger(ctx, "n"))))))
			.then(Commands.literal("runs")
				.then(Commands.literal("list").executes(ctx -> RunsCommand.list(ctx, 1))
					.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> RunsCommand.list(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
				.then(Commands.literal("delete")
					.then(Commands.argument("n", IntegerArgumentType.integer(1))
						.executes(ctx -> RunsCommand.delete(ctx, IntegerArgumentType.getInteger(ctx, "n"), false))
						.then(Commands.literal("confirm")
							.executes(ctx -> RunsCommand.delete(ctx, IntegerArgumentType.getInteger(ctx, "n"), true)))))
				.then(Commands.literal("set-time")
					.then(Commands.argument("seconds", LongArgumentType.longArg(0, MAX_SET_TIME_SECONDS))
						.then(Commands.literal("confirm")
							.executes(ctx -> setRunTime(ctx, LongArgumentType.getLong(ctx, "seconds")))))))
			.then(Commands.literal("fresh-start").executes(ctx -> RunsCommand.freshStart(ctx, false))
				.then(Commands.literal("confirm").executes(ctx -> RunsCommand.freshStart(ctx, true))))
			.then(Commands.literal("stats")
				.then(Commands.literal("rebuild").executes(StatsCommand::rebuild)))
			.then(Commands.literal("player")
				.then(Commands.literal("info")
					.then(Commands.argument("name", StringArgumentType.word())
						.executes(ctx -> playerInfo(ctx, StringArgumentType.getString(ctx, "name"))))))
			.then(Commands.literal("run")
				.then(Commands.literal("start").executes(HtCommands::forceRunStart)))
			.then(Commands.literal("test-death")
				.then(Commands.argument("name", StringArgumentType.word())
					.executes(ctx -> testDeath(ctx, StringArgumentType.getString(ctx, "name"), ""))
					.then(Commands.argument("cause", StringArgumentType.greedyString())
						.executes(ctx -> testDeath(ctx, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "cause")))))));
	}

	/** /ht run start: starts the next run for everyone online, without the portal (only when a new world waits). */
	private static int forceRunStart(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		if (ResetController.isRunning()) {
			source.sendFailure(Component.literal("The next world is still being prepared; try again when the reset has finished."));
			return 0;
		}
		HardcoreTogether.LOGGER.info("[HT portal] /ht run start by {}", source.getTextName());
		int sent = ResetController.startNewRun(source.getServer(), "/ht run start by " + source.getTextName());
		if (sent < 0) {
			source.sendFailure(Component.literal("Nothing to start: no new world is waiting for the portal."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Run started for " + sent + " player(s)."), true);
		return 1;
	}

	/**
	 * /ht test-death <name> [<damage type> [<attacker type>]]: records a death for that name without killing anyone,
	 * e.g. "minecraft:explosion minecraft:creeper". Uses the online player's UUID and skin, else the registered
	 * UUID, else the offline UUID. Only works with allow_test_deaths.
	 */
	private static int testDeath(CommandContext<CommandSourceStack> ctx, String name, String cause) {
		CommandSourceStack source = ctx.getSource();
		if (!HardcoreTogether.config().allowTestDeaths()) {
			source.sendFailure(Component.literal("Test deaths are disabled on this server"));
			return 0;
		}
		MinecraftServer server = source.getServer();
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		java.util.UUID uuid;
		String shown;
		PlayerRecords records = HardcoreTogether.playerRecords();
		java.util.Optional<java.util.Map.Entry<java.util.UUID, PlayerRecords.Entry>> registered =
			records == null ? java.util.Optional.empty() : records.findByName(name);
		if (online != null) {
			uuid = online.getUUID();
			shown = online.getGameProfile().name();
		} else if (registered.isPresent()) {
			uuid = registered.get().getKey();
			shown = registered.get().getValue().name() == null ? name : registered.get().getValue().name();
		} else {
			uuid = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name);
			shown = name;
		}
		String[] parts = cause.isBlank() ? new String[0] : cause.trim().split("\\s+");
		String damage = parts.length > 0 ? parts[0] : "hardcore_together:test_death";
		String attacker = parts.length > 1 ? parts[1] : null;
		String message = shown + " died (test death: " + damage + (attacker == null ? "" : " by " + attacker) + ")";
		net.hardcoretogether.data.RunDeath death = new net.hardcoretogether.data.RunDeath(uuid, shown, message,
			"minecraft:overworld", 0, 64, 0, damage, attacker, online != null ? DeathListener.skinOf(online.getGameProfile()) : null);
		DeathTrigger.Outcome outcome = DeathListener.testDeath(server, death);
		if (outcome != DeathTrigger.Outcome.RESET_STARTED) {
			source.sendFailure(Component.literal("Test death of " + shown + " not counted: " + outcome));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Test death of " + shown + " recorded; the reset has started."), true);
		return 1;
	}

	/** With gc, runs a full garbage collection first so heap readings are comparable between resets. */
	private static int status(CommandContext<CommandSourceStack> ctx, boolean gc) {
		CommandSourceStack source = ctx.getSource();
		if (gc) {
			System.gc();
		}
		MinecraftServer server = source.getServer();
		HtConfig config = HardcoreTogether.config();
		HtWorldData data = HardcoreTogether.worldData();
		List<ServerPlayer> inHall = DeathHall.playersInside(server);

		String players = inHall.isEmpty()
			? "none"
			: inHall.stream().map(p -> p.getGameProfile().name()).collect(Collectors.joining(", "));
		String seed = config.hasFixedSeed() ? config.fixedSeed() : "(random each run)";

		StringBuilder out = new StringBuilder("[HT status]");
		out.append("\n Death Hall loaded: ").append(DeathHall.level(server) != null ? "yes" : "NO");
		if (data != null) {
			out.append("\n Reset: #").append(data.runNumber()).append(" (worlds reset so far; the run number is under Run timer)");
			out.append("\n Reset state: ").append(data.resetState());
			out.append("\n Data folder: ").append(data.folder());
		} else {
			out.append("\n Data folder: NOT AVAILABLE (see log)");
		}
		out.append("\n Config: countdown_seconds=").append(config.countdownSeconds()).append(", fixed_seed=").append(seed);
		out.append("\n Players in Death Hall (").append(inHall.size()).append("): ").append(players);
		ResetState phase = ResetController.phase();
		out.append("\n Reset phase: ").append(phase);
		if (phase != ResetState.IDLE) {
			out.append(ResetController.isDryRun() ? " (dry run)" : "")
				.append(", ").append(ResetController.ticksInPhase()).append(" ticks in phase, reset #")
				.append(ResetController.runNumber());
			Long chosen = ResetController.seed();
			String requested = ResetController.requestedSeed();
			out.append("\n Chosen seed: ").append(chosen != null ? chosen.toString()
				: "not chosen yet" + (requested != null ? " (requested: " + requested + ")" : ""));
			out.append("\n Held players: ").append(names(server, ResetController.heldPlayers()));
		}
		out.append("\n Current world seed: ").append(server.getWorldGenSettings().options().seed());
		if (data != null) {
			out.append("\n Last reset duration: ").append(data.lastResetSeconds() < 0 ? "none yet"
				: String.format("%.1f s", data.lastResetSeconds()));
			out.append("\n Last DRAIN flush: ").append(data.lastFlushMillis().isEmpty() ? "none yet"
				: ResetController.formatFlushTimes(data.lastFlushMillis()));
			out.append("\n Last swap duration: ").append(data.lastSwapMillis() < 0 ? "none yet" : data.lastSwapMillis() + " ms");
		}
		RunTracker timer = RunTimer.tracker();
		CurrentRun run = timer == null ? null : timer.current();
		if (run == null) {
			out.append("\n Run timer: OFF (see log)");
		} else {
			long saved = timer.lastSaveEpoch();
			out.append("\n Run timer: run #").append(run.run()).append(" ").append(run.status()).append(", ")
				.append(RunFormat.elapsed(timer.activeMillis())).append(" (").append(timer.activeMillis()).append(" ms), Day ")
				.append(timer.day()).append(", segment ").append(timer.segmentOpen() ? "open" : "closed")
				.append(", last save ").append(saved < 0 ? "never" : Instant.ofEpochMilli(saved).toString())
				.append(timer.saveFailing() ? " (SAVES FAILING)" : "").append(", participants ").append(run.participantMillis().size())
				.append(", history ").append(timer.history().size()).append(" runs, ").append(timer.saveAttempts()).append(" save attempts");
		}
		out.append("\n Difficulty: ").append(server.getWorldData().getDifficulty().getSerializedName())
			.append(", locked=").append(server.getWorldData().isDifficultyLocked())
			.append(" (config: ").append(config.difficulty().getSerializedName())
			.append(", lock_difficulty=").append(config.lockDifficulty()).append(")");
		ServerLevel overworld = server.overworld();
		out.append("\n Overworld rain level: ").append(overworld.getRainLevel(1.0F))
			.append(", thunder level: ").append(overworld.getThunderLevel(1.0F));
		for (var key : WorldSwapper.RESET_LEVELS) {
			ServerLevel level = server.getLevel(key);
			out.append("\n ").append(key.identifier().getPath()).append(": ");
			if (level == null) {
				out.append("not loaded");
			} else {
				out.append(level.getChunkSource().getLoadedChunksCount()).append(" chunks loaded, hasWork=")
					.append(level.getChunkSource().chunkMap.hasWork());
			}
		}
		Runtime rt = Runtime.getRuntime();
		out.append(gc ? "\n JVM heap used after GC: " : "\n JVM heap used: ").append((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
			.append(" MB of ").append(rt.maxMemory() / (1024 * 1024)).append(" MB");
		out.append("\n Open files: ").append(openFileCount());
		out.append("\n Trash folders being deleted: ").append(TrashBin.pending());
		out.append("\n Journal: ").append(data != null && ResetJournal.exists(data) ? "present" : "none");
		if (data != null && !data.pendingReturn().isEmpty()) {
			out.append("\n Pending return on join: ").append(names(server, data.pendingReturn()));
		}
		out.append("\n Hall edit mode: ").append(names(server, DeathHall.editors()));

		out.append("\n Commands: /ht status [gc] | hall [export] | hall edit on|off | hall anchor add|list|remove|show | hall bounds [show] | hall backup | hall restore [<b> [confirm]] | leave | reset [seed] | reset dryrun [seed]")
			.append(" | reset cancel | reset-count set <n> (IDLE only) | runs list [page] | runs delete <n> [confirm]")
			.append(" | runs set-time <s> confirm | fresh-start [confirm] | stats rebuild | player info <name>; /run, /stats (everyone)");

		String text = out.toString();
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	/** Open file descriptors of the server process (files, jars and sockets), or "unknown" off Unix. */
	private static String openFileCount() {
		return ManagementFactory.getOperatingSystemMXBean() instanceof UnixOperatingSystemMXBean unix
			? Long.toString(unix.getOpenFileDescriptorCount())
			: "unknown";
	}

	private static int runInfo(CommandContext<CommandSourceStack> ctx) {
		RunTracker timer = RunTimer.tracker();
		CurrentRun run = timer == null ? null : timer.current();
		if (run == null) {
			ctx.getSource().sendSuccess(() -> Component.literal("No runs yet.").withStyle(ChatFormatting.WHITE), false);
			return 0;
		}
		Component number = Component.literal("Run #" + run.run()).withStyle(ChatFormatting.GOLD);
		String elapsed = RunFormat.elapsed(timer.activeMillis());
		Component text = run.isActive()
			? Component.empty().append(number).append(Component.literal(" \u2014 " + elapsed + " \u2014 Day " + timer.day()).withStyle(ChatFormatting.WHITE))
			: Component.literal("No active run. ").withStyle(ChatFormatting.WHITE).append(number)
				.append(Component.literal(" lasted " + elapsed + " and reached Day " + timer.day() + ".").withStyle(ChatFormatting.WHITE));
		ctx.getSource().sendSuccess(() -> text, false);
		return run.run();
	}

	private static int setRunTime(CommandContext<CommandSourceStack> ctx, long seconds) {
		CommandSourceStack source = ctx.getSource();
		RunTracker timer = RunTimer.tracker();
		CurrentRun run = timer == null ? null : timer.current();
		if (run == null) {
			source.sendFailure(Component.literal("No run is being timed right now."));
			return 0;
		}
		long old = timer.activeMillis();
		timer.setActiveMillis(seconds * 1000L);
		HardcoreTogether.LOGGER.info("[HT timer] run #{} time set from {} ms to {} ms by {}", run.run(), old, seconds * 1000L, source.getTextName());
		source.sendSuccess(() -> Component.literal("Run #" + run.run() + " time set from " + RunFormat.elapsed(old) + " to "
			+ RunFormat.elapsed(seconds * 1000L) + "."), true);
		return 1;
	}

	private static int playerInfo(CommandContext<CommandSourceStack> ctx, String name) {
		CommandSourceStack source = ctx.getSource();
		PlayerRecords records = HardcoreTogether.playerRecords();
		if (records == null) {
			source.sendFailure(Component.literal("The player registry is not available (see log)."));
			return 0;
		}
		var found = records.findByName(name);
		if (found.isEmpty()) {
			source.sendFailure(Component.literal("No player named " + name + " in the registry (current or past names)."));
			return 0;
		}
		UUID uuid = found.get().getKey();
		PlayerRecords.Entry e = found.get().getValue();
		String text = "[HT player] " + (e.name() == null ? "(no name yet)" : e.name())
			+ "\n UUID: " + uuid
			+ "\n Names seen: " + (e.namesSeen().isEmpty() ? "none" : String.join(", ", e.namesSeen()))
			+ "\n First seen: " + (e.firstSeen() == null ? "never joined since the registry started" : e.firstSeen())
			+ "\n Last seen: " + (e.lastSeen() == null ? "-" : e.lastSeen())
			+ "\n Last reset played: " + (e.lastRunId().isPresent() ? String.valueOf(e.lastRunId().getAsInt()) : "none")
			+ statsLine(uuid)
			+ connectionLine(source.getServer().getPlayerList().getPlayer(uuid));
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	/** Where the server thinks an online player is, and whether it accepts their movement. */
	private static String connectionLine(ServerPlayer player) {
		if (player == null) {
			return "\n Online: no";
		}
		return String.format(java.util.Locale.ROOT, "\n Online: %s at %.2f %.2f %.2f, client loaded: %s, waiting for respawn: %s",
			player.level().dimension().identifier(), player.getX(), player.getY(), player.getZ(),
			player.connection.hasClientLoaded() ? "yes" : "NO", PlayerReset.waitingForRespawn(player) ? "YES (movement ignored)" : "no");
	}

	private static String statsLine(UUID uuid) {
		RunTracker timer = RunTimer.tracker();
		if (timer == null) {
			return "\n Stats: not available";
		}
		var s = timer.stats().get(uuid);
		return "\n Runs played: " + s.runsPlayed() + ", deaths: " + s.deaths() + ", playtime: " + RunFormat.elapsed(s.totalPlaytimeMillis())
			+ " (finished runs; /stats adds the current one)";
	}

	/** Renumbers the reset counter (state.json) without a reset. */
	private static int setResetCount(CommandContext<CommandSourceStack> ctx, int count) {
		CommandSourceStack source = ctx.getSource();
		HtWorldData data = HardcoreTogether.worldData();
		if (data == null) {
			source.sendFailure(Component.literal("The data folder is not available (see log)."));
			return 0;
		}
		if (ResetController.isRunning() || data.resetState() != ResetState.IDLE) {
			source.sendFailure(Component.literal("Can't change the reset count during a reset."));
			return 0;
		}
		int old = data.runNumber();
		data.setRunNumber(count);
		try {
			data.save();
		} catch (IOException e) {
			data.setRunNumber(old);
			HardcoreTogether.LOGGER.error("Could not save the reset count", e);
			source.sendFailure(Component.literal("Could not save state.json (see log)."));
			return 0;
		}
		HardcoreTogether.LOGGER.info("[HT] reset count changed from {} to {} by {}", old, count, source.getTextName());
		RunTimer.worldResetRenumbered(count);
		source.sendSuccess(() -> Component.literal("Reset count changed from " + old + " to " + count + " (run numbers are separate)."), true);
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx, boolean dry, String seed) {
		CommandSourceStack source = ctx.getSource();
		return switch (ResetController.request(source.getServer(), source.getTextName(), dry, seed)) {
			case STARTED -> {
				source.sendSuccess(() -> Component.literal(dry ? "Dry-run reset started." : "World reset started."), true);
				yield 1;
			}
			case GUARD_FAILED -> {
				source.sendFailure(Component.literal("Reset refused: the dimension folder safety check failed (see server log)."));
				yield 0;
			}
			case ALREADY_RUNNING -> {
				source.sendFailure(Component.literal("A reset is already running (" + ResetController.phase() + ")."));
				yield 0;
			}
			case UNAVAILABLE -> {
				source.sendFailure(Component.literal("Can't reset: the Death Hall or the data folder is not available."));
				yield 0;
			}
		};
	}

	private static int cancelReset(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		return switch (ResetController.cancel(source.getServer())) {
			case CANCELLED -> {
				source.sendSuccess(() -> Component.literal("Reset cancelled."), true);
				yield 1;
			}
			case TOO_LATE -> {
				source.sendFailure(Component.literal("The reset is past COMMIT (" + ResetController.phase() + ") and can't be cancelled."));
				yield 0;
			}
			case NOT_RUNNING -> {
				source.sendFailure(Component.literal("No reset is running."));
				yield 0;
			}
			case NOT_CANCELLABLE -> {
				source.sendFailure(Component.literal("A reset started by a death can't be cancelled."));
				yield 0;
			}
		};
	}

	private static String names(MinecraftServer server, Collection<UUID> uuids) {
		if (uuids.isEmpty()) {
			return "none";
		}
		return uuids.stream().map(uuid -> {
			ServerPlayer p = server.getPlayerList().getPlayer(uuid);
			return p != null ? p.getGameProfile().name() : uuid + " (offline)";
		}).collect(Collectors.joining(", "));
	}

	private static int hall(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ServerLevel hall = DeathHall.level(ctx.getSource().getServer());
		if (hall == null) {
			ctx.getSource().sendFailure(Component.literal("The Death Hall dimension is not loaded."));
			return 0;
		}

		if (!DeathHall.contains(player)) {
			modeBeforeHall.put(player.getUUID(), player.gameMode());
		}
		DeathHall.teleportToSpawn(player, hall);
		if (!DeathHall.isEditing(player)) {
			player.setGameMode(GameType.ADVENTURE);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Moved to the Death Hall."), false);
		return 1;
	}

	private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		MinecraftServer server = ctx.getSource().getServer();

		DeathHall.sendToWorldSpawn(player);

		GameType mode = modeBeforeHall.remove(player.getUUID());
		if (mode == null) {
			mode = server.getDefaultGameType();
		}
		player.setGameMode(mode);
		GameType restored = mode;
		ctx.getSource().sendSuccess(() -> Component.literal("Moved to the overworld spawn, game mode " + restored.getName() + "."), false);
		return 1;
	}
}
