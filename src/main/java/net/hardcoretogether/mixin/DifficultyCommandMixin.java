package net.hardcoretogether.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.hardcoretogether.config.DifficultyControl;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.DifficultyCommand;
import net.minecraft.world.Difficulty;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's /difficulty calls MinecraftServer.setDifficulty(d, true), which ignores the difficulty lock. While
 * lock_difficulty is on, refuse it with a command error instead (querying with plain /difficulty still works).
 */
@Mixin(DifficultyCommand.class)
abstract class DifficultyCommandMixin {
	@Unique
	private static final SimpleCommandExceptionType hardcoreTogether$LOCKED = new SimpleCommandExceptionType(
		Component.literal("The difficulty is locked by Hardcore Together (lock_difficulty in config/hardcore_together.json)."));

	@Inject(method = "setDifficulty", at = @At("HEAD"))
	private static void hardcoreTogether$refuseWhileLocked(CommandSourceStack source, Difficulty difficulty,
			CallbackInfoReturnable<Integer> cir) throws CommandSyntaxException {
		if (DifficultyControl.configLocked()) {
			throw hardcoreTogether$LOCKED.create();
		}
	}
}
