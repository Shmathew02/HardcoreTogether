package net.hardcoretogether.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.hardcoretogether.hall.DeathHall;
import net.hardcoretogether.reset.JoinRouting;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec2;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * In 26.3 a joining player's level is chosen when the configuration task starts, several ticks before the
 * player is placed. This routes joiners during a reset to the Death Hall (so they never hold a level that is
 * about to close), and players from an older run to the new world spawn. It also reports tasks that target a
 * vanilla level, so DRAIN can wait for them.
 */
@Mixin(PrepareSpawnTask.class)
abstract class PrepareSpawnTaskMixin {
	@Shadow
	@Final
	private MinecraftServer server;

	@Shadow
	@Final
	private NameAndId nameAndId;

	@Unique
	private JoinRouting.Route hardcoreTogether$route = JoinRouting.Route.VANILLA;
	@Unique
	private DeathHall.SpawnPoint hardcoreTogether$hallSpawn;

	@ModifyExpressionValue(method = "start", at = @At(value = "INVOKE",
		target = "Ljava/util/Optional;orElseGet(Ljava/util/function/Supplier;)Ljava/lang/Object;", ordinal = 0))
	private Object hardcoreTogether$chooseLevel(Object vanillaLevel) {
		hardcoreTogether$route = JoinRouting.route(server, nameAndId.id());
		return switch (hardcoreTogether$route) {
			case DEATH_HALL -> DeathHall.level(server);
			case NEW_WORLD_SPAWN -> server.overworld();
			case VANILLA -> vanillaLevel;
		};
	}

	@ModifyExpressionValue(method = "start", at = @At(value = "INVOKE",
		target = "Ljava/util/Optional;orElseGet(Ljava/util/function/Supplier;)Ljava/lang/Object;", ordinal = 1))
	private Object hardcoreTogether$choosePosition(Object vanillaPosition) {
		return switch (hardcoreTogether$route) {
			case DEATH_HALL -> {
				hardcoreTogether$hallSpawn = DeathHall.spawnPoint(DeathHall.level(server));
				yield CompletableFuture.completedFuture(hardcoreTogether$hallSpawn.pos());
			}
			case NEW_WORLD_SPAWN -> {
				LevelData.RespawnData spawn = server.getWorldData().overworldData().getRespawnData();
				yield PlayerSpawnFinder.findSpawn(server.overworld(), spawn.pos());
			}
			case VANILLA -> vanillaPosition;
		};
	}

	/** Joins routed to the hall face the hall spawn's direction, not the saved one. */
	@ModifyExpressionValue(method = "start", at = @At(value = "INVOKE",
		target = "Ljava/util/Optional;orElse(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 1))
	private Object hardcoreTogether$chooseAngle(Object vanillaAngle) {
		if (hardcoreTogether$route == JoinRouting.Route.DEATH_HALL && hardcoreTogether$hallSpawn != null) {
			return new Vec2(hardcoreTogether$hallSpawn.yaw(), hardcoreTogether$hallSpawn.pitch());
		}
		return vanillaAngle;
	}

	@Inject(method = "start", at = @At("TAIL"))
	private void hardcoreTogether$track(Consumer<?> connection, CallbackInfo ci) {
		if (hardcoreTogether$route != JoinRouting.Route.DEATH_HALL) {
			JoinRouting.trackPending(this);
		}
	}

	@Inject(method = "spawnPlayer", at = @At("HEAD"))
	private void hardcoreTogether$untrackOnSpawn(CallbackInfoReturnable<ServerPlayer> cir) {
		JoinRouting.untrack(this);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void hardcoreTogether$untrackOnClose(CallbackInfo ci) {
		JoinRouting.untrack(this);
	}
}
