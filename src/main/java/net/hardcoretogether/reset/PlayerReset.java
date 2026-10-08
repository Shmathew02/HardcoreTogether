package net.hardcoretogether.reset;

import net.hardcoretogether.mixin.ServerGamePacketListenerImplAccessor;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;

/** The fresh-player treatment for a new run (RETURN). */
public final class PlayerReset {
	private PlayerReset() {
	}

	/**
	 * Every PlayerList.respawn call outside vanilla's Respawn button goes through here. respawn() leaves the
	 * connection to its caller; do what PERFORM_RESPAWN (ServerGamePacketListenerImpl.handleClientCommand) does:
	 * <ul>
	 * <li>swap the connection's player;</li>
	 * <li>resetPosition(), so the movement check's last good position is the new spot (without it the client's
	 * teleport ack was checked against the old position: "moved too quickly!");</li>
	 * <li>restartClientLoadTimerAfterRespawn(): clears waitingForRespawn, which die() sets. Left set, the server
	 * ignores the client's movement and block use until reconnect (the player stays at the respawn spot
	 * server-side; placing and pick block only work near it).</li>
	 * </ul>
	 * Returns the respawned player.
	 */
	public static ServerPlayer attachRespawned(ServerPlayer respawned) {
		respawned.connection.player = respawned;
		respawned.connection.resetPosition();
		((ServerGamePacketListenerImplAccessor) respawned.connection).hardcoreTogether$restartClientLoadTimerAfterRespawn();
		return respawned;
	}

	/** True while the connection ignores movement because it waits for a respawn (should never stay true). */
	public static boolean waitingForRespawn(ServerPlayer player) {
		return ((ServerGamePacketListenerImplAccessor) player.connection).hardcoreTogether$waitingForRespawn();
	}

	/**
	 * Replaces the player with a fresh one at the new world spawn, through vanilla's death-respawn path, then
	 * clears what that path keeps. Returns the new player object; the old one must not be used afterwards.
	 */
	public static ServerPlayer makeFresh(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		player.stopRiding();
		discardEnderPearls(player);
		player.setRespawnPosition(null, false);

		ServerPlayer fresh = attachRespawned(server.getPlayerList().respawn(player, false, Entity.RemovalReason.CHANGED_DIMENSION));

		// respawn() keeps the ender chest and recipe book, and the inventory and XP when keepInventory is on.
		fresh.getInventory().clearContent();
		fresh.getEnderChestInventory().clearContent();
		fresh.setExperienceLevels(0);
		fresh.setExperiencePoints(0);
		fresh.removeAllEffects();
		fresh.clearFire();
		fresh.resetFallDistance();
		fresh.setHealth(fresh.getMaxHealth());
		fresh.resetRecipes(server.getRecipeManager().getRecipes());
		revokeAllAdvancements(server, fresh);
		fresh.setRespawnPosition(null, false);
		fresh.setGameMode(GameType.SURVIVAL);
		fresh.containerMenu.sendAllDataToRemote();
		return fresh;
	}

	private static void revokeAllAdvancements(MinecraftServer server, ServerPlayer player) {
		PlayerAdvancements advancements = player.getAdvancements();
		for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
			AdvancementProgress progress = advancements.getOrStartProgress(holder);
			if (!progress.hasProgress()) {
				continue;
			}
			List<String> completed = new ArrayList<>();
			progress.getCompletedCriteria().forEach(completed::add);
			for (String criterion : completed) {
				advancements.revoke(holder, criterion);
			}
		}
	}

	/** Thrown pearls live in the old world and would otherwise keep a chunk of it loaded. */
	static void discardEnderPearls(ServerPlayer player) {
		List<ThrownEnderpearl> pearls = new ArrayList<>(player.getEnderPearls());
		pearls.forEach(Entity::discard);
		player.getEnderPearls().clear();
	}
}
