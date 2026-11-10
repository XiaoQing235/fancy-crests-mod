package dev.fancycrests.render

import net.minecraft.network.protocol.Packet
import net.minecraft.server.network.ServerCommonPacketListenerImpl
import net.minecraft.server.network.ServerGamePacketListenerImpl
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridge between the send-injection mixin and the active render service.
 *
 * Holds the currently attached service and the thread-local bypass marker
 * that identifies packets produced by this mod: our own sends re-enter the
 * same `send` entry points but must never be transformed again (each outbound
 * packet passes the transformation exactly once, no recursive interception,
 * and the marker lives outside any packet object).
 */
object RenderPackets {

    private val activeService = AtomicReference<RenderService?>(null)

    private val bypass = ThreadLocal.withInitial { false }

    internal val active: RenderService? get() = activeService.get()

    internal fun attach(service: RenderService) {
        activeService.set(service)
    }

    internal fun detach(service: RenderService) {
        activeService.compareAndSet(service, null)
    }

    /** True when the current thread is sending a mod-produced packet. */
    @JvmStatic
    fun isBypassed(): Boolean = bypass.get()

    /**
     * Runs [block] with the bypass marker set; all `send` calls on the
     * current thread inside it skip the transformation.
     */
    fun <T> bypassing(block: () -> T): T {
        val old = bypass.get()
        bypass.set(true)
        try {
            return block()
        } finally {
            bypass.set(old)
        }
    }

    /**
     * Entry point of the send mixin. Returns null when the packet must pass
     * through unchanged, otherwise the ordered replacement list (possibly
     * empty = drop entirely, possibly several packets = rebuild + follow-ups).
     */
    @JvmStatic
    fun transformOutbound(listener: ServerCommonPacketListenerImpl, packet: Packet<*>): List<Packet<*>>? {
        val service = activeService.get() ?: return null
        if (listener !is ServerGamePacketListenerImpl) return null
        return try {
            service.transformForConnection(listener, packet)
        } catch (e: Exception) {
            // Exception (not Throwable): java.lang.Error must propagate and
            // crash the tick rather than being masked as render noise.
            RenderDiagnostics.recordError("outbound transform ${packet.type()}", e)
            null // fail open: deliver the original packet untouched
        }
    }

    /**
     * Re-sends replacement packets on the calling thread with the bypass
     * marker set. A non-null ChannelFutureListener from the original call is
     * attached to the last packet of the list only (a netty future listener
     * is single-shot; re-attaching it to every packet would fire it multiple
     * times).
     *
     * The caller already cancelled the original send, so an exception here
     * must not escape into the vanilla call site: it is recorded and, when
     * nothing had been delivered yet, the untouched original packet
     * ([fallback]) is sent as a last resort so the packet is not lost. A
     * partial failure (some replacements already on the wire) does not
     * re-send the original: re-delivering a packet whose rebuild was already
     * partially applied could double-apply team/entity state client-side.
     */
    @JvmStatic
    fun sendBypassing(
        listener: ServerCommonPacketListenerImpl,
        packets: List<Packet<*>>,
        originalListener: io.netty.channel.ChannelFutureListener?,
        fallback: Packet<*>? = null,
    ) {
        val old = bypass.get()
        bypass.set(true)
        try {
            var sent = 0
            try {
                for ((index, packet) in packets.withIndex()) {
                    val isLast = index == packets.size - 1
                    if (originalListener == null || !isLast) {
                        listener.send(packet)
                    } else {
                        listener.send(packet, originalListener)
                    }
                    sent++
                }
            } catch (e: Exception) {
                RenderDiagnostics.recordError("bypassed resend", e)
                if (fallback != null && sent == 0) {
                    try {
                        if (originalListener == null) {
                            listener.send(fallback)
                        } else {
                            listener.send(fallback, originalListener)
                        }
                    } catch (e2: Exception) {
                        RenderDiagnostics.recordError("bypassed fallback send", e2)
                    }
                }
            }
        } finally {
            bypass.set(old)
        }
    }

    /** Direct bypassed send used by the render service lifecycle paths. */
    fun send(listener: ServerGamePacketListenerImpl, packet: Packet<*>) {
        bypassing { listener.send(packet) }
    }

    /** Direct bypassed ordered send used by the render service lifecycle paths. */
    fun sendAll(listener: ServerGamePacketListenerImpl, packets: List<Packet<*>>) {
        bypassing { for (p in packets) listener.send(p) }
    }
}
