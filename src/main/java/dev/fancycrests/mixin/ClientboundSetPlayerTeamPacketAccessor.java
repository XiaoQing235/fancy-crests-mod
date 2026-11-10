package dev.fancycrests.mixin;

import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;
import java.util.Optional;

/**
 * Rebuild of {@link ClientboundSetPlayerTeamPacket} with a filtered member
 * list.
 *
 * <p>The 26.2 factories only accept a live {@code PlayerTeam}; for rebuilds we
 * need to preserve the original packet's method, name and
 * {@code Optional<Parameters>} while replacing only the player collection
 * (dropping entries taken over by our per-player virtual teams). The class
 * owns a private canonical constructor
 * {@code (String name, int method, Optional<Parameters>, Collection<String> players)}
 * used by all public factories; this invoker exposes it.</p>
 *
 * <p>Method ints (verified from the factory bytecode):
 * 0 = ADD (create with members), 1 = REMOVE (delete team),
 * 2 = CHANGE (property update), 3 = JOIN (member add), 4 = LEAVE (member
 * remove).</p>
 */
@Mixin(ClientboundSetPlayerTeamPacket.class)
public interface ClientboundSetPlayerTeamPacketAccessor {

    @Invoker("<init>")
    static ClientboundSetPlayerTeamPacket fancycrestsCreate(
            String name,
            int method,
            Optional<ClientboundSetPlayerTeamPacket.Parameters> parameters,
            Collection<String> players
    ) {
        throw new AssertionError();
    }
}
