package net.hardcoretogether.mixin;

import com.mojang.serialization.Lifecycle;

import net.hardcoretogether.hall.DeathHall;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla marks every non-vanilla dimension as experimental, which makes single-player show the
 * "experimental settings" warning for any world with the Death Hall. Fabric API 0.161 does not change this.
 * Only our own dimension is marked stable; every other dimension keeps the vanilla check.
 */
@Mixin(WorldDimensions.class)
abstract class WorldDimensionsMixin {
	@Inject(method = "checkStability", at = @At("HEAD"), cancellable = true)
	private static void hardcoreTogether$deathHallIsStable(ResourceKey<LevelStem> key, LevelStem dimension, CallbackInfoReturnable<Lifecycle> cir) {
		if (key.identifier().equals(DeathHall.KEY.identifier())) {
			cir.setReturnValue(Lifecycle.stable());
		}
	}
}
