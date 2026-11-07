package essential.core.ui

import essential.common.database.data.PlayerData
import essential.core.OwnedMenus
import mindustry.gen.Player
import mindustry.gen.Playerc
import kotlin.math.absoluteValue

/** The 0-based page a typed `[page]` argument names: a sign is ignored, absent or not a number is page 1. */
fun pageArg(arg: String?): Int = (arg?.toIntOrNull()?.takeIf { it != Int.MIN_VALUE }?.absoluteValue ?: 1) - 1

/**
 * A paged owned menu: optional one-button-per-item rows, [extra] rows, a nav row and Close, re-shown on
 * the same owned id every time the page changes, so it never takes another slot.
 *
 * Without a [label] the items are only text (see [header]) and the menu carries no item buttons.
 * Nav row: [fullNav] gives `« ‹ n/m › »`, otherwise `<- n/m ->`; the middle button re-shows the page.
 *
 * [showCard] swaps the page for a card on the same id until [show] is called again, so an item can
 * open a detail view without registering a second menu.
 */
class PagedMenu<T>(
    owner: PlayerData,
    private val title: String,
    private val items: List<T>,
    private val pageSize: Int,
    startPage: Int = 0,
    private val closeLabel: String,
    private val fullNav: Boolean = false,
    private val pageLabel: (page: Int, pages: Int) -> String = { page, pages -> "$page/$pages" },
    /** Message above the buttons for the page's items; offset is the index of the first one in all items. */
    private val header: (shown: List<T>, offset: Int) -> String = { _, _ -> "" },
    private val label: ((T) -> String)? = null,
    private val extra: List<Pair<String, (Player) -> Unit>> = emptyList(),
    private val onPick: PagedMenu<T>.(Player, T) -> Unit = { _, _ -> }
) {
    val pages = maxOf(1, (items.size + pageSize - 1) / pageSize)
    var page = startPage.coerceIn(0, pages - 1)
        private set

    private var card: ((Player, Int) -> Unit)? = null
    private val id: Int = OwnedMenus.register(owner) { p, select -> handle(p, select) }

    init {
        show(owner.player)
    }

    private fun pageItems() = items.drop(page * pageSize).take(pageSize)

    fun show(p: Playerc) {
        card = null
        val shown = pageItems()
        val rows = ArrayList<Array<String>>()
        label?.let { l -> shown.forEach { rows.add(arrayOf(l(it))) } }
        extra.forEach { rows.add(arrayOf(it.first)) }
        val current = pageLabel(page + 1, pages)
        rows.add(if (fullNav) arrayOf("«", "‹", current, "›", "»") else arrayOf("<-", current, "->"))
        rows.add(arrayOf(closeLabel))
        OwnedMenus.show(p.con(), id, title, header(shown, page * pageSize), rows.toTypedArray())
    }

    /** Replaces the page with a card on this menu's id; [onChoose] gets the card's option index. */
    fun showCard(p: Playerc, cardTitle: String, message: String, rows: Array<Array<String>>, onChoose: (Player, Int) -> Unit) {
        card = onChoose
        OwnedMenus.show(p.con(), id, cardTitle, message, rows)
    }

    private fun handle(p: Player, select: Int) {
        card?.let { it(p, select); return }
        val shown = pageItems()
        val picks = if (label == null) 0 else shown.size
        when {
            select < picks -> this.onPick(p, shown[select])
            select < picks + extra.size -> extra[select - picks].second(p)
            else -> {
                val nav = select - picks - extra.size
                val target = if (fullNav) listOf(0, page - 1, page, page + 1, pages - 1).getOrNull(nav)
                else listOf(page - 1, page, page + 1).getOrNull(nav)
                if (target != null) {
                    page = target.coerceIn(0, pages - 1)
                    show(p)
                }
            }
        }
    }
}
