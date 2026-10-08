package net.hardcoretogether.run;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.data.CurrentRun;
import net.hardcoretogether.data.RunDeath;
import net.hardcoretogether.hall.DeathHall;
import net.hardcoretogether.reset.ResetController;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The death trigger. ALLOW_DEATH fires inside hurtServer before vanilla's totem check and die(), so a player about
 * to die in the run world (overworld, nether or end) is never actually killed: the death is blocked in that call,
 * the player is healed and put in Spectator where they died, and the death message goes to chat as vanilla's
 * would. Because die() never runs, the client never gets the death screen and there is no Respawn button.
 *
 * The first death that counts ends the run at that moment and starts the reset (not cancellable). Deaths after it,
 * until the reset gathers everyone, record nothing: the run is already over, so each run has one death in the
 * history, stats and statues. Nobody goes to the Death Hall before the countdown ends; then GATHER takes everyone,
 * in Adventure.
 */
public final class DeathListener {
	/** Players put in Spectator by a blocked death, until the reset gathers them (or a cancelled reset revives them). */
	private static final Set<UUID> spectators = new LinkedHashSet<>();
	/** The reset running while those players died has passed COMMIT, so it gathers them (or holds them on join). */
	private static boolean resetCommitted;

	private DeathListener() {
	}

	public static void register() {
		ServerLivingEntityEvents.ALLOW_DEATH.register(DeathListener::allowDeath);
		ServerTickEvents.END_SERVER_TICK.register(DeathListener::tickSpectators);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			spectators.clear();
			resetCommitted = false;
		});
	}

	/** False blocks the death (the player becomes a spectator); true lets vanilla go on (totem, then die()). */
	private static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer player)) {
			return true;
		}
		String name = player.getGameProfile().name();
		if (DeathHall.contains(player)) {
			HardcoreTogether.LOGGER.warn("[HT death] {} died in the Death Hall; ignored (vanilla death)", name);
			return true;
		}
		if (!isRunWorld(player.level().dimension())) {
			return true;
		}
		if (totemSaves(player, source)) {
			HardcoreTogether.LOGGER.info("[HT death] {} is saved by a totem of undying; not a death", name);
			return true;
		}
		RunTracker tracker = RunTimer.tracker();
		if (tracker == null) {
			HardcoreTogether.LOGGER.warn("[HT death] {} died but the run timer is off; ignored", name);
			return true;
		}
		MinecraftServer server = player.level().getServer();
		BlockPos pos = player.blockPosition();
		// The combat log still holds this hit (die() has not run), so this is the message vanilla would broadcast.
		Component captured = captureDeathMessage(player);
		String capturedText = captured == null ? null : captured.getString();
		String text = DeathTrigger.messageText(capturedText, name);
		if (!text.equals(capturedText)) {
			HardcoreTogether.LOGGER.warn("[HT death] no death message captured for {}; recording \"{}\"", name, text);
		}
		Component message = text.equals(capturedText) ? captured : Component.literal(text);
		Entity killer = source.getEntity();
		RunDeath death = new RunDeath(player.getUUID(), name, text,
			player.level().dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ(),
			source.typeHolder().unwrapKey().map(k -> k.identifier().toString()).orElse(null),
			killer == null ? null : EntityType.getKey(killer.getType()).toString(),
			killer instanceof net.minecraft.world.entity.player.Player p ? p.getGameProfile().name() : null, skinOf(player.getGameProfile()));

		DeathTrigger.Outcome outcome = decide(server, tracker, death, false);

		switch (outcome) {
			case RESET_STARTED, RESET_REFUSED -> {
				CurrentRun run = tracker.current();
				HardcoreTogether.LOGGER.info("[HT death] run #{} ended: \"{}\" in {} at {} {} {}, {}, Day {} (damage {}, killer {})", run.run(),
					death.message(), death.dimension(), death.x(), death.y(), death.z(), RunFormat.elapsed(run.activeMillis()), run.finalDay(),
					death.damageType(), death.killerType());
				if (outcome == DeathTrigger.Outcome.RESET_REFUSED) {
					HardcoreTogether.LOGGER.error("[HT death] the reset could not start (see above); run #{} stays ENDED, use /ht reset", run.run());
				}
			}
			case IGNORED_NO_ACTIVE_RUN -> HardcoreTogether.LOGGER.info("[HT death] {} died with no ACTIVE run; not recorded", name);
			case IGNORED_RESET_RUNNING -> HardcoreTogether.LOGGER.info("[HT death] {} died during a reset ({}); not recorded", name, ResetController.phase());
			case IGNORED_IN_DEATH_HALL -> HardcoreTogether.LOGGER.warn("[HT death] {} died in the Death Hall; ignored", name);
		}
		if (!ResetController.isRunning()) {
			// No reset will gather this player (refused, or no run in this world): an ordinary vanilla death, so
			// nobody is ever left in Spectator.
			HardcoreTogether.LOGGER.warn("[HT death] {}: no reset running; vanilla death and respawn", name);
			return true;
		}
		becomeSpectator(player, message);
		if (outcome == DeathTrigger.Outcome.RESET_STARTED) {
			for (ServerPlayer other : server.getPlayerList().getPlayers()) {
				if (other != player) {
					showTitle(other, Component.literal(name + " died").withStyle(ChatFormatting.RED), message);
				}
			}
		}
		showTitle(player, Component.literal("You died").withStyle(ChatFormatting.RED), message);
		HardcoreTogether.LOGGER.info("[HT death] {} is a spectator at {} {} {} in {} until the reset gathers everyone", name, pos.getX(),
			pos.getY(), pos.getZ(), death.dimension());
		return false;
	}

	private static @Nullable Component captureDeathMessage(ServerPlayer player) {
		try {
			return player.getCombatTracker().getDeathMessage();
		} catch (RuntimeException e) {
			HardcoreTogether.LOGGER.warn("[HT death] could not capture the death message of {}", player.getGameProfile().name(), e);
			return null;
		}
	}

	private static boolean isRunWorld(ResourceKey<Level> dimension) {
		return dimension == Level.OVERWORLD || dimension == Level.NETHER || dimension == Level.END;
	}

	/**
	 * Vanilla's totem check (LivingEntity.checkTotemDeathProtection) without its side effects: a death protection
	 * item in either hand, and damage that doesn't bypass invulnerability. Then vanilla pops the totem.
	 */
	private static boolean totemSaves(ServerPlayer player, DamageSource source) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return false;
		}
		for (InteractionHand hand : InteractionHand.values()) {
			if (player.getItemInHand(hand).get(DataComponents.DEATH_PROTECTION) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Instead of dying: full health, no fire, effects, fall distance or motion, Spectator where they stand, and the
	 * death message in chat with vanilla's rules (show_death_messages, team visibility). Same tick as the hit, so the
	 * client never sees zero health.
	 */
	private static void becomeSpectator(ServerPlayer player, Component message) {
		player.setHealth(player.getMaxHealth());
		player.clearFire();
		player.setTicksFrozen(0);
		player.removeAllEffects();
		player.resetFallDistance();
		player.setDeltaMovement(Vec3.ZERO);
		player.syncVelocity = true; // sends the zero motion to the client
		player.setGameMode(GameType.SPECTATOR);
		spectators.add(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (player.level().getGameRules().get(GameRules.SHOW_DEATH_MESSAGES)) {
			Team team = player.getTeam();
			if (team == null || team.getDeathMessageVisibility() == Team.Visibility.ALWAYS) {
				server.getPlayerList().broadcastSystemMessage(message, false);
			} else if (team.getDeathMessageVisibility() == Team.Visibility.HIDE_FOR_OTHER_TEAMS) {
				server.getPlayerList().broadcastSystemToTeam(player, message);
			} else if (team.getDeathMessageVisibility() == Team.Visibility.HIDE_FOR_OWN_TEAM) {
				server.getPlayerList().broadcastSystemToAllExceptTeam(player, message);
			}
		}
	}

	/**
	 * Keeps death spectators from being stuck. A reset that passed COMMIT gathers them into the hall in Adventure
	 * (or holds them / gives a fresh start on join), so they are forgotten. A reset cancelled before COMMIT (only an
	 * op's /ht reset can be) gathers nobody: they go back to Survival where they are, offline ones when they return.
	 */
	private static void tickSpectators(MinecraftServer server) {
		if (spectators.isEmpty()) {
			return;
		}
		if (ResetController.isRunning()) {
			resetCommitted |= ResetController.phase().isCommitted();
			return;
		}
		if (resetCommitted) {
			spectators.clear();
			resetCommitted = false;
			return;
		}
		spectators.removeIf(uuid -> {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player == null) {
				return false;
			}
			if (player.gameMode() == GameType.SPECTATOR && !DeathHall.contains(player)) {
				player.setGameMode(GameType.SURVIVAL);
				HardcoreTogether.LOGGER.warn("[HT death] the reset was cancelled; {} is back in Survival where they are", player.getGameProfile().name());
			}
			return true;
		});
	}

	/** The profile's "textures" property (the skin at this moment), or null if it has none. */
	public static RunDeath.@Nullable SkinTextures skinOf(GameProfile profile) {
		for (Property p : profile.properties().get("textures")) {
			if (p.value() != null && !p.value().isEmpty()) {
				return new RunDeath.SkinTextures(p.value(), p.signature());
			}
		}
		return null;
	}

	/** Ends the run for this death and starts the reset, by the same rules for real and test deaths. */
	private static DeathTrigger.Outcome decide(MinecraftServer server, RunTracker tracker, RunDeath death, boolean inDeathHall) {
		return DeathTrigger.onDeath(tracker, new DeathTrigger.Reset() {
			@Override
			public boolean isRunning() {
				return ResetController.isRunning();
			}

			@Override
			public boolean request(RunDeath d) {
				return ResetController.requestForDeath(server, d) == ResetController.RequestResult.STARTED;
			}
		}, death, inDeathHall, RunTimer.dayTime(server));
	}

	/**
	 * /ht test-death (only with allow_test_deaths): records a death for a player who does not die,
	 * through the same run end, memorial, statue and reset path as a real death. Nobody is respawned. With nobody
	 * online no run has started since the last reset, so the next run is started first, as a player joining would.
	 */
	public static DeathTrigger.Outcome testDeath(MinecraftServer server, RunDeath death) {
		RunTracker tracker = RunTimer.tracker();
		if (tracker == null) {
			return DeathTrigger.Outcome.IGNORED_NO_ACTIVE_RUN;
		}
		if ((tracker.current() == null || !tracker.current().isActive()) && !ResetController.isRunning()) {
			RunTimer.startRunInNewWorld(server, "test death");
		}
		DeathTrigger.Outcome outcome = decide(server, tracker, death, false);
		HardcoreTogether.LOGGER.info("[HT death] TEST death of {} ({}): {}", death.name(), death.uuid(), outcome);
		return outcome;
	}

	private static void showTitle(ServerPlayer player, Component title, Component subtitle) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle.copy().withStyle(ChatFormatting.GRAY)));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}
}
