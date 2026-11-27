package cn.orzmc.realtime.paper.update

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Runs the updater on Paper's async scheduler, which is the Folia-safe place for
 * blocking work: it owns no region, so it can do network and file I/O while
 * remaining unable to touch world state.
 */
class PaperUpdateScheduler(
    private val plugin: Plugin,
    private val asyncScheduler: AsyncScheduler,
) : UpdateScheduler {
    override fun submit(action: () -> Unit) {
        asyncScheduler.runNow(plugin, Consumer { action() })
    }

    override fun repeat(
        initialDelay: Duration,
        period: Duration,
        action: () -> Unit,
    ): AutoCloseable {
        val task =
            asyncScheduler.runAtFixedRate(
                plugin,
                Consumer { action() },
                initialDelay.toMillis(),
                period.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        return AutoCloseable { task.cancel() }
    }
}
