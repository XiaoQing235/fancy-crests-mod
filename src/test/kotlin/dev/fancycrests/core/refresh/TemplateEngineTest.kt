package dev.fancycrests.core.refresh

import dev.fancycrests.api.PlaceholderKey
import dev.fancycrests.api.PlaceholderRequest
import dev.fancycrests.api.PlaceholderResolver
import dev.fancycrests.api.PlaceholderService
import dev.fancycrests.core.config.LegacyTextParser
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rendering semantics of the template engine: static templates keep the exact
 * P2 legacy-text output, placeholders splice structured components, and the
 * guards (unknown keys, depth cap) degrade instead of throwing.
 */
class TemplateEngineTest {

    private val target = UUID.randomUUID()

    private class MapPlaceholders(private val values: Map<String, Any?>) : PlaceholderService {
        override fun register(key: PlaceholderKey, resolver: PlaceholderResolver) = Unit
        override fun unregister(key: PlaceholderKey) = Unit
        override fun resolve(key: PlaceholderKey, request: PlaceholderRequest) =
            values[key.value]?.let { v ->
                (v as? net.minecraft.network.chat.Component) ?: net.minecraft.network.chat.Component.literal(v.toString())
            }
        override fun registeredKeys(): List<PlaceholderKey> = values.keys.map { PlaceholderKey(it) }
    }

    @Test
    fun `static template renders exactly like the legacy parser`() {
        val engine = TemplateEngine(MapPlaceholders(emptyMap()))
        val template = "§7Rank: §6Knight§7 — wins"
        val result = engine.render(template, PlaceholderRequest(target, null))
        assertEquals(LegacyTextParser.parse(template), result.component)
        assertTrue(result.missingKeys.isEmpty())
        assertTrue(!result.truncated)
    }

    @Test
    fun `placeholders splice structured components and unknown keys go empty`() {
        val engine = TemplateEngine(
            MapPlaceholders(mapOf("prefix" to net.minecraft.network.chat.Component.literal("§6Knight"))),
        )
        val result = engine.render(
            "§7[%fancycrests:prefix%§7] %fancycrests:unknown%",
            PlaceholderRequest(target, null),
        )
        assertTrue(!result.truncated)
        assertEquals(listOf(PlaceholderKey("unknown")), result.missingKeys)
        // [prefix] present, unknown key contributes nothing.
        assertTrue(result.component.string.contains("Knight"))
    }

    @Test
    fun `depth cap truncates recursive resolvers instead of throwing`() {
        // A resolver that re-enters the engine on the same thread must be cut
        // off by the render-depth cap, not blow the stack or hang.
        lateinit var engine: TemplateEngine
        val recursive = object : PlaceholderService {
            override fun register(key: PlaceholderKey, resolver: PlaceholderResolver) = Unit
            override fun unregister(key: PlaceholderKey) = Unit
            override fun resolve(key: PlaceholderKey, request: PlaceholderRequest): net.minecraft.network.chat.Component {
                val rendered = engine.render("(x%fancycrests:loop%)", request)
                return rendered.component
            }
            override fun registeredKeys(): List<PlaceholderKey> = listOf(PlaceholderKey("loop"))
        }
        engine = TemplateEngine(recursive)
        val result = engine.render("%fancycrests:loop%", PlaceholderRequest(target, null))
        // The top-level render completes (nested renders are cut off) and the
        // depth counter is restored, so a plain render still succeeds.
        assertEquals("ok", engine.render("ok", PlaceholderRequest(target, null)).component.string)
        assertTrue(result.component.string.length < TemplateEngine.MAX_TOTAL_PLAIN_LENGTH)
    }
}
