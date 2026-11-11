package dev.fancycrests.command

import dev.fancycrests.core.InvalidNicknameEntry
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pure /diagnose section formatting (K2): nickname policy violations and
 * render-module counters. The command body only forwards
 * DiagnosticsInfo fields, so the output contract lives in these two
 * functions. Requiring a CommandSourceStack would make them untestable,
 * which is exactly why they are standalone.
 */
class DiagnoseFormattingTest {
    private fun entry(
        profileName: String?,
        nickname: String = "X",
        reason: String = "NICKNAME_TOO_LONG",
    ) = InvalidNicknameEntry(
        uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
        profileName = profileName,
        nickname = nickname,
        reason = reason,
    )

    // ------------------------------------------------------------------
    // Invalid nicknames
    // ------------------------------------------------------------------

    @Test
    fun `no violations print a single none line`() {
        assertEquals(
            listOf("Invalid nicknames (kept, admin action required): none"),
            FancyCrestsCommands.nicknameViolationLines(emptyList()),
        )
    }

    @Test
    fun `each violation is one line with name or uuid, raw nickname and reason`() {
        val lines = FancyCrestsCommands.nicknameViolationLines(
            listOf(
                entry("Steve", nickname = "§aBad§r", reason = "NICKNAME_PATTERN"),
                entry(null, nickname = "WayTooLong", reason = "NICKNAME_TOO_LONG"),
            ),
        )
        assertEquals(3, lines.size)
        assertEquals("Invalid nicknames (kept, admin action required): 2", lines[0])
        assertEquals(
            "  - Steve: nickname='§aBad§r' (NICKNAME_PATTERN)",
            lines[1],
        )
        // Offline/unknown profile falls back to the UUID.
        assertEquals(
            "  - 550e8400-e29b-41d4-a716-446655440000: nickname='WayTooLong' (NICKNAME_TOO_LONG)",
            lines[2],
        )
    }

    @Test
    fun `the copy never claims nicknames were reset`() {
        // Design decision: invalid nicknames are kept on display and only
        // listed; the wording must not say "reset".
        val lines = FancyCrestsCommands.nicknameViolationLines(listOf(entry("Steve")))
        for (line in lines) {
            assertFalse("reset" in line.lowercase(), "diagnose copy must not say 'reset': $line")
        }
    }

    // ------------------------------------------------------------------
    // Render diagnostics
    // ------------------------------------------------------------------

    @Test
    fun `an empty render map reports the module idle or absent`() {
        assertEquals(
            "Renderer internals: render module idle or absent",
            FancyCrestsCommands.renderDiagnosticsLine(emptyMap()),
        )
    }

    @Test
    fun `render counters print as key=value pairs`() {
        assertEquals(
            "Renderer internals: frames=12, errors=0, lastError=none",
            FancyCrestsCommands.renderDiagnosticsLine(
                linkedMapOf("frames" to "12", "errors" to "0", "lastError" to "none"),
            ),
        )
    }
}
