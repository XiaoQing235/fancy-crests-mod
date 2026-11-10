package dev.fancycrests.core

import dev.fancycrests.api.TitleSlot
import dev.fancycrests.core.config.ChannelsConfig
import dev.fancycrests.core.config.EquipmentConfig
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.config.NicknameConfig
import dev.fancycrests.core.config.NodeGrants
import dev.fancycrests.core.config.OffsetConfig
import dev.fancycrests.core.config.PermissionsConfig
import dev.fancycrests.core.config.RenderConfig
import dev.fancycrests.core.config.TemplateSegment
import dev.fancycrests.core.config.TemplateSegmentType
import dev.fancycrests.core.config.TitleConfig
import java.util.UUID
import java.util.concurrent.Executor
import java.util.regex.Pattern
import net.minecraft.network.chat.Component

/** Shared fakes for core service tests (no Minecraft server required). */
object TestFixtures {

    /** Runs everything inline; test callbacks still hop through the storage thread. */
    val directExecutor: Executor = Executor { runnable -> runnable.run() }

    /** Minimal [ServerAdapter] backed by mutable maps. */
    class FakeAdapter : ServerAdapter {
        val messages = mutableListOf<Pair<UUID, Component>>()
        var online: List<UUID> = emptyList()
        var knownNames: Map<String, UUID> = emptyMap()
        var operators: Map<UUID, Int> = emptyMap()
        var dimension: String? = "minecraft:overworld"
        private var ticks = 0L

        override fun execute(runnable: Runnable) = runnable.run()
        override fun currentTick(): Long = ticks++
        override fun onlinePlayerUuids(): List<UUID> = online
        override fun profileName(uuid: UUID): String? =
            knownNames.entries.firstOrNull { it.value == uuid }?.key

        override fun playerNameExact(name: String): UUID? = knownNames[name.lowercase()]
        override fun operatorLevel(uuid: UUID): Int = operators[uuid] ?: 0
        override fun dimensionKey(uuid: UUID): String? = dimension
        override fun knownPlayerNames(): Map<String, UUID> = knownNames
        override fun sendSystemMessage(uuid: UUID, message: Component) {
            messages += uuid to message
        }

        override fun broadcastTabListDisplayName(uuid: UUID, displayName: Component?) = Unit
        override fun registerEndTickHandler(handler: () -> Unit): AutoCloseable = AutoCloseable {}
    }

    fun title(
        id: String,
        slot: TitleSlot,
        permission: String? = null,
    ): TitleConfig = TitleConfig(
        id = id,
        slot = slot,
        displayName = id,
        text = "[$id]",
        permission = permission,
        category = null,
        iconItem = null,
        sortOrder = 0,
    )

    fun config(
        titles: List<TitleConfig> = listOf(
            title("vip", TitleSlot.PREFIX),
            title("helper", TitleSlot.SUFFIX),
        ),
        maxPrefixes: Int = 3,
        maxSuffixes: Int = 3,
        allow: Map<UUID, List<String>> = emptyMap(),
        deny: Map<UUID, List<String>> = emptyMap(),
    ): FancyCrestsConfig = FancyCrestsConfig(
        channels = ChannelsConfig(
            headRender = false,
            chat = false,
            tabList = false,
            placeholdersExport = false,
        ),
        equipment = EquipmentConfig(maxPrefixes, maxSuffixes),
        nickname = NicknameConfig(
            maxLength = 16,
            minLength = 3,
            pattern = Pattern.compile("[A-Za-z0-9_]+"),
            reservedNames = listOf("server"),
            allowSet = true,
        ),
        permissions = PermissionsConfig(
            nodes = (allow.keys + deny.keys).associateWith { uuid ->
                NodeGrants(allow[uuid] ?: emptyList(), deny[uuid] ?: emptyList())
            },
        ),
        firstLineTemplate = listOf(
            TemplateSegment(TemplateSegmentType.PREFIXES, " ", null),
            TemplateSegment(TemplateSegmentType.NAME, "", null),
            TemplateSegment(TemplateSegmentType.SUFFIXES, " ", null),
        ),
        secondLineRules = emptyList(),
        render = RenderConfig(
            distanceBlocks = 48.0,
            firstLineOffset = OffsetConfig(0.0, 0.4, 0.0),
            secondLineOffset = OffsetConfig(0.0, 0.15, 0.0),
        ),
        titles = titles,
    )
}