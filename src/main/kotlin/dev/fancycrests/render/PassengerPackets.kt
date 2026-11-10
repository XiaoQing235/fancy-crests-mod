package dev.fancycrests.render

import dev.fancycrests.mixin.ClientboundSetPassengersPacketAccessor
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket

/**
 * Pure passenger-list merge plus the packet builder that goes through the
 * buffer-constructor invoker mixin.
 *
 * Merge rule (spec): the original real passenger list is the authoritative
 * base, order preserved; virtual label ids are appended at the end, dropping
 * ids already present (dedup). We never reorder, never drop, and never
 * replace real ids with cached ones.
 */
internal object PassengerMerge {

    /** [real] order preserved; [extra] appended once each if not already present. */
    fun merge(real: List<Int>, extra: List<Int>): List<Int> {
        if (extra.isEmpty()) return real
        val seen = real.toHashSet()
        val out = ArrayList<Int>(real.size + extra.size)
        out.addAll(real)
        for (id in extra) {
            if (seen.add(id)) out.add(id)
        }
        return out
    }
}

/**
 * Builds [ClientboundSetPassengersPacket] instances carrying a custom
 * (vehicle, passengers) pair. 26.2 only exposes an `(Entity)` constructor, so
 * the packet is decoded from an in-memory buffer via the private codec
 * constructor exposed by [ClientboundSetPassengersPacketAccessor]
 * (wire format: `vehicle: VarInt, passengers: VarIntArray`).
 */
internal object PassengerPackets {

    fun create(vehicleId: Int, passengers: IntArray): ClientboundSetPassengersPacket {
        // VarInt worst case 5 bytes per entry; plenty for label-sized lists.
        val buf = FriendlyByteBuf(Unpooled.buffer(8 + passengers.size * 5))
        try {
            buf.writeVarInt(vehicleId)
            buf.writeVarIntArray(passengers)
            return ClientboundSetPassengersPacketAccessor.fancycrestsCreate(buf)
        } finally {
            buf.release()
        }
    }
}
