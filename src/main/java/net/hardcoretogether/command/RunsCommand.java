package net.hardcoretogether.command;

import com.mojang.brigadier.context.CommandContext;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.CurrentRun;
import net.hardcoretogether.data.MemorialData;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.data.RunHistoryEntry;
import net.hardcoretogether.reset.ResetController;
import net.hardcoretogether.run.RunFormat;
import net.hardcoretogether.run.RunTimer;
import net.hardcoretogether.run.RunTracker;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;

/** /ht runs list, /ht runs delete and /ht fresh-start. */
final class RunsCommand {
	private static final int PAGE_SIZE = 10;

	private RunsCommand() {
	}

	/** /ht runs list [page]: finished runs, newest first, 10 per page. */
	static int list(CommandContext<CommandSourceStack> ctx, int page) {
		CommandSourceStack source = ctx.getSource();
		MemorialData data = MemorialData.get();
		int total = data.totals().totalRuns();
		if (total == 0) {
			source.sendSuccess(() -> Component.literal("No finished runs."), false);
			return 0;
		}
		int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
		if (page > pages) {
			source.sendFailure(Component.literal("Page " + page + " doesn't exist (" + pages + " page" + (pages == 1 ? "" : "s") + ")."));
			return 0;
		}
		List<RunHistoryEntry> runs = data.recentRuns(page * PAGE_SIZE);
		StringBuilder out = new StringBuilder("Runs, newest first (page " + page + "/" + pages + ", " + total + " total):");
		for (RunHistoryEntry r : runs.subList((page - 1) * PAGE_SIZE, runs.size())) {
			out.append("\n ").append(summary(r));
		}
		String text = out.toString();
		source.sendSuccess(() -> Component.literal(text), false);
		return runs.size() - (page - 1) * PAGE_SIZE;
	}

	/** /ht runs delete <n> [confirm]: the first call only shows the run. The ACTIVE run is always refused. */
	static int delete(CommandContext<CommandSourceStack> ctx, int number, boolean confirm) {
		CommandSourceStack source = ctx.getSource();
		RunTracker timer = RunTimer.tracker();
		if (timer == null) {
			source.sendFailure(Component.literal("The run records are not available right now (see log)."));
			return 0;
		}
		CurrentRun current = timer.current();
		if (current != null && current.isActive() && current.run() == number) {
			source.sendFailure(Component.literal("Run #" + number + " is the ACTIVE run and can't be deleted."));
			return 0;
		}
		Optional<RunHistoryEntry> run = MemorialData.get().run(number);
		if (run.isEmpty()) {
			source.sendFailure(Component.literal("No finished run #" + number + " in the history."));
			return 0;
		}
		String summary = summary(run.get());
		if (!confirm) {
			source.sendSuccess(() -> Component.literal(summary + "\nRepeat with: /ht runs delete " + number + " confirm")
				.withStyle(ChatFormatting.YELLOW), false);
			return 0;
		}
		RunTracker.DeleteResult result = timer.deleteRun(number);
		if (result != RunTracker.DeleteResult.DELETED) {
			source.sendFailure(Component.literal("Run #" + number + " was not deleted (" + result + ")."));
			return 0;
		}
		HardcoreTogether.LOGGER.info("[HT runs] run #{} deleted by {} ({}); stats rebuilt, run number stays used", number,
			source.getTextName(), summary);
		source.sendSuccess(() -> Component.literal("Deleted run #" + number + "; stats rebuilt. Run numbers are not reused."), true);
		return 1;
	}

	/**
	 * /ht fresh-start [confirm]: wipes the run history, stats and current run, then resets the world (not
	 * cancellable, not recorded as a run end); the next run is Run #1. Without confirm it only explains.
	 */
	static int freshStart(CommandContext<CommandSourceStack> ctx, boolean confirm) {
		CommandSourceStack source = ctx.getSource();
		if (!confirm) {
			source.sendSuccess(() -> Component.literal("Fresh start deletes run-history.json, player-stats.json and current-run.json"
				+ " (every run and every player's stats), then resets the world. The next run is Run #1. The player registry,"
				+ " config and reset counter are kept. It can't be cancelled or undone. Nothing has changed."
				+ "\nTo do it: /ht fresh-start confirm").withStyle(ChatFormatting.YELLOW), false);
			return 0;
		}
		RunTracker timer = RunTimer.tracker();
		if (timer == null) {
			source.sendFailure(Component.literal("The run records are not available right now (see log)."));
			return 0;
		}
		int runs = timer.history().size();
		int players = timer.stats().all().size();
		CurrentRun current = timer.current();
		ResetController.RequestResult result = ResetController.request(source.getServer(), "fresh start", false, null, false);
		if (result != ResetController.RequestResult.STARTED) {
			source.sendFailure(Component.literal("Fresh start refused: the world reset could not start (" + result + "). Nothing was deleted."));
			return 0;
		}
		// The reset is in COUNTDOWN now; with no current run its COMMIT records nothing, and RETURN starts Run #1.
		if (!timer.freshStart()) {
			HardcoreTogether.LOGGER.error("[HT fresh-start] by {}: the run files could not be deleted; the world reset continues", source.getTextName());
			source.sendFailure(Component.literal("Fresh start: the run files could not be deleted (see log); the world reset continues."));
			return 0;
		}
		HardcoreTogether.LOGGER.info("[HT fresh-start] by {}: deleted run-history.json ({} runs), player-stats.json ({} players) and"
			+ " current-run.json (run #{}); next run is Run #1; world reset started (players.json, config, reset counter kept)",
			source.getTextName(), runs, players, current == null ? "none" : current.run());
		source.sendSuccess(() -> Component.literal("Fresh start: run records wiped, world reset started. The next run is Run #1."), true);
		return 1;
	}

	/** One line: Run #, duration, Day, end reason, who died (or "command"), death message. */
	private static String summary(RunHistoryEntry r) {
		RunDeath d = r.death();
		boolean death = "death".equals(r.endKind()) && d != null;
		return "Run #" + r.run() + " | " + RunFormat.elapsed(r.finalActiveMillis()) + " | Day " + r.finalDay() + " | " + r.endKind()
			+ " | " + (death ? d.name() : "command") + " | " + (death ? "\"" + d.message() + "\"" : "-");
	}
}
