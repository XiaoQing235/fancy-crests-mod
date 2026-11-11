package dev.fancycrests.render

import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.api.HeadDisplayChannel
import dev.fancycrests.api.RenderSettings
import java.util.UUID

/**
 * [HeadDisplayChannel] implementation backed by the packet-level render
 * pipeline (per-player virtual teams + virtual text_display labels).
 *
 * The constructor performs no runtime access; the integration layer may
 * instantiate it during mod init. [start] attaches the service to the global
 * send hook and publishes it to the module-level Fabric event forwarder
 * ([RenderServiceEvents] registers the lifecycle handlers exactly once per
 * process and forwards to the active instance — audit II-5); [stop] runs the
 * ordered teardown (labels/mounts -> virtual teams -> source restore) and
 * clears the forwarder slot. Restart after stop re-initialises from the
 * then-current state; each start creates a fresh [RenderService] as before,
 * only the Fabric registration is no longer per-instance.
 */
class HeadRenderChannel : HeadDisplayChannel {

    @Volatile
    private var service: RenderService? = null

    override fun start(settings: RenderSettings) {
        stop()
        val fresh = RenderService(settings)
        RenderPackets.attach(fresh)
        fresh.start()
        service = fresh
    }

    override fun updateSettings(settings: RenderSettings) {
        service?.updateSettings(settings)
    }

    override fun publish(snapshot: DisplaySnapshot) {
        service?.publish(snapshot)
    }

    override fun clearTarget(target: UUID) {
        service?.clearTarget(target)
    }

    override fun stop() {
        val current = service
        if (current != null) {
            service = null
            current.stop()
        }
    }

    /**
     * Read-only diagnostics snapshot of the head render channel for the
     * integration layer (`CoreServices.renderDiagnosticsProvider` →
     * `/fancycrests diagnose`): whether a service instance is attached, how
     * many connections it holds, plus every [RenderDiagnostics] counter and
     * the most recent pipeline error, all stringified.
     *
     * Deliberately declared on the implementation class only — the frozen
     * [HeadDisplayChannel] api contract stays untouched. Safe from any
     * thread; values are point-in-time.
     */
    fun snapshot(): Map<String, String> {
        val current = service
        return buildMap {
            put("running", (current != null).toString())
            put("connections", current?.connections?.size?.toString() ?: "0")
            putAll(RenderDiagnostics.snapshot())
        }
    }
}
