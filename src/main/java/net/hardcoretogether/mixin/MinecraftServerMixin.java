package net.hardcoretogether.mixin;

import net.hardcoretogether.config.DifficultyControl;
import net.hardcoretogether.reset.ResetController;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Two small server hooks:
 * - An empty server stops ticking after pause-when-empty-seconds, which would freeze a reset with nobody
 *   online. While a reset runs the empty-tick counter is kept at zero; outside a reset vanilla's behaviour is
 *   untouched.
 * - While lock_difficulty is on, the difficulty can't be unlocked.
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
	@Shadow
	private int emptyTicks;

	@Inject(method = "tickServer", at = @At("HEAD"))
	private void hardcoreTogether$noPauseDuringReset(BooleanSupplier haveTime, CallbackInfo ci) {
		if (ResetController.isRunning()) {
			emptyTicks = 0;
		}
	}

	/**
	 * While lock_difficulty is on, nothing can unlock the difficulty: vanilla's lock packet
	 * (ServerboundLockDifficultyPacket) and any other caller of setDifficultyLocked(false) get true instead.
	 */
	@ModifyVariable(method = "setDifficultyLocked", at = @At("HEAD"), argsOnly = true)
	private boolean hardcoreTogether$keepDifficultyLocked(boolean locked) {
		return locked || DifficultyControl.configLocked();
	}
}
