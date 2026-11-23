package cn.orzmc.realtime.paper

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.plugin.java.JavaPlugin

class RealTimePlugin :
    JavaPlugin(),
    TabExecutor {
    override fun onEnable() {
        saveDefaultConfig()
        getCommand("rtime")?.setExecutor(this)
        getCommand("rtime")?.tabCompleter = this
        logger.info("RealTime ${description.version} enabled. Feature implementation is in progress.")
    }

    override fun onDisable() {
        logger.info("RealTime disabled.")
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.isNotEmpty()) {
            sender.sendMessage("Usage: /rtime")
            return true
        }

        sender.sendMessage("RealTime ${description.version} is enabled.")
        sender.sendMessage("Real-world time service is not implemented yet.")
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = emptyList()
}
