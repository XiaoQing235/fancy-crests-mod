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
 * send hook and registers the lifecycle handlers; [stop] runs the ordered
 * teardown (labels/mounts -> virtual teams -> source restore). Restart after
 * stop re-initialises from the then-current state.
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
}
