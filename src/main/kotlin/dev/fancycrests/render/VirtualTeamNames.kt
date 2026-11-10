package dev.fancycrests.render

import java.util.UUID

/**
 * Generation of per-player virtual team names inside the client-visible team
 * namespace.
 *
 * Constraints (protocol / 26.2 scoreboard):
 *  - team names are short strings; we keep them <= [MAX_TEAM_NAME_LENGTH] (16)
 *  - the charset stays within `[a-z0-9_]` (no spaces, no formatting chars)
 *  - a name must be unique per client connection and must not collide with
 *    any source team name known to the connection ledger
 *
 * Layout: `"fc" + base36(64-bit mix of the player UUID)` = 2 + <=13 = <=15
 * chars. On a reservation collision the mix is re-salted deterministically
 * (bounded attempts), so the same UUID always maps to the same name for a
 * given reservation set.
 */
object VirtualTeamNames {
    const val MAX_TEAM_NAME_LENGTH: Int = 16
    const val PREFIX: String = "fc"
    const val MOD_ID: String = "fancycrests"
    private const val MAX_ATTEMPTS = 64

    /**
     * Deterministic virtual team name for [uuid].
     *
     * @param isReserved returns true when a candidate name is already in use
     *   on that client (source team names or other virtual teams); must be
     *   deterministic for the duration of one connection session.
     */
    fun forUuid(uuid: UUID, isReserved: (String) -> Boolean = { false }): String {
        for (attempt in 0 until MAX_ATTEMPTS) {
            val candidate = PREFIX + mix(uuid, attempt).toString(Character.MAX_RADIX)
            if (candidate.length <= MAX_TEAM_NAME_LENGTH && !isReserved(candidate)) {
                checkCharset(candidate)
                return candidate
            }
        }
        // Practically unreachable: 64 salts over a 64-bit space.
        throw IllegalStateException("cannot allocate virtual team name for $uuid")
    }

    private fun checkCharset(name: String) {
        require(name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }) {
            "virtual team name outside safe charset: $name"
        }
    }

    /** SplitMix64-style deterministic mix; attempt is mixed in as salt. */
    internal fun mix(uuid: UUID, attempt: Int): Long {
        val golden: Long = 0x9E3779B97F4A7C15uL.toLong()
        val mix1: Long = 0xBF58476D1CE4E5B9uL.toLong()
        val mix2: Long = 0x94D049BB133111EBuL.toLong()
        // rotateLeft(lsb, 32) == swap of the 64-bit halves.
        val swapped = (uuid.leastSignificantBits ushr 32) or (uuid.leastSignificantBits shl 32)
        var h = uuid.mostSignificantBits xor swapped
        h += attempt.toLong() * golden
        h = (h xor (h ushr 30)) * mix1
        h = (h xor (h ushr 27)) * mix2
        h = h xor (h ushr 31)
        return h and Long.MAX_VALUE // non-negative -> clean base36
    }
}
