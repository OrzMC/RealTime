package cn.orzmc.realtime.paper

import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.command.CommandTarget
import cn.orzmc.realtime.paper.command.RealTimeCommandHandler
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.config.RealTimeConfigurationLoader
import cn.orzmc.realtime.paper.device.DeviceAdministrationService
import cn.orzmc.realtime.paper.device.DeviceCleanupAdministrationService
import cn.orzmc.realtime.paper.device.DeviceCleanupManager
import cn.orzmc.realtime.paper.device.DeviceRetirementService
import cn.orzmc.realtime.paper.device.DeviceStoreSnapshot
import cn.orzmc.realtime.paper.device.RedstoneConfigurationLoader
import cn.orzmc.realtime.paper.device.RedstoneDeviceManager
import cn.orzmc.realtime.paper.device.YamlDeviceRepository
import cn.orzmc.realtime.paper.message.MessageCatalog
import cn.orzmc.realtime.paper.placeholder.PlaceholderApiBridge
import cn.orzmc.realtime.paper.scheduler.PaperFoliaScheduler
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.time.Clock
import java.util.UUID

class RealTimePlugin :
    JavaPlugin(),
    TabExecutor {
    private val configurationLoader = RealTimeConfigurationLoader()
    private val redstoneConfigurationLoader = RedstoneConfigurationLoader()
    private val runtimeStateLock = Any()

    @Volatile
    private var runtimeState: RuntimeState? = null

    private lateinit var commandHandler: RealTimeCommandHandler
    private lateinit var deviceRepository: YamlDeviceRepository
    private var deviceAdministration: DeviceAdministrationService? = null
    private var deviceRetirementService: DeviceRetirementService? = null
    private var deviceCleanupManager: DeviceCleanupManager? = null
    private var cleanupAdministration: DeviceCleanupAdministrationService? = null
    private var schedulerAdapter: PaperFoliaScheduler? = null
    private var redstoneDeviceManager: RedstoneDeviceManager? = null
    private var placeholderExpansion: Any? = null

    override fun onEnable() {
        saveDefaultConfig()
        ensureBundledResource("messages.yml")
        ensureBundledResource("modules/command.yml")
        ensureBundledResource("modules/redstone.yml")
        ensureBundledResource("data/devices.yml")
        deviceRepository = YamlDeviceRepository(dataFolder)

        try {
            runtimeState = createRuntimeState()
        } catch (exception: Exception) {
            logger.severe("RealTime configuration error: ${exception.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

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

        deviceCleanupManager =
            DeviceCleanupManager(
                server = server,
                scheduler = checkNotNull(schedulerAdapter),
                stateLock = runtimeStateLock,
                stateProvider = { checkNotNull(runtimeState) },
                persistStore = deviceRepository::save,
                replaceState = { runtimeState = it },
                logger = logger,
            ).also { it.start(this) }

        deviceRetirementService =
            DeviceRetirementService(
                stateLock = runtimeStateLock,
                stateProvider = { checkNotNull(runtimeState) },
                persistStore = deviceRepository::save,
                replaceState = { runtimeState = it },
                requestCleanup = { deviceCleanupManager?.requestRefresh() },
            )

        cleanupAdministration =
            DeviceCleanupAdministrationService(
                stateLock = runtimeStateLock,
                stateProvider = { checkNotNull(runtimeState) },
                persistStore = deviceRepository::save,
                replaceState = { runtimeState = it },
                requestCleanup = { deviceCleanupManager?.requestRefresh() },
            )

        deviceAdministration =
            DeviceAdministrationService(
                stateLock = runtimeStateLock,
                stateProvider = { checkNotNull(runtimeState) },
                persistDevices = { devices ->
                    val current = checkNotNull(runtimeState)
                    deviceRepository.save(DeviceStoreSnapshot(devices, current.cleanupJobs))
                },
                replaceState = { runtimeState = it },
                requestRefresh = { redstoneDeviceManager?.requestRefresh() },
            )
        commandHandler =
            RealTimeCommandHandler(
                pluginVersion = description.version,
                currentState = { checkNotNull(runtimeState) },
                reload = ::reloadRuntimeState,
                deviceAdministration = checkNotNull(deviceAdministration),
                deviceDiagnostics = redstoneDeviceManager,
                cleanupAdministration = cleanupAdministration,
                deviceRetirement = deviceRetirementService,
                logger = logger,
            )

        val command = getCommand("rtime")
        if (command == null) {
            logger.severe("Command 'rtime' is missing from plugin.yml")
            server.pluginManager.disablePlugin(this)
            return
        }
        command.setExecutor(this)
        command.tabCompleter = this

        if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion =
                PlaceholderApiBridge.register(description.version) {
                    checkNotNull(runtimeState)
                }
            if (placeholderExpansion == null) {
                logger.warning("PlaceholderAPI is present, but the RealTime expansion could not be registered.")
            } else {
                logger.info("PlaceholderAPI expansion registered: %realtime_time%.")
            }
        }

        val configuration = checkNotNull(runtimeState).configuration
        logger.info(
            "RealTime ${description.version} enabled " +
                "(timezone=${configuration.zoneId.id}, locale=${configuration.locale.toLanguageTag()}).",
        )
    }

    override fun onDisable() {
        placeholderExpansion?.let { PlaceholderApiBridge.unregister(it) }
        placeholderExpansion = null
        deviceAdministration = null
        deviceRetirementService = null
        deviceCleanupManager = null
        cleanupAdministration = null
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
        val deviceStore = deviceRepository.loadStore()
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
            devices = deviceStore.devices,
            cleanupJobs = deviceStore.cleanupJobs,
        )
    }

    private fun reloadRuntimeState(): Result<RuntimeState> =
        synchronized(runtimeStateLock) {
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
        override val actorName: String = if (sender is Player) sender.name else "console"

        override val actorId: UUID? = (sender as? Player)?.uniqueId

        override fun hasPermission(permission: String): Boolean = sender.hasPermission(permission)

        override fun sendMessage(message: String) {
            sender.sendMessage(message)
        }
    }
}
