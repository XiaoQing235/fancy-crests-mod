package dev.fancycrests.core

import dev.fancycrests.api.HeadDisplayChannel
import dev.fancycrests.api.PlayerTitleState
import dev.fancycrests.core.catalog.CatalogReplaceResult
import dev.fancycrests.core.catalog.CatalogService
import dev.fancycrests.core.config.ConfigLoadResult
import dev.fancycrests.core.config.ConfigService
import dev.fancycrests.core.config.FancyCrestsConfig
import dev.fancycrests.core.event.TitleEventBus
import dev.fancycrests.core.permission.BuiltInBackend
import dev.fancycrests.core.permission.PermissionManager
import dev.fancycrests.core.resolve.SnapshotPublisher
import dev.fancycrests.core.resolve.SnapshotResolver
import dev.fancycrests.core.selection.SelectionService
import dev.fancycrests.core.storage.SqliteStorage
import dev.fancycrests.core.storage.StorageSchemaException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID

/** Aggregated diagnostic snapshot for /fancycrests diagnose. */
data class DiagnosticsInfo(
    val modVersion: String,
    val channels: dev.fancycrests.core.config.ChannelsConfig,
    val permissionBackends: List<String>,
    val catalogVersion: Long,
    val catalogSize: Int,
    val trackedPlayers: Int,
    val ownershipRecords: Int,
    val storageAvailable: Boolean,
    val storageError: String?,
    val publishedSnapshotTargets: Int,
)

/**
 * Facade assembling the core domain services. The integration layer
 * (FancyCrestsMod) constructs one instance with the config directory and the
 * head-channel provider, then calls [start] with a [ServerAdapter] when the
 * server has started and [stop] on server stopping.
 *
 * Owns:
 *  - config loading/validation/hot-reload ([ConfigService])
 *  - the immutable title catalog ([CatalogService])
 *  - the permission chain with the built-in backend ([PermissionManager])
 *  - SQLite persistence ([SqliteStorage])
 *  - selection/eligibility/nickname transactions ([SelectionService])
 *  - event bus ([TitleEventBus]) and snapshot publication
 *    ([SnapshotResolver]/[SnapshotPublisher])
 *
 * Config reload semantics: a successful replacement bumps the config/catalog
 * version, swaps the catalog, reloads the built-in permission grants and
 * revalidates every online player (stale titles are unequipped and
 * persisted). A failed reload keeps the previous config active.
 */
class CoreServices(
    private val configDir: Path,
    val modVersion: String,
    private val headChannelProvider: () -> HeadDisplayChannel? = { null },
) {
    private val logger: Logger = LoggerFactory.getLogger("FancyCrests/Core")

    lateinit var configService: ConfigService
        private set
    lateinit var catalogService: CatalogService
        private set
    lateinit var permissionManager: PermissionManager
        private set
    lateinit var builtInBackend: BuiltInBackend
        private set
    lateinit var eventBus: TitleEventBus
        private set
    lateinit var snapshotResolver: SnapshotResolver
        private set
    lateinit var snapshotPublisher: SnapshotPublisher
        private set
    lateinit var selectionService: SelectionService
        private set

    /** Built-in placeholder registry (P3); shared by templates and export adapters. */
    lateinit var placeholderService: dev.fancycrests.core.refresh.PlaceholderServiceImpl
        private set

    private lateinit var templateEngine: dev.fancycrests.core.refresh.TemplateEngine
    private lateinit var refreshScheduler: dev.fancycrests.core.refresh.RefreshScheduler
    private lateinit var channelManager: dev.fancycrests.core.channel.ChannelManager

    private lateinit var storage: SqliteStorage

    /** The adapter injected at [start]; used by the command layer. */
    lateinit var serverAdapter: ServerAdapter
        private set

    @Volatile
    private var started = false

    /** Set when the database could not be opened (unsupported schema, ...). */
    @Volatile
    var storageAvailable: Boolean = true
        private set

    @Volatile
    private var storageError: String? = null

    /** True when this facade started the head channel (and must stop it). */
    private var channelStartedByUs = false

    val isStarted: Boolean get() = started

    /**
     * Boots all services. Call once from ServerLifecycleEvents.SERVER_STARTED
     * with a live adapter. Loads (or creates) the config synchronously, then
     * the database; an unsupported database schema disables persistence but
     * never silently rewrites data.
     */
    fun start(adapter: ServerAdapter) {
        check(!started) { "CoreServices already started" }
        serverAdapter = adapter
        val mainExecutor = java.util.concurrent.Executor { runnable -> adapter.execute(runnable) }

        // ---- configuration -------------------------------------------
        configService = ConfigService(configDir.resolve("fancycrests.json"), mainExecutor)
        when (val result = configService.loadOrCreate()) {
            is ConfigLoadResult.Success ->
                logger.info("Loaded configuration with ${result.config.titles.size} titles")
            is ConfigLoadResult.Failure -> {
                logger.error(
                    "Configuration invalid ({}); running with safe defaults. Errors: {}",
                    configDir.resolve("fancycrests.json"),
                    result.errors.joinToString("; "),
                )
            }
        }
        val initialConfig = configService.current

        // ---- catalog & permissions -----------------------------------
        catalogService = CatalogService(initialConfig.toTitleDefinitions())
        builtInBackend = BuiltInBackend().apply { reload(initialConfig.permissions.nodes) }
        permissionManager = PermissionManager(adapter, listOf(builtInBackend))

        // ---- events & resolution -------------------------------------
        eventBus = TitleEventBus(mainExecutor)
        placeholderService = dev.fancycrests.core.refresh.PlaceholderServiceImpl()
        templateEngine = dev.fancycrests.core.refresh.TemplateEngine(placeholderService)
        snapshotResolver = SnapshotResolver(catalogService, permissionManager, { configService.current }, templateEngine)
        snapshotPublisher = SnapshotPublisher(
            snapshotResolver,
            adapter,
            { configService.current },
            headChannelProvider,
        )
        // Built-in placeholders read published immutable state only; the
        // state lookup defers to selectionService, which is created below.
        dev.fancycrests.core.refresh.registerStandardPlaceholders(
            service = placeholderService,
            engine = templateEngine,
            stateOf = { uuid -> stateOfPlayer(uuid) },
            viewOf = { uuid -> snapshotPublisher.latestView(uuid) },
            catalogOf = { catalogService },
            configOf = { configService.current },
        )

        // ---- storage --------------------------------------------------
        storage = SqliteStorage(configDir.resolve("fancycrests-data.db"), mainExecutor)
        val loaded = try {
            val data = storage.start()
            storageAvailable = true
            storageError = null
            data
        } catch (e: StorageSchemaException) {
            storageAvailable = false
            storageError = e.message
            logger.error("Persistence disabled: {}", e.message)
            dev.fancycrests.core.storage.SqliteStorage.LoadedData(emptyMap(), emptyList())
        } catch (e: Exception) {
            storageAvailable = false
            storageError = e.message ?: e.javaClass.simpleName
            logger.error("Persistence disabled: could not open database", e)
            dev.fancycrests.core.storage.SqliteStorage.LoadedData(emptyMap(), emptyList())
        }

        // ---- selection -------------------------------------------------
        selectionService = SelectionService(
            adapter = adapter,
            storage = storage,
            catalog = catalogService,
            permissions = permissionManager,
            configProvider = { configService.current },
            eventBus = eventBus,
            publisher = snapshotPublisher,
            initialStates = loaded.states,
            initialOwnership = loaded.ownership,
        )

        // Permission changes re-run eligibility for the affected player.
        permissionManager.onPermissionChanged = { uuid ->
            selectionService.revalidatePlayer(uuid, "permission-changed") {}
        }

        // Config replacement cascades: catalog swap, grants, online recheck.
        configService.addOnReplaced { newConfig -> onConfigReplaced(newConfig) }

        // ---- dynamic second line & export channels (P3) ----------------
        refreshScheduler = dev.fancycrests.core.refresh.RefreshScheduler(
            adapter = adapter,
            configProvider = { configService.current },
            resolver = snapshotResolver,
            stateOf = { uuid -> stateOfPlayer(uuid) },
            publisher = snapshotPublisher,
        )
        channelManager = dev.fancycrests.core.channel.ChannelManager(
            adapter = adapter,
            resolver = snapshotResolver,
            stateOf = { uuid -> stateOfPlayer(uuid) },
            eventBus = eventBus,
            publisher = snapshotPublisher,
        )

        // ---- head channel ---------------------------------------------
        applyHeadChannel(initialConfig, startOrStop = true)
        refreshScheduler.rebuild()
        channelManager.apply(initialConfig)

        started = true
        logger.info(
            "Core services started: {} titles, {} tracked players, {} ownership records, storage={}",
            catalogService.size(),
            selectionService.trackedPlayerCount,
            selectionService.ownershipCount,
            if (storageAvailable) "ok" else "disabled",
        )
    }

    /** Shuts services down and releases background threads. */
    fun stop() {
        if (!started) return
        try {
            channelManager.stop()
        } catch (e: Exception) {
            logger.warn("Channel manager stop failed", e)
        }
        try {
            refreshScheduler.stop()
        } catch (e: Exception) {
            logger.warn("Refresh scheduler stop failed", e)
        }
        try {
            if (channelStartedByUs) {
                headChannelProvider()?.stop()
                channelStartedByUs = false
            }
        } catch (e: Exception) {
            logger.warn("Head channel stop failed", e)
        }
        try {
            storage.stop()
        } catch (e: Exception) {
            logger.warn("Storage stop failed", e)
        }
        try {
            configService.stop()
        } catch (e: Exception) {
            logger.warn("Config service stop failed", e)
        }
        started = false
    }

    /** Hot reload entry point (also used by /fancycrests reload). */
    fun reloadAsync(callback: (ConfigLoadResult) -> Unit) {
        configService.reloadAsync(callback)
    }

    /** Login hook for the integration layer. */
    fun onPlayerJoin(uuid: UUID) {
        selectionService.handlePlayerJoin(uuid)
        channelManager.onPlayerJoin(uuid)
    }

    /** Logout hook for the integration layer. */
    fun onPlayerQuit(uuid: UUID) {
        selectionService.handlePlayerQuit(uuid)
        channelManager.onPlayerQuit(uuid)
    }

    /** Diagnostics snapshot for /fancycrests diagnose. */
    fun diagnostics(): DiagnosticsInfo = DiagnosticsInfo(
        modVersion = modVersion,
        channels = configService.current.channels,
        permissionBackends = permissionManager.backendNames(),
        catalogVersion = configService.version,
        catalogSize = catalogService.size(),
        trackedPlayers = selectionService.trackedPlayerCount,
        ownershipRecords = selectionService.ownershipCount,
        storageAvailable = storageAvailable,
        storageError = storageError,
        publishedSnapshotTargets = snapshotPublisher.trackedTargets,
    )

    // ----------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------

    private fun onConfigReplaced(newConfig: FancyCrestsConfig) {
        // Swap the catalog and remember which equipped references broke.
        val replaceResult: CatalogReplaceResult =
            catalogService.replace(newConfig.toTitleDefinitions())
        // Refresh built-in grants from the same file.
        builtInBackend.reload(newConfig.permissions.nodes)
        // Head channel toggles / render settings.
        applyHeadChannel(newConfig, startOrStop = true)
        // Dynamic refresh schedule: replaced periods are cancelled by rebuild.
        refreshScheduler.rebuild()
        // Chat / tab channel switches take effect on reload.
        channelManager.apply(newConfig)
        // Re-check every online player: stale ids (removed/renamed titles,
        // lost eligibility) are unequipped and persisted.
        selectionService.revalidateOnline("config-reloaded")
        if (replaceResult.removedIds.isNotEmpty() || replaceResult.slotChangedIds.isNotEmpty()) {
            logger.info(
                "Catalog reload invalidated {} removed and {} slot-changed titles; affected players were revalidated",
                replaceResult.removedIds.size,
                replaceResult.slotChangedIds.size,
            )
        }
    }

    /** Current selection state; safe once the selection service exists. */
    private fun stateOfPlayer(uuid: UUID): PlayerTitleState =
        if (this::selectionService.isInitialized) selectionService.stateOf(uuid)
        else PlayerTitleState(uuid, emptyList(), emptyList(), null, 0)

    private fun applyHeadChannel(config: FancyCrestsConfig, startOrStop: Boolean) {
        val channel = headChannelProvider() ?: return
        if (config.channels.headRender) {
            val settings = config.render.toRenderSettings()
            if (startOrStop && !channelStartedByUs) {
                channel.start(settings)
                channelStartedByUs = true
            } else {
                channel.updateSettings(settings)
            }
        } else if (startOrStop && channelStartedByUs) {
            channel.stop()
            channelStartedByUs = false
        }
    }

    companion object {
        /**
         * Convenience factory reading the config directory from Fabric Loader.
         * The integration layer may use this inside its initializer.
         */
        fun defaultConfigDir(): Path =
            net.fabricmc.loader.api.FabricLoader.getInstance().configDir
    }
}
