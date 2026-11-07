package essential.core

import PluginTest.Companion.clientCommand
import PluginTest.Companion.err
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.pumpApp
import PluginTest.Companion.waitUntil
import essential.common.bundle.Bundle
import essential.common.permission.Permission
import essential.core.ui.Sent
import essential.core.ui.UiHarness
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.MenuCallPacket
import mindustry.gen.TextInputCallPacket2
import mindustry.ui.Menus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fixed-choice command typed without its argument opens buttons; a pick runs the typed form as the player. */
class ChoicePickerTest {
    private val b = Bundle()

    @BeforeTest
    fun setup() = UiHarness.load()

    @AfterTest
    fun restore() = UiHarness.netServer(false)

    private fun buttons(menu: MenuCallPacket) = menu.options.takeWhile { it.size == 1 }.map { it.single() }

    /** Types [line] and returns the menu it opened. */
    private fun opened(sent: Sent, line: String): MenuCallPacket {
        val before = sent.menus.size
        clientCommand.handleMessage(line, sent.player)
        assertEquals(before + 1, sent.menus.size, "$line opened no menu")
        return sent.menus.last()
    }

    private fun pick(sent: Sent, menu: MenuCallPacket, label: String) =
        Menus.menuChoose(sent.player, menu.menuId, buttons(menu).indexOfFirst { it == label || it.contains(label) }.also { assertTrue(it >= 0, "$label listed in ${buttons(menu)}") })

    private fun text(sent: Sent, answer: String) = Menus.textInputResult(sent.player, sent.last<TextInputCallPacket2>().textInputId, answer)

    private fun <T> within(vararg all: Sent, body: () -> T): T = try {
        body()
    } finally {
        all.forEach { leavePlayer(it.player) }
    }

    @Test
    fun langShowsTheCurrentLanguageAndEveryTranslationAndPicksLikeTyping() {
        val me = UiHarness.joined("user")
        within(me) {
            val menu = opened(me, "/lang")
            assertEquals(me.data.bundle["command.lang.current", me.data.localeTag(), Bundle.translations.joinToString(", ")], menu.message)
            assertEquals(Bundle.translations + me.data.bundle["command.menu.lang.auto"] + me.data.bundle["command.menu.close"], buttons(menu))
            pick(me, menu, "uk")
            assertEquals("uk", me.data.languageChoice)
            pick(me, opened(me, "/lang"), me.data.bundle["command.menu.lang.auto"])
            assertNull(me.data.languageChoice)
        }
    }

    @Test
    fun weatherAsksTheTypeThenTheSecondsAndRunsTheTypedForm() {
        val me = UiHarness.joined("owner")
        within(me) {
            val menu = opened(me, "/weather")
            assertEquals(listOf("snow", "sandstorm", "sporestorm", "fog", "suspendParticles", "rain"), buttons(menu).dropLast(1))
            pick(me, menu, "rain")
            text(me, "abc")
            assertEquals(err("command.weather.not.number"), me.data.lastReceivedMessage, "the typed form's own check ran")
            // A type typed alone asks for the seconds.
            val before = me.packets.filterIsInstance<TextInputCallPacket2>().size
            clientCommand.handleMessage("/weather snow", me.player)
            assertEquals(before + 1, me.packets.filterIsInstance<TextInputCallPacket2>().size)
        }
    }

    private fun voteLabels(types: List<String>) = types.map { b["command.menu.vote.$it"] }

    private val voteTypes = listOf("kick", "map", "gg", "skip", "back", "random", "draw")

    /** The "user" group's node list, which is the one thing `vote.<type>` is read from. */
    private fun userNodes(): MutableList<String> {
        val field = Permission::class.java.getDeclaredField("main").apply { isAccessible = true }
        val role = (field.get(Permission) as Map<*, *>)["user"]!!
        @Suppress("UNCHECKED_CAST")
        return role.javaClass.getMethod("getPermission").invoke(role) as MutableList<String>
    }

    @Test
    fun voteListsOnlyTheTypesThePlayerMayStart() {
        val me = UiHarness.joined("user")
        within(me) {
            assertEquals(voteLabels(voteTypes), buttons(opened(me, "/vote")).dropLast(1))
            val nodes = userNodes()
            nodes.remove("vote.kick")
            nodes.remove("vote.draw")
            try {
                assertEquals(voteLabels(voteTypes - "kick" - "draw"), buttons(opened(me, "/vote")).dropLast(1))
            } finally {
                nodes.add("vote.kick")
                nodes.add("vote.draw")
            }
        }
    }

    @Test
    fun votePicksRunTheTypedForm() {
        val me = UiHarness.joined("user")
        val other = UiHarness.joined("user")
        within(me, other) {
            // The same refusal a typed vote gets on a server this small tells the pick ran as the typed form.
            me.data.lastReceivedMessage = ""
            clientCommand.handleMessage("/vote gg", me.player)
            val typed = me.data.lastReceivedMessage
            assertTrue(typed.isNotEmpty(), "a typed vote answers")

            me.data.lastReceivedMessage = ""
            pick(me, opened(me, "/vote"), b["command.menu.vote.gg"])
            assertEquals(typed, me.data.lastReceivedMessage)

            me.data.lastReceivedMessage = ""
            clientCommand.handleMessage("/vote skip 2", me.player)
            val typedSkip = me.data.lastReceivedMessage
            me.data.lastReceivedMessage = ""
            pick(me, opened(me, "/vote"), b["command.menu.vote.skip"])
            text(me, "2")
            assertEquals(typedSkip, me.data.lastReceivedMessage)

            // kick: a player, then a reason with spaces (the typed reason takes the rest of the line).
            val kick = opened(me, "/vote")
            pick(me, kick, b["command.menu.vote.kick"])
            pick(me, me.menus.last(), other.player.plainName())
            me.data.lastReceivedMessage = ""
            text(me, "two words")
            val viaMenu = me.data.lastReceivedMessage
            me.data.lastReceivedMessage = ""
            clientCommand.handleMessage("/vote kick #${other.data.entityId} two words", me.player)
            assertEquals(viaMenu, me.data.lastReceivedMessage)
            assertEquals(err("command.vote.enough"), viaMenu)
        }
    }

    @Test
    fun voteMapListsTheMapsThenAsksTheReason() {
        val me = UiHarness.joined("user")
        within(me) {
            pick(me, opened(me, "/vote"), b["command.menu.vote.map"])
            val maps = me.menus.last()
            assertTrue(buttons(maps).size > 1, "maps listed: ${buttons(maps)}")
            val before = me.packets.filterIsInstance<TextInputCallPacket2>().size
            Menus.menuChoose(me.player, maps.menuId, 0)
            assertEquals(before + 1, me.packets.filterIsInstance<TextInputCallPacket2>().size)
        }
    }

    @Test
    fun voteWithNoTypeAllowedTellsInsteadOfOpeningAnEmptyMenu() {
        val me = UiHarness.joined("visitor")
        within(me) {
            val before = me.menus.size
            Commands().vote(me.data, emptyArray())
            assertEquals(before, me.menus.size)
            assertEquals(err("command.vote.arg.empty"), me.data.lastReceivedMessage)
        }
    }

    @Test
    fun effectOffersOnOffAndLevelsUpToTheirOwn() {
        val me = UiHarness.joined("user")
        within(me) {
            me.data.level = 2
            me.data.effectVisibility = true
            val menu = opened(me, "/effect")
            assertEquals(
                listOf(b["command.menu.effect.on"], b["command.menu.effect.off"]) + (0..2).map { b["command.menu.effect.level", it.toString()] },
                buttons(menu)
            )
            pick(me, menu, b["command.menu.effect.off"])
            assertTrue(waitUntil(5000) { pumpApp(); !me.data.effectVisibility }, "off applied")
            pick(me, opened(me, "/effect"), b["command.menu.effect.level", "1"])
            assertTrue(waitUntil(5000) { pumpApp(); me.data.effectLevel == 1.toShort() }, "level applied")
        }
    }

    @Test
    fun teamOffersTheTeamsAndAPickMovesThePlayer() {
        val me = UiHarness.joined("owner")
        within(me) {
            me.player.team(Team.blue)
            val menu = opened(me, "/team")
            assertEquals(6, buttons(menu).size - 1)
            pick(me, menu, "sharded")
            assertEquals(Team.sharded, me.player.team())
        }
    }

    @Test
    fun setpermPicksThePlayerThenTheGroupAndRunsTheTypedForm() {
        val me = UiHarness.joined("owner")
        val peer = UiHarness.joined("owner")
        val other = UiHarness.joined("user")
        within(me, peer, other) {
            val players = opened(me, "/setperm")
            assertTrue(buttons(players).contains(other.player.plainName()))
            assertFalse(buttons(players).contains(peer.player.plainName()), "an owner does not outrank an owner")
            pick(me, players, other.player.plainName())
            val groups = buttons(me.menus.last()).dropLast(1)
            assertTrue("admin" in groups && "user" in groups, "$groups")
            assertFalse("owner" in groups, "only groups below the caller's own: $groups")
            answerYes(me.player, "setperm") { pick(me, me.menus.last(), "admin") }
            assertEquals("admin", Permission.groupOf(other.data.uuid, other.data.permission))

            // A typed player skips straight to the groups, and the typed form still works.
            val typed = opened(me, "/setperm #${other.data.entityId}")
            assertEquals(groups, buttons(typed).dropLast(1))
            answerYes(me.player, "setperm") { pick(me, typed, "user") }
            assertEquals("user", Permission.groupOf(other.data.uuid, other.data.permission))
            answerYes(me.player, "setperm") { clientCommand.handleMessage("/setperm #${other.data.entityId} admin", me.player) }
            assertEquals("admin", Permission.groupOf(other.data.uuid, other.data.permission))
            answerYes(me.player, "setperm") { clientCommand.handleMessage("/setperm #${other.data.entityId} user", me.player) }
        }
    }

    @Test
    fun setpermWithATypedPrefixKeepsItsTargetWhenAnotherPlayerMatchesBeforeTheGroupIsPicked() {
        val me = UiHarness.joined("owner")
        val first = UiHarness.joined("user")
        val second = UiHarness.joined("user")
        within(me, first, second) {
            first.player.name("abAlpha${first.data.entityId}")
            val groups = opened(me, "/setperm abAlpha")
            // A later joiner now matches the same prefix, so the raw prefix would be ambiguous or another target.
            second.player.name("abAlphaTwo${second.data.entityId}")
            answerYes(me.player, "setperm") { pick(me, groups, "admin") }
            assertEquals("admin", Permission.groupOf(first.data.uuid, first.data.permission))
            assertEquals("user", Permission.groupOf(second.data.uuid, second.data.permission))
            answerYes(me.player, "setperm") { clientCommand.handleMessage("/setperm #${first.data.entityId} user", me.player) }
        }
    }

    @Test
    fun voteMapWhoseMapLeavesBeforeTheReasonIsAnsweredTellsNotFoundAndStartsNothing() {
        val me = UiHarness.joined("user")
        within(me) {
            pick(me, opened(me, "/vote"), b["command.menu.vote.map"])
            val maps = me.menus.last()
            val map = mapsById()[1]
            Menus.menuChoose(me.player, maps.menuId, 1)
            Vars.maps.all().remove(map)
            try {
                me.data.lastReceivedMessage = ""
                text(me, "because")
                assertEquals(err("command.vote.map.not.exists"), me.data.lastReceivedMessage)
            } finally {
                Vars.maps.all().add(map)
            }
        }
    }
}
