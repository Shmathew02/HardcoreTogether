package net.hardcoretogether.mixin;

import net.hardcoretogether.reset.DrainDiagnostics;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * DRAIN diagnostics: every main-thread chunk request goes through getChunkFutureMainThread, which adds the
 * UNKNOWN ticket that deactivateTicketsOnClosing() never removes. Reports the caller while a reset is in DRAIN.
 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "getChunkFutureMainThread", at = @At("HEAD"))
	private void hardcoreTogether$reportDrainChunkRequest(int x, int z, ChunkStatus status, boolean loadOrGenerate,
			CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
		if (DrainDiagnostics.isActive()) {
			DrainDiagnostics.chunkRequested(level, x, z, status, loadOrGenerate);
		}
	}
}
