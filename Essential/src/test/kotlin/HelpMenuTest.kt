import PluginTest.Companion.clientCommand
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.pumpApp
import PluginTest.Companion.waitUntil
import arc.util.io.Writes
import essential.common.bundle.Bundle
import essential.core.ui.HelpMenu
import essential.core.ui.Sent
import essential.core.ui.UiHarness
import mindustry.gen.CopyToClipboardCallPacket
import mindustry.gen.HideMenuBuilderCallPacket
import mindustry.gen.MenuBuilderCallPacket
import mindustry.gen.MenuBuilderUpdateCallPacket
import mindustry.gen.MenuCallPacket
import mindustry.gen.TextInputCallPacket2
import mindustry.ui.Menus
import mindustry.ui.builder.MenuResult
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** /help is one menuBuilder dialog: tabs, a search field, a row per command with Run and Copy. */
class HelpMenuTest {
    companion object {
        private var done = false
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    // Most tests press tabs back to back: the gap is off unless a test sets it.
    @BeforeTest
    fun noGap() {
        HelpMenu.pressGapMs = 0
    }

    @AfterTest
    fun restoreNet() {
        UiHarness.netServer(false)
        HelpMenu.pressGapMs = 250
        HelpMenu.rowsBudget = HelpMenu.ROWS_BUDGET
    }

    private fun Sent.dialogs() = packets.filterIsInstance<MenuBuilderCallPacket>()

    private fun Sent.updates() = packets.filterIsInstance<MenuBuilderUpdateCallPacket>()

    private fun open(sent: Sent, command: String = "/help"): MenuBuilderCallPacket {
        clientCommand.handleMessage(command, sent.player)
        return sent.dialogs().last()
    }

    /** What a client would send back: [action] pressed, [query] in the search field. */
    private fun press(sent: Sent, dialog: MenuBuilderCallPacket, action: String?, query: String? = null, token: Long = dialog.token) {
        val result = MenuResult(action)
        result.token = token
        if (query != null) result.values.put("q", query)
        Menus.menuBuilderChoose(sent.player, dialog.id, result)
    }

    private fun dsl(dialog: MenuBuilderCallPacket) = dialog.builder.toString()

    private fun dsl(update: MenuBuilderUpdateCallPacket) = update.builder.toString()

    private fun bytes(dialog: MenuBuilderCallPacket): Int {
        val out = ByteArrayOutputStream()
        dialog.write(Writes(DataOutputStream(out)))
        return out.size()
    }

    @Test
    fun helpOpensTheBuilderDialogWithTabsAndSearch() {
        val sent = UiHarness.joined("owner")
        try {
            val dialog = open(sent)
            assertTrue(sent.menus.isEmpty(), "no old-style menu")
            assertEquals(Bundle()["command.page"], dialog.title)
            assertFalse(dialog.hideOnClick, "tabs and search must not close it")
            val text = dsl(dialog)
            listOf("all", "player", "admin").forEach { assertContains(text, "tab:$it") }
            assertContains(text, "search")
            assertContains(text, "run:time")
            assertContains(text, "copy:time")
            assertTrue(bytes(dialog) < 32768, "the packet fits the engine cap")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun pageNumberStillOpensTheDialog() {
        val sent = UiHarness.joined("owner")
        try {
            open(sent, "/help 2")
            open(sent, "/help 999")
            assertEquals(2, sent.dialogs().size)
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun aCommandThePlayerMayNotUseIsNotListed() {
        val owner = UiHarness.joined("owner")
        val visitor = UiHarness.joined("visitor")
        try {
            assertContains(dsl(open(owner)), "run:js")
            val shown = dsl(open(visitor))
            assertFalse("run:js" in shown)
            assertFalse("tab:admin" in shown, "a tab nobody can see anything in is not offered")
        } finally {
            leavePlayer(owner.player)
            leavePlayer(visitor.player)
        }
    }

    @Test
    fun tabSwitchAndSearchUpdateTheListInPlace() {
        val sent = UiHarness.joined("owner")
        try {
            val dialog = open(sent)
            press(sent, dialog, "tab:admin")
            val admin = dsl(sent.updates().last())
            assertEquals("list", sent.updates().last().tableId)
            assertContains(admin, "run:kill\"")
            assertFalse("run:ach" in admin, "ach is a player command")
            press(sent, dialog, "tab:player")
            val player = dsl(sent.updates().last())
            assertContains(player, "run:ach")
            assertFalse(Regex("run:kill[^a-z]").containsMatchIn(player), "kill is an admin command")

            press(sent, dialog, "search", query = "ranking")
            val found = dsl(sent.updates().last())
            assertContains(found, "run:ranking")
            assertFalse("run:time" in found)
            assertEquals(1, sent.dialogs().size, "nothing was re-shown")
            assertTrue(sent.packets.none { it is HideMenuBuilderCallPacket })
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun searchTextIsCleanedAndEscapedWhereItIsEchoed() {
        val sent = UiHarness.joined("owner")
        try {
            val dialog = open(sent)
            press(sent, dialog, "search", query = "[red]x\nzzzz" + "y".repeat(100))
            val empty = dsl(sent.updates().last())
            assertContains(empty, "[[red]xzzzz")
            assertFalse("y".repeat(41) in empty, "the query is capped")
            assertContains(empty, "y".repeat(30))
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun runExecutesANoArgCommandAsThePlayerAndClosesTheDialog() {
        val sent = UiHarness.joined("owner")
        try {
            val dialog = open(sent)
            sent.data.lastReceivedMessage = ""
            press(sent, dialog, "run:time")
            assertTrue(sent.data.lastReceivedMessage.isNotEmpty(), "/time answered the player")
            assertEquals(dialog.id, sent.last<HideMenuBuilderCallPacket>().menuId)
            // The hide sends a cancel with the same token, and the session is gone: a second press does nothing.
            press(sent, dialog, null)
            sent.data.lastReceivedMessage = ""
            press(sent, dialog, "run:time")
            assertEquals("", sent.data.lastReceivedMessage)
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun runOnACommandWithParamsAsksForInputThenExecutesIt() {
        val sent = UiHarness.joined("owner")
        try {
            val dialog = open(sent)
            press(sent, dialog, "run:help")
            val input = sent.last<TextInputCallPacket2>()
            assertEquals("/help", input.title)
            sent.data.lastReceivedMessage = ""
            Menus.textInputResult(sent.player, input.textInputId, "motd")
            assertEquals(Bundle()["command.help.motd"], sent.data.lastReceivedMessage)
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun aDestructiveCommandRunFromTheDialogAsksExactlyOnce() {
        val me = UiHarness.joined("owner")
        val (other, _) = PluginTest.newPlayer()
        try {
            val dialog = open(me)
            val before = me.menus.size
            press(me, dialog, "run:kickall")
            assertTrue(waitUntil(10000) { pumpApp(); me.menus.size > before }, "Run asks first")
            assertEquals(before + 1, me.menus.size, "Run opens exactly one menu")
            assertFalse(other.con.kicked, "nothing happens before Yes")
            Menus.menuChoose(me.player, me.last<MenuCallPacket>().menuId, 0)
            assertTrue(waitUntil(10000) { pumpApp(); other.con.kicked }, "Yes does it")
            assertEquals(before + 1, me.menus.size, "Yes asks nothing more")
        } finally {
            leavePlayer(me.player)
            leavePlayer(other)
        }
    }

    @Test
    fun copySendsTheCommandToTheClipboardAndKeepsTheDialog() {
        val sent = UiHarness.joined("owner")
        try {
            val dialog = open(sent)
            press(sent, dialog, "copy:time")
            assertEquals("/time ", sent.last<CopyToClipboardCallPacket>().text)
            assertTrue(sent.packets.none { it is HideMenuBuilderCallPacket })
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun aResultWithoutASessionOrWithTheWrongTokenOrFromAnotherPlayerIsIgnored() {
        val a = UiHarness.joined("owner")
        val b = UiHarness.joined("owner")
        try {
            val dialog = open(a)
            a.data.lastReceivedMessage = ""
            press(a, dialog, "run:time", token = dialog.token + 1)
            assertEquals("", a.data.lastReceivedMessage, "wrong token")
            // b was never shown the dialog: even with a's token it has no session.
            b.data.lastReceivedMessage = ""
            press(b, dialog, "run:time")
            assertEquals("", b.data.lastReceivedMessage, "no session")
            // b has its own dialog, with its own token: a's token does not open it.
            val own = open(b)
            assertNotEquals(dialog.token, own.token)
            press(b, own, "run:time", token = dialog.token)
            assertEquals("", b.data.lastReceivedMessage, "another player's token")
            // A name the dialog never listed is not run either.
            press(a, dialog, "run:nosuchthing")
            press(a, dialog, "tab:nosuchtab")
            assertTrue(a.updates().isEmpty())
            assertEquals("", a.data.lastReceivedMessage)
            press(a, dialog, "run:time")
            assertTrue(a.data.lastReceivedMessage.isNotEmpty(), "the real one still works")
        } finally {
            leavePlayer(a.player)
            leavePlayer(b.player)
        }
    }

    @Test
    fun aStaleCancelAfterAReShowIsIgnoredAndTheCurrentCancelEndsTheSession() {
        val sent = UiHarness.joined("owner")
        try {
            val first = open(sent)
            val second = open(sent)
            assertNotEquals(first.token, second.token, "a fresh token per show")
            press(sent, first, null) // the engine hides the old dialog on a re-show
            press(sent, second, "tab:admin")
            assertEquals(1, sent.updates().size, "the new dialog still answers")
            press(sent, second, null)
            press(sent, second, "tab:admin")
            assertEquals(1, sent.updates().size, "its own cancel ended the session")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun theSessionIsClearedWhenThePlayerLeaves() {
        val sent = UiHarness.joined("owner")
        val dialog = open(sent)
        leavePlayer(sent.player)
        sent.packets.clear()
        // The forged results carry the old token: with the session forgotten, neither may answer.
        press(sent, dialog, "run:time")
        press(sent, dialog, "copy:time")
        press(sent, dialog, "tab:admin")
        assertTrue(sent.packets.none { it is HideMenuBuilderCallPacket }, "no hide after the player left")
        assertTrue(sent.packets.none { it is CopyToClipboardCallPacket }, "no copy after the player left")
        assertTrue(sent.updates().isEmpty(), "no update after the player left")
    }

    @Test
    fun twoTabPressesInsideTheGapRebuildOnceAndKeepTheSession() {
        val sent = UiHarness.joined("owner")
        try {
            HelpMenu.pressGapMs = 60_000
            val dialog = open(sent)
            press(sent, dialog, "tab:admin")
            press(sent, dialog, "tab:player")
            press(sent, dialog, "search", query = "ranking")
            assertEquals(1, sent.updates().size, "only the first press rebuilt")
            assertContains(dsl(sent.updates().single()), "run:kill\"")
            assertTrue(sent.packets.none { it is HideMenuBuilderCallPacket }, "the dialog stays open")
            // Run is not throttled, and the session is still valid for it.
            sent.data.lastReceivedMessage = ""
            press(sent, dialog, "copy:time")
            assertEquals("/time ", sent.last<CopyToClipboardCallPacket>().text)
            press(sent, dialog, "run:time")
            assertTrue(sent.data.lastReceivedMessage.isNotEmpty(), "Run still works inside the gap")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun aTabPressedInsideTheGapIsKeptForTheNextAcceptedPress() {
        val sent = UiHarness.joined("owner")
        try {
            HelpMenu.pressGapMs = 60_000
            val dialog = open(sent)
            press(sent, dialog, "tab:admin")
            press(sent, dialog, "tab:player")
            HelpMenu.pressGapMs = 0
            press(sent, dialog, "search", query = "ranking")
            val found = dsl(sent.updates().last())
            assertContains(found, "run:ranking")
            assertFalse(Regex("run:kill[^a-z]").containsMatchIn(found), "the list is the player tab's")
            press(sent, dialog, "search", query = "kill")
            assertFalse(Regex("run:kill[^a-z]").containsMatchIn(dsl(sent.updates().last())), "kill is an admin command")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun aListOverTheBudgetIsCutAndAsksToRefineTheSearch() {
        val sent = UiHarness.joined("owner")
        try {
            HelpMenu.rowsBudget = 4_000
            val dialog = open(sent)
            val text = dsl(dialog)
            val tail = Regex("""Showing (\d+) of (\d+) commands\. Refine your search\.""").find(text)
            assertNotNull(tail, "the cut list says so")
            val (shown, total) = tail.destructured
            assertTrue(shown.toInt() in 1 until total.toInt(), "shown $shown of $total")
            assertEquals(shown.toInt(), Regex("run:").findAll(text).count(), "one Run per shown row")
            assertTrue(bytes(dialog) < 4_000 + 2_000, "${bytes(dialog)} bytes")
            // A search narrow enough to fit gets no label.
            press(sent, dialog, "search", query = "ranking")
            assertFalse("Refine your search" in dsl(sent.updates().last()))
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun theBiggestListStaysUnderThePacketCap() {
        val sent = UiHarness.joined("owner")
        try {
            sent.data.languageChoice = "uk"
            val size = bytes(open(sent))
            println("HELP-PACKET owner uk: $size bytes")
            assertTrue(size < 32768, "$size bytes")
            assertTrue(size < HelpMenu.ROWS_BUDGET + 2000, "$size bytes")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun helpWithACommandNameKeepsTheTextAnswer() {
        val sent = UiHarness.joined("owner")
        try {
            sent.packets.clear()
            sent.data.lastReceivedMessage = ""
            clientCommand.handleMessage("/help motd", sent.player)
            assertEquals(Bundle()["command.help.motd"], sent.data.lastReceivedMessage)
            clientCommand.handleMessage("/help nosuchthing", sent.player)
            assertContains(sent.data.lastReceivedMessage, Bundle()["command.help.not.exists"])
            assertTrue(sent.packets.none { it is MenuCallPacket || it is MenuBuilderCallPacket }, "a name opens no menu")
        } finally {
            leavePlayer(sent.player)
        }
    }
}
