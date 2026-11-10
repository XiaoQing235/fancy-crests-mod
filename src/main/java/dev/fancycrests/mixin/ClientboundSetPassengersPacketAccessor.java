package dev.fancycrests.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Construction of {@link ClientboundSetPassengersPacket} with a custom
 * passenger list.
 *
 * <p>26.2 only exposes the public {@code (Entity vehicle)} constructor, which
 * reads the passengers live from the entity and cannot express
 * "real passengers + virtual label ids". The class however owns a private
 * buffer constructor used by its stream codec decoder
 * ({@code private ClientboundSetPassengersPacket(FriendlyByteBuf)}), which
 * reads {@code vehicle: VarInt} then {@code passengers: VarIntArray[]}. This
 * invoker turns that constructor into a legal static factory: the renderer
 * writes the desired ids into a heap {@link FriendlyByteBuf} and decodes the
 * packet back from it. No field of any shared packet object is mutated.</p>
 */
@Mixin(ClientboundSetPassengersPacket.class)
public interface ClientboundSetPassengersPacketAccessor {

    @Invoker("<init>")
    static ClientboundSetPassengersPacket fancycrestsCreate(FriendlyByteBuf buffer) {
        throw new AssertionError();
    }
}
