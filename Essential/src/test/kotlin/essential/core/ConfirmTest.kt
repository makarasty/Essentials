package essential.core

import PluginTest.Companion.clientCommand
import PluginTest.Companion.err
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.observeMessages
import PluginTest.Companion.pumpApp
import PluginTest.Companion.serverCommand
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import arc.Events
import arc.func.Cons
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.permission.Permission
import essential.common.util.findPlayerData
import essential.core.ui.UiHarness
import mindustry.Vars
import mindustry.content.UnitTypes
import mindustry.game.EventType.GameOverEvent
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.MenuBuilderCallPacket
import mindustry.gen.MenuCallPacket
import mindustry.gen.Player
import mindustry.gen.Playerc
import mindustry.ui.Menus
import mindustry.ui.builder.MenuResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs [block] (a typed command), waits for the one owned menu it opens - a lookup may come first - and
 * answers it with [option]: 0 is Yes, 1 Cancel, -1 dismissing the dialog. Fails when nothing was asked.
 */
fun answerConfirm(player: Playerc, what: String, option: Int, block: () -> Unit) {
    val before = OwnedMenus.allocationCount
    block()
    assertTrue(waitUntil(10000) { pumpApp(); OwnedMenus.allocationCount > before }, "$what should ask first")
    val ids = OwnedMenus.idsAllocatedAfter(before)
    assertEquals(1, ids.size, "$what should open exactly one menu")
    Menus.menuChoose(player.self(), ids.single(), option)
}

fun answerYes(player: Playerc, what: String, block: () -> Unit) = answerConfirm(player, what, 0, block)

/** Every destructive client command asks first; only Yes does it. */
class ConfirmTest {
    companion object {
        private var done = false
    }

    private var gameOvers = 0
    private val gameOverListener = Cons<GameOverEvent> { gameOvers++ }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
        gameOvers = 0
        Events.on(GameOverEvent::class.java, gameOverListener)
    }

    @AfterTest
    fun restore() {
        Events.remove(GameOverEvent::class.java, gameOverListener)
        UiHarness.netServer(false)
        Vars.state.rules.infiniteResources = false
        Groups.unit.filter { it.type() == UnitTypes.dagger }.forEach { it.remove() }
        Groups.unit.update()
    }

    private fun owner(): Pair<Player, PlayerData> =
        newPlayer().also { (p, data) ->
            setPermission(p, "owner", true)
            assertTrue(waitUntil(5000) { Permission.check(data, "js") }, "owner group applied")
        }

    /** Cancel and dismissing do nothing; Yes does it. [probe] says whether the effect happened. */
    private fun cancelThenYes(admin: Player, line: String, probe: () -> Boolean) {
        assertFalse(probe(), "$line: nothing happened yet")
        answerConfirm(admin, line, 1) { clientCommand.handleMessage(line, admin) }
        pumpApp()
        assertFalse(probe(), "$line: Cancel does nothing")
        answerConfirm(admin, line, -1) { clientCommand.handleMessage(line, admin) }
        pumpApp()
        assertFalse(probe(), "$line: dismissing does nothing")
        answerYes(admin, line) { clientCommand.handleMessage(line, admin) }
        assertTrue(waitUntil(10000) { probe() }, "$line: Yes does it")
    }

    private fun daggers(team: Team) = Groups.unit.count { it.type() == UnitTypes.dagger && it.team == team }

    private fun spawnDagger(team: Team) {
        UnitTypes.dagger.spawn(team, 100f, 100f)
    }

    @Test
    fun gg() {
        val (admin, _) = owner()
        try {
            cancelThenYes(admin, "/gg green") { gameOvers > 0 }
            assertEquals(1, gameOvers)
            assertTrue(waitUntil(10000) { !Vars.state.gameOver })
        } finally {
            leavePlayer(admin)
        }
    }

    @Test
    fun killAnotherPlayerAsksButKillingYourselfDoesNot() {
        val (admin, _) = owner()
        val (target, _) = newPlayer()
        try {
            cancelThenYes(admin, "/kill ${target.name}") { target.unit()?.dead() == true }

            val before = OwnedMenus.allocationCount
            clientCommand.handleMessage("/kill", admin)
            clientCommand.handleMessage("/kill ${admin.name}", admin)
            assertEquals(before, OwnedMenus.allocationCount, "self kill asks nothing")
            assertTrue(admin.unit().dead())
        } finally {
            leavePlayer(admin)
            leavePlayer(target)
        }
    }

    @Test
    fun killTargetThatLeftWhileAskedIsNotFound() {
        val (admin, adminData) = owner()
        val (target, _) = newPlayer()
        try {
            answerYes(admin, "/kill") {
                clientCommand.handleMessage("/kill ${target.name}", admin)
                leavePlayer(target)
            }
            assertEquals(err(Commands.PLAYER_NOT_FOUND), adminData.lastReceivedMessage)
        } finally {
            leavePlayer(admin)
        }
    }

    @Test
    fun killall() {
        val (admin, _) = owner()
        try {
            spawnDagger(Team.sharded)
            cancelThenYes(admin, "/killall") { daggers(Team.sharded) == 0 }
            spawnDagger(Team.crux)
            spawnDagger(Team.sharded)
            val sharded = daggers(Team.sharded)
            cancelThenYes(admin, "/killall crux") { daggers(Team.crux) == 0 }
            assertEquals(sharded, daggers(Team.sharded), "the other team keeps its unit")
        } finally {
            leavePlayer(admin)
        }
    }

    @Test
    fun killunit() {
        val (admin, _) = owner()
        try {
            val sharded = daggers(Team.sharded)
            spawnDagger(Team.crux)
            spawnDagger(Team.sharded)
            cancelThenYes(admin, "/killunit dagger 0 crux") { daggers(Team.crux) == 0 }
            assertEquals(sharded + 1, daggers(Team.sharded), "the other team keeps its unit")
        } finally {
            leavePlayer(admin)
        }
    }

    @Test
    fun kickall() {
        val (admin, _) = owner()
        val (other, _) = newPlayer()
        try {
            cancelThenYes(admin, "/kickall") { other.con.kicked }
            assertFalse(admin.con.kicked)
        } finally {
            leavePlayer(admin)
            leavePlayer(other)
        }
    }

    @Test
    fun js() {
        val (admin, _) = owner()
        try {
            cancelThenYes(admin, "/js Vars.state.rules.infiniteResources = true") { Vars.state.rules.infiniteResources }
        } finally {
            leavePlayer(admin)
        }
    }

    @Test
    fun jsConfirmShowsTheCodeLiterallyAndYesRunsItUnchanged() {
        val me = UiHarness.joined("owner")
        try {
            val code = "Vars.state.rules.infiniteResources = true; \"[clear]x[red]y\""
            answerYes(me.player, "/js") { clientCommand.handleMessage("/js $code", me.player) }
            assertEquals(me.data.bundle["command.menu.confirm.js", code.replace("[", "[[")], me.menus.last().message)
            assertTrue(waitUntil(10000) { Vars.state.rules.infiniteResources }, "Yes runs the code as typed")
        } finally {
            leavePlayer(me.player)
        }
    }

    @Test
    fun changemap() {
        val (admin, _) = owner()
        val saved = Groups.player.toList()
        val before = Vars.state.map.name()
        val line = "/changemap ${if (before == "Fork") "glacier" else "fork"} survival"
        try {
            cancelThenYes(admin, line) { Vars.state.map.name() != before }
        } finally {
            saved.filter { !it.isAdded }.forEach { it.add() }
            Groups.player.update()
            leavePlayer(admin)
        }
    }

    @Test
    fun rollback() {
        val (admin, adminData) = owner()
        val (target, _) = newPlayer()
        val done = Bundle()["command.rollback.success", target.name, 0].dropLast(2)
        try {
            val seen = { observeMessages(adminData, 300) { it.startsWith(done) }.any { it.startsWith(done) } }
            answerConfirm(admin, "/rollback", 1) { clientCommand.handleMessage("/rollback ${target.name}", admin) }
            assertFalse(seen(), "Cancel does nothing")
            answerYes(admin, "/rollback") { clientCommand.handleMessage("/rollback ${target.name}", admin) }
            assertTrue(observeMessages(adminData, 10000) { it.startsWith(done) }.any { it.startsWith(done) }, "Yes rolls back")
        } finally {
            leavePlayer(admin)
            leavePlayer(target)
        }
    }

    @Test
    fun setperm() {
        val (admin, _) = owner()
        val (target, targetData) = newPlayer()
        val uuid = target.uuid()
        try {
            cancelThenYes(admin, "/setperm $uuid admin") { Permission.groupOf(uuid, targetData.permission) == "admin" }
        } finally {
            leavePlayer(admin)
            leavePlayer(target)
        }
    }

    @Test
    fun aCommandRunFromTheHelpMenuAsksExactlyOnce() {
        val me = UiHarness.joined("owner")
        val (other, _) = newPlayer()
        try {
            clientCommand.handleMessage("/help", me.player)
            val dialog = me.last<MenuBuilderCallPacket>()
            val before = me.menus.size
            val result = MenuResult("run:kickall")
            result.token = dialog.token
            Menus.menuBuilderChoose(me.player, dialog.id, result)
            assertTrue(waitUntil(10000) { pumpApp(); me.menus.size > before }, "Run asks first")
            assertEquals(before + 1, me.menus.size, "Run opens exactly one menu")
            val confirm = me.last<MenuCallPacket>()
            assertEquals(listOf(me.data.bundle["command.menu.yes"], me.data.bundle["command.menu.cancel"]), confirm.options.single().toList())
            assertFalse(other.con.kicked, "nothing happens before Yes")
            Menus.menuChoose(me.player, confirm.menuId, 0)
            assertTrue(waitUntil(10000) { pumpApp(); other.con.kicked }, "Yes does it")
            assertEquals(before + 1, me.menus.size, "Yes asks nothing more")
            assertEquals(1, me.packets.count { it is MenuBuilderCallPacket }, "no second help dialog")
        } finally {
            leavePlayer(me.player)
            leavePlayer(other)
        }
    }

    @Test
    fun doubleYesDoesItOnce() {
        val (admin, _) = owner()
        try {
            val before = OwnedMenus.allocationCount
            clientCommand.handleMessage("/gg green", admin)
            val id = OwnedMenus.idsAllocatedAfter(before).single()
            Menus.menuChoose(admin, id, 0)
            Menus.menuChoose(admin, id, 0)
            assertEquals(1, gameOvers)
            assertTrue(waitUntil(10000) { !Vars.state.gameOver })
        } finally {
            leavePlayer(admin)
        }
    }

    @Test
    fun yesDoesNothingWhenThePermissionIsGoneByThen() {
        val (admin, _) = owner()
        try {
            val before = OwnedMenus.allocationCount
            clientCommand.handleMessage("/gg green", admin)
            val id = OwnedMenus.idsAllocatedAfter(before).single()
            setPermission(admin, "user", false)
            Menus.menuChoose(admin, id, 0)
            assertEquals(0, gameOvers)
        } finally {
            leavePlayer(admin)
        }
    }

    @Test
    fun noPermissionNoQuestion() {
        val (user, _) = newPlayer()
        try {
            setPermission(user, "user", false)
            val before = OwnedMenus.allocationCount
            clientCommand.handleMessage("/killall", user)
            clientCommand.handleMessage("/gg", user)
            assertEquals(before, OwnedMenus.allocationCount)
            assertEquals(0, gameOvers)
        } finally {
            leavePlayer(user)
        }
    }

    @Test
    fun serverConsoleNeverAsks() {
        val (target, targetData) = newPlayer()
        try {
            spawnDagger(Team.crux)
            val before = OwnedMenus.allocationCount
            serverCommand.handleMessage("kill ${target.name}")
            serverCommand.handleMessage("killall crux")
            serverCommand.handleMessage("setperm ${target.uuid()} admin")
            assertTrue(waitUntil(10000) { Permission.groupOf(target.uuid(), targetData.permission) == "admin" }, "setperm ran")
            assertTrue(target.unit().dead(), "kill ran")
            assertEquals(0, daggers(Team.crux), "killall ran")
            assertEquals(before, OwnedMenus.allocationCount, "the console is never asked")
            assertTrue(findPlayerData(target.uuid()) != null)
        } finally {
            leavePlayer(target)
        }
    }
}
