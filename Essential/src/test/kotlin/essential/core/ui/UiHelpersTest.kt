package essential.core.ui

import PluginTest.Companion.leavePlayer
import arc.util.Log
import essential.common.bundle.Bundle
import essential.common.permission.Permission
import mindustry.gen.MenuCallPacket
import mindustry.gen.TextInputCallPacket2
import mindustry.net.Administration
import mindustry.ui.Menus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiHelpersTest {
    @BeforeTest
    fun setup() = UiHarness.load()

    @AfterTest
    fun restoreNet() = UiHarness.netServer(false)

    @Test
    fun confirmFiresOnYesOnly() {
        val sent = UiHarness.joined("owner")
        try {
            var yes = 0
            fun ask(): Int {
                Confirm.ask(sent.data, "t", "sure?") { yes++ }
                return sent.last<MenuCallPacket>().menuId
            }
            val bundle = Bundle()
            assertEquals(listOf(bundle["command.menu.yes"], bundle["command.menu.cancel"]), run { ask(); sent.last<MenuCallPacket>().options.single().toList() })
            Menus.menuChoose(sent.player, ask(), 1)
            assertEquals(0, yes, "Cancel")
            Menus.menuChoose(sent.player, ask(), -1)
            assertEquals(0, yes, "dismissed")
            Menus.menuChoose(sent.player, ask(), 0)
            assertEquals(1, yes, "Yes")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun confirmYesTwiceRunsOnYesOnce() {
        val sent = UiHarness.joined("owner")
        try {
            var yes = 0
            Confirm.ask(sent.data, "t", "sure?") { yes++ }
            val id = sent.last<MenuCallPacket>().menuId
            Menus.menuChoose(sent.player, id, 0)
            Menus.menuChoose(sent.player, id, 0)
            assertEquals(1, yes)
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun choicePickerPassesTheValueAndCloseDoesNothing() {
        val sent = UiHarness.joined("owner")
        try {
            val got = ArrayList<String>()
            ChoicePicker.open(sent.data, "t", "m", listOf("A" to "a", "B" to "b")) { _, v -> got.add(v) }
            val menu = sent.last<MenuCallPacket>()
            assertEquals(listOf("A", "B", Bundle()["command.menu.close"]), menu.options.map { it.single() })
            Menus.menuChoose(sent.player, menu.menuId, 1)
            assertEquals(listOf("b"), got)
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun playerPickerListsFilteredPlayersAndChecksThePickIsStillOnline() {
        val sent = UiHarness.joined("owner")
        val other = UiHarness.joined("owner")
        try {
            val picked = ArrayList<String>()
            PlayerPicker.open(sent.data, "t", filter = { it.uuid != sent.data.uuid }) { picked.add(it.uuid) }
            val menu = sent.last<MenuCallPacket>()
            val buttons = menu.options.takeWhile { it.size == 1 }.map { it.single() }
            assertTrue(buttons.contains(other.player.plainName()), "others are listed")
            assertFalse(buttons.contains(sent.player.plainName()), "the filter drops the caller")
            val index = buttons.indexOf(other.player.plainName())
            leavePlayer(other.player)
            sent.data.lastReceivedMessage = ""
            Menus.menuChoose(sent.player, menu.menuId, index)
            assertTrue(picked.isEmpty(), "a player who left is not passed on")
        } finally {
            leavePlayer(sent.player)
        }
    }

    private fun ask(sent: Sent, limit: Int, got: MutableList<String>): Int {
        TextInput.ask(sent.player, "t", "m", limit) { _, text -> got.add(text) }
        return sent.last<TextInputCallPacket2>().textInputId
    }

    @Test
    fun textInputStripsNewlinesAndDropsOverLength() {
        val sent = UiHarness.joined("owner")
        try {
            val got = ArrayList<String>()
            var id = ask(sent, 150, got)
            Menus.textInputResult(sent.player, id, "a\n[scarlet]x\r")
            assertEquals(listOf("a[scarlet]x"), got)

            id = ask(sent, 150, got)
            Menus.textInputResult(sent.player, id, "a\n[scarlet]x" + "y".repeat(500))
            assertEquals(1, got.size, "over the limit never reaches the callback")

            // The limit counts what is left after the newlines come out.
            id = ask(sent, 6, got)
            Menus.textInputResult(sent.player, id, "ab\ncd\nef")
            assertEquals(listOf("a[scarlet]x", "abcdef"), got, "8 chars raw, 6 after stripping, limit 6")

            id = ask(sent, 150, got)
            Menus.textInputResult(sent.player, id, null)
            assertEquals(2, got.size, "null is no answer")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun textInputFromAPlayerNobodyAskedIsIgnoredAndLeaveClearsThePending() {
        val asked = UiHarness.joined("owner")
        val other = UiHarness.joined("owner")
        try {
            val got = ArrayList<String>()
            val id = ask(asked, 150, got)
            Menus.textInputResult(other.player, id, "x")
            assertTrue(got.isEmpty())
            Menus.textInputResult(asked.player, id, "y")
            assertEquals(listOf("y"), got)

            ask(asked, 150, got)
            leavePlayer(asked.player)
            Menus.textInputResult(asked.player, id, "late")
            assertEquals(listOf("y"), got, "the entry went with the player")
        } finally {
            leavePlayer(other.player)
        }
    }

    private fun <T> withLogCommands(on: Boolean, block: () -> T): T {
        val before = Administration.Config.logCommands.bool()
        Administration.Config.logCommands.set(on)
        try {
            return block()
        } finally {
            Administration.Config.logCommands.set(before)
        }
    }

    private fun capture(block: () -> Unit): List<String> {
        val previous = Log.logger
        val lines = ArrayList<String>()
        Log.logger = Log.LogHandler { level, text ->
            lines.add(text)
            previous.log(level, text)
        }
        try {
            block()
        } finally {
            Log.logger = previous
        }
        return lines
    }

    @Test
    fun runAsPlayerLogsTheCommandOnlyWhenLogCommandsIsOn() {
        val sent = UiHarness.joined("owner")
        try {
            val on = capture { withLogCommands(true) { runAsPlayer(sent.player, "help motd") } }
            assertTrue(on.any { it.contains("/help motd") && it.contains(sent.player.plainName()) }, "logged: $on")
            val off = capture { withLogCommands(false) { runAsPlayer(sent.player, "help motd") } }
            assertTrue(off.none { it.contains("/help motd") }, "not logged: $off")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun runAsPlayerCutsNewlinesAndRefusesOverLongLines() {
        val sent = UiHarness.joined("owner")
        try {
            sent.data.lastReceivedMessage = ""
            runAsPlayer(sent.player, "help\n motd")
            assertEquals(Bundle()["command.help.motd"], sent.data.lastReceivedMessage)
            sent.data.lastReceivedMessage = ""
            runAsPlayer(sent.player, "help " + "x".repeat(200))
            assertEquals("", sent.data.lastReceivedMessage, "over 150 characters is dropped, not run")
        } finally {
            leavePlayer(sent.player)
        }
    }

    @Test
    fun runAsPlayerIsRateLimitedLikeTypedChat() {
        val sent = UiHarness.joined("owner")
        try {
            val limit = Administration.Config.chatSpamLimit.num()
            repeat(limit) { runAsPlayer(sent.player, "help motd") }
            assertFalse(sent.player.con.kicked, "$limit in the window are allowed")
            sent.data.lastReceivedMessage = ""
            runAsPlayer(sent.player, "help motd")
            assertEquals("", sent.data.lastReceivedMessage, "the next one is refused")
            assertTrue(sent.player.con.kicked, "and kicks, as vanilla does for chat")
        } finally {
            mindustry.Vars.netServer.admins.dosBlacklist.clear()
            leavePlayer(sent.player)
        }
    }

    @Test
    fun runAsPlayerStillChecksPermission() {
        val owner = UiHarness.joined("owner")
        val visitor = UiHarness.joined("visitor")
        try {
            assertFalse(Permission.check(visitor.data, "log"), "premise: a visitor may not use /log")
            runAsPlayer(visitor.player, "log")
            assertFalse(visitor.data.viewHistoryMode)
            runAsPlayer(owner.player, "log")
            assertTrue(owner.data.viewHistoryMode)
        } finally {
            leavePlayer(owner.player)
            leavePlayer(visitor.player)
        }
    }
}
