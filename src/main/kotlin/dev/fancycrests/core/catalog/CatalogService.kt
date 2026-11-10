package dev.fancycrests.core.catalog

import dev.fancycrests.api.TitleDefinition
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import java.util.concurrent.atomic.AtomicLong

/** Outcome of replacing the catalog contents. */
data class CatalogReplaceResult(
    val newVersion: Long,
    /** Title ids that disappeared from the catalog (must be unequipped). */
    val removedIds: Set<TitleId>,
    /** Title ids that still exist but changed their slot (equipped entries in the other slot became invalid). */
    val slotChangedIds: Set<TitleId>,
)

/**
 * Immutable title catalog: an unmodifiable id → definition map plus a
 * monotonic version. Replaced atomically on config reload; readers never see
 * a half-updated catalog. Callers (selection service) must unequip references
 * to [CatalogReplaceResult.removedIds] / slotChangedIds and persist that.
 */
class CatalogService(initial: Map<TitleId, TitleDefinition> = emptyMap()) {

    private val versionCounter = AtomicLong(0)

    @Volatile
    private var entries: Map<TitleId, TitleDefinition> = initial.toMap()

    val version: Long get() = versionCounter.get()

    fun get(id: TitleId): TitleDefinition? = entries[id]

    fun all(): Collection<TitleDefinition> = entries.values

    fun idsForSlot(slot: TitleSlot): List<TitleId> =
        entries.values.asSequence().filter { it.slot == slot }.map { it.id }
            .sortedBy { it.value }.toList()

    fun size(): Int = entries.size

    /**
     * Atomically replaces the catalog. Returns which equipped references
     * became invalid so the caller can unequip and persist them.
     */
    fun replace(newEntries: Map<TitleId, TitleDefinition>): CatalogReplaceResult {
        val old = entries
        val removed = old.keys - newEntries.keys
        val slotChanged = old.filter { (id, def) ->
            val next = newEntries[id]
            next != null && next.slot != def.slot
        }.keys
        entries = newEntries.toMap()
        return CatalogReplaceResult(versionCounter.incrementAndGet(), removed, slotChanged)
    }
}
