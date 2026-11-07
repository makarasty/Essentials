package essential.core

import PluginTest.Companion.clientCommand
import PluginTest.Companion.leavePlayer
import essential.common.bundle.Bundle
import essential.core.ui.Sent
import essential.core.ui.UiHarness
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.MenuCallPacket
import mindustry.gen.SendMessageCallPacket
import mindustry.gen.TextInputCallPacket2
import mindustry.ui.Menus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A command typed without its player opens a player picker; the pick runs the command as the player, checks and all. */
class PlayerPickerTest {
    private val b = Bundle()

    @BeforeTest
    fun setup() = UiHarness.load()

    @AfterTest
    fun restore() = UiHarness.netServer(false)

    private fun buttons(menu: MenuCallPacket) = menu.options.takeWhile { it.size == 1 }.map { it.single() }

    /** Types [line] and returns the picker it opened. */
    private fun picker(sent: Sent, line: String): MenuCallPacket {
        val before = sent.menus.size
        clientCommand.handleMessage(line, sent.player)
        assertEquals(before + 1, sent.menus.size, "$line opened no menu")
        return sent.menus.last()
    }

    private fun pick(sent: Sent, menu: MenuCallPacket, who: Sent) =
        Menus.menuChoose(sent.player, menu.menuId, buttons(menu).indexOf(who.player.plainName()).also { assertTrue(it >= 0, "${who.player.plainName()} listed") })

    private fun <T> within(vararg all: Sent, body: () -> T): T = try {
        body()
    } finally {
        all.forEach { leavePlayer(it.player) }
    }

    @Test
    fun tpWithoutANameListsTheOthersAndNotTheCaller() {
        val me = UiHarness.joined("owner")
        val other = UiHarness.joined("owner")
        within(me, other) {
            val list = buttons(picker(me, "/tp"))
            assertTrue(list.contains(other.player.plainName()))
            assertFalse(list.contains(me.player.plainName()))
        }
    }

    @Test
    fun pickRunsTheCommandOnThatPlayerAndTypedSyntaxStillWorks() {
        val me = UiHarness.joined("owner")
        val other = UiHarness.joined("user")
        within(me, other) {
            pick(me, picker(me, "/strict"), other)
            assertTrue(other.data.strictMode, "picked")
            clientCommand.handleMessage("/strict #${other.data.entityId}", me.player)
            assertFalse(other.data.strictMode, "typed")
        }
    }

    @Test
    fun aPlayerWhoLeftBeforeThePickGetsAnErrorAndNothingRuns() {
        val me = UiHarness.joined("owner")
        val other = UiHarness.joined("user")
        within(me) {
            val menu = picker(me, "/strict")
            val index = buttons(menu).indexOf(other.player.plainName())
            val data = other.data
            leavePlayer(other.player)
            me.data.lastReceivedMessage = ""
            Menus.menuChoose(me.player, menu.menuId, index)
            assertFalse(data.strictMode)
            assertTrue(me.data.lastReceivedMessage.isNotEmpty(), "told")
        }
    }

    @Test
    fun theCommandsOwnChecksStillRunOnAPick() {
        val admin = UiHarness.joined("admin")
        val owner = UiHarness.joined("owner")
        val user = UiHarness.joined("user")
        within(admin, owner, user) {
            pick(admin, picker(admin, "/strict"), owner)
            assertFalse(owner.data.strictMode, "an admin does not outrank an owner")
            // The command itself is denied, so the picker never opens.
            val before = user.menus.size
            clientCommand.handleMessage("/strict", user.player)
            assertEquals(before, user.menus.size)
        }
    }

    @Test
    fun pmAsksForTheMessageAfterThePickAndAfterATypedName() {
        val me = UiHarness.joined("owner")
        val other = UiHarness.joined("owner")
        within(me, other) {
            pick(me, picker(me, "/pm"), other)
            Menus.textInputResult(me.player, me.last<TextInputCallPacket2>().textInputId, "hello")
            assertTrue(me.data.lastReceivedMessage.contains("hello"), me.data.lastReceivedMessage)
            me.data.lastReceivedMessage = ""
            clientCommand.handleMessage("/pm #${other.data.entityId}", me.player)
            Menus.textInputResult(me.player, me.last<TextInputCallPacket2>().textInputId, "again")
            assertTrue(me.data.lastReceivedMessage.contains("again"), me.data.lastReceivedMessage)
        }
    }

    @Test
    fun reportAsksForTheReasonAfterThePick() {
        val me = UiHarness.joined("owner")
        val other = UiHarness.joined("owner")
        within(me, other) {
            pick(me, picker(me, "/report"), other)
            Menus.textInputResult(me.player, me.last<TextInputCallPacket2>().textInputId, "griefing")
            assertTrue(me.data.lastReceivedMessage.contains(other.player.plainName()), me.data.lastReceivedMessage)
        }
    }

    @Test
    fun infoOffersOtherPlayerOnlyToThoseWhoMayUseIt() {
        val admin = UiHarness.joined("admin")
        val other = UiHarness.joined("user")
        within(admin, other) {
            val own = picker(admin, "/info")
            assertEquals(listOf(b["info.button.close"], b["command.menu.other.player"]), own.options.map { it.single() })
            val before = admin.menus.size
            Menus.menuChoose(admin.player, own.menuId, 1)
            assertTrue(buttons(admin.menus.last()).contains(other.player.plainName()))
            assertEquals(before + 1, admin.menus.size)
            assertEquals(1, picker(other, "/info").options.size)
        }
    }

    @Test
    fun votekickPickerOnPvpListsOnlyTheCallersTeam() {
        val me = UiHarness.joined("owner")
        val ally = UiHarness.joined("user")
        val enemy = UiHarness.joined("user")
        val pvp = Vars.state.rules.pvp
        within(me, ally, enemy) {
            try {
                Vars.state.rules.pvp = true
                me.player.team(Team.sharded)
                ally.player.team(Team.sharded)
                enemy.player.team(Team.blue)
                // /votekick is off in the test config (enableVotekick), so its command is not registered: call it directly.
                Commands().votekick(me.data, emptyArray())
                val list = buttons(me.menus.last())
                assertTrue(list.contains(ally.player.plainName()), "ally listed")
                assertFalse(list.contains(enemy.player.plainName()), "enemy not listed")
            } finally {
                Vars.state.rules.pvp = pvp
            }
        }
    }

    @Test
    fun pmByNamePrefixGoesToThePlayerResolvedNotWhoeverMatchesLater() {
        val me = UiHarness.joined("owner")
        val bob = UiHarness.joined("user")
        bob.player.name("Qzbob")
        within(me) {
            clientCommand.handleMessage("/pm Qzbo", me.player)
            val input = me.last<TextInputCallPacket2>().textInputId
            leavePlayer(bob.player)
            val boris = UiHarness.joined("user")
            boris.player.name("Qzboris")
            within(boris) {
                me.data.lastReceivedMessage = ""
                Menus.textInputResult(me.player, input, "secret")
                assertTrue(boris.packets.none { it.toString().contains("secret") || (it as? SendMessageCallPacket)?.message?.contains("secret") == true }, "boris got the pm")
                assertFalse(me.data.lastReceivedMessage.contains("Qzboris"), me.data.lastReceivedMessage)
                assertTrue(me.data.lastReceivedMessage.isNotEmpty(), "told")
            }
        }
    }
}
