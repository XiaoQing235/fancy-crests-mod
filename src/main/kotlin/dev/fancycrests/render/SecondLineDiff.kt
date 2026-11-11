package dev.fancycrests.render

/**
 * Wire action required to bring one observer's second-line state in sync with
 * the target's newest snapshot, decided purely from the two content values.
 *
 * This is the router of the second-line lifecycle in
 * [RenderService.pushSnapshot]. The observer-side state it drives obeys one
 * invariant:
 *
 * `obs.sentSecondLine != null` `<=>` `obs.secondLineId != 0`
 * `<=>` the observer's client holds a second-line label entity
 *
 * Every code path that changes one side of that equivalence must change the
 * other in the same transaction (spawn: entity + cache; removal: no entity +
 * no cache). The content is compared as a whole (`null` = absent), so the
 * presence signal and the content signal are the same field — which is why
 * [UPDATE] covers "present but different text" while equal values (including
 * both `null`) require nothing.
 *
 * Generic in the content type so the routing table is testable without any
 * Minecraft class; production instantiates it with
 * `net.minecraft.network.chat.Component`, whose equality is structural.
 */
internal enum class SecondLineAction {
    /** Client has no entity but the snapshot has content: AddEntity + metadata + remount. */
    ADD,

    /** Client has an entity but the snapshot has no content: unmount + RemoveEntities. */
    REMOVE,

    /** Both present, different content: SetEntityData only (entity id unchanged). */
    UPDATE,

    /** Both sides agree (including both absent): no packet. */
    NONE,
}

internal object SecondLineDiff {

    /** Routes the (sent, current) pair to the wire action; see [SecondLineAction]. */
    fun <T> actionOf(sent: T?, current: T?): SecondLineAction = when {
        sent == null && current != null -> SecondLineAction.ADD
        sent != null && current == null -> SecondLineAction.REMOVE
        sent != current -> SecondLineAction.UPDATE
        else -> SecondLineAction.NONE
    }
}
