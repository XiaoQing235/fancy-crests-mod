package dev.fancycrests.pubapi

import dev.fancycrests.core.selection.RejectionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * K3: [ApiRejectionReason] mirrors core [RejectionReason] by name via
 * `ApiRejectionReason.valueOf(result.reason.name)`. A core value without a
 * mirror would make every mapped rejection throw
 * IllegalArgumentException at runtime, so the mirror must stay complete —
 * asserted over the whole core enum, not just the newest entry.
 */
class ApiRejectionReasonMirrorTest {
    @Test
    fun `every core rejection reason has a mirror constant`() {
        for (reason in RejectionReason.entries) {
            val mirror = try {
                ApiRejectionReason.valueOf(reason.name)
            } catch (e: IllegalArgumentException) {
                fail("RejectionReason.${reason.name} has no ApiRejectionReason mirror")
            }
            assertEquals(reason.name, mirror.name)
        }
    }

    @Test
    fun `config protection maps to its documented mirror`() {
        assertEquals(
            ApiRejectionReason.CONFIG_PROTECTED,
            ApiRejectionReason.valueOf(RejectionReason.CONFIG_PROTECTED.name),
        )
    }
}
