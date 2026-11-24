package cn.orzmc.realtime.core.device

/**
 * Digit ↔ lectern page ↔ comparator level mapping for [OutputEncoding.ANALOG].
 *
 * Paper 26.3 `LecternBlockEntity#getRedstoneSignal()` is:
 *
 * ```
 * float f = pageCount > 1 ? page / (pageCount - 1) : 1.0F;
 * return floor(f * 14.0F) + (hasBook ? 1 : 0);
 * ```
 *
 * A [BOOK_PAGES]-page book makes `pageCount - 1 == 14`, so the formula collapses
 * to `page + 1`: every digit maps onto a distinct comparator level with no
 * rounding loss.
 *
 * | Digit | Page | Comparator level |
 * |---|---|---|
 * | 0 | 0 | 1 |
 * | 1 | 1 | 2 |
 * | … | … | … |
 * | 9 | 9 | 10 |
 *
 * Levels 11–15 (pages 10–14) are reserved so the mapping keeps its 1:1 shape and
 * downstream circuits can distinguish "clock digit" from "out of range". A
 * lectern without a book emits level 0, which is [NO_BOOK].
 */
object AnalogScale {
    /** Pages in the book the plugin installs into every analog output lectern. */
    const val BOOK_PAGES = 15

    /** Highest decimal digit an analog output can carry. */
    const val MAX_DIGIT = 9

    /** Slot value that means "no book installed"; reads as comparator level 0. */
    const val NO_BOOK = -1

    /** Highest comparator level an analog output can produce. */
    const val MAX_LEVEL = 15

    init {
        require(BOOK_PAGES - 1 == MAX_LEVEL - 1) {
            "A $BOOK_PAGES-page book must span exactly $MAX_LEVEL comparator levels"
        }
    }

    /** Page index that encodes [digit]; see the table above. */
    fun pageForDigit(digit: Int): Int {
        require(digit in 0..MAX_DIGIT) { "digit must be between 0 and $MAX_DIGIT" }
        return digit
    }

    /** Comparator level a lectern emits for [digit]. */
    fun levelForDigit(digit: Int): Int = pageForDigit(digit) + 1

    /** Comparator level of a lectern holding [pageCount] pages at [page]. */
    fun level(
        page: Int,
        pageCount: Int,
    ): Int {
        require(pageCount >= 0) { "pageCount must not be negative" }
        require(page in 0 until maxOf(pageCount, 1)) { "page must be between 0 and ${maxOf(pageCount, 1) - 1}" }
        if (pageCount <= 1) return MAX_LEVEL
        return (page.toDouble() / (pageCount - 1) * 14.0).toInt() + 1
    }

    /** True when [value] is a digit this scale can encode. */
    fun isDigit(value: Int): Boolean = value in 0..MAX_DIGIT
}
