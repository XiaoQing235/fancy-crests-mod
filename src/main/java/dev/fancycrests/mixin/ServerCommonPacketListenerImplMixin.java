package dev.fancycrests.mixin;

import dev.fancycrests.render.RenderPackets;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Per-connection outbound packet transformation for the head render channel.
 *
 * <p>Both {@code send} overloads are intercepted at HEAD, before the packet is
 * handed to the netty pipeline (i.e. before encoding). The transformation runs
 * on the calling thread, which for all vanilla send sites is the server main
 * thread; {@link Connection#send} itself is safe from any thread. When the
 * transformer produces a replacement, the original call is cancelled and the
 * replacement packets are re-sent through the same {@code send} entry point
 * with a thread-local bypass marker set, so every outbound packet passes this
 * injection exactly once and our own packets are never re-transformed
 * (no recursive interception).</p>
 *
 * <p>See docs/render-injections.md for the injection rationale, ordering and
 * failure behaviour.</p>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {

    @SuppressWarnings("unused")
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void fancycrests$transformSend(Packet<?> packet, CallbackInfo ci) {
        fancycrests$handle(packet, null, ci);
    }

    @SuppressWarnings("unused")
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void fancycrests$transformSendWithListener(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        fancycrests$handle(packet, listener, ci);
    }

    private void fancycrests$handle(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if (RenderPackets.isBypassed()) {
            return;
        }
        ServerCommonPacketListenerImpl self = (ServerCommonPacketListenerImpl) (Object) this;
        List<Packet<?>> replacement = RenderPackets.transformOutbound(self, packet);
        if (replacement == null) {
            // Unchanged: let the original call proceed untouched.
            return;
        }
        ci.cancel();
        RenderPackets.sendBypassing(self, replacement, listener);
    }
}
