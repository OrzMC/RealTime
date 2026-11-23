package cn.orzmc.realtime.paper

import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.command.CommandTarget
import cn.orzmc.realtime.paper.command.RealTimeCommandHandler
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.config.RealTimeConfigurationLoader
import cn.orzmc.realtime.paper.message.MessageCatalog
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.time.Clock

class RealTimePlugin :
    JavaPlugin(),
    TabExecutor {
    private val configurationLoader = RealTimeConfigurationLoader()

    @Volatile
    private var runtimeState: RuntimeState? = null

    private lateinit var commandHandler: RealTimeCommandHandler

    override fun onEnable() {
        saveDefaultConfig()
        ensureBundledResource("messages.yml")
        ensureBundledResource("modules/command.yml")

        try {
            runtimeState = createRuntimeState()
        } catch (exception: Exception) {
            logger.severe("RealTime configuration error: ${exception.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        commandHandler =
            RealTimeCommandHandler(
                pluginVersion = description.version,
                currentState = { checkNotNull(runtimeState) },
                reload = ::reloadRuntimeState,
            )

        val command = getCommand("rtime")
        if (command == null) {
            logger.severe("Command 'rtime' is missing from plugin.yml")
            server.pluginManager.disablePlugin(this)
            return
        }
        command.setExecutor(this)
        command.tabCompleter = this

        val configuration = checkNotNull(runtimeState).configuration
        logger.info(
            "RealTime ${description.version} enabled (timezone=${configuration.zoneId.id}, locale=${configuration.locale.toLanguageTag()}).",
        )
    }

    override fun onDisable() {
        runtimeState = null
        logger.info("RealTime disabled.")
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean = commandHandler.execute(BukkitCommandTarget(sender), args)

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = commandHandler.complete(BukkitCommandTarget(sender), args)

    private fun createRuntimeState(): RuntimeState {
        val configuration = configurationLoader.load(dataFolder)
        return RuntimeState(
            configuration = configuration,
            timeService =
                ClockRealTimeService(
                    clock = Clock.systemUTC(),
                    zoneId = configuration.zoneId,
                    locale = configuration.locale,
                ),
            messages = MessageCatalog(configuration.messages),
        )
    }

    private fun reloadRuntimeState(): Result<RuntimeState> =
        runCatching {
            val candidate = createRuntimeState()
            runtimeState = candidate
            candidate
        }.onFailure { exception ->
            logger.warning("RealTime configuration reload rejected; previous configuration remains active: ${exception.message}")
        }

    private fun ensureBundledResource(path: String) {
        val target = File(dataFolder, path)
        if (target.isFile) return
        target.parentFile?.mkdirs()
        saveResource(path, false)
    }

    private class BukkitCommandTarget(
        private val sender: CommandSender,
    ) : CommandTarget {
        override fun hasPermission(permission: String): Boolean = sender.hasPermission(permission)

        override fun sendMessage(message: String) {
            sender.sendMessage(message)
        }
    }
}
