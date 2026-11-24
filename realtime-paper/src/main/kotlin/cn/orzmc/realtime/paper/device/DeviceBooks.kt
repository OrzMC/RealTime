package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.AnalogScale
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import java.util.UUID

/**
 * Book handling for the lecterns behind [cn.orzmc.realtime.core.device.OutputEncoding.ANALOG]
 * outputs.
 *
 * Every analog slot is a lectern holding a [AnalogScale.BOOK_PAGES]-page book.
 * The page index is the digit: Paper's `LecternBlockEntity#getRedstoneSignal()`
 * computes `floor(page / (pageCount - 1) * 14) + 1`, which collapses to
 * `page + 1` for that page count.
 *
 * The book doubles as the ownership marker. Its title embeds the first eight
 * characters of the device UUID and its author is the constant [DeviceBooks.AUTHOR],
 * so the plugin only ever pages through lecterns it installed itself: a lectern
 * that holds anything else is reported and never overwritten, and cleanup only
 * removes a lectern whose book still belongs to the retired device.
 *
 * The item never leaves this abstraction, which keeps the device managers
 * testable without a running server.
 */
interface AnalogBookFactory {
    /** Puts this plugin's book for [deviceId] in the lectern inventory. */
    fun install(
        inventory: Inventory,
        deviceId: UUID,
    )

    /** True when the lectern inventory holds this plugin's book for [deviceId]. */
    fun holdsDeviceBook(
        inventory: Inventory,
        deviceId: UUID,
    ): Boolean

    /** True when the lectern holds no book at all. */
    fun isSlotEmpty(inventory: Inventory): Boolean

    /** Removes whatever the lectern holds. */
    fun clear(inventory: Inventory)
}

object DeviceBooks : AnalogBookFactory {
    /** Author stamped on every book the plugin creates. */
    const val AUTHOR: String = "RealTime"

    /** Visible title prefix; followed by the short device UUID. */
    const val TITLE_PREFIX: String = "RealTime "

    /** Lectern inventory slot that holds the book. */
    const val SLOT: Int = 0

    private const val SHORT_ID_LENGTH = 8

    /** Title a device's lectern book carries. */
    fun title(deviceId: UUID): String = TITLE_PREFIX + deviceId.toString().take(SHORT_ID_LENGTH)

    /**
     * Creates the book for [deviceId].
     *
     * Page `n` holds `n + 1`, which makes the page count obvious when an
     * operator opens the lectern, while the content stays meaningless to
     * redstone.
     */
    fun create(deviceId: UUID): ItemStack {
        val item = ItemStack(Material.WRITTEN_BOOK)
        val meta = item.itemMeta as? BookMeta ?: return item
        meta.setTitle(title(deviceId))
        meta.setAuthor(AUTHOR)
        meta.setPages((1..AnalogScale.BOOK_PAGES).map(Int::toString))
        item.itemMeta = meta
        return item
    }

    /** True when [item] is the plugin's book for [deviceId]. */
    fun isDeviceBook(
        item: ItemStack?,
        deviceId: UUID,
    ): Boolean {
        if (item == null || item.type != Material.WRITTEN_BOOK) return false
        val meta = item.itemMeta as? BookMeta ?: return false
        return meta.title == title(deviceId) && meta.author == AUTHOR
    }

    /** True when [item] is one of the plugin's lectern books, for any device. */
    fun isAnyDeviceBook(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.WRITTEN_BOOK) return false
        val meta = item.itemMeta as? BookMeta ?: return false
        return meta.author == AUTHOR && meta.title?.startsWith(TITLE_PREFIX) == true
    }

    override fun install(
        inventory: Inventory,
        deviceId: UUID,
    ) {
        inventory.setItem(SLOT, create(deviceId))
    }

    override fun holdsDeviceBook(
        inventory: Inventory,
        deviceId: UUID,
    ): Boolean = isDeviceBook(inventory.getItem(SLOT), deviceId)

    override fun isSlotEmpty(inventory: Inventory): Boolean = inventory.getItem(SLOT) == null

    override fun clear(inventory: Inventory) {
        inventory.setItem(SLOT, null)
    }
}
