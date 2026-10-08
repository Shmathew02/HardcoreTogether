package net.hardcoretogether.config;

import net.hardcoretogether.HardcoreTogether;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;

/**
 * Applies the configured difficulty (config "difficulty" / "lock_difficulty") on server start and after every
 * reset. Locking uses vanilla's own flag (WorldData.isDifficultyLocked, the one hardcore worlds use): it greys
 * out the single-player difficulty button and makes MinecraftServer.setDifficulty(d, false) a no-op. Two mixins
 * close the paths vanilla's flag doesn't cover: /difficulty (DifficultyCommandMixin) and unlocking
 * (MinecraftServerMixin).
 */
public final class DifficultyControl {
	private DifficultyControl() {
	}

	public static boolean configLocked() {
		return HardcoreTogether.config().lockDifficulty();
	}

	public static void apply(MinecraftServer server, String when) {
		HtConfig config = HardcoreTogether.config();
		Difficulty before = server.getWorldData().getDifficulty();
		server.setDifficulty(config.difficulty(), true);
		if (config.lockDifficulty()) {
			server.setDifficultyLocked(true);
		}
		HardcoreTogether.LOGGER.info("Difficulty {} ({}): {} -> {}, locked={}", when, config.lockDifficulty() ? "config, locked" : "config",
			before.getSerializedName(), server.getWorldData().getDifficulty().getSerializedName(), server.getWorldData().isDifficultyLocked());
	}
}
