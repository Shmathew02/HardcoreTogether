package net.hardcoretogether.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Instant respawns (PlayerReset.attachRespawned): the connection state vanilla's Respawn button (PERFORM_RESPAWN)
 * clears. die() sets waitingForRespawn, and while it is set the server ignores the client's movement and block use.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {
	@Invoker("restartClientLoadTimerAfterRespawn")
	void hardcoreTogether$restartClientLoadTimerAfterRespawn();

	@Accessor("waitingForRespawn")
	boolean hardcoreTogether$waitingForRespawn();
}
