package cn.orzmc.realtime.paper

import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.command.CommandTarget
import cn.orzmc.realtime.paper.command.RealTimeCommandHandler
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.config.RealTimeConfigurationLoader
import cn.orzmc.realtime.paper.device.RedstoneConfigurationLoader
import cn.orzmc.realtime.paper.device.RedstoneDeviceManager
import cn.orzmc.realtime.paper.device.YamlDeviceRepository
import cn.orzmc.realtime.paper.message.MessageCatalog
import cn.orzmc.realtime.paper.scheduler.PaperFoliaScheduler
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
    private val redstoneConfigurationLoader = RedstoneConfigurationLoader()

    @Volatile
    private var runtimeState: RuntimeState? = null

    private lateinit var commandHandler: RealTimeCommandHandler
    private var schedulerAdapter: PaperFoliaScheduler? = null
    private var redstoneDeviceManager: RedstoneDeviceManager? = null

    override fun onEnable() {
        saveDefaultConfig()
        ensureBundledResource("messages.yml")
        ensureBundledResource("modules/command.yml")
        ensureBundledResource("modules/redstone.yml")
        ensureBundledResource("data/devices.yml")

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

        schedulerAdapter =
            PaperFoliaScheduler(
                plugin = this,
                globalScheduler = server.globalRegionScheduler,
                regionScheduler = server.regionScheduler,
            )
        schedulerAdapter?.runGlobal {
            logger.info("RealTime global scheduler adapter ready.")
        }
        redstoneDeviceManager =
            RedstoneDeviceManager(
                server = server,
                scheduler = checkNotNull(schedulerAdapter),
                stateProvider = { checkNotNull(runtimeState) },
                logger = logger,
            ).also { it.start(this) }

        val configuration = checkNotNull(runtimeState).configuration
        logger.info(
            "RealTime ${description.version} enabled " +
                "(timezone=${configuration.zoneId.id}, locale=${configuration.locale.toLanguageTag()}).",
        )
    }

    override fun onDisable() {
        redstoneDeviceManager = null
        schedulerAdapter?.cancelAll()
        schedulerAdapter = null
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
        val redstoneConfiguration = redstoneConfigurationLoader.load(dataFolder)
        val devices = YamlDeviceRepository(dataFolder).load()
        return RuntimeState(
            configuration = configuration,
            timeService =
                ClockRealTimeService(
                    clock = Clock.systemUTC(),
                    zoneId = configuration.zoneId,
                    locale = configuration.locale,
                ),
            messages = MessageCatalog(configuration.messages),
            redstoneConfiguration = redstoneConfiguration,
            devices = devices,
        )
    }

    private fun reloadRuntimeState(): Result<RuntimeState> =
        runCatching {
            val candidate = createRuntimeState()
            runtimeState = candidate
            redstoneDeviceManager?.requestRefresh()
            candidate
        }.onFailure { exception ->
            logger.warning(
                "RealTime configuration reload rejected; previous configuration remains active: " +
                    exception.message,
            )
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
