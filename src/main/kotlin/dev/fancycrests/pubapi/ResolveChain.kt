package dev.fancycrests.pubapi

/**
 * Per-thread resolution-source chain guard, shared by every boundary where
 * external engines can re-enter this mod's placeholder resolution
 * (Text Placeholder API export, public API placeholder reads).
 *
 * It implements two protections required by the plan (chapter 5):
 *  - a direct loop "this mod -> external -> this mod" for the same source id
 *    is rejected immediately (re-entry while the id is still active);
 *  - any nesting beyond [MAX_DEPTH] is rejected as a runaway chain.
 *
 * The counter is a plain ThreadLocal: external engines resolve synchronously
 * on the calling thread, so a cycle always re-enters on the same thread.
 * Asynchronous callbacks that re-enter on a different thread are separate
 * chains (they cannot recurse into their own stack) and stay valid.
 *
 * All methods must be called in balanced enter/exit pairs (use try/finally).
 */
object ResolveChain {
    /** Maximum nesting depth of placeholder resolution through this mod. */
    const val MAX_DEPTH: Int = 8

    private class Frame {
        var depth: Int = 0
        val active: MutableSet<String> = HashSet()
    }

    private val frame: ThreadLocal<Frame> = ThreadLocal.withInitial { Frame() }

    /**
     * Marks the start of resolving source [id] on this thread.
     * Returns false when the chain must abort (re-entry of an active id, or
     * depth exhausted); the caller then yields an empty value instead of
     * resolving, breaking the cycle.
     */
    fun enter(id: String): Boolean {
        val current = frame.get()
        if (current.depth >= MAX_DEPTH) return false
        if (!current.active.add(id)) return false
        current.depth++
        return true
    }

    /** Marks the end of resolving source [id]; must pair with a successful [enter]. */
    fun exit(id: String) {
        val current = frame.get()
        if (current.depth > 0) current.depth--
        current.active.remove(id)
    }

    /** Current nesting depth on this thread (diagnostics/tests). */
    fun depth(): Int = frame.get().depth

    /** ids currently being resolved on this thread (diagnostics/tests). */
    fun activeIds(): Set<String> = frame.get().active.toSet()
}
