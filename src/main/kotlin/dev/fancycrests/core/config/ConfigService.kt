package dev.fancycrests.core.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

/**
 * One segment of a per-channel first-line template ([ChannelsConfig]).
 * Mirrors [TemplateSegment] as raw configuration; consumers convert it when
 * they build a channel's rendered first line.
 */
data class TemplateSegmentConfig(
    val type: TemplateSegmentType,
    /** Joins the items *within* the segment. */
    val separator: String,
    /** Optional legacy styling (& codes) for the separator component. */
    val separatorStyle: String? = null,
    /**
     * Separator placed between this segment and the next non-empty one; null
     * falls back to [separator] (the global-template behaviour).
     */
    val segmentSeparator: String? = null,
)

/**
 * Boolean channel switches; channels never implicitly enable each other.
 *
 * The three optional per-channel templates override the global
 * [FancyCrestsConfig.firstLineTemplate] for that channel only: null means
 * "not configured" (fall back to the global template), an empty list means
 * "configured empty" (that channel renders no first line at all).
 */
data class ChannelsConfig(
    val headRender: Boolean,
    val chat: Boolean,
    val tabList: Boolean,
    val placeholdersExport: Boolean,
    val headRenderTemplate: List<TemplateSegmentConfig>? = null,
    val chatTemplate: List<TemplateSegmentConfig>? = null,
    val tabListTemplate: List<TemplateSegmentConfig>? = null,
)

/** Per-slot equip limits. */
data class EquipmentConfig(val maxPrefixes: Int, val maxSuffixes: Int)

/** Nickname constraints enforced by the selection service. */
data class NicknameConfig(
    val maxLength: Int,
    val minLength: Int,
    val pattern: Pattern,
    val reservedNames: List<String>,
    /** Global switch for custom and preset nickname changes. */
    val allowSet: Boolean,
    /** Administrator-defined nickname choices, in menu order. */
    val presets: List<String> = emptyList(),
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

/** Title id reserved by the unequip-all operation; never a valid title id (audit #13). */
private const val RESERVED_TITLE_ALL = "all"

/** Namespace implied by a dimension shorthand such as `overworld` (batch J3). */
private const val DEFAULT_DIMENSION_NAMESPACE = "minecraft"

/**
 * Exact integer tokens: digits, optionally signed, optionally followed by a
 * zero-only fraction (`3.0`). Exponent notation is a configuration error
 * rather than a number because it hides the magnitude from the reader.
 */
private val EXACT_INT_PATTERN = Regex("-?\\d+(?:\\.0+)?")

/**
 * Explicit bounds for every numeric field (batch J3). Ranges live in one
 * place so the parser and the semantic validator cannot drift apart; an
 * out-of-range value is an error at load time instead of a silent clamp.
 */
private object NumericBounds {
    /** 0 disables the slot entirely; 10 is the practical prefix/suffix cap. */
    val EQUIP_LIMIT = 0..10
    val NICKNAME_LENGTH = 1..64
    val PRIORITY = 0..1000
    val SORT_ORDER = 0..10000
    val DISTANCE_BLOCKS = 8.0..128.0
}

/** Result of a config load or reload attempt. */
sealed interface ConfigLoadResult {
    /**
     * @param state which source the config came from. Only [LoadState.OK]
     *   means "the regular config file". A reload always produces OK (it
     *   reads the regular file); startup may fall back to the last-good
     *   copy.
     */
    data class Success(
        val config: FancyCrestsConfig,
        val state: LoadState = LoadState.OK,
    ) : ConfigLoadResult

    /** The previous configuration stayed active. */
    data class Failure(val errors: List<ConfigError>) : ConfigLoadResult
}

/**
 * Outcome of the startup load (see ConfigService.loadOrCreate).
 *
 * Protection mode is decided by the *load state* only: a config that is
 * valid but intentionally minimal (`titles: []`) runs normally (equipment
 * rules follow the documented revalidation semantics); PROTECTED means no
 * valid configuration existed at all, so state changes are disabled until
 * one is available.
 */
enum class LoadState {
    /** The regular config file loaded and validated. */
    OK,

    /**
     * The regular config file was invalid; the last-known-good copy loaded
     * instead. Fully functional; the admin should fix the main file.
     */
    FALLBACK_LAST_GOOD,

    /**
     * Neither the config file nor the last-good copy produced a valid
     * configuration. No valid config exists: callers must refuse state
     * changes (nothing may be unequipped or written against an unknown
     * catalog).
     */
    PROTECTED,
}

/**
 * Loads, validates and hot-swaps the YAML configuration.
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
    private val ioExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { thread -> Thread(thread, "fancycrests-config").apply { isDaemon = true } }

    /** Monotonic version of the successfully replaced configuration. */
    private val versionCounter = AtomicLong(0)

    @Volatile
    private var currentConfig: FancyCrestsConfig = DefaultConfig.create()

    @Volatile
    private var currentState: LoadState = LoadState.OK

    private val replaceListeners = mutableListOf<(FancyCrestsConfig) -> Unit>()

    val current: FancyCrestsConfig get() = currentConfig

    /** Bumped only on successful replacement; used to invalidate stale operations. */
    val version: Long get() = versionCounter.get()

    /** How the active configuration was obtained (startup protection semantics). */
    val loadState: LoadState get() = currentState

    /**
     * True while no valid configuration exists ([LoadState.PROTECTED]).
     * Wire this to SelectionService's configHealthy gate: all state changes
     * must be refused so persisted equipment is never unequipped against an
     * unknown catalog.
     */
    val protectionActive: Boolean get() = currentState == LoadState.PROTECTED

    /** Companion copy of the config file, updated after every successful full validation. */
    val lastGoodPath: Path = configFile.resolveSibling(configFile.fileName.toString() + ".last-good")

    fun addOnReplaced(listener: (FancyCrestsConfig) -> Unit) {
        synchronized(replaceListeners) { replaceListeners.add(listener) }
    }

    /**
     * Initial synchronous load, called once on server start. Creates and
     * persists safe defaults when the file is missing; the server always
     * starts with a valid configuration.
     *
     * Startup failure protection: when an existing file fails to parse or
     * validate, the last-known-good copy is tried before anything else. If
     * that copy is usable the server runs on it ([LoadState.FALLBACK_LAST_GOOD],
     * loud warning, fully functional). Only when no valid configuration
     * exists at all does the service enter [LoadState.PROTECTED]: the
     * in-memory config is irrelevant then — callers must refuse state
     * changes (see [protectionActive]) because running with implicit empty
     * defaults would unequip every player's titles on their next login and
     * persist that data loss.
     */
    fun loadOrCreate(): ConfigLoadResult {
        if (!Files.isRegularFile(configFile)) {
            val defaults = DefaultConfig.create()
            writeDefaults(defaults)
            applyNewConfig(defaults)
            currentState = LoadState.OK
            return ConfigLoadResult.Success(defaults)
        }
        val result = readParseAndValidate(configFile)
        if (result is ConfigLoadResult.Success) {
            applyNewConfig(result.config)
            currentState = LoadState.OK
            persistLastGood()
            return result
        }
        val errors = (result as ConfigLoadResult.Failure).errors
        logger.error(
            "Configuration invalid ({}). Errors: {}",
            configFile,
            errors.joinToString("; "),
        )
        val fallback = readParseAndValidate(lastGoodPath)
        if (fallback is ConfigLoadResult.Success) {
            logger.warn(
                "Falling back to the last-known-good configuration {} ({} titles). " +
                    "Fix the main configuration file; the last-good copy is only a safety net.",
                lastGoodPath,
                fallback.config.titles.size,
            )
            applyNewConfig(fallback.config)
            currentState = LoadState.FALLBACK_LAST_GOOD
            return ConfigLoadResult.Success(fallback.config, LoadState.FALLBACK_LAST_GOOD)
        }
        logger.error(
            "No valid configuration available (config file and last-good copy both unusable). " +
                "Entering PROTECTED mode: all title state changes are disabled until a valid " +
                "configuration is loaded. Fix '{}' and restart (or /fancycrests reload). " +
                "Last-good problems: {}",
            configFile,
            if (fallback is ConfigLoadResult.Failure) {
                fallback.errors.joinToString("; ")
            } else {
                "unknown"
            },
        )
        currentState = LoadState.PROTECTED
        return result
    }

    /**
     * Hot reload: background read + parse, main-thread validation and swap.
     * A failed reload keeps the previous configuration (and load state)
     * active — entering protection on a failed hot reload would lock out
     * the very admin command that fixes it. A successful reload is also the
     * recovery path out of FALLBACK_LAST_GOOD/PROTECTED and refreshes the
     * last-good copy.
     */
    fun reloadAsync(callback: (ConfigLoadResult) -> Unit) {
        ioExecutor.execute {
            val read = try {
                readParseAndValidate(configFile)
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
                        currentState = LoadState.OK
                        persistLastGood()
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
            val errors = when (val result = readParseAndValidate(configFile)) {
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

    private fun readParseAndValidate(file: Path): ConfigLoadResult {
        if (!Files.isRegularFile(file)) {
            return ConfigLoadResult.Failure(
                listOf(ConfigError(file.toString(), "configuration file is missing")),
            )
        }
        val root = try {
            ConfigYaml.parse(Files.readString(file, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            return ConfigLoadResult.Failure(
                listOf(ConfigError(file.toString(), "cannot read or parse YAML: ${e.message}")),
            )
        }
        if (!root.isJsonObject) {
            return ConfigLoadResult.Failure(listOf(ConfigError("$", "root must be a mapping")))
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
            Files.writeString(temp, ConfigYaml.dump(toJson(config)), StandardCharsets.UTF_8)
            try {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // Best effort: the server still runs with in-memory defaults.
        }
    }

    /**
     * Atomically refreshes the last-good copy from the config file that just
     * passed full validation (write temp + move, so a crash mid-write can
     * never leave a half-written copy). A damaged or missing copy must never
     * take the server down — failures are logged and ignored; the previous
     * copy simply stays as it was.
     */
    private fun persistLastGood() {
        try {
            val parent = lastGoodPath.toAbsolutePath().parent
            if (parent != null) Files.createDirectories(parent)
            val temp = lastGoodPath.resolveSibling(lastGoodPath.fileName.toString() + ".tmp")
            Files.copy(configFile, temp, StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(temp, lastGoodPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(temp, lastGoodPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            logger.warn(
                "Could not refresh the last-known-good configuration copy {}; keeping the previous one",
                lastGoodPath,
                e,
            )
        }
    }

    // ------------------------------------------------------------------
    // Semantic validation (path addressed)
    // ------------------------------------------------------------------

    fun validate(root: FancyCrestsConfig): List<ConfigError> {
        val errors = mutableListOf<ConfigError>()

        for ((path, limit) in listOf(
            "equipment.maxPrefixes" to root.equipment.maxPrefixes,
            "equipment.maxSuffixes" to root.equipment.maxSuffixes,
        )) {
            if (limit !in NumericBounds.EQUIP_LIMIT) {
                errors += ConfigError(
                    path,
                    "must be between ${NumericBounds.EQUIP_LIMIT.first} and " +
                        "${NumericBounds.EQUIP_LIMIT.last} inclusive (was $limit)",
                )
            }
        }

        val nickname = root.nickname
        for ((path, length) in listOf(
            "nickname.maxLength" to nickname.maxLength,
            "nickname.minLength" to nickname.minLength,
        )) {
            if (length !in NumericBounds.NICKNAME_LENGTH) {
                errors += ConfigError(
                    path,
                    "must be between ${NumericBounds.NICKNAME_LENGTH.first} and " +
                        "${NumericBounds.NICKNAME_LENGTH.last} inclusive (was $length)",
                )
            }
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
        val nicknamePresets = HashSet<String>()
        for ((index, preset) in nickname.presets.withIndex()) {
            if (LegacyTextParser.strip(preset, knownCodesOnly = true).isBlank()) {
                errors += ConfigError("nickname.presets[$index]", "must not be blank")
            }
            if (!nicknamePresets.add(preset)) {
                errors += ConfigError("nickname.presets[$index]", "duplicate nickname preset")
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
        if (!render.distanceBlocks.isFinite() || render.distanceBlocks !in NumericBounds.DISTANCE_BLOCKS) {
            errors += ConfigError(
                "render.distanceBlocks",
                "must be between ${NumericBounds.DISTANCE_BLOCKS.start} and " +
                    "${NumericBounds.DISTANCE_BLOCKS.endInclusive} inclusive (was ${render.distanceBlocks})",
            )
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
            if (title.id == RESERVED_TITLE_ALL) {
                errors += ConfigError(
                    "titles[$index].id",
                    "'${title.id}' is reserved for the unequip-all operation and cannot be a title id",
                )
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

    /**
     * Dimension shorthand: a condition without a namespace means the vanilla
     * namespace (`overworld` -> `minecraft:overworld`), matching how the
     * runtime compares the rule against a full dimension key (batch J3). The
     * normalized value is what the config object holds, so both the runtime
     * comparison and the validation error message see the full identifier.
     */
    private fun normalizeDimension(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.indexOf(':') >= 0) trimmed else "$DEFAULT_DIMENSION_NAMESPACE:$trimmed"
    }

    // ------------------------------------------------------------------
    // JSON mapping (manual, path preserving)
    // ------------------------------------------------------------------

    internal class ConfigParseException(val errors: List<ConfigError>) : RuntimeException("config parse failed")

    /**
     * Known-key whitelists for every object layer (batch J2). Gson silently
     * ignores unknown members, which turns typos into behaviour changes:
     * `permisson` made a rule condition unconditional, `titels` produced an
     * empty catalog (and, before last-good protection, permanent data loss).
     * Every misspelling must be a load error with the exact path instead.
     */
    private object KnownKeys {
        val ROOT = setOf(
            "channels", "equipment", "nickname", "permissions",
            "firstLineTemplate", "secondLineRules", "render", "titles",
        )
        val CHANNELS = setOf(
            "headRender", "chat", "tabList", "placeholdersExport",
            "headRenderTemplate", "chatTemplate", "tabListTemplate",
        )
        val EQUIPMENT = setOf("maxPrefixes", "maxSuffixes")
        val NICKNAME = setOf("maxLength", "minLength", "pattern", "reservedNames", "allowSet", "presets")
        val PERMISSIONS = setOf("nodes")
        val NODE_GRANTS = setOf("allow", "deny")
        val TEMPLATE_SEGMENT = setOf("type", "separator", "separatorStyle")
        val CHANNEL_TEMPLATE_SEGMENT = setOf("type", "separator", "separatorStyle", "segmentSeparator")
        val RULE = setOf("id", "priority", "condition", "template", "refreshTicks")
        val CONDITION = setOf("dimension", "permission")
        val RENDER = setOf("distanceBlocks", "firstLineOffset", "secondLineOffset")
        val OFFSET = setOf("x", "y", "z")
        val TITLE = setOf(
            "id", "slot", "displayName", "text", "permission", "category", "iconItem", "sortOrder",
        )
    }

    /**
     * Reports every member outside [allowed] with its full JSON path and the
     * list of legal field names, so the fix is obvious from the error alone.
     * The root object uses an empty [path]; members there address as `key`.
     */
    private fun JsonObject.rejectUnknownKeys(path: String, allowed: Set<String>, errors: MutableList<ConfigError>) {
        for (key in keySet()) {
            if (key !in allowed) {
                val where = if (path.isEmpty()) key else "$path.$key"
                errors += ConfigError(
                    where,
                    "unknown field '$key' (allowed fields: ${allowed.sorted().joinToString(", ")})",
                )
            }
        }
    }

    /**
     * Required string member: absent (or explicit null) is an "is required"
     * error — optional members silently default, but a missing required one
     * used to degrade into a blank value that later stages had to catch.
     */
    private fun JsonObject.requiredString(member: String, path: String, errors: MutableList<ConfigError>): String? {
        val element = get(member)
        if (element == null || element.isJsonNull) {
            errors += ConfigError(path, "is required")
            return null
        }
        return string(member, null, path, errors)
    }

    fun parseConfig(root: JsonObject): FancyCrestsConfig {
        val errors = mutableListOf<ConfigError>()
        root.rejectUnknownKeys("", KnownKeys.ROOT, errors)

        val channelsObj = root.optObject("channels", "channels", errors)
        channelsObj.rejectUnknownKeys("channels", KnownKeys.CHANNELS, errors)
        val channels = ChannelsConfig(
            headRender = channelsObj.boolean("headRender", true, "channels.headRender", errors),
            chat = channelsObj.boolean("chat", false, "channels.chat", errors),
            tabList = channelsObj.boolean("tabList", false, "channels.tabList", errors),
            placeholdersExport = channelsObj.boolean(
                "placeholdersExport", false, "channels.placeholdersExport", errors,
            ),
            headRenderTemplate = channelsObj.optTemplate(
                "headRenderTemplate", "channels.headRenderTemplate", errors,
            ),
            chatTemplate = channelsObj.optTemplate(
                "chatTemplate", "channels.chatTemplate", errors,
            ),
            tabListTemplate = channelsObj.optTemplate(
                "tabListTemplate", "channels.tabListTemplate", errors,
            ),
        )

        val equipmentObj = root.optObject("equipment", "equipment", errors)
        equipmentObj.rejectUnknownKeys("equipment", KnownKeys.EQUIPMENT, errors)
        val equipment = EquipmentConfig(
            maxPrefixes = equipmentObj.strictInt(
                "maxPrefixes", 3, "equipment.maxPrefixes", errors, NumericBounds.EQUIP_LIMIT,
            ),
            maxSuffixes = equipmentObj.strictInt(
                "maxSuffixes", 3, "equipment.maxSuffixes", errors, NumericBounds.EQUIP_LIMIT,
            ),
        )

        val nicknameObj = root.optObject("nickname", "nickname", errors)
        nicknameObj.rejectUnknownKeys("nickname", KnownKeys.NICKNAME, errors)
        val patternText = nicknameObj.string("pattern", "[A-Za-z0-9_]+", "nickname.pattern", errors)
        val pattern = try {
            Pattern.compile(patternText)
        } catch (e: PatternSyntaxException) {
            errors += ConfigError("nickname.pattern", "invalid regex: ${e.description}")
            Pattern.compile("[A-Za-z0-9_]+")
        }
        val nickname = NicknameConfig(
            maxLength = nicknameObj.strictInt(
                "maxLength", 16, "nickname.maxLength", errors, NumericBounds.NICKNAME_LENGTH,
            ),
            minLength = nicknameObj.strictInt(
                "minLength", 3, "nickname.minLength", errors, NumericBounds.NICKNAME_LENGTH,
            ),
            pattern = pattern,
            reservedNames = nicknameObj.stringList("reservedNames", "nickname.reservedNames", errors),
            allowSet = nicknameObj.boolean("allowSet", true, "nickname.allowSet", errors),
            presets = nicknameObj.stringList("presets", "nickname.presets", errors),
        )

        val permissionsObj = root.optObject("permissions", "permissions", errors)
        permissionsObj.rejectUnknownKeys("permissions", KnownKeys.PERMISSIONS, errors)
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
            val grantsObj = value.asJsonObject
            grantsObj.rejectUnknownKeys(path, KnownKeys.NODE_GRANTS, errors)
            nodes[uuid] = NodeGrants(
                allow = grantsObj.stringList("allow", "$path.allow", errors),
                deny = grantsObj.stringList("deny", "$path.deny", errors),
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
            obj.rejectUnknownKeys(path, KnownKeys.TEMPLATE_SEGMENT, errors)
            val type = segmentType(obj, path, errors)
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
            obj.rejectUnknownKeys(path, KnownKeys.RULE, errors)
            val conditionObj = obj.optObject("condition", "$path.condition", errors)
            conditionObj.rejectUnknownKeys("$path.condition", KnownKeys.CONDITION, errors)
            val condition = SecondLineCondition(
                dimension = conditionObj.string("dimension", null, "$path.condition.dimension", errors)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { normalizeDimension(it) },
                permission = conditionObj.string("permission", null, "$path.condition.permission", errors)
                    ?.takeIf { it.isNotBlank() },
            )
            rules += SecondLineRuleConfig(
                id = obj.requiredString("id", "$path.id", errors) ?: "",
                priority = obj.strictInt("priority", 0, "$path.priority", errors, NumericBounds.PRIORITY),
                condition = condition,
                template = obj.requiredString("template", "$path.template", errors) ?: "",
                refreshTicks = obj.strictInt(
                    member = "refreshTicks",
                    default = SecondLineRuleConfig.DEFAULT_REFRESH_TICKS,
                    path = "$path.refreshTicks",
                    errors = errors,
                    bounds = SecondLineRuleConfig.MIN_REFRESH_TICKS..SecondLineRuleConfig.MAX_REFRESH_TICKS,
                ),
            )
        }

        val renderObj = root.optObject("render", "render", errors)
        renderObj.rejectUnknownKeys("render", KnownKeys.RENDER, errors)
        val distanceBlocks = renderObj.double("distanceBlocks", 48.0, "render.distanceBlocks", errors)
        if (distanceBlocks !in NumericBounds.DISTANCE_BLOCKS) {
            errors += ConfigError(
                "render.distanceBlocks",
                "must be between ${NumericBounds.DISTANCE_BLOCKS.start} and " +
                    "${NumericBounds.DISTANCE_BLOCKS.endInclusive} inclusive (was $distanceBlocks)",
            )
        }
        val render = RenderConfig(
            distanceBlocks = distanceBlocks,
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
            obj.rejectUnknownKeys(path, KnownKeys.TITLE, errors)
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
                    id = obj.requiredString("id", "$path.id", errors) ?: "",
                    slot = slot,
                    displayName = obj.requiredString("displayName", "$path.displayName", errors) ?: "",
                    text = obj.requiredString("text", "$path.text", errors) ?: "",
                    permission = obj.string("permission", null, "$path.permission", errors)?.takeIf { it.isNotBlank() },
                    category = obj.string("category", null, "$path.category", errors)?.takeIf { it.isNotBlank() },
                    iconItem = obj.string("iconItem", null, "$path.iconItem", errors)?.takeIf { it.isNotBlank() },
                    sortOrder = obj.strictInt("sortOrder", 0, "$path.sortOrder", errors, NumericBounds.SORT_ORDER),
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

    /**
     * Parses one segment's `type`. Unknown spellings and missing values are
     * errors with the exact path; the tolerated singular spellings
     * ("prefix"/"suffix") stay accepted for backward compatibility.
     */
    private fun segmentType(obj: JsonObject, path: String, errors: MutableList<ConfigError>): TemplateSegmentType? {
        val typeText = obj.string("type", null, "$path.type", errors)
        return when (typeText?.lowercase()?.trim()) {
            "prefixes", "prefix" -> TemplateSegmentType.PREFIXES
            "name" -> TemplateSegmentType.NAME
            "suffixes", "suffix" -> TemplateSegmentType.SUFFIXES
            null -> {
                errors += ConfigError("$path.type", "is required (must be prefixes|name|suffixes)")
                null
            }
            else -> {
                errors += ConfigError("$path.type", "must be prefixes|name|suffixes (was '$typeText')")
                null
            }
        }
    }

    /**
     * Parses an optional per-channel template. Absent member (or explicit
     * null) yields null = "not configured, use the global template"; a
     * present array is parsed element by element (an empty array is legal
     * and means "this channel renders no first line").
     */
    private fun JsonObject.optTemplate(
        member: String,
        path: String,
        errors: MutableList<ConfigError>,
    ): List<TemplateSegmentConfig>? {
        val element = get(member) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonArray) {
            errors += ConfigError(path, "must be an array of template segments")
            return null
        }
        val segments = mutableListOf<TemplateSegmentConfig>()
        for ((index, entry) in element.asJsonArray.withIndex()) {
            val entryPath = "$path[$index]"
            if (!entry.isJsonObject) {
                errors += ConfigError(entryPath, "must be an object")
                continue
            }
            val obj = entry.asJsonObject
            obj.rejectUnknownKeys(entryPath, KnownKeys.CHANNEL_TEMPLATE_SEGMENT, errors)
            val type = segmentType(obj, entryPath, errors)
            if (type != null) {
                segments += TemplateSegmentConfig(
                    type = type,
                    separator = obj.string("separator", " ", "$entryPath.separator", errors) ?: " ",
                    separatorStyle = obj.string("separatorStyle", null, "$entryPath.separatorStyle", errors),
                    segmentSeparator = obj.string(
                        "segmentSeparator", null, "$entryPath.segmentSeparator", errors,
                    )?.takeIf { it.isNotEmpty() },
                )
            }
        }
        return segments
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

    /**
     * Strict integer parse from the JSON token text itself: never through
     * `Double` (which rounds integers beyond 2^53 and saturates at the Int
     * bounds), never through `JsonPrimitive.asInt` (which truncates
     * fractions). Fractional values and exponent notation are errors; an
     * integral decimal such as `3.0` describes the same integer exactly and
     * stays accepted. Range violations report the exact JSON path.
     */
    private fun JsonObject.strictInt(
        member: String,
        default: Int,
        path: String,
        errors: MutableList<ConfigError>,
        bounds: IntRange? = null,
    ): Int {
        val element = get(member) ?: return default
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isNumber) {
            errors += ConfigError(path, "must be an integer${boundsSuffix(bounds)}")
            return default
        }
        val raw = primitive.asString
        val value = parseExactInt(raw)
        if (value == null || (bounds != null && value !in bounds)) {
            errors += ConfigError(path, "must be an integer${boundsSuffix(bounds)} (was $raw)")
            return default
        }
        return value
    }

    /**
     * Exact integer parse of a JSON number token. Returns null for fractional
     * values, exponent notation and values outside the Int range, so callers
     * report an error instead of a rounded or saturated number.
     */
    private fun parseExactInt(raw: String): Int? {
        if (raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) return null
        if (!EXACT_INT_PATTERN.matches(raw)) return null
        return try {
            java.math.BigDecimal(raw).toBigIntegerExact().intValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun boundsSuffix(bounds: IntRange?): String =
        if (bounds == null) "" else " between ${bounds.first} and ${bounds.last} inclusive"

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
        rejectUnknownKeys(path, KnownKeys.OFFSET, errors)
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
        config.channels.headRenderTemplate?.let { channels.add("headRenderTemplate", templateConfigJson(it)) }
        config.channels.chatTemplate?.let { channels.add("chatTemplate", templateConfigJson(it)) }
        config.channels.tabListTemplate?.let { channels.add("tabListTemplate", templateConfigJson(it)) }
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
        nickname.add("presets", JsonArray().apply { config.nickname.presets.forEach { add(it) } })
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

    private fun templateConfigJson(segments: List<TemplateSegmentConfig>): JsonArray =
        JsonArray().apply {
            for (segment in segments) {
                add(
                    JsonObject().apply {
                        addProperty("type", segment.type.name.lowercase())
                        addProperty("separator", segment.separator)
                        segment.separatorStyle?.let { addProperty("separatorStyle", it) }
                        segment.segmentSeparator?.let { addProperty("segmentSeparator", it) }
                    },
                )
            }
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
