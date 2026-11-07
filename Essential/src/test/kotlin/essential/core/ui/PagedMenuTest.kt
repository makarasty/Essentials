package essential.core.ui

import PluginTest.Companion.leavePlayer
import mindustry.gen.MenuCallPacket
import mindustry.ui.Menus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PagedMenuTest {
    @BeforeTest
    fun setup() = UiHarness.load()

    @AfterTest
    fun restoreNet() = UiHarness.netServer(false)

    private fun menu(sent: Sent, items: List<Int>, start: Int = 0, picked: MutableList<Int> = ArrayList(), full: Boolean = true) =
        PagedMenu(
            sent.data, "t", items, 8, start, closeLabel = "close", fullNav = full,
            header = { shown, offset -> "$offset:${shown.size}" }, label = { "item $it" }
        ) { _, item -> picked.add(item) }

    private fun MenuCallPacket.nav() = options.first { it.size == if (options.any { r -> r.size == 5 }) 5 else 3 }

    @Test
    fun emptyListIsOnePageWithNoItemRows() {
        val sent = UiHarness.joined("owner")
        try {
            val m = menu(sent, emptyList())
            assertEquals(1, m.pages)
            val shown = sent.last<MenuCallPacket>()
            assertEquals(listOf(listOf("«", "‹", "1/1", "›", "»"), listOf("close")), shown.options.map { it.toList() })
            // Every nav button on a single page stays on it.
            for (i in 0..4) {
                Menus.menuChoose(sent.player, shown.menuId, i)
                assertEquals("1/1", sent.last<MenuCallPacket>().nav()[2])
            }
            assertEquals(0, m.page)
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun lastPartialPageAndNavIndices() {
        val sent = UiHarness.joined("owner")
        try {
            menu(sent, (0 until 19).toList())
            val first = sent.last<MenuCallPacket>()
            assertEquals("0:8", first.message)
            assertEquals(8, first.options.count { it.size == 1 && it[0].startsWith("item ") })
            val id = first.menuId

            fun press(index: Int) = Menus.menuChoose(sent.player, id, index).let { sent.last<MenuCallPacket>() }
            assertEquals("8:8", press(8 + 3).message, "next")
            val last = press(8 + 4)
            assertEquals("16:3", last.message, "last page holds the 3 left over")
            assertEquals(3, last.options.count { it.size == 1 && it[0].startsWith("item ") })
            assertEquals("3/3", last.nav()[2])
            assertEquals("3/3", press(3 + 3).nav()[2], "next past the end stays")
            assertEquals("2/3", press(3 + 1).nav()[2], "prev")
            assertEquals("1/3", press(8 + 0).nav()[2], "first")
            assertEquals("1/3", press(8 + 1).nav()[2], "prev before the start stays")
            assertEquals("1/3", press(8 + 2).nav()[2], "the page button re-shows it")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun startPageIsClamped() {
        val sent = UiHarness.joined("owner")
        try {
            assertEquals(2, menu(sent, (0 until 19).toList(), start = 99).page)
            assertEquals(0, menu(sent, (0 until 19).toList(), start = -5).page)
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun itemButtonPicksThatItemAndCloseDoesNothing() {
        val sent = UiHarness.joined("owner")
        try {
            val picked = ArrayList<Int>()
            menu(sent, (100 until 119).toList(), picked = picked)
            val id = sent.last<MenuCallPacket>().menuId
            val before = sent.menus.size
            Menus.menuChoose(sent.player, id, 2)
            assertEquals(listOf(102), picked)
            Menus.menuChoose(sent.player, id, 8 + 5) // close
            Menus.menuChoose(sent.player, id, 8 + 6) // past every row
            assertEquals(listOf(102), picked)
            assertEquals(before, sent.menus.size, "close does not re-show")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun textOnlyMenuUsesTheShortNavRow() {
        val sent = UiHarness.joined("owner")
        try {
            val m = PagedMenu(sent.data, "t", (0 until 20).toList(), 8, closeLabel = "close", header = { s, _ -> s.joinToString() })
            val shown = sent.last<MenuCallPacket>()
            assertEquals(listOf(listOf("<-", "1/3", "->"), listOf("close")), shown.options.map { it.toList() })
            Menus.menuChoose(sent.player, shown.menuId, 2) // ->
            assertEquals("8, 9, 10, 11, 12, 13, 14, 15", sent.last<MenuCallPacket>().message)
            assertEquals(1, m.page)
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun cardReplacesThePageUntilShownAgain() {
        val sent = UiHarness.joined("owner")
        try {
            val answers = ArrayList<Int>()
            PagedMenu(sent.data, "t", listOf(1, 2), 8, closeLabel = "close", label = { "i$it" }) { p, _ ->
                showCard(p, "card", "body", arrayOf(arrayOf("a", "b"))) { q, s -> if (s == 1) show(q) else answers.add(s) }
            }
            val id = sent.last<MenuCallPacket>().menuId
            Menus.menuChoose(sent.player, id, 0)
            assertEquals("card", sent.last<MenuCallPacket>().title)
            Menus.menuChoose(sent.player, id, 0)
            assertEquals(listOf(0), answers)
            Menus.menuChoose(sent.player, id, 1)
            assertEquals("t", sent.last<MenuCallPacket>().title)
            assertEquals(4, sent.last<MenuCallPacket>().options.size, "two items, nav, close")
        } finally {
            leavePlayer(sent.player)
        }
    }
}
