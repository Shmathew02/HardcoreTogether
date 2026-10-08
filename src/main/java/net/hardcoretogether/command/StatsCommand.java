package net.hardcoretogether.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.PlayerRecords;
import net.hardcoretogether.data.PlayerStats;
import net.hardcoretogether.run.RunFormat;
import net.hardcoretogether.run.RunTimer;
import net.hardcoretogether.run.RunTracker;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** /stats [player] for everyone, and /ht stats rebuild. */
final class StatsCommand {
	private StatsCommand() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("stats").executes(StatsCommand::own)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests((ctx, builder) -> {
					PlayerRecords records = HardcoreTogether.playerRecords();
					return SharedSuggestionProvider.suggest(records == null ? List.of() : records.currentNames(), builder);
				})
				.executes(ctx -> show(ctx.getSource(), StringArgumentType.getString(ctx, "player")))));
	}

	private static int own(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("From the console, name a player: /stats <player>"));
			return 0;
		}
		return show(source, player.getUUID(), player.getGameProfile().name());
	}

	private static int show(CommandSourceStack source, String name) {
		RunTracker timer = RunTimer.tracker();
		PlayerRecords records = HardcoreTogether.playerRecords();
		Optional<UUID> uuid = records == null ? Optional.empty() : records.findByName(name).map(e -> e.getKey());
		if (uuid.isEmpty() && timer != null) {
			uuid = timer.stats().findByName(name);
		}
		if (uuid.isEmpty()) {
			source.sendFailure(Component.literal("No stats for " + name + "."));
			return 0;
		}
		return show(source, uuid.get(), displayName(records, timer, uuid.get(), name));
	}

	private static String displayName(@Nullable PlayerRecords records, @Nullable RunTracker timer, UUID uuid, String typed) {
		PlayerRecords.Entry entry = records == null ? null : records.get(uuid);
		if (entry != null && entry.name() != null) {
			return entry.name();
		}
		String fromStats = timer == null ? null : timer.stats().get(uuid).name();
		return fromStats != null ? fromStats : typed;
	}

	private static int show(CommandSourceStack source, UUID uuid, String name) {
		RunTracker timer = RunTimer.tracker();
		if (timer == null) {
			source.sendFailure(Component.literal("Stats are not available right now (see log)."));
			return 0;
		}
		PlayerStats.View v = timer.stats().view(uuid, timer.current(), timer.activeMillis(), timer.day());
		MutableComponent text = Component.literal("Stats for " + name).withStyle(ChatFormatting.GOLD)
			.append(white("\nRuns played: " + v.runsPlayed() + "   Deaths: " + v.deaths()))
			.append(white("\nTotal playtime: " + RunFormat.elapsed(v.totalPlaytimeMillis())))
			.append(white("\nLongest run: "));
		if (v.longestRun() == 0) {
			text.append(white("none"));
		} else {
			text.append(white(RunFormat.elapsed(v.longestRunMillis()) + " ")).append(record(v.longestRun(), v.longestInProgress()));
		}
		text.append(white("   Furthest day: "));
		if (v.furthestDayRun() == 0) {
			text.append(white("none"));
		} else {
			text.append(white("Day " + v.furthestDay() + " ")).append(record(v.furthestDayRun(), v.furthestInProgress()));
		}
		source.sendSuccess(() -> text, false);
		return 1;
	}

	/** "(Run #n)" or "(Run #n, in progress)", with Run #n in gold like /run. */
	private static Component record(int run, boolean inProgress) {
		return white("(").append(Component.literal("Run #" + run).withStyle(ChatFormatting.GOLD))
			.append(white(inProgress ? ", in progress)" : ")"));
	}

	private static MutableComponent white(String s) {
		return Component.literal(s).withStyle(ChatFormatting.WHITE);
	}

	/** /ht stats rebuild (op): recomputes every player's stats from run-history.json alone. */
	static int rebuild(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		RunTracker timer = RunTimer.tracker();
		if (timer == null) {
			source.sendFailure(Component.literal("Stats are not available right now (see log)."));
			return 0;
		}
		boolean changed = timer.rebuildStats();
		int players = timer.stats().all().size();
		int runs = timer.history().size();
		HardcoreTogether.LOGGER.info("[HT stats] rebuilt by {}: {} player(s) from {} run(s), {}", source.getTextName(), players, runs,
			changed ? "CHANGED (the saved stats differed)" : "unchanged");
		source.sendSuccess(() -> Component.literal("Stats rebuilt from " + runs + " run(s) for " + players + " player(s): "
			+ (changed ? "values changed (see log)." : "no change.")), true);
		return players;
	}
}
