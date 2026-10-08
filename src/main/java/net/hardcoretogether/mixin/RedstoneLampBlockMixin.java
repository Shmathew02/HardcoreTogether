package net.hardcoretogether.mixin;

import net.hardcoretogether.hall.AlwaysLitLamps;
import net.hardcoretogether.hall.DeathHall;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An unpowered lit lamp turns itself off in its scheduled tick (scheduled by any neighbour update, for example
 * structure placement). Death Hall lamps kept lit by {@link AlwaysLitLamps} skip that tick, so they never go dark.
 */
@Mixin(RedstoneLampBlock.class)
abstract class RedstoneLampBlockMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void hardcoreTogether$keepHallLampLit(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (level.dimension() == DeathHall.KEY && AlwaysLitLamps.contains(pos)) {
			ci.cancel();
		}
	}
}
