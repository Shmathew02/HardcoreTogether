package net.hardcoretogether;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.hardcoretogether.command.HtCommands;
import net.hardcoretogether.config.DifficultyControl;
import net.hardcoretogether.config.HtConfig;
import net.hardcoretogether.data.HtWorldData;
import net.hardcoretogether.data.PlayerRecords;
import net.hardcoretogether.hall.DeathHall;
import net.hardcoretogether.reset.ResetController;
import net.hardcoretogether.run.DeathListener;
import net.hardcoretogether.run.RunTimer;

import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** Mod entry point: loads the config and the world's data folder, and registers every part of the mod. */
public class HardcoreTogether implements ModInitializer {
	public static final String MOD_ID = "hardcore_together";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static HtConfig config = HtConfig.defaults();
	private static @Nullable HtWorldData worldData;
	private static @Nullable PlayerRecords playerRecords;

	@Override
	public void onInitialize() {
		config = HtConfig.load();
		LOGGER.info("Config: countdown_seconds={}, fixed_seed={}", config.countdownSeconds(),
			config.hasFixedSeed() ? config.fixedSeed() : "(random)");

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			try {
				worldData = HtWorldData.load(server);
				playerRecords = PlayerRecords.load(worldData.folder());
				LOGGER.info("Data folder {} (run {}, state {})", worldData.folder(), worldData.runNumber(), worldData.resetState());
			} catch (IOException e) {
				worldData = null;
				playerRecords = null;
				LOGGER.error("Could not load the Hardcore Together data folder", e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			worldData = null;
			playerRecords = null;
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> DifficultyControl.apply(server, "on server start"));

		// Order matters: RunTimer's startup hook expects ResetController's shutdown recovery to have run.
		DeathHall.register();
		ResetController.register();
		RunTimer.register();
		DeathListener.register();
		HtCommands.register();

		LOGGER.info("Hardcore Together loaded");
	}

	/** An identifier in the mod's namespace. */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** The config loaded at startup. */
	public static HtConfig config() {
		return config;
	}

	/** Null only if the data folder could not be read; the cause is in the log. */
	public static @Nullable PlayerRecords playerRecords() {
		return playerRecords;
	}

	/** Null only if the data folder could not be read; the cause is in the log. */
	public static @Nullable HtWorldData worldData() {
		return worldData;
	}
}
