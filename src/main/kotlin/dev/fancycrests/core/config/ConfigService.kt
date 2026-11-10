package dev.fancycrests.core.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.fancycrests.api.RenderDistance
import dev.fancycrests.api.RenderSettings
import dev.fancycrests.api.TitleDefinition
import dev.fancycrests.api.TitleId
import dev.fancycrests.api.TitleSlot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Boolean channel switches; channels never implicitly enable each other. */
data class ChannelsConfig(
    val headRender: Boolean,
    val chat: Boolean,
    val tabList: Boolean,
    val placeholdersExport: Boolean,
)

/** Per-slot equip limits. */
data class EquipmentConfig(val maxPrefixes: Int, val maxSuffixes: Int)

/** Nickname constraints enforced by the selection service. */
data class NicknameConfig(
    val maxLength: Int,
    val minLength: Int,
    val pattern: Pattern,
    val reservedNames: List<String>,
    /** Global switch; per-player access still requires the permission node. */
    val allowSet: Boolean,
) {
    /** Case-insensitive reserved-name lookup after normalization. */
    fun isReserved(normalized: String): Boolean =
        reservedNames.any { it.equals(normalized, ignoreCase = true) }
}

/** Explicit allow/deny lists for one player (built-in backend storage). */
data class NodeGrants(val allow: List<String>, val deny: List<String>)

/** Built-in local permission grants keyed by player UUID. */
data class PermissionsConfig(val nodes: Map<UUID, NodeGrants>)

/** Ordered first-line template segment types. */
enum class TemplateSegmentType { PREFIXES, NAME, SUFFIXES }

/**
 * One segment of the first line. [separator] joins the items *within* the
 * segment and is also placed between this segment and the next non-empty one;
 * empty segments produce nothing (no stray separators).
 */
data class TemplateSegment(
    val type: TemplateSegmentType,
    val separator: String,
    /** Optional legacy styling (& codes) for the separator component. */
    val separatorStyle: String?,
)

/** Match condition of a second-line rule. Present fields must all match (AND). */
data class SecondLineCondition(val dimension: String?, val permission: String?)

/**
 * Admin-configured second-line rule. The template mixes static text (& codes)
 * with built-in `%fancycrests:key%` placeholders; [refreshTicks] controls how
 * often dynamic templates are re-resolved.
 */
data class SecondLineRuleConfig(
    val id: String,
    /** Higher wins; ties resolved by stable ascending id order. */
    val priority: Int,
    val condition: SecondLineCondition?,
    val template: String,
    /**
     * Dynamic-content refresh period in server ticks, inclusive bounds
     * [MIN_REFRESH_TICKS]..[MAX_REFRESH_TICKS]. Only affects placeholder
     * re-resolution; eligibility and lifecycle events stay immediate.
     */
    val refreshTicks: Int = DEFAULT_REFRESH_TICKS,
) {
    companion object {
        const val MIN_REFRESH_TICKS = 1
        const val MAX_REFRESH_TICKS = 72000

        /** 1 second at 20 tps. */
        const val DEFAULT_REFRESH_TICKS = 20

        /** Hard cap on raw template text length (rendered output is capped separately). */
        const val MAX_TEMPLATE_LENGTH = 1024
    }
}

/** Raw offset triple from config; converted to [dev.fancycrests.api.RenderOffset] after validation. */
data class OffsetConfig(val x: Double, val y: Double, val z: Double)

/** Raw render configuration; converted to [RenderSettings] after validation. */
data class RenderConfig(
    val distanceBlocks: Double,
    val firstLineOffset: OffsetConfig,
    val secondLineOffset: OffsetConfig,
) {
    fun toRenderSettings(): RenderSettings = RenderSettings(
        renderDistance = RenderDistance(distanceBlocks),
        firstLineOffset = dev.fancycrests.api.RenderOffset(firstLineOffset.x, firstLineOffset.y, firstLineOffset.z),
        secondLineOffset = dev.fancycrests.api.RenderOffset(secondLineOffset.x, secondLineOffset.y, secondLineOffset.z),
    )
}

/**
 * Raw title entry as written in the config. [text] uses plain text plus &
 * (or §) legacy codes; [displayName] is plain text. Both are parsed to Components
 * when the catalog is built.
 */
data class TitleConfig(
    val id: String,
    val slot: TitleSlot,
    val displayName: String,
    val text: String,
    val permission: String?,
    val category: String?,
    val iconItem: String?,
    val sortOrder: Int,
)

/** Fully validated, immutable configuration snapshot. */
data class FancyCrestsConfig(
    val channels: ChannelsConfig,
    val equipment: EquipmentConfig,
    val nickname: NicknameConfig,
    val permissions: PermissionsConfig,
    val firstLineTemplate: List<TemplateSegment>,
    val secondLineRules: List<SecondLineRuleConfig>,
    val render: RenderConfig,
    val titles: List<TitleConfig>,
) {
    /** Builds the immutable catalog entries (text parsed to Components). */
    fun toTitleDefinitions(): Map<TitleId, TitleDefinition> {
        val map = LinkedHashMap<TitleId, TitleDefinition>()
        for (title in titles) {
            val id = TitleId(title.id)
            map[id] = TitleDefinition(
                id = id,
                slot = title.slot,
                displayName = LegacyTextParser.parse(title.displayName),
                text = LegacyTextParser.parse(title.text),
                permission = title.permission,
                category = title.category,
                iconItem = title.iconItem,
                sortOrder = title.sortOrder,
            )
        }
        return map
    }

    /** Rules pre-sorted for matching: priority desc, then stable id asc. */
    fun sortedSecondLineRules(): List<SecondLineRuleConfig> =
        secondLineRules.sortedWith(compareByDescending<SecondLineRuleConfig> { it.priority }.thenBy { it.id })
}

/** A configuration problem, addressed by its JSON path. */
data class ConfigError(val path: String, val message: String) {
    override fun toString(): String = "$path: $message"
}

/** Result of a config load or reload attempt. */
sealed interface ConfigLoadResult {
    data class Success(val config: FancyCrestsConfig) : ConfigLoadResult

    /** The previous configuration stayed active. */
    data class Failure(val errors: List<ConfigError>) : ConfigLoadResult
}

/**
 * Loads, validates and hot-swaps `fancycrests.json`.
 *
 * Pipeline (plan chapter 2 "配置重载"): background read + parse, main-thread
 * semantic validation, build immutable config, atomic replacement, then notify
 * listeners (catalog replacement, online-player rechecks). On failure the old
 * configuration stays active and errors carry the offending JSON paths.
 */
class ConfigService(
    private val configFile: Path,
    private val mainExecutor: Executor,
) {
    private val logger: Logger = LoggerFactory.getLogger("FancyCrests/Config")
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private val ioExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { thread -> Thread(thread, "fancycrests-config").apply { isDaemon = true } }

    /** Monotonic version of the successfully replaced configuration. */
    private val versionCounter = AtomicLong(0)

    @Volatile
    private var currentConfig: FancyCrestsConfig = DefaultConfig.create()

    private val replaceListeners = mutableListOf<(FancyCrestsConfig) -> Unit>()

    val current: FancyCrestsConfig get() = currentConfig

    /** Bumped only on successful replacement; used to invalidate stale operations. */
    val version: Long get() = versionCounter.get()

    fun addOnReplaced(listener: (FancyCrestsConfig) -> Unit) {
        synchronized(replaceListeners) { replaceListeners.add(listener) }
    }

    /**
     * Initial synchronous load, called once on server start. Creates and
     * persists safe defaults when the file is missing; the server always
     * starts with a valid configuration.
     */
    fun loadOrCreate(): ConfigLoadResult {
        if (!Files.isRegularFile(configFile)) {
            val defaults = DefaultConfig.create()
            writeDefaults(defaults)
            applyNewConfig(defaults)
            return ConfigLoadResult.Success(defaults)
        }
        val result = readParseAndValidate()
        if (result is ConfigLoadResult.Success) {
            applyNewConfig(result.config)
        }
        return result
    }

    /** Hot reload: background read + parse, main-thread validation and swap. */
    fun reloadAsync(callback: (ConfigLoadResult) -> Unit) {
        ioExecutor.execute {
            val read = try {
                readParseAndValidate()
            } catch (t: Throwable) {
                logger.error("Config reload failed unexpectedly", t)
                ConfigLoadResult.Failure(
                    listOf(ConfigError("$", "reload failed: ${t.message ?: t.javaClass.simpleName}")),
                )
            }
            mainExecutor.execute {
                var final: ConfigLoadResult = read
                try {
                    if (read is ConfigLoadResult.Success) {
                        applyNewConfig(read.config)
                        final = read
                    }
                } catch (t: Throwable) {
                    // applyNewConfig already isolates its listeners; this is a
                    // last resort so the callback always receives a result.
                    logger.error("Config replacement failed; keeping previous reporting honest", t)
                    final = ConfigLoadResult.Failure(
                        listOf(ConfigError("$", "config replacement failed: ${t.message ?: t.javaClass.simpleName}")),
                    )
                } finally {
                    callback(final)
                }
            }
        }
    }

    /** Validates the file on disk without replacing the active config. */
    fun validateFileAsync(callback: (List<ConfigError>) -> Unit) {
        ioExecutor.execute {
            val errors = when (val result = readParseAndValidate()) {
                is ConfigLoadResult.Success -> emptyList()
                is ConfigLoadResult.Failure -> result.errors
            }
            mainExecutor.execute { callback(errors) }
        }
    }

    fun stop() {
        ioExecutor.shutdownNow()
    }

    // ------------------------------------------------------------------
    // Pipeline internals
    // ------------------------------------------------------------------

    private fun readParseAndValidate(): ConfigLoadResult {
        if (!Files.isRegularFile(configFile)) {
            return ConfigLoadResult.Failure(
                listOf(ConfigError(configFile.toString(), "configuration file is missing")),
            )
        }
        val root = try {
            JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8))
        } catch (e: JsonParseException) {
            return ConfigLoadResult.Failure(
                listOf(ConfigError("$", "invalid JSON: ${e.message ?: e.javaClass.simpleName}")),
            )
        } catch (e: Exception) {
            return ConfigLoadResult.Failure(
                listOf(ConfigError(configFile.toString(), "cannot read file: ${e.message}")),
            )
        }
        if (!root.isJsonObject) {
            return ConfigLoadResult.Failure(listOf(ConfigError("$", "root must be a JSON object")))
        }

        val config = try {
            parseConfig(root.asJsonObject)
        } catch (e: ConfigParseException) {
            return ConfigLoadResult.Failure(e.errors)
        }
        val semantic = validate(config)
        return if (semantic.isEmpty()) {
            ConfigLoadResult.Success(config)
        } else {
            ConfigLoadResult.Failure(semantic)
        }
    }

    /** Swaps in the new config and notifies listeners. Main thread only. */
    private fun applyNewConfig(config: FancyCrestsConfig) {
        currentConfig = config
        versionCounter.incrementAndGet()
        val listeners = synchronized(replaceListeners) { replaceListeners.toList() }
        for (listener in listeners) {
            // One misbehaving listener must not skip the remaining ones —
            // especially the online revalidation that runs late in the chain
            // (same per-listener isolation as TitleEventBus.firePost).
            try {
                listener(config)
            } catch (t: Throwable) {
                logger.error("Config replace listener threw; continuing with the remaining listeners", t)
            }
        }
    }

    private fun writeDefaults(config: FancyCrestsConfig) {
        try {
            val parent = configFile.toAbsolutePath().parent
            if (parent != null) Files.createDirectories(parent)
            val temp = configFile.resolveSibling(configFile.fileName.toString() + ".tmp")
            Files.writeString(temp, gson.toJson(toJson(config)), StandardCharsets.UTF_8)
            try {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // Best effort: the server still runs with in-memory defaults.
        }
    }

    // ------------------------------------------------------------------
    // Semantic validation (path addressed)
    // ------------------------------------------------------------------

    fun validate(root: FancyCrestsConfig): List<ConfigError> {
        val errors = mutableListOf<ConfigError>()

        if (root.equipment.maxPrefixes < 1) {
            errors += ConfigError("equipment.maxPrefixes", "must be >= 1 (was ${root.equipment.maxPrefixes})")
        }
        if (root.equipment.maxSuffixes < 1) {
            errors += ConfigError("equipment.maxSuffixes", "must be >= 1 (was ${root.equipment.maxSuffixes})")
        }

        val nickname = root.nickname
        if (nickname.maxLength < 1) {
            errors += ConfigError("nickname.maxLength", "must be >= 1 (was ${nickname.maxLength})")
        }
        if (nickname.minLength < 1) {
            errors += ConfigError("nickname.minLength", "must be >= 1 (was ${nickname.minLength})")
        }
        if (nickname.minLength > nickname.maxLength) {
            errors += ConfigError(
                "nickname.minLength",
                "minLength (${nickname.minLength}) must not exceed maxLength (${nickname.maxLength})",
            )
        }
        for ((index, name) in nickname.reservedNames.withIndex()) {
            if (name.isBlank()) {
                errors += ConfigError("nickname.reservedNames[$index]", "must not be blank")
            }
        }

        if (root.firstLineTemplate.isEmpty()) {
            errors += ConfigError("firstLineTemplate", "must contain at least one segment")
        }

        val ruleIds = HashSet<String>()
        for ((index, rule) in root.secondLineRules.withIndex()) {
            if (rule.id.isBlank()) {
                errors += ConfigError("secondLineRules[$index].id", "must not be blank")
            }
            if (!ruleIds.add(rule.id)) {
                errors += ConfigError("secondLineRules[$index].id", "duplicate rule id '${rule.id}'")
            }
            if (rule.condition?.dimension != null && !isValidIdentifier(rule.condition.dimension)) {
                errors += ConfigError(
                    "secondLineRules[$index].condition.dimension",
                    "'${rule.condition.dimension}' is not a valid dimension identifier",
                )
            }
            if (rule.template.length > SecondLineRuleConfig.MAX_TEMPLATE_LENGTH) {
                errors += ConfigError(
                    "secondLineRules[$index].template",
                    "must not exceed ${SecondLineRuleConfig.MAX_TEMPLATE_LENGTH} characters " +
                        "(was ${rule.template.length})",
                )
            }
            if (rule.refreshTicks !in SecondLineRuleConfig.MIN_REFRESH_TICKS..SecondLineRuleConfig.MAX_REFRESH_TICKS) {
                errors += ConfigError(
                    "secondLineRules[$index].refreshTicks",
                    "must be an integer between ${SecondLineRuleConfig.MIN_REFRESH_TICKS} and " +
                        "${SecondLineRuleConfig.MAX_REFRESH_TICKS} inclusive (was ${rule.refreshTicks})",
                )
            }
        }

        val render = root.render
        if (!render.distanceBlocks.isFinite() || render.distanceBlocks <= 0.0) {
            errors += ConfigError("render.distanceBlocks", "must be positive and finite (was ${render.distanceBlocks})")
        }
        for ((path, offset) in listOf(
            "render.firstLineOffset" to render.firstLineOffset,
            "render.secondLineOffset" to render.secondLineOffset,
        )) {
            if (!offset.x.isFinite() || !offset.y.isFinite() || !offset.z.isFinite()) {
                errors += ConfigError(path, "offset must be finite (was ${offset.x}/${offset.y}/${offset.z})")
            }
        }

        val titleIds = HashSet<String>()
        for ((index, title) in root.titles.withIndex()) {
            if (title.id.isBlank()) {
                errors += ConfigError("titles[$index].id", "must not be blank")
            }
            if (!titleIds.add(title.id)) {
                errors += ConfigError("titles[$index].id", "duplicate title id '${title.id}'")
            }
            if (title.displayName.isBlank()) {
                errors += ConfigError("titles[$index].displayName", "must not be blank")
            }
            if (LegacyTextParser.strip(title.text).isBlank()) {
                errors += ConfigError("titles[$index].text", "must not be blank after stripping format codes")
            }
            if (title.iconItem != null && !isValidIdentifier(title.iconItem)) {
                errors += ConfigError("titles[$index].iconItem", "'${title.iconItem}' is not a valid item identifier")
            }
        }

        return errors
    }

    private fun isValidIdentifier(value: String): Boolean =
        net.minecraft.resources.Identifier.tryParse(value) != null

    // ------------------------------------------------------------------
    // JSON mapping (manual, path preserving)
    // ------------------------------------------------------------------

    internal class ConfigParseException(val errors: List<ConfigError>) : RuntimeException("config parse failed")

    fun parseConfig(root: JsonObject): FancyCrestsConfig {
        val errors = mutableListOf<ConfigError>()

        val channelsObj = root.optObject("channels", "channels", errors)
        val channels = ChannelsConfig(
            headRender = channelsObj.boolean("headRender", true, "channels.headRender", errors),
            chat = channelsObj.boolean("chat", false, "channels.chat", errors),
            tabList = channelsObj.boolean("tabList", false, "channels.tabList", errors),
            placeholdersExport = channelsObj.boolean(
                "placeholdersExport", false, "channels.placeholdersExport", errors,
            ),
        )

        val equipmentObj = root.optObject("equipment", "equipment", errors)
        val equipment = EquipmentConfig(
            maxPrefixes = equipmentObj.int("maxPrefixes", 3, "equipment.maxPrefixes", errors),
            maxSuffixes = equipmentObj.int("maxSuffixes", 3, "equipment.maxSuffixes", errors),
        )

        val nicknameObj = root.optObject("nickname", "nickname", errors)
        val patternText = nicknameObj.string("pattern", "[A-Za-z0-9_]+", "nickname.pattern", errors)
        val pattern = try {
            Pattern.compile(patternText)
        } catch (e: PatternSyntaxException) {
            errors += ConfigError("nickname.pattern", "invalid regex: ${e.description}")
            Pattern.compile("[A-Za-z0-9_]+")
        }
        val nickname = NicknameConfig(
            maxLength = nicknameObj.int("maxLength", 16, "nickname.maxLength", errors),
            minLength = nicknameObj.int("minLength", 3, "nickname.minLength", errors),
            pattern = pattern,
            reservedNames = nicknameObj.stringList("reservedNames", "nickname.reservedNames", errors),
            allowSet = nicknameObj.boolean("allowSet", true, "nickname.allowSet", errors),
        )

        val permissionsObj = root.optObject("permissions", "permissions", errors)
        val nodesObj = permissionsObj.optObject("nodes", "permissions.nodes", errors)
        val nodes = LinkedHashMap<UUID, NodeGrants>()
        for ((key, value) in nodesObj.entrySet()) {
            val path = "permissions.nodes[$key]"
            val uuid = try {
                UUID.fromString(key)
            } catch (e: IllegalArgumentException) {
                errors += ConfigError(path, "key must be a UUID")
                continue
            }
            if (!value.isJsonObject) {
                errors += ConfigError(path, "must be an object with allow/deny arrays")
                continue
            }
            nodes[uuid] = NodeGrants(
                allow = value.asJsonObject.stringList("allow", "$path.allow", errors),
                deny = value.asJsonObject.stringList("deny", "$path.deny", errors),
            )
        }

        val templateArray = root.optArray("firstLineTemplate", "firstLineTemplate", errors)
        val template = mutableListOf<TemplateSegment>()
        for ((index, element) in templateArray.withIndex()) {
            val path = "firstLineTemplate[$index]"
            if (!element.isJsonObject) {
                errors += ConfigError(path, "must be an object")
                continue
            }
            val obj = element.asJsonObject
            val typeText = obj.string("type", null, "$path.type", errors)
            val type = when (typeText?.lowercase()?.trim()) {
                "prefixes" -> TemplateSegmentType.PREFIXES
                "name" -> TemplateSegmentType.NAME
                "suffixes" -> TemplateSegmentType.SUFFIXES
                "prefix" -> TemplateSegmentType.PREFIXES // tolerated spelling
                "suffix" -> TemplateSegmentType.SUFFIXES
                null -> null
                else -> {
                    errors += ConfigError("$path.type", "must be one of prefixes|name|suffixes (was '$typeText')")
                    null
                }
            }
            if (type != null) {
                template += TemplateSegment(
                    type = type,
                    separator = obj.string("separator", " ", "$path.separator", errors) ?: " ",
                    separatorStyle = obj.string("separatorStyle", null, "$path.separatorStyle", errors),
                )
            }
        }

        val rulesArray = root.optArray("secondLineRules", "secondLineRules", errors)
        val rules = mutableListOf<SecondLineRuleConfig>()
        for ((index, element) in rulesArray.withIndex()) {
            val path = "secondLineRules[$index]"
            if (!element.isJsonObject) {
                errors += ConfigError(path, "must be an object")
                continue
            }
            val obj = element.asJsonObject
            val conditionObj = obj.optObject("condition", "$path.condition", errors)
            val condition = SecondLineCondition(
                dimension = conditionObj.string("dimension", null, "$path.condition.dimension", errors)
                    ?.takeIf { it.isNotBlank() },
                permission = conditionObj.string("permission", null, "$path.condition.permission", errors)
                    ?.takeIf { it.isNotBlank() },
            )
            rules += SecondLineRuleConfig(
                id = obj.string("id", "", "$path.id", errors) ?: "",
                priority = obj.int("priority", 0, "$path.priority", errors),
                condition = condition,
                template = obj.string("template", "", "$path.template", errors) ?: "",
                refreshTicks = obj.strictInt(
                    member = "refreshTicks",
                    default = SecondLineRuleConfig.DEFAULT_REFRESH_TICKS,
                    path = "$path.refreshTicks",
                    errors = errors,
                    min = SecondLineRuleConfig.MIN_REFRESH_TICKS,
                    max = SecondLineRuleConfig.MAX_REFRESH_TICKS,
                ),
            )
        }

        val renderObj = root.optObject("render", "render", errors)
        val render = RenderConfig(
            distanceBlocks = renderObj.double("distanceBlocks", 48.0, "render.distanceBlocks", errors),
            firstLineOffset = renderObj.optObject("firstLineOffset", "render.firstLineOffset", errors)
                .toOffset("render.firstLineOffset", errors) ?: OffsetConfig(0.0, 0.4, 0.0),
            secondLineOffset = renderObj.optObject("secondLineOffset", "render.secondLineOffset", errors)
                .toOffset("render.secondLineOffset", errors) ?: OffsetConfig(0.0, 0.15, 0.0),
        )

        val titlesArray = root.optArray("titles", "titles", errors)
        val titles = mutableListOf<TitleConfig>()
        for ((index, element) in titlesArray.withIndex()) {
            val path = "titles[$index]"
            if (!element.isJsonObject) {
                errors += ConfigError(path, "must be an object")
                continue
            }
            val obj = element.asJsonObject
            val slotText = obj.string("slot", null, "$path.slot", errors)
            val slot = when (slotText?.lowercase()?.trim()) {
                "prefix" -> TitleSlot.PREFIX
                "suffix" -> TitleSlot.SUFFIX
                null -> {
                    // Missing (or null) slot used to drop the title silently, which
                    // could then unequip it from every player on reload. A title
                    // without a slot is a configuration error, not a skip.
                    errors += ConfigError("$path.slot", "is required (must be prefix or suffix)")
                    null
                }
                else -> {
                    errors += ConfigError("$path.slot", "must be prefix or suffix (was '$slotText')")
                    null
                }
            }
            if (slot != null) {
                titles += TitleConfig(
                    id = obj.string("id", "", "$path.id", errors) ?: "",
                    slot = slot,
                    displayName = obj.string("displayName", "", "$path.displayName", errors) ?: "",
                    text = obj.string("text", "", "$path.text", errors) ?: "",
                    permission = obj.string("permission", null, "$path.permission", errors)?.takeIf { it.isNotBlank() },
                    category = obj.string("category", null, "$path.category", errors)?.takeIf { it.isNotBlank() },
                    iconItem = obj.string("iconItem", null, "$path.iconItem", errors)?.takeIf { it.isNotBlank() },
                    sortOrder = obj.int("sortOrder", 0, "$path.sortOrder", errors),
                )
            }
        }

        if (errors.isNotEmpty()) {
            throw ConfigParseException(errors)
        }
        return FancyCrestsConfig(channels, equipment, nickname, PermissionsConfig(nodes), template, rules, render, titles)
    }

    private fun JsonObject.optObject(member: String, path: String, errors: MutableList<ConfigError>): JsonObject {
        val element = get(member) ?: return JsonObject()
        if (element.isJsonObject) return element.asJsonObject
        errors += ConfigError(path, "must be an object")
        return JsonObject()
    }

    private fun JsonObject.optArray(member: String, path: String, errors: MutableList<ConfigError>): JsonArray {
        val element = get(member) ?: return JsonArray()
        if (element.isJsonArray) return element.asJsonArray
        errors += ConfigError(path, "must be an array")
        return JsonArray()
    }

    private fun JsonObject.string(member: String, default: String?, path: String, errors: MutableList<ConfigError>): String? {
        val element = get(member) ?: return default
        if (element is JsonPrimitive && element.isString) return element.asString
        if (element is JsonPrimitive && element.isNumber) return element.asString
        errors += ConfigError(path, "must be a string")
        return default
    }

    private fun JsonObject.int(member: String, default: Int, path: String, errors: MutableList<ConfigError>): Int {
        val element = get(member) ?: return default
        if (element is JsonPrimitive && element.isNumber) return element.asInt
        errors += ConfigError(path, "must be an integer")
        return default
    }

    /**
     * Strict integer parse used for range-checked fields: rejects non-numbers,
     * fractional numbers and values outside [min]..[max], always reporting the
     * exact JSON path (never silently rewriting user input).
     */
    private fun JsonObject.strictInt(
        member: String,
        default: Int,
        path: String,
        errors: MutableList<ConfigError>,
        min: Int,
        max: Int,
    ): Int {
        val element = get(member) ?: return default
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isNumber) {
            errors += ConfigError(path, "must be an integer between $min and $max")
            return default
        }
        val value = primitive.asDouble
        val isIntegral = !value.isNaN() && kotlin.math.floor(value) == value
        if (!isIntegral || value < min || value > max) {
            errors += ConfigError(
                path,
                "must be an integer between $min and $max inclusive (was ${primitive.asString})",
            )
            return default
        }
        return value.toInt()
    }

    private fun JsonObject.double(member: String, default: Double, path: String, errors: MutableList<ConfigError>): Double {
        val element = get(member) ?: return default
        if (element is JsonPrimitive && element.isNumber) return element.asDouble
        errors += ConfigError(path, "must be a number")
        return default
    }

    private fun JsonObject.boolean(member: String, default: Boolean, path: String, errors: MutableList<ConfigError>): Boolean {
        val element = get(member) ?: return default
        if (element is JsonPrimitive && element.isBoolean) return element.asBoolean
        errors += ConfigError(path, "must be a boolean")
        return default
    }

    private fun JsonObject.stringList(member: String, path: String, errors: MutableList<ConfigError>): List<String> {
        val array = optArray(member, path, errors)
        val result = mutableListOf<String>()
        for ((index, element) in array.withIndex()) {
            if (element is JsonPrimitive && element.isString) {
                result += element.asString
            } else {
                errors += ConfigError("$path[$index]", "must be a string")
            }
        }
        return result
    }

    private fun JsonObject.toOffset(path: String, errors: MutableList<ConfigError>): OffsetConfig? {
        if (get("x") == null && get("y") == null && get("z") == null) return null
        return OffsetConfig(
            x = double("x", 0.0, "$path.x", errors),
            y = double("y", 0.0, "$path.y", errors),
            z = double("z", 0.0, "$path.z", errors),
        )
    }

    // ------------------------------------------------------------------
    // Serialization (defaults writing)
    // ------------------------------------------------------------------

    private fun toJson(config: FancyCrestsConfig): JsonObject {
        val root = JsonObject()

        val channels = JsonObject()
        channels.addProperty("headRender", config.channels.headRender)
        channels.addProperty("chat", config.channels.chat)
        channels.addProperty("tabList", config.channels.tabList)
        channels.addProperty("placeholdersExport", config.channels.placeholdersExport)
        root.add("channels", channels)

        val equipment = JsonObject()
        equipment.addProperty("maxPrefixes", config.equipment.maxPrefixes)
        equipment.addProperty("maxSuffixes", config.equipment.maxSuffixes)
        root.add("equipment", equipment)

        val nickname = JsonObject()
        nickname.addProperty("maxLength", config.nickname.maxLength)
        nickname.addProperty("minLength", config.nickname.minLength)
        nickname.addProperty("pattern", config.nickname.pattern.pattern())
        nickname.add("reservedNames", JsonArray().apply { config.nickname.reservedNames.forEach { add(it) } })
        nickname.addProperty("allowSet", config.nickname.allowSet)
        root.add("nickname", nickname)

        root.add("permissions", JsonObject().apply { add("nodes", JsonObject()) })

        val template = JsonArray()
        for (segment in config.firstLineTemplate) {
            template.add(
                JsonObject().apply {
                    addProperty("type", segment.type.name.lowercase())
                    addProperty("separator", segment.separator)
                    segment.separatorStyle?.let { addProperty("separatorStyle", it) }
                },
            )
        }
        root.add("firstLineTemplate", template)

        root.add("secondLineRules", JsonArray())

        root.add(
            "render",
            JsonObject().apply {
                addProperty("distanceBlocks", config.render.distanceBlocks)
                add("firstLineOffset", offsetJson(config.render.firstLineOffset))
                add("secondLineOffset", offsetJson(config.render.secondLineOffset))
            },
        )

        root.add("titles", JsonArray())
        return root
    }

    private fun offsetJson(offset: OffsetConfig): JsonObject =
        JsonObject().apply {
            addProperty("x", offset.x)
            addProperty("y", offset.y)
            addProperty("z", offset.z)
        }
}

/** Safe defaults used when the file is missing or on first start. */
object DefaultConfig {
    fun create(): FancyCrestsConfig = FancyCrestsConfig(
        channels = ChannelsConfig(
            headRender = true,
            chat = false,
            tabList = false,
            placeholdersExport = false,
        ),
        equipment = EquipmentConfig(maxPrefixes = 3, maxSuffixes = 3),
        nickname = NicknameConfig(
            maxLength = 16,
            minLength = 3,
            pattern = Pattern.compile("[A-Za-z0-9_]+"),
            reservedNames = emptyList(),
            allowSet = true,
        ),
        permissions = PermissionsConfig(emptyMap()),
        firstLineTemplate = listOf(
            TemplateSegment(TemplateSegmentType.PREFIXES, " ", "&7"),
            TemplateSegment(TemplateSegmentType.NAME, " ", null),
            TemplateSegment(TemplateSegmentType.SUFFIXES, " ", "&7"),
        ),
        secondLineRules = emptyList(),
        render = RenderConfig(
            distanceBlocks = 48.0,
            firstLineOffset = OffsetConfig(0.0, 0.4, 0.0),
            secondLineOffset = OffsetConfig(0.0, 0.15, 0.0),
        ),
        titles = emptyList(),
    )
}
