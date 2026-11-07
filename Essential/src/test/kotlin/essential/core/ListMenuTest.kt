package essential.core

import PluginTest.Companion.clientCommand
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.waitUntil
import essential.common.bundle.Bundle
import essential.common.database.table.PlayerTable
import essential.core.ui.Sent
import essential.core.ui.UiHarness
import mindustry.Vars
import mindustry.gen.MenuCallPacket
import mindustry.ui.Menus
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** /ranking, /ach, /status, /undo list and /nextmap open menus; the typed arguments still pick the page. */
class ListMenuTest {
    private val b = Bundle()

    @BeforeTest
    fun setup() {
        UiHarness.load()
        // The throttle is under test in one place only; the other tests press as fast as the menu answers.
        Commands.rankingPressGapMs = 0
    }

    @AfterTest
    fun restore() {
        Commands.rankingPressGapMs = 1000
        mapVotes.clear()
        Vars.maps.setNextMapOverride(null)
        UiHarness.netServer(false)
    }

    private fun joined(group: String, admin: Boolean = false) = UiHarness.joined(group).also { it.player.admin = admin }

    /** Runs [line] and waits for the menu it opens: /ranking reads the database first. */
    private fun open(sent: Sent, line: String): MenuCallPacket {
        val before = sent.menus.size
        clientCommand.handleMessage(line, sent.player)
        assertTrue(waitUntil(10000) { sent.menus.size > before }, "$line opened no menu")
        return sent.menus.last()
    }

    private fun press(sent: Sent, menu: MenuCallPacket, option: Int): MenuCallPacket {
        val before = sent.menus.size
        Menus.menuChoose(sent.player, menu.menuId, option)
        assertTrue(waitUntil(10000) { sent.menus.size > before }, "option $option opened no menu")
        return sent.menus.last()
    }

    private fun nav(menu: MenuCallPacket) = menu.options.single { it.size == 3 }

    private fun items(menu: MenuCallPacket) = menu.options.takeWhile { it.size == 1 }.map { it[0] }

    private fun pageOf(menu: MenuCallPacket) = nav(menu)[1].split('/').map { it.toInt() }

    private fun <T> withPlayer(sent: Sent, body: () -> T): T = try {
        body()
    } finally {
        leavePlayer(sent.player)
    }

    @Test
    fun rankingWithoutAKindOffersTheSixKinds() {
        val sent = joined("owner", admin = true)
        withPlayer(sent) {
            val menu = open(sent, "/ranking")
            assertEquals(listOf("time", "exp", "attack", "place", "break", "pvp").map { b["command.ranking.kind.$it"] } + b["command.menu.close"], items(menu))
            val place = press(sent, menu, 3)
            assertTrue(place.message.startsWith(b["command.ranking.place", 1, pageOf(place)[1]]), place.message)
        }
    }

    @Test
    fun rankingKindMenuHasOwnRankAndSwitchesKind() {
        val sent = joined("owner", admin = true)
        withPlayer(sent) {
            val menu = open(sent, "/ranking exp")
            val (page, pages) = pageOf(menu)
            assertEquals(1, page)
            assertTrue(menu.message.startsWith(b["command.ranking.exp", 1, pages]), menu.message)
            assertTrue(menu.message.contains("[purple]="), "the caller's own rank line: ${menu.message}")
            assertEquals(listOf("time", "attack", "place", "break", "pvp").map { b["command.ranking.kind.$it"] }, items(menu))

            val time = press(sent, menu, 0)
            assertTrue(time.message.startsWith(b["command.ranking.time", 1, pageOf(time)[1]]), time.message)
            assertEquals(listOf("exp", "attack", "place", "break", "pvp").map { b["command.ranking.kind.$it"] }, items(time))
        }
    }

    @Test
    fun rankingPageArgumentIsHonouredAndClamped() {
        val sent = joined("owner", admin = true)
        withPlayer(sent) {
            val (_, pages) = pageOf(open(sent, "/ranking exp"))
            assertEquals(minOf(2, pages), pageOf(open(sent, "/ranking exp 2"))[0])
            assertEquals(pages, pageOf(open(sent, "/ranking exp 9999"))[0], "past the end lands on the last page")
            assertEquals(1, pageOf(open(sent, "/ranking exp 0"))[0])
            assertEquals(minOf(3, pages), pageOf(open(sent, "/ranking exp -3"))[0], "a sign is ignored")
            assertEquals(1, pageOf(open(sent, "/ranking exp x"))[0], "not a number is page 1")
        }
    }

    /** 120 ranked rows: seed001 is last, seed120 first, on both exp and pvp wins. Removed again after [body]. */
    private fun <T> withSeededRanking(body: () -> T): T {
        val uuids = (1..120).map { "rk%03d".format(it) }
        runBlocking {
            suspendTransaction {
                (1..120).forEach { i ->
                    PlayerTable.insert {
                        it[PlayerTable.name] = "seed%03d".format(i)
                        it[PlayerTable.uuid] = uuids[i - 1]
                        it[PlayerTable.exp] = 5000 + i
                        it[PlayerTable.pvpWinCount] = i.toShort()
                    }
                }
            }
        }
        return try {
            body()
        } finally {
            runBlocking { suspendTransaction { PlayerTable.deleteWhere { PlayerTable.uuid inList uuids } } }
        }
    }

    @Test
    fun rankingPagesAreReadFromTheDatabaseSoRanksPastOneHundredAreReachable() {
        val sent = joined("owner", admin = true)
        withPlayer(sent) {
            withSeededRanking {
                val pvp = open(sent, "/ranking pvp 2")
                val (page, pages) = pageOf(pvp)
                assertEquals(2, page, "/ranking pvp 2 lands on page 2")
                assertTrue(pages > 14, "120 rows are many pages: $pages")
                assertTrue(pvp.message.contains("9. seed112"), pvp.message)

                // rank 113..120 is page 15, beyond what a 100-row cut could show
                val deep = open(sent, "/ranking exp 15")
                assertEquals(15, pageOf(deep)[0])
                assertTrue(deep.message.contains("113. seed008"), deep.message)
                assertTrue(deep.message.contains("120. seed001"), deep.message)

                val prev = press(sent, deep, items(deep).size)
                assertEquals(14, pageOf(prev)[0], "<- re-reads the previous page")
                assertTrue(prev.message.contains("105. seed016"), prev.message)
                val next = press(sent, prev, items(prev).size + 2)
                assertEquals(15, pageOf(next)[0], "-> re-reads the next page")
                val kind = press(sent, next, 0)
                assertEquals(1, pageOf(kind)[0], "switching kind starts at page 1")
                assertTrue(kind.message.startsWith(b["command.ranking.time", 1, pageOf(kind)[1]]), kind.message)
            }
        }
    }

    @Test
    fun rankingShowsOnAFreshIdWhenAnotherPlayerTookTheSlotDuringTheQuery() {
        val sent = joined("owner", admin = true)
        val other = joined("user")
        withPlayer(sent) {
            withPlayer(other) {
                val menu = open(sent, "/ranking exp")
                val before = sent.menus.size
                // The press frees its slot at once; the query result is only shown on a later pumped turn.
                Menus.menuChoose(sent.player, menu.menuId, items(menu).size + 2)
                var otherHits = 0
                val otherId = OwnedMenus.register(other.data) { _, _ -> otherHits++ }
                OwnedMenus.show(other.player.con(), otherId, "other", "", arrayOf(arrayOf("x")))
                assertTrue(waitUntil(10000) { sent.menus.size > before }, "the ranking was not shown")
                val shown = sent.menus.last()
                assertNotEquals(otherId, shown.menuId, "the ranking landed on the other player's menu id")
                Menus.menuChoose(sent.player, otherId, 0)
                assertEquals(0, otherHits, "the ranking owner drove the other player's menu")
                Menus.menuChoose(other.player, otherId, 0)
                assertEquals(1, otherHits, "the other player's menu still answers them")
                assertTrue(pageOf(press(sent, shown, items(shown).size + 1)).isNotEmpty(), "the ranking still answers its owner")
            }
        }
    }

    private fun shape(menu: MenuCallPacket) = menu.message to menu.options.map { it.toList() }

    // A gap no loaded box can outlast, so "inside the gap" never depends on how fast the test runs.
    @Test
    fun rankingThrottledPressReShowsTheSamePageWithoutAQuery() {
        Commands.rankingPressGapMs = 60_000
        val sent = joined("owner", admin = true)
        withPlayer(sent) {
            val menu = open(sent, "/ranking exp")
            val shown = press(sent, menu, items(menu).size + 1)
            val gate = Commands.rankingGates.getValue(sent.data.uuid)
            val last = gate.last
            val again = press(sent, shown, items(shown).size + 1)
            assertEquals(shape(shown), shape(again), "a refused press shows the page it was on")
            assertEquals(last, gate.last, "a refused press must not count as a query")
            // and the re-shown dialog is live: a refused press again is answered again
            assertEquals(shape(shown), shape(press(sent, again, items(again).size + 1)))
        }
    }

    @Test
    fun rankingCloseWithinTheGapClosesInsteadOfReShowing() {
        Commands.rankingPressGapMs = 60_000
        val sent = joined("owner", admin = true)
        withPlayer(sent) {
            val menu = open(sent, "/ranking exp")
            val shown = press(sent, menu, items(menu).size + 1)
            val shows = sent.menus.size
            Menus.menuChoose(sent.player, shown.menuId, items(shown).size + 3)
            waitUntil(500) { false }
            assertEquals(shows, sent.menus.size, "Close inside the gap re-showed the dialog")
        }
    }

    @Test
    fun typedRankingRunsWhileAPressQueryIsInFlight() {
        val sent = joined("owner", admin = true)
        withPlayer(sent) {
            open(sent, "/ranking exp")
            Commands.rankingGates.getValue(sent.data.uuid).busy.set(true)
            val typed = open(sent, "/ranking pvp")
            assertTrue(typed.message.startsWith(b["command.ranking.pvp", 1, pageOf(typed)[1]]), typed.message)
        }
    }

    @Test
    fun rankingSessionsOfOnePlayerShareTheThrottle() {
        Commands.rankingPressGapMs = 60_000
        val sent = joined("owner", admin = true)
        withPlayer(sent) {
            val first = open(sent, "/ranking exp")
            val second = open(sent, "/ranking pvp")
            val gate = Commands.rankingGates.getValue(sent.data.uuid)
            press(sent, first, items(first).size + 1)
            val last = gate.last
            val refused = press(sent, second, items(second).size + 1)
            assertEquals(shape(second), shape(refused), "the second session is throttled by the first one's press")
            assertEquals(last, gate.last)
        }
    }

    @Test
    fun rankingLeftDuringTheQueryShowsNothingAndTakesNoSlot() {
        val sent = joined("owner", admin = true)
        val menu = open(sent, "/ranking exp")
        val allocated = OwnedMenus.allocationCount
        val shows = sent.menus.size
        Menus.menuChoose(sent.player, menu.menuId, items(menu).size + 1)
        leavePlayer(sent.player)
        waitUntil(1500) { false }
        assertEquals(shows, sent.menus.size, "a menu was shown to a player who left")
        assertEquals(allocated, OwnedMenus.allocationCount, "a slot was taken for nobody")
        assertTrue(Commands.rankingGates[sent.data.uuid] == null, "the gate outlived the player")
    }

    @Test
    fun rankingShowsOnTheConnectionThePlayerHasNowNotTheOneTheQueryStartedOn() {
        val sent = joined("owner", admin = true)
        withPlayer(sent) {
            val menu = open(sent, "/ranking exp")
            val shows = sent.menus.size
            Menus.menuChoose(sent.player, menu.menuId, items(menu).size + 1)
            // The player reconnects before the query result lands: same uuid, a new connection.
            val fresh = ArrayList<Any>()
            sent.player.con = object : mindustry.net.NetConnection(sent.player.con.address) {
                override fun send(`object`: Any?, reliable: Boolean) {
                    if (`object` != null) fresh.add(`object`)
                }

                override fun close() {}
            }.also { it.uuid = sent.player.con.uuid }
            assertTrue(waitUntil(10000) { fresh.any { it is MenuCallPacket } }, "the new connection got no menu")
            assertEquals(shows, sent.menus.size, "the old connection got the menu")
        }
    }

    @Test
    fun achOpensPagedMenuAndHonoursPageArgument() {
        val sent = joined("user")
        withPlayer(sent) {
            val menu = open(sent, "/ach")
            val (page, pages) = pageOf(menu)
            assertEquals(1, page)
            assertTrue(items(menu).isEmpty(), "achievements are text, not buttons")
            assertTrue(menu.message.contains(" / "), "name (current / value): ${menu.message}")
            assertTrue(menu.message.split("[yellow]").size - 1 in 1..3, "three achievements to a page: ${menu.message}")
            assertEquals(minOf(2, pages), pageOf(open(sent, "/ach 2"))[0])
            assertEquals(pages, pageOf(open(sent, "/ach 9999"))[0])
            assertEquals(1, pageOf(open(sent, "/ach x"))[0], "not a number is page 1")
            val next = press(sent, menu, items(menu).size + 2)
            assertEquals(minOf(2, pages), pageOf(next)[0], "-> moves to the next page")
        }
    }

    @Test
    fun statusIsAnInfoWindowWithClose() {
        val sent = joined("user")
        withPlayer(sent) {
            val menu = open(sent, "/status")
            assertEquals("[#DEA82A]${b["command.status.info"]}[]", menu.title)
            assertTrue(menu.message.contains(b["command.status.name"]), menu.message)
            assertEquals(listOf(listOf(b["command.menu.close"])), menu.options.map { it.toList() })
            val shown = sent.menus.size
            Menus.menuChoose(sent.player, menu.menuId, 0)
            assertEquals(shown, sent.menus.size, "Close shows nothing more")
        }
    }

    @Test
    fun undoListOffersEntriesAndUndoRunsTheEntry() {
        val sent = joined("owner")
        withPlayer(sent) {
            val reverted = ArrayList<String>()
            Undo.record(sent.data, "mute", "first-target", "first") { reverted.add(it) }
            Undo.record(sent.data, "mute", "second-target", "second") { reverted.add(it) }
            val newest = Undo.stack(sent.data.uuid).first()

            val list = open(sent, "/undo list")
            assertEquals(1, pageOf(list)[1])
            assertEquals(
                Undo.stack(sent.data.uuid).map { b["command.undo.list", it.id.toString(), it.description] },
                items(list)
            )

            val card = press(sent, list, 0)
            assertEquals(newest.description, card.message)
            assertEquals(listOf(listOf(b["command.undo.button.undo"], b["command.menu.back"])), card.options.map { it.toList() })

            val back = press(sent, card, 1)
            assertEquals(items(list), items(back), "Back shows the list again")

            val card2 = press(sent, back, 0)
            Menus.menuChoose(sent.player, card2.menuId, 0)
            assertEquals(listOf("second-target"), reverted, "Undo runs /undo <id> for the tapped entry")
            assertEquals(1, Undo.stack(sent.data.uuid).size)
        }
    }

    @Test
    fun nextmapShowsTheTallyAndATapVotes() {
        val sent = joined("user")
        withPlayer(sent) {
            val map = mapsById().firstOrNull()
            assertTrue(map != null, "the test world has maps")
            mapVotes["someone-else"] = map

            val menu = open(sent, "/nextmap")
            assertEquals(b["command.nextmap.vote.current"], menu.title)
            assertEquals(b["command.nextmap.vote.none.yours"], menu.message)
            assertEquals(b["command.nextmap.vote.count", map.plainName(), 1], items(menu).single())

            Menus.menuChoose(sent.player, menu.menuId, 0)
            assertEquals(map, mapVotes[sent.data.uuid], "the tap is a vote for that map")
        }
    }
}
