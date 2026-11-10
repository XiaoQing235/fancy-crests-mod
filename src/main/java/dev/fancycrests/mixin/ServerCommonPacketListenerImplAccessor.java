package dev.fancycrests.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the protected {@code connection} field of the common
 * packet listener.
 *
 * <p>Motivation: the render service must refuse to (re)build per-connection
 * state for a listener whose connection is already closed (disconnect race:
 * {@code onDisconnect} removed the state while a main-thread send was in
 * flight). MC 26.2 has no public {@code isDisconnected()} or connection
 * accessor on the listener (verified with javap against
 * {@code ServerCommonPacketListenerImpl}); the channel-state ground truth is
 * {@link Connection#isConnected()}, which needs the field.</p>
 *
 * <p>Read-only accessor; applied to the common (client+server) listener base
 * class like the send injection mixin.</p>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerImplAccessor {

    @Accessor("connection")
    Connection fancycrests$connection();
}
