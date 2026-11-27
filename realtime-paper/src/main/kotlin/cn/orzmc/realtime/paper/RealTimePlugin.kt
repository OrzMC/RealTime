package cn.orzmc.realtime.paper

import cn.orzmc.realtime.core.device.LayoutAxis
import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.command.CommandTarget
import cn.orzmc.realtime.paper.command.PlayerPlacement
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
import cn.orzmc.realtime.paper.update.GitHubUpdateSource
import cn.orzmc.realtime.paper.update.HangarUpdateSource
import cn.orzmc.realtime.paper.update.ModrinthUpdateSource
import cn.orzmc.realtime.paper.update.PaperUpdateScheduler
import cn.orzmc.realtime.paper.update.UpdateConfiguration
import cn.orzmc.realtime.paper.update.UpdateConfigurationLoader
import cn.orzmc.realtime.paper.update.UpdateHttp
import cn.orzmc.realtime.paper.update.UpdateInstaller
import cn.orzmc.realtime.paper.update.UpdateManifestSource
import cn.orzmc.realtime.paper.update.UpdateNotifier
import cn.orzmc.realtime.paper.update.UpdateService
import cn.orzmc.realtime.paper.update.UpdateSourcePreference
import cn.orzmc.realtime.paper.update.UpdateStateStore
import cn.orzmc.realtime.paper.update.UpdateStatus
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

class RealTimePlugin :
    JavaPlugin(),
    TabExecutor {
    private val configurationLoader = RealTimeConfigurationLoader()
    private val redstoneConfigurationLoader = RedstoneConfigurationLoader()
    private val updateConfigurationLoader = UpdateConfigurationLoader()
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
    private var updateService: UpdateService? = null
    private var updateNotifier: UpdateNotifier? = null
    private var updateHttp: UpdateHttp? = null
    private var updateHttpKey: Pair<Set<String>, Boolean>? = null

    override fun onEnable() {
        saveDefaultConfig()
        ensureBundledResource("messages.yml")
        ensureBundledResource("modules/command.yml")
        ensureBundledResource("modules/redstone.yml")
        ensureBundledResource("modules/updates.yml")
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
        logModuleState(checkNotNull(runtimeState))
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
        updateService = createUpdateService()
        commandHandler =
            RealTimeCommandHandler(
                pluginVersion = description.version,
                currentState = { checkNotNull(runtimeState) },
                reload = ::reloadRuntimeState,
                deviceAdministration = checkNotNull(deviceAdministration),
                deviceDiagnostics = redstoneDeviceManager,
                devicePlacement = redstoneDeviceManager,
                skippedLegacyDevices = { deviceRepository.skippedLegacyEntries },
                cleanupAdministration = cleanupAdministration,
                deviceRetirement = deviceRetirementService,
                updateAdministration = updateService,
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

        updateService?.start()
        updateNotifier?.let { notifier -> server.pluginManager.registerEvents(notifier, this) }

        if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion =
                PlaceholderApiBridge.register(
                    pluginVersion = description.version,
                    stateProvider = { checkNotNull(runtimeState) },
                    updateStatus = { updateService?.status() ?: UpdateStatus.Idle },
                )
            if (placeholderExpansion == null) {
                logger.warning("PlaceholderAPI is present, but the RealTime expansion could not be registered.")
            } else {
                logger.info("PlaceholderAPI expansion registered: %realtime_time%, %realtime_update_available%.")
            }
        }

        val configuration = checkNotNull(runtimeState).configuration
        logger.info(
            "RealTime ${description.version} enabled " +
                "(timezone=${configuration.zoneId.id}, locale=${configuration.locale.toLanguageTag()}).",
        )
    }

    override fun onDisable() {
        updateService?.stop()
        updateService = null
        updateNotifier = null
        updateHttp = null
        updateHttpKey = null
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

    /**
     * Builds the updater. Everything here is optional on purpose: a server whose
     * `bukkit.yml` disables the update folder, or an operator who turned updates off,
     * simply gets a service that reports why it is idle.
     */
    private fun createUpdateService(): UpdateService {
        val notifier =
            UpdateNotifier(
                status = { updateService?.status() ?: UpdateStatus.Idle },
                pluginVersion = description.version,
                stateProvider = { runtimeState },
            )
        updateNotifier = notifier
        val service =
            UpdateService(
                configuration = { checkNotNull(runtimeState).updateConfiguration },
                currentVersion = description.version,
                sources = ::updateSources,
                installer = UpdateInstaller(httpFor(checkNotNull(runtimeState).updateConfiguration), logger),
                updateFolder = ::updateFolderPath,
                stateStore = UpdateStateStore(dataFolder) { message -> logger.warning("RealTime update state: $message") },
                scheduler = PaperUpdateScheduler(plugin = this, asyncScheduler = server.asyncScheduler),
                logger = logger,
                onAvailable = { notifier.reset() },
            )
        return service
    }

    private fun updateSources(configuration: UpdateConfiguration): List<UpdateManifestSource> {
        val http = httpFor(configuration)
        val hangar = HangarUpdateSource(http, apiBase = configuration.apiBaseOverride ?: HangarUpdateSource.DEFAULT_API)
        val github = GitHubUpdateSource(http, apiBase = configuration.apiBaseOverride ?: GitHubUpdateSource.DEFAULT_API)
        val modrinth = ModrinthUpdateSource(http, apiBase = configuration.apiBaseOverride ?: ModrinthUpdateSource.DEFAULT_API)
        return when (configuration.source) {
            UpdateSourcePreference.AUTO -> listOf(hangar, github)
            UpdateSourcePreference.HANGAR -> listOf(hangar)
            UpdateSourcePreference.GITHUB -> listOf(github)
            UpdateSourcePreference.MODRINTH -> listOf(modrinth)
        }
    }

    /** Caches the HTTP client per allowlist so repeated checks do not rebuild it. */
    private fun httpFor(configuration: UpdateConfiguration): UpdateHttp {
        val key = configuration.allowedHostSet to configuration.allowInsecureHttp
        val cached = updateHttp
        if (cached != null && updateHttpKey == key) return cached
        val created =
            UpdateHttp(
                allowedHosts = configuration.allowedHostSet,
                allowInsecureHttp = configuration.allowInsecureHttp,
                userAgent = "RealTime/${description.version} (+https://github.com/OrzMC/RealTime)",
            )
        updateHttp = created
        updateHttpKey = key
        return created
    }

    /** `bukkit.yml -> settings.update-folder`; an empty value disables the folder. */
    private fun updateFolderPath(): Path? {
        val name = server.updateFolder
        if (name.isBlank()) return null
        return server.updateFolderFile.toPath()
    }

    private fun createRuntimeState(): RuntimeState {
        val configuration = configurationLoader.load(dataFolder)
        val redstoneConfiguration = redstoneConfigurationLoader.load(dataFolder)
        val updateConfiguration = updateConfigurationLoader.load(dataFolder)
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
            updateConfiguration = updateConfiguration,
            devices = deviceStore.devices,
            cleanupJobs = deviceStore.cleanupJobs,
        )
    }

    private fun reloadRuntimeState(): Result<RuntimeState> =
        synchronized(runtimeStateLock) {
            runCatching {
                val candidate = createRuntimeState()
                runtimeState = candidate
                logModuleState(candidate)
                redstoneDeviceManager?.requestRefresh()
                updateService?.reconfigure()
                candidate
            }.onFailure { exception ->
                logger.warning(
                    "RealTime configuration reload rejected; previous configuration remains active: " +
                        exception.message,
                )
            }
        }

    /**
     * States the two settings that decide whether anything is written to the
     * world, once per start/reload, so an operator never has to deduce it from
     * "the command succeeded but nothing appeared".
     */
    private fun logModuleState(state: RuntimeState) {
        val redstone = state.redstoneConfiguration
        if (redstone.enabled) {
            logger.info("RealTime redstone module enabled: device commands write blocks.")
        } else {
            logger.warning(
                "RealTime redstone module DISABLED: no blocks will be written " +
                    "(modules/redstone.yml -> enabled: true, then /rtime reload).",
            )
        }
        if (redstone.limits.allowedAreas.isEmpty()) {
            logger.info(
                "RealTime device limits: limits.allowed-areas is empty, so devices may be created anywhere. " +
                    "Add areas to restrict placement to a region.",
            )
        } else {
            logger.info("RealTime device limits: ${redstone.limits.allowedAreas.size} allowed area(s) configured.")
        }
    }

    private fun ensureBundledResource(path: String) {
        val target = File(dataFolder, path)
        if (target.isFile) return
        target.parentFile?.mkdirs()
        saveResource(path, false)
    }

    private companion object {
        /** Reach used for the coordinate-free forms, in blocks. */
        const val PLACEMENT_REACH = 6
    }

    private inner class BukkitCommandTarget(
        private val sender: CommandSender,
    ) : CommandTarget {
        override val actorName: String = if (sender is Player) sender.name else "console"

        /**
         * Aims of the executing player: the targeted block when one is within
         * reach, otherwise the block the player stands on.
         */
        override val placement: PlayerPlacement? =
            (sender as? Player)?.let { player ->
                val world = player.world
                val aimed = player.getTargetBlockExact(PLACEMENT_REACH)?.location
                val standing = player.location
                val base = aimed ?: standing.clone().subtract(0.0, 1.0, 0.0)
                PlayerPlacement(
                    worldName = world.name,
                    x = base.blockX,
                    y = base.blockY,
                    z = base.blockZ,
                    axis =
                        when (player.facing) {
                            org.bukkit.block.BlockFace.NORTH -> LayoutAxis.Z_MINUS
                            org.bukkit.block.BlockFace.SOUTH -> LayoutAxis.Z_PLUS
                            org.bukkit.block.BlockFace.EAST -> LayoutAxis.X_PLUS
                            org.bukkit.block.BlockFace.WEST -> LayoutAxis.X_MINUS
                            else -> LayoutAxis.X_PLUS
                        },
                    // The lectern opens towards whoever placed it, so a slot's
                    // direction is obvious from the spot where it was built.
                    lecternFacing = player.facing.oppositeFace,
                )
            }

        override val actorId: UUID? = (sender as? Player)?.uniqueId

        override fun hasPermission(permission: String): Boolean = sender.hasPermission(permission)

        override fun sendMessage(message: String) {
            sender.sendMessage(message)
        }

        /**
         * Async answers (the updater) are moved back to a thread that may touch this
         * sender: the player's own region under Folia, the global region for the
         * console. A retired player simply receives nothing.
         */
        override fun runOnOwner(action: () -> Unit) {
            val player = sender as? Player
            if (player == null) {
                server.globalRegionScheduler.run(this@RealTimePlugin, java.util.function.Consumer { action() })
                return
            }
            player.scheduler.run(this@RealTimePlugin, java.util.function.Consumer { action() }, Runnable { })
        }
    }
}
