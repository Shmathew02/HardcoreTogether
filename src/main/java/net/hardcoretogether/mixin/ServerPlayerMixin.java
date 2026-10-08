package net.hardcoretogether.mixin;

import net.hardcoretogether.hall.DeathHall;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hall rule: players cannot drop items (except in edit mode). */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	/** The drop key (Q / Ctrl+Q): cancel before the item leaves the slot, then resync the client. */
	@Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
	private void hardcoreTogether$noDropKeyInHall(boolean all, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (DeathHall.rulesApply(self)) {
			ci.cancel();
			self.containerMenu.sendAllDataToRemote();
		}
	}

	/**
	 * Throwing from an inventory screen (clicking outside it, or Q over a slot). The stack has already been
	 * taken from the slot or cursor, so put it back. Passing the original stack empties it, which also ends
	 * vanilla's "throw the whole stack" loop. Non-thrown drops (e.g. a full inventory when closing a crafting
	 * grid) are left to vanilla.
	 */
	@Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
		at = @At("HEAD"), cancellable = true)
	private void hardcoreTogether$noThrowInHall(ItemStack stack, boolean thrownFromHand, Prediction prediction, CallbackInfoReturnable<ItemEntity> cir) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (thrownFromHand && !stack.isEmpty() && DeathHall.rulesApply(self)) {
			self.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
			self.containerMenu.sendAllDataToRemote();
			cir.setReturnValue(null);
		}
	}
}
