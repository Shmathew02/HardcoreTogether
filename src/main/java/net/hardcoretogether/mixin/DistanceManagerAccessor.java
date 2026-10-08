package net.hardcoretogether.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** DRAIN diagnostics: players the distance manager still keeps chunks loaded for. */
@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {
	@Accessor("playersPerChunk")
	Long2ObjectMap<ObjectSet<ServerPlayer>> hardcoreTogether$playersPerChunk();
}
