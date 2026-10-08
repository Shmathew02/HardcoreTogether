package net.hardcoretogether.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;
import java.util.concurrent.Executor;

/** What the world reset needs from MinecraftServer to close and rebuild the vanilla levels. */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
	@Accessor("levels")
	Map<ResourceKey<Level>, ServerLevel> hardcoreTogether$levels();

	@Accessor("worldGenSettings")
	@Mutable
	void hardcoreTogether$setWorldGenSettings(WorldGenSettings settings);

	@Accessor("executor")
	Executor hardcoreTogether$executor();

	@Accessor("storageSource")
	LevelStorageSource.LevelStorageAccess hardcoreTogether$storageSource();

	@Invoker("setInitialSpawn")
	static void hardcoreTogether$setInitialSpawn(ServerLevel level, ServerLevelData levelData, boolean spawnBonusChest,
			boolean isDebug, LevelLoadListener levelLoadListener) {
		throw new AssertionError();
	}

	@Invoker("updateEffectiveRespawnData")
	void hardcoreTogether$updateEffectiveRespawnData();
}
