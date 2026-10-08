package net.hardcoretogether.mixin;

import net.hardcoretogether.hall.DeathHall;

import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hall rule: no hunger loss. Every source of food exhaustion goes through this method. */
@Mixin(Player.class)
abstract class PlayerMixin {
	@Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
	private void hardcoreTogether$noHungerInHall(float amount, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (!self.level().isClientSide() && DeathHall.contains(self)) {
			ci.cancel();
		}
	}
}
