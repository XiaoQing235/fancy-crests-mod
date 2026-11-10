package dev.fancycrests.core.event

import dev.fancycrests.api.PlayerTitleState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Kind of mutation that flows through the selection pipeline. */
enum class TitleOperationType {
    EQUIP,
    UNEQUIP,
    NICKNAME_SET,
    NICKNAME_RESET,
    GRANT,
    REVOKE,
    /** Eligibility/config driven recheck that may unequip stale titles. */
    RECHECK,
}

/** Unique id assigned to every operation entering the pipeline. */
@JvmInline
value class OperationId(val value: String) {
    override fun toString(): String = value
}

/**
 * Cancellable pre-commit event. It only ever sees the *candidate* state that
 * has not been written yet. Cancelling it (or a listener throwing) aborts the
 * operation: nothing is persisted, nothing is published.
 */
class TitleChangeEventPre(
    val uuid: UUID,
    val operationId: OperationId,
    val type: TitleOperationType,
    /** State before the operation. */
    val current: PlayerTitleState,
    /** Proposed state; has not been persisted yet. */
    val candidate: PlayerTitleState,
    /** Human-readable reason tag, e.g. "command", "eligibility-lost". */
    val reason: String,
) {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }
}

/**
 * Post-commit event. Fired on the main thread after the transaction was
 * persisted AND the new in-memory state was published. Cannot be cancelled;
 * a listener exception is logged and the remaining listeners still run.
 */
class TitleChangeEventPost(
    val uuid: UUID,
    val operationId: OperationId,
    val type: TitleOperationType,
    val oldRevision: Long,
    val newRevision: Long,
    val reason: String,
)

/**
 * Minimal synchronous event bus. Pre events are dispatched inline on the main
 * thread; Post events are dispatched on the main thread executor. Reentrant
 * Post dispatches (a listener mutating state again) are queued and drained
 * after the in-flight dispatch completes, never nested.
 */
class TitleEventBus(
    private val mainExecutor: Executor,
    private val logger: Logger = LoggerFactory.getLogger("FancyCrests/Events"),
) {
    private val nextOperationId = AtomicLong(0)

    @Volatile
    private var preListeners: List<(TitleChangeEventPre) -> Unit> = emptyList()

    @Volatile
    private var postListeners: List<(TitleChangeEventPost) -> Unit> = emptyList()

    /** Reentrancy guard for Post dispatch. Only touched on the main thread. */
    private var dispatchingPost = false

    private val queuedPosts = ArrayDeque<() -> Unit>()

    fun addPreListener(listener: (TitleChangeEventPre) -> Unit) {
        preListeners = preListeners + listener
    }

    fun addPostListener(listener: (TitleChangeEventPost) -> Unit) {
        postListeners = postListeners + listener
    }

    fun newOperationId(): OperationId = OperationId("op-${nextOperationId.incrementAndGet()}")

    /**
     * Fires the Pre event inline. Returns true when the operation must be
     * cancelled. A listener exception cancels the operation as well (the
     * candidate must not be written when a listener misbehaves).
     */
    fun firePre(event: TitleChangeEventPre): Boolean {
        for (listener in preListeners) {
            try {
                listener(event)
            } catch (t: Throwable) {
                logger.error(
                    "Pre listener threw for {} {} (op {}); cancelling operation",
                    event.type,
                    event.uuid,
                    event.operationId,
                    t,
                )
                return true
            }
            if (event.cancelled) return true
        }
        return event.cancelled
    }

    /** Queues the Post event onto the main thread, serialising reentrancy. */
    fun firePost(event: TitleChangeEventPost) {
        mainExecutor.execute {
            if (dispatchingPost) {
                queuedPosts.add { dispatchPost(event) }
                return@execute
            }
            dispatchingPost = true
            try {
                dispatchPost(event)
                while (queuedPosts.isNotEmpty()) {
                    val queued = queuedPosts.poll()
                    if (queued != null) queued()
                }
            } finally {
                dispatchingPost = false
            }
        }
    }

    private fun dispatchPost(event: TitleChangeEventPost) {
        for (listener in postListeners) {
            try {
                listener(event)
            } catch (t: Throwable) {
                logger.error(
                    "Post listener threw for {} {} (op {}); state stays committed",
                    event.type,
                    event.uuid,
                    event.operationId,
                    t,
                )
            }
        }
    }
}
