package net.hardcoretogether.mixin;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.TicketStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** DRAIN diagnostics: the ticket store and distance manager of a level's chunk cache. */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
	@Accessor("ticketStorage")
	TicketStorage hardcoreTogether$ticketStorage();

	@Accessor("distanceManager")
	DistanceManager hardcoreTogether$distanceManager();
}
