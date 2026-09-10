import PluginTest.Companion.clientCommand
import PluginTest.Companion.createPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.player
import PluginTest.Companion.pumpApp
import PluginTest.Companion.serverCommand
import PluginTest.Companion.setPermission
import essential.common.database.data.checkRoutingPermission
import essential.common.database.data.consumeRoutingPermission
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.database.data.createTemporaryPlayerData
import essential.common.database.data.getWorldHistoryByCoordinates
import essential.common.database.data.getPlayerData
import essential.common.database.data.setAchievement
import essential.common.database.data.plugin.WarpBlock
import essential.common.database.WorldHistoryBuffer
import essential.common.database.databaseClose
import essential.common.database.databaseInit
import essential.common.database.defaultDatabase
import essential.common.database.table.ServerRoutingTable
import essential.common.database.data.checkPlayerBannedByIpOrUuid
import essential.common.database.data.createBanInfo
import essential.common.database.data.removeBanInfoByIP
import essential.common.database.data.update
import essential.common.event.CustomEvents
import essential.common.mapStartTime
import essential.common.players
import essential.common.permission.Permission
import essential.common.pluginData
import essential.common.rootPath
import essential.common.systemTimezone
import essential.common.timeSource
import essential.common.service.fileWatchService
import essential.core.Main
import essential.core.buildingBulletDestroy
import essential.core.connectPacket
import essential.core.gameOver
import essential.core.loadJoinedPlayerData
import essential.core.mapRatings
import essential.core.mergeTemporaryPlayerData
import essential.core.playerDataRetries
import essential.core.playerIpUnban
import essential.core.selectAutoTeam
import essential.core.service.achievements.AchievementHooks
import essential.core.swapTemporaryPlayerData
import essential.core.tap
import essential.core.Undo
import essential.core.worldLoad
import arc.Events
import arc.func.Cons
import arc.util.Log
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.EventType.BuildingBulletDestroyEvent
import mindustry.game.EventType.ConnectPacketEvent
import mindustry.game.EventType.GameOverEvent
import mindustry.game.EventType.PlayerIpUnbanEvent
import mindustry.game.EventType.PlayerJoin
import mindustry.game.EventType.TapEvent
import mindustry.game.EventType.WorldLoadEvent
import mindustry.game.Team
import mindustry.gen.Bullet
import mindustry.gen.Groups
import mindustry.net.Administration
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import mindustry.net.NetConnection
import mindustry.ui.Menus
import mindustry.net.Packets
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime


class FeatureTest {
    companion object {
        private var done = false
    }

    private class TestConnection(address: String) : NetConnection(address) {
        var kickedMessage: String? = null

        override fun send(`object`: Any?, reliable: Boolean) {}

        override fun close() {}

        override fun kick(reason: String, kickDuration: Long) {
            kicked = true
            kickedMessage = reason
        }
    }

    /**
     * Same contract as [PluginTest.waitUntil], including the pump: work a background coroutine
     * hands to the game thread with `Core.app.post` only ever runs because something drains that
     * queue, and on a live server the main loop does it every frame. A wait that only sleeps sees
     * the database side of an async handler and never its engine side, so an assertion on engine
     * state - a connection kicked, a ban lifted - reads as "it never happened".
     */
    private fun awaitCondition(timeoutMs: Long = 3000L, intervalMs: Long = 50L, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            pumpApp()
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
        pumpApp()
        return condition()
    }

    private fun <T> withoutLogErrors(block: () -> T): T {
        val original = Log.logger
        Log.logger = Log.LogHandler { level, text -> println("[$level] $text") }
        try {
            return block()
        } finally {
            Log.logger = original
        }
    }

    private fun captureLogs(block: () -> Unit): List<String> {
        val original = Log.logger
        val captured = CopyOnWriteArrayList<String>()
        Log.logger = Log.LogHandler { level, text ->
            captured.add(text)
            println("[$level] $text")
        }
        try {
            block()
        } finally {
            Log.logger = original
        }
        return captured
    }

    private fun pumpAppTasksStrict() {
        pumpApp()
    }

    /** Pumps the queue for the whole window and returns the first runnable that blew up. */
    private fun pumpForThrow(timeoutMs: Long): Throwable? {
        var thrown: Throwable? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                pumpAppTasksStrict()
            } catch (e: Throwable) {
                if (thrown == null) thrown = e
            }
            Thread.sleep(50)
        }
        return thrown
    }

    private fun withRetryAttempts(attempts: Int) {
        Main.conf = Main.conf.copy(
            feature = Main.conf.feature.copy(
                playerData = Main.conf.feature.playerData.copy(retryAttempts = attempts)
            )
        )
    }

    private fun pumpAppTasks() {
        pumpApp()
    }

    private fun awaitPumped(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            pumpAppTasks()
            if (condition()) return true
            Thread.sleep(50)
        }
        pumpAppTasks()
        return condition()
    }

    private fun breakDatabase() {
        databaseClose()
    }

    private fun restoreDatabase() {
        runBlocking {
            databaseInit(
                Main.conf.plugin.database.url,
                Main.conf.plugin.database.username,
                Main.conf.plugin.database.password
            )
        }
    }

    private fun joinPlayer(): mindustry.gen.Player {
        val newPlayer = createPlayer()
        Events.fire(PlayerJoin(newPlayer))
        return newPlayer
    }

    private fun playerDataOf(uuid: String): PlayerData? = players.find { it.uuid == uuid }

    private fun allowPlaceBlock(target: mindustry.gen.Player): Boolean {
        val tile = PluginTest.randomTile()
        val action = Administration.PlayerAction()
        action.player = target
        action.type = Administration.ActionType.placeBlock
        action.tile = tile
        action.block = Blocks.copperWall
        return Vars.netServer.admins.actionFilters.all { it.allow(action) }
    }

    private fun makeConnectPacket(name: String, uuid: String): Packets.ConnectPacket {
        return Packets.ConnectPacket().apply {
            this.name = name
            this.uuid = uuid
            this.locale = "ko"
            this.version = 0
            this.versionType = "test"
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun seedRoutingPermission(playerUuid: String, hubServerName: String, targetServerName: String, targetPort: Int = 6567, validSeconds: Int = 60) {
        val now = Clock.System.now().toLocalDateTime(systemTimezone)
        val expiresAt = (Clock.System.now() + validSeconds.seconds).toLocalDateTime(systemTimezone)

        runBlocking {
            suspendTransaction {
                ServerRoutingTable.insert {
                    it[ServerRoutingTable.playerUuid] = playerUuid
                    it[ServerRoutingTable.hubServerName] = hubServerName
                    it[ServerRoutingTable.targetServerName] = targetServerName
                    it[ServerRoutingTable.targetPort] = targetPort
                    it[ServerRoutingTable.hubConnectionTime] = now
                    it[ServerRoutingTable.routingAllowedTime] = now
                    it[ServerRoutingTable.isUsed] = false
                    it[ServerRoutingTable.usedTime] = null
                    it[ServerRoutingTable.expiresAt] = expiresAt
                }
            }
        }
    }

    private fun clearRoutingPermission(playerUuid: String) {
        runBlocking {
            suspendTransaction {
                ServerRoutingTable.deleteWhere { ServerRoutingTable.playerUuid eq playerUuid }
            }
        }
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            System.setProperty("test", "yes")
            loadGame(true)

            // Menus.menuChoose fires MenuOptionChooseEvent before it dispatches, and CoreEvent's
            // undoMenuChoose reads Undo.menuId - a lazy that registers a menu of its own. Force it here
            // so the menu counts below measure only what the click did. InfoMenuTest and UndoTest do the
            // same. Nothing forks the test JVM (Essential/build.gradle.kts:452 sets no forkEvery), so
            // without this the class is green only while some earlier class, or some earlier test in
            // this one, happens to have clicked a menu first.
            Undo.menuId

            val p = newPlayer()
            player = p.first.self()

            done = true
        }
    }

    @Test
    fun serverRoutingAllowWhenReachedExpectedServerFromHubWarpBlock() {
        val testPlayer: mindustry.gen.Player = player.self()
        val currentMapName = Vars.state.map.name()
        val originalHubMapName = pluginData.hubMapName
        val originalWarpBlocks = pluginData.data.warpBlock.map { it.copy().apply { online = it.online } }
        val originalConf = Main.conf

        try {
            val hubServerName = "hub-server"
            val targetServerName = "target-server"
            Main.conf = originalConf.copy(plugin = originalConf.plugin.copy(serverId = targetServerName))
            pluginData.hubMapName = hubServerName
            pluginData.data.warpBlock.clear()
            pluginData.data.warpBlock.add(
                WarpBlock(
                    mapName = hubServerName,
                    x = 0,
                    y = 0,
                    tileName = "router",
                    size = 1,
                    ip = "127.0.0.1",
                    port = 6567,
                    description = currentMapName
                ).apply { online = true }
            )

            val targetPort = Vars.port
            seedRoutingPermission(testPlayer.uuid(), hubServerName, targetServerName, targetPort)

            assertTrue(
                awaitCondition {
                    runBlocking { checkRoutingPermission(testPlayer.uuid(), targetServerName, targetPort) }
                },
                "허브 워프 블록 이동 후 현재 서버 포트에 대한 라우팅 권한이 생성되어야 합니다."
            )

            val connection = TestConnection("127.0.0.1")
            val packet = makeConnectPacket(testPlayer.name(), testPlayer.uuid())
            connectPacket(ConnectPacketEvent(connection, packet))

            assertTrue(
                awaitCondition {
                    !runBlocking { checkRoutingPermission(testPlayer.uuid(), targetServerName, targetPort) }
                },
                "대상 서버 접속이 허용되면 라우팅 권한이 사용 처리되어야 합니다."
            )
            assertFalse(connection.kicked)
            assertNull(connection.kickedMessage)
        } finally {
            pluginData.hubMapName = originalHubMapName
            pluginData.data.warpBlock.clear()
            pluginData.data.warpBlock.addAll(originalWarpBlocks)
            Main.conf = originalConf
            clearRoutingPermission(testPlayer.uuid())
        }
    }

    @Test
    fun serverRoutingDenyWhenReachedDifferentServerFromHubWarpBlock() {
        val testPlayer: mindustry.gen.Player = player.self()
        val originalHubMapName = pluginData.hubMapName
        val originalWarpBlocks = pluginData.data.warpBlock.map { it.copy().apply { online = it.online } }
        val originalConf = Main.conf

        try {
            val hubServerName = "hub-server"
            val targetServerName = "another-server"
            Main.conf = originalConf.copy(plugin = originalConf.plugin.copy(serverId = "different-server"))
            pluginData.hubMapName = hubServerName
            pluginData.data.warpBlock.clear()
            pluginData.data.warpBlock.add(
                WarpBlock(
                    mapName = hubServerName,
                    x = 0,
                    y = 0,
                    tileName = "router",
                    size = 1,
                    ip = "127.0.0.1",
                    port = 6567,
                    description = "another-server"
                ).apply { online = true }
            )

            val targetPort = Vars.port + 1
            seedRoutingPermission(testPlayer.uuid(), hubServerName, targetServerName, targetPort)

            assertTrue(
                awaitCondition {
                    runBlocking { checkRoutingPermission(testPlayer.uuid(), targetServerName, targetPort) }
                },
                "허브 워프 블록 이동 후 설정된 대상 서버 포트로 라우팅 권한이 생성되어야 합니다."
            )

            val connection = TestConnection("127.0.0.1")
            val packet = makeConnectPacket(testPlayer.name(), testPlayer.uuid())
            connectPacket(ConnectPacketEvent(connection, packet))

            assertTrue(
                awaitCondition { connection.kicked },
                "허용되지 않은 대상 서버 포트(현재 서버 포트와 불일치)로 직접 접속 시도가 오면 연결이 거부되어야 합니다."
            )
            assertEquals(connection.kickedMessage?.contains("Direct connection denied"), true)
            assertTrue(runBlocking { checkRoutingPermission(testPlayer.uuid(), targetServerName, targetPort) })
        } finally {
            pluginData.hubMapName = originalHubMapName
            pluginData.data.warpBlock.clear()
            pluginData.data.warpBlock.addAll(originalWarpBlocks)
            Main.conf = originalConf
            clearRoutingPermission(testPlayer.uuid())
        }
    }

    @Test
    fun serverRoutingPermissionIsBoundToDestinationAndConsumedOnce() {
        val testPlayer: mindustry.gen.Player = player.self()
        val targetServerName = "target-server"
        val targetPort = Vars.port

        try {
            seedRoutingPermission(testPlayer.uuid(), "hub-server", targetServerName, targetPort)

            assertFalse(runBlocking { consumeRoutingPermission(testPlayer.uuid(), "other-server", targetPort) })
            assertTrue(runBlocking { checkRoutingPermission(testPlayer.uuid(), targetServerName, targetPort) })
            assertTrue(runBlocking { consumeRoutingPermission(testPlayer.uuid(), targetServerName, targetPort) })
            assertFalse(runBlocking { consumeRoutingPermission(testPlayer.uuid(), targetServerName, targetPort) })
        } finally {
            clearRoutingPermission(testPlayer.uuid())
        }
    }

    /**
     * The teams selectAutoTeam actually scores: active, not derelict, holding a core, and not the wave
     * team. Not the same set as `Vars.state.teams.active`, which keeps a team that has lost every core
     * on its remaining buildings - CoreEvent.kt:788-789 says so in its own comment.
     */
    /**
     * Runs [block] with a `PlayerData` for a player that is in no scored list and never enters
     * `players`, so it cannot move a count or an average - a stand-in for a joining player, which
     * `selectAutoTeam` excludes by uuid anyway. The unit is removed for the reason `leavePlayer` gives:
     * `createPlayer()` spawns one, and removing the player does not remove it.
     */
    private fun <T> withProbeData(block: (PlayerData) -> T): T {
        val probe = createPlayer()
        try {
            return block(createTemporaryPlayerData(probe))
        } finally {
            probe.unit()?.takeIf { it.isValid }?.remove()
            probe.remove()
            Groups.player.update()
        }
    }

    private fun playableTeamCounts(): kotlin.collections.Map<Team, Int> = Vars.state.teams.active
        .filter {
            it.team != Team.derelict && it.hasCore() &&
                    !(Vars.state.rules.waves && Vars.state.rules.waveTeam == it.team)
        }
        .associate { td -> td.team to players.count { it.player.team() == td.team } }

    /**
     * selectAutoTeam (CoreEvent.kt:1612) promises the lowest average win rate among the teams that hold
     * a core, unless that team already has two more members than the smallest of the others, in which
     * case the next one by win rate that passes the same guard. Both halves are scored over the plugin's
     * own `players` list, and that list is not this class's to control: `/changemap` goes through
     * WorldReloader, whose begin() clears Groups.player via Logic.reset() and whose end() re-teams the
     * saved players but never calls player.add() again. So every player of every earlier test in this
     * class stays in `players`, invisible to Groups.player, and is counted by both the averages and the
     * guard.
     *
     * Measured on a green run at 30772163, immediately after this test's own /changemap: `players=7
     * groups=0`, and by the time the test predicted anything the four teams it believes are 2/2/2/2 were
     * 5/4/2/2. The old assertion "the eleventh player goes to the second lowest win rate" follows from
     * the guard only while every other team is at least two behind the lowest, and with green sitting on
     * exactly 2 it was one leaked player away from flipping back to blue - which is the recorded flake,
     * "Player 11 should go to second lowest win rate team expected:<green> but was:<blue>".
     *
     * So the rule is asserted twice below: exactly, against a list this test owns - selectAutoTeam takes
     * the list it scores as a parameter - and end to end as the invariant the guard actually maintains
     * whatever else is in `players`.
     */
    @Test
    fun pvpBalanceTest() {
        setPermission("owner", true)

        // Enable autoTeam in config
        Main.conf = Main.conf.copy(feature = Main.conf.feature.copy(pvp = Main.conf.feature.pvp.copy(autoTeam = true)))

        clientCommand.handleMessage("/changemap Glacier pvp", player)
        assertTrue(awaitCondition(7000L, 100L) { Vars.state.rules.pvp })

        // Scenario: 4 teams (A, B, C, D) with 2 players each
        // Win rates: A=100%, B=75%, C=50%, D=25%
        val testTeams = listOf(Team.sharded, Team.crux, Team.green, Team.blue)
        // Fixed, well separated tiles rather than PluginTest.randomTile(), which is an unseeded
        // java.util.Random over a 100x100 window. A coreShard is 3x3, so two of four random placements
        // land on each other about one run in seventy, and the team whose core was overwritten stays in
        // teams.active on its remaining buildings while dropping out of the set selectAutoTeam scores.
        // Nothing here needs the placement to vary, and a fix for a flake should not leave a dice roll.
        val corners = listOf(10 to 10, 10 to 60, 60 to 10, 60 to 60)
        for ((i, team) in testTeams.withIndex()) {
            val (x, y) = corners[i]
            Vars.world.tile(x, y).setNet(mindustry.content.Blocks.coreShard, team, 0)
        }
        
        val activeTeams = Vars.state.teams.active.filter { testTeams.contains(it.team) }.toList()
        assertEquals(4, activeTeams.size, "Need 4 teams for test")
        // The candidate set is a precondition of the rule asserted below, and the old test assumed it
        // rather than checking it. Fail here, naming the set, rather than three assertions later.
        assertEquals(
            testTeams.toSet(),
            playableTeamCounts().keys,
            "selectAutoTeam scores exactly the core-holding teams, and the rule asserted below assumes " +
                    "those are the four this test planted"
        )

        val winRates = listOf(1.0, 0.75, 0.5, 0.25)

        // Fill teams with 2 players each
        val seeded = mutableListOf<PlayerData>()
        for (i in activeTeams.indices) {
            val team = activeTeams[i].team
            val rate = winRates[i]
            repeat(2) {
                val p = newPlayer()
                // Force join the specific team for initial setup
                p.first.team(team)
                p.second.pvpWinCount = (rate * 100).toInt().toShort()
                p.second.pvpLoseCount = ((1.0 - rate) * 100).toInt().toShort()
                seeded.add(p.second)
            }
        }

        // Over the seeded eight only - the whole `players` list carries earlier tests' players, which is
        // exactly what the old assertion assumed away.
        val byWinRate = activeTeams.map { it.team }.sortedBy { team ->
            seeded.filter { it.player.team() == team }.map {
                val total = it.pvpWinCount + it.pvpLoseCount
                if (total == 0) 0.5 else it.pvpWinCount.toDouble() / total
            }.average()
        }
        val teamLowest = byWinRate[0]
        val teamSecondLowest = byWinRate[1]

        withProbeData { joining ->
            val byTeam = seeded.groupBy { it.player.team() }
            // Extra members are repeats of a team's own seeded player, so they raise that team's count
            // without moving its average - the count guard is what is under test here, not the averages.
            fun scored(vararg extra: Pair<Team, Int>): List<PlayerData> =
                seeded + extra.flatMap { (team, n) -> List(n) { byTeam.getValue(team).first() } }

            assertEquals(
                teamLowest, selectAutoTeam(joining, scored()),
                "level counts: the lowest average win rate takes the player"
            )
            assertEquals(
                teamLowest, selectAutoTeam(joining, scored(teamLowest to 1)),
                "one ahead of the smallest team is still inside the handicap the guard allows"
            )
            assertEquals(
                teamSecondLowest, selectAutoTeam(joining, scored(teamLowest to 2)),
                "two ahead of the smallest team is the handicap ceiling, so the next win rate takes the player"
            )
            // The recorded flake, forced. The guard compares each team against the smallest OTHER team,
            // so once every other team is within one, the lowest win rate takes the player at +2 as well.
            // That is the state a single leaked player from an earlier test produces, and it is why "the
            // eleventh player goes to the second lowest win rate" was never the promise.
            assertEquals(
                teamLowest,
                selectAutoTeam(
                    joining,
                    scored(teamLowest to 2, *byWinRate.drop(1).map { it to 1 }.toTypedArray())
                ),
                "the guard is about counts, not win rates: with every other team within one of it, the " +
                        "lowest win rate takes the player at +2 as well"
            )
        }

        // End to end, three real joins through the real join path. Two things are asserted per join.
        // First, that the path actually routes through selectAutoTeam: the oracle is the same function
        // asked, immediately beforehand and on the same list, where it would put a joiner. That covers
        // the wiring at CoreEvent.kt:1596-1601 - a rememberTeam or spector branch swallowing a fresh
        // join, say - without depending on what is in `players`, because both sides read the same list.
        // Second, the invariant the guard maintains whatever else is in `players`: a joiner lands on a
        // team that holds a core, and never on one already more than one player ahead of the smallest.
        repeat(3) { i ->
            val before = playableTeamCounts()
            val oracle = withProbeData { selectAutoTeam(it, players) }
            val joined = newPlayer().first.team()
            assertEquals(
                oracle, joined,
                "join ${i + 1} landed on $joined, but selectAutoTeam asked the same question on the same " +
                        "list a moment earlier said $oracle - the join path is not routing through it. " +
                        "playable=$before"
            )
            val had = assertNotNull(
                before[joined],
                "join ${i + 1} went to $joined, which holds no core. playable=$before"
            )
            val minOthers = (before - joined).values.minOrNull() ?: had
            assertTrue(
                had <= minOthers + 1,
                "join ${i + 1} went to $joined, which already had $had players while the smallest other " +
                        "core-holding team had $minOthers; the handicap guard allows at most minOthers + 1. " +
                        "playable=$before"
            )
        }

        clientCommand.handleMessage("/status", player)
    }

    @Test
    fun pvpWorldLoadAutoTeamTest() {
        setPermission("owner", true)

        Main.conf = Main.conf.copy(feature = Main.conf.feature.copy(pvp = Main.conf.feature.pvp.copy(autoTeam = true, spector = true)))

        clientCommand.handleMessage("/changemap Glacier pvp", player)
        assertTrue(awaitCondition(7000L, 100L) { Vars.state.rules.pvp })

        val testTeams = listOf(Team.sharded, Team.crux)
        for (team in testTeams) {
            val tile = PluginTest.randomTile()
            tile.setNet(mindustry.content.Blocks.coreShard, team, 0)
        }

        val p1 = newPlayer()
        p1.first.team(Team.sharded)
        p1.second.pvpWinCount = 8
        p1.second.pvpLoseCount = 2

        val p2 = newPlayer()
        p2.first.team(Team.sharded)
        p2.second.pvpWinCount = 7
        p2.second.pvpLoseCount = 3

        // Simulate players who lost the previous match and became derelict
        val p3 = newPlayer()
        p3.first.team(Team.derelict)
        p3.second.pvpWinCount = 4
        p3.second.pvpLoseCount = 6

        val p4 = newPlayer()
        p4.first.team(Team.derelict)
        p4.second.pvpWinCount = 3
        p4.second.pvpLoseCount = 7

        // Simulate an intentional spectator with pvp.spector permission
        val pAdmin = newPlayer()
        pAdmin.first.team(Team.derelict)
        setPermission(pAdmin.first, "admin", true)

        // Fire WorldLoadEvent to simulate transitioning to the next world
        Events.fire(WorldLoadEvent())

        // Non-spectator players should no longer be Team.derelict
        assertNotEquals(Team.derelict, p1.first.team(), "Player 1 should not be derelict after world load")
        assertNotEquals(Team.derelict, p2.first.team(), "Player 2 should not be derelict after world load")
        assertNotEquals(Team.derelict, p3.first.team(), "Player 3 should not be derelict after world load")
        assertNotEquals(Team.derelict, p4.first.team(), "Player 4 should not be derelict after world load")

        // Player with pvp.spector permission should remain Team.derelict
        assertEquals(Team.derelict, pAdmin.first.team(), "Admin spectator should remain derelict")

        // Non-spectator players should be balanced between the active teams
        val activeNonSpectators = players.filter { !Permission.check(it, "pvp.spector") }
        val shardedCount = activeNonSpectators.count { it.player.team() == Team.sharded }
        val cruxCount = activeNonSpectators.count { it.player.team() == Team.crux }
        assertEquals(activeNonSpectators.size, shardedCount + cruxCount, "All active non-spectators should be on Sharded or Crux")
        assertTrue(kotlin.math.abs(shardedCount - cruxCount) <= 2, "Teams should be balanced within autoTeam handicap range")
    }

    @Test
    fun playerDataSavedBeforeWarpBlockTransfer() {
        val p = newPlayer()
        val testPlayer = p.first
        val testPlayerData = p.second
        val currentMapName = Vars.state.map.name()
        val originalWarpBlocks = pluginData.data.warpBlock.map { it.copy().apply { online = it.online } }

        try {
            val tile = PluginTest.randomTile()
            tile.setBlock(mindustry.content.Blocks.router)

            pluginData.data.warpBlock.clear()
            pluginData.data.warpBlock.add(
                WarpBlock(
                    mapName = currentMapName,
                    x = tile.build.tileX(),
                    y = tile.build.tileY(),
                    tileName = tile.block().name,
                    size = 1,
                    ip = "127.0.0.1",
                    port = 6567,
                    description = "warp-test-target"
                ).apply { online = true }
            )

            testPlayerData.exp = 99999
            testPlayerData.totalPlayed = 88888
            testPlayerData.blockPlaceCount = 77777

            // What the code promises is an ordering, not a latency: CoreEvent.kt:286-302 is one
            // coroutine that awaits `data.update()` at :291 and only then fires ServerTransfer at :299
            // and calls Call.connect at :301, so the row is durable before anything hands the player
            // over. The old assertion polled the row for three seconds after the tap and said nothing
            // about the transfer - it would have passed just as happily had the write landed a minute
            // after the player left, and it reddened whenever the write took longer than its budget,
            // which is what made it the fifth flake in the base-rate measurement. So read the row at
            // the instant the transfer fires instead.
            val rowAtTransfer = AtomicReference<PlayerData?>(null)
            val readFailure = AtomicReference<Throwable?>(null)
            val transferSeen = AtomicBoolean(false)
            val transferListener = Cons<CustomEvents.ServerTransfer> { ev ->
                // Three other sites fire ServerTransfer - the warpZone branch below this one at
                // CoreEvent.kt:330, and Trigger.kt:760 and :859 - and all three are live while this test
                // runs. Answering for somebody else's transfer would read this row at a moment the write
                // has not happened and redden for it.
                if (ev.player.uuid() != testPlayerData.uuid) return@Cons
                // `handled` is the documented seam for taking over the transfer. Setting it also keeps
                // Call.connect out of it, which under test has no net provider and throws into the
                // coroutine's exception handler.
                ev.handled = true
                try {
                    rowAtTransfer.set(runBlocking { getPlayerData(testPlayerData.uuid) })
                } catch (e: Throwable) {
                    // Otherwise this is swallowed by the scope's CoroutineExceptionHandler and the test
                    // reports "never reached the transfer" twelve seconds later, which is the wrong
                    // diagnosis for a read that blew up.
                    readFailure.set(e)
                }
                transferSeen.set(true)
            }
            Events.on(CustomEvents.ServerTransfer::class.java, transferListener)
            try {
                tap(TapEvent(testPlayer, tile))

                // The write's own ceiling is the connection pool's maxAcquireTime (Database.kt:128,
                // ten seconds), so a shorter budget than that asserts a latency the code never
                // promised. Nothing here rides on how long it takes - the assertions below are on what
                // the row held when the transfer fired - so the wait only has to outlast the write.
                assertTrue(
                    awaitCondition(12000L) { transferSeen.get() },
                    "the WarpBlock tap never reached the server transfer"
                )
                readFailure.get()?.let { throw AssertionError("reading the row at the transfer failed", it) }
                val row = assertNotNull(
                    rowAtTransfer.get(),
                    "WarpBlock 탭 후 대상 서버 연결 전에 playerData가 DB에 즉시 저장되어야 합니다: " +
                            "the player had no row at all when the transfer fired"
                )
                assertEquals(99999, row.exp, "exp was not durable when the transfer fired")
                assertEquals(88888, row.totalPlayed, "totalPlayed was not durable when the transfer fired")
                assertEquals(77777, row.blockPlaceCount, "blockPlaceCount was not durable when the transfer fired")
                assertFalse(
                    row.isConnected,
                    "the row still claimed the player was connected here when the transfer fired"
                )
            } finally {
                Events.remove(CustomEvents.ServerTransfer::class.java, transferListener)
            }
        } finally {
            pluginData.data.warpBlock.clear()
            pluginData.data.warpBlock.addAll(originalWarpBlocks)
        }
    }

    @Test
    fun playerDataTemporaryWhenDatabaseUnavailable() {
        val originalConf = Main.conf
        var target: mindustry.gen.Player? = null

        withoutLogErrors {
            try {
                withRetryAttempts(0)
                breakDatabase()

                val joined = joinPlayer()
                target = joined
                assertTrue(
                    awaitPumped(10000L) { playerDataOf(joined.uuid())?.temporary == true },
                    "A player joining while the database is down must get temporary data."
                )

                val temporary = playerDataOf(joined.uuid())!!
                assertEquals(Permission.default, temporary.permission)
                assertTrue(allowPlaceBlock(joined), "Temporary data must still allow building.")

                clientCommand.handleMessage("/help", joined)
                assertNotEquals(
                    Bundle(joined.locale())["command.data.loading"],
                    temporary.lastReceivedMessage,
                    "Commands must work while the data is temporary."
                )
                assertTrue(temporary.lastReceivedMessage.isNotEmpty())

                temporary.blockPlaceCount = 12
                temporary.blockBreakCount = 7
                temporary.chatMuted = true
                temporary.strictMode = true

                restoreDatabase()

                assertFalse(
                    awaitPumped(15000L) { playerDataOf(joined.uuid())?.temporary == false },
                    "retryAttempts = 0 must keep the background reload from swapping the data in."
                )

                serverCommand.handleMessage("reloadplayer ${joined.uuid()}")
                assertTrue(
                    awaitPumped(10000L) { playerDataOf(joined.uuid())?.temporary == false },
                    "reloadplayer must swap the temporary data for the real one."
                )

                val loaded = playerDataOf(joined.uuid())!!
                assertNotEquals(0u, loaded.id)
                assertEquals(12, loaded.blockPlaceCount)
                assertEquals(7, loaded.blockBreakCount)
                assertTrue(loaded.chatMuted, "A mute set on temporary data must survive the swap.")
                assertTrue(loaded.strictMode, "Strict mode set on temporary data must survive the swap.")

                // A late retry holding the same temporary object must not merge the counters again.
                val second = runBlocking { getPlayerData(joined.uuid()) }!!
                swapTemporaryPlayerData(second, temporary)
                awaitPumped(1000L) { false }

                assertSame(loaded, playerDataOf(joined.uuid()), "A second swap must be a no-op.")
                assertEquals(12, loaded.blockPlaceCount)
                assertEquals(7, loaded.blockBreakCount)
            } finally {
                Main.conf = originalConf
                if (defaultDatabase == null) restoreDatabase()
                target?.let { leavePlayer(it) }
            }
        }
    }

    @Test
    fun staleReloadDoesNotEvictTheLivePlayerData() {
        val p = newPlayer()
        val joined = p.first
        val uuid = joined.uuid()
        val live = playerDataOf(uuid)!!

        try {
            // The retry started from a temporary object that is no longer the registered one.
            val orphan = createTemporaryPlayerData(joined).apply { temporary = true }
            swapTemporaryPlayerData(runBlocking { getPlayerData(uuid) }!!, orphan)
            awaitPumped(1000L) { false }
            assertSame(live, playerDataOf(uuid), "A retry started from a stale object must not replace the live data.")

            // The player reconnected while the read was running, so the temporary object holds a dead connection.
            val ghostCon = TestConnection("127.0.0.1")
            ghostCon.uuid = uuid
            val ghost = mindustry.gen.Player.create()
            ghost.con = ghostCon
            ghost.name(joined.name())
            val stale = createTemporaryPlayerData(ghost).apply { temporary = true }

            players.removeIf { it.uuid == uuid }
            players.add(stale)
            swapTemporaryPlayerData(runBlocking { getPlayerData(uuid) }!!, stale)
            awaitPumped(1000L) { false }
            assertSame(stale, playerDataOf(uuid), "A retry holding a dead connection must not bind the new one.")
            assertSame(joined, Groups.player.find { it.uuid() == uuid }, "The live player must stay untouched.")
        } finally {
            players.removeIf { it.uuid == uuid }
            players.add(live)
            leavePlayer(joined)
        }
    }

    @Test
    fun playerDataSwapRunsThePostLoadSteps() {
        val originalConf = Main.conf
        var target: mindustry.gen.Player? = null
        val ended = AtomicReference<PlayerData?>(null)
        val listener = Cons<CustomEvents.PlayerDataLoadEnd> { ended.set(it.playerData) }
        Events.on(CustomEvents.PlayerDataLoadEnd::class.java, listener)

        withoutLogErrors {
            try {
                withRetryAttempts(0)
                breakDatabase()

                val joined = joinPlayer()
                target = joined
                assertTrue(awaitPumped(10000L) { playerDataOf(joined.uuid())?.temporary == true })

                restoreDatabase()
                ended.set(null)

                serverCommand.handleMessage("reloadplayer ${joined.uuid()}")
                assertTrue(
                    awaitPumped(10000L) {
                        val data = ended.get()
                        data != null && data.uuid == joined.uuid() && !data.temporary
                    },
                    "The swap must run the same post load steps as a join, up to PlayerDataLoadEnd."
                )

                val loaded = playerDataOf(joined.uuid())!!
                assertEquals(
                    Clock.System.now().toLocalDateTime(systemTimezone).date,
                    loaded.lastLoginDate.date,
                    "The swap must record the login date."
                )
            } finally {
                Events.remove(CustomEvents.PlayerDataLoadEnd::class.java, listener)
                Main.conf = originalConf
                if (defaultDatabase == null) restoreDatabase()
                target?.let { leavePlayer(it) }
            }
        }
    }

    @Test
    fun playerDataRetryJobIsCancelled() {
        val originalConf = Main.conf
        var target: mindustry.gen.Player? = null

        withoutLogErrors {
            try {
                withRetryAttempts(30)
                breakDatabase()

                val joined = joinPlayer()
                target = joined
                assertTrue(awaitPumped(10000L) { playerDataOf(joined.uuid())?.temporary == true })
                assertNotNull(playerDataRetries[joined.uuid()], "The background reload job must be tracked.")

                restoreDatabase()
                serverCommand.handleMessage("reloadplayer ${joined.uuid()}")
                assertTrue(
                    awaitPumped(10000L) { playerDataRetries[joined.uuid()] == null },
                    "reloadplayer must cancel the background reload job."
                )

                breakDatabase()
                val second = joinPlayer()
                assertTrue(awaitPumped(10000L) { playerDataOf(second.uuid())?.temporary == true })
                assertNotNull(playerDataRetries[second.uuid()])
                restoreDatabase()

                leavePlayer(second)
                assertNull(playerDataRetries[second.uuid()], "Leaving must cancel the background reload job.")
            } finally {
                Main.conf = originalConf
                if (defaultDatabase == null) restoreDatabase()
                target?.let { leavePlayer(it) }
            }
        }
    }

    @Test
    fun playerDataLoadGivesUpWhenTheDatabaseHangs() {
        val originalConf = Main.conf
        val target = createPlayer()

        withoutLogErrors {
            try {
                Main.conf = originalConf.copy(
                    feature = originalConf.feature.copy(
                        playerData = originalConf.feature.playerData.copy(loadTimeout = 1)
                    )
                )

                val started = System.currentTimeMillis()
                val result = runBlocking {
                    loadJoinedPlayerData(target, target.name()) { _, _ -> awaitCancellation() }
                }
                val elapsed = System.currentTimeMillis() - started

                assertNull(result.data, "A hanging read must time out instead of pinning the thread.")
                assertFalse(result.duplicateName)
                assertTrue(elapsed < 10000L, "The read should have been abandoned after the timeout, took $elapsed ms.")
            } finally {
                Main.conf = originalConf
                leavePlayer(target)
            }
        }
    }

    @Test
    fun duplicateNameStopsTheReloadInsteadOfRetryingForever() {
        val originalConf = Main.conf
        val existing = newPlayer()
        var target: mindustry.gen.Player? = null

        withoutLogErrors {
            try {
                withRetryAttempts(30)
                breakDatabase()

                val duplicate = createPlayer()
                duplicate.name(existing.first.name())
                target = duplicate
                Events.fire(PlayerJoin(duplicate))
                assertTrue(awaitPumped(10000L) { playerDataOf(duplicate.uuid())?.temporary == true })

                restoreDatabase()

                assertTrue(
                    awaitPumped(25000L) { playerDataRetries[duplicate.uuid()] == null },
                    "A duplicate name must end the background reload instead of retrying forever."
                )
                assertEquals(
                    true,
                    playerDataOf(duplicate.uuid())?.temporary,
                    "A duplicate name must never be turned into real player data."
                )

                val logs = captureLogs {
                    serverCommand.handleMessage("reloadplayer ${duplicate.uuid()}")
                    awaitPumped(10000L) { false }
                }
                assertTrue(
                    logs.any { it.contains("duplicate name") },
                    "reloadplayer must report a duplicate name, got: $logs"
                )
            } finally {
                Main.conf = originalConf
                if (defaultDatabase == null) restoreDatabase()
                target?.let { leavePlayer(it) }
                leavePlayer(existing.first)
            }
        }
    }

    @Test
    fun reloadKeepsTheOldConfigWhenTheYamlIsBroken() {
        val configFile = rootPath.child(Main.CONFIG_PATH)
        val original = if (configFile.exists()) configFile.readString() else null
        val before = Main.conf

        withoutLogErrors {
            try {
                configFile.writeString("plugin:\n  - broken: [\n", false)

                serverCommand.handleMessage("reload")
                val thrown = pumpForThrow(5000L)

                assertNull(thrown, "A broken config must not unwind the main loop: $thrown")
                assertSame(before, Main.conf, "A broken config must leave the loaded one in place.")
            } finally {
                if (original != null) configFile.writeString(original, false) else configFile.delete()
                Main.conf = before
            }
        }
    }

    @Test
    fun rollbackRepliesWhenTheUndoFails() {
        val p = newPlayer()
        setPermission(p.first, "owner", true)
        val ghost = mindustry.gen.Player.create()
        ghost.add()
        Groups.player.update()

        withoutLogErrors {
            try {
                clientCommand.handleMessage("/rollback ${p.first.name()}", p.first)
                val thrown = pumpForThrow(5000L)

                assertNull(thrown, "A failing rollback must not unwind the main loop: $thrown")
                assertTrue(
                    p.second.lastReceivedMessage.contains(Bundle(p.first.locale())["command.rollback.failed"]),
                    "A failing rollback must tell the admin, got: ${p.second.lastReceivedMessage}"
                )
            } finally {
                ghost.remove()
                Groups.player.update()
                leavePlayer(p.first)
            }
        }
    }

    @Test
    fun achievementsAreLoadedOffTheMainThreadAndSkippedForTemporaryData() {
        val p = newPlayer()
        val data = p.second

        try {
            runBlocking { setAchievement(data, "builder") }
            data.achievementStatus.clear()

            Events.fire(CustomEvents.PlayerDataLoad(data))
            assertFalse(
                data.achievementStatus.contains("builder"),
                "The achievement load must not block the main thread."
            )
            assertTrue(
                awaitPumped(10000L) { data.achievementStatus.contains("builder") },
                "The achievement load must still finish and post its result back."
            )

            withoutLogErrors {
                breakDatabase()
                val temporary = createTemporaryPlayerData(p.first).apply { temporary = true }
                AchievementHooks.processPlayerDataLoad(temporary)
                awaitPumped(1000L) { false }
                assertTrue(
                    temporary.achievementStatus.isEmpty(),
                    "Temporary data has no row to read achievements for."
                )
                restoreDatabase()
            }
        } finally {
            if (defaultDatabase == null) restoreDatabase()
            leavePlayer(p.first)
        }
    }

    @Test
    fun gameOverSkipsTheRatingMenuForPlayersWhoLeft() {
        val originalConf = Main.conf
        val originalStart = mapStartTime
        val originalInfinite = Vars.state.rules.infiniteResources
        val p = newPlayer()
        val uuid = p.first.uuid()
        val snapshot = players.toList()

        try {
            Main.conf = originalConf.copy(feature = originalConf.feature.copy(mapVote = true))
            Vars.state.rules.infiniteResources = true
            mapStartTime = timeSource.markNow() - 10.minutes
            mapRatings.remove(uuid)

            // The player is gone but their data has not been unregistered yet.
            players.clear()
            players.add(p.second)
            p.first.remove()
            Groups.player.update()

            val menusBefore = Menus.registerMenu { _, _ -> }
            gameOver(GameOverEvent(Team.crux))
            awaitPumped(5000L) { false }
            val menusAfter = Menus.registerMenu { _, _ -> }

            assertEquals(
                1,
                menusAfter - menusBefore,
                "No rating menu should be built for a player who already left."
            )
        } finally {
            Vars.state.rules.infiniteResources = originalInfinite
            mapStartTime = originalStart
            Main.conf = originalConf
            players.clear()
            players.addAll(snapshot)
            players.removeIf { it.uuid == uuid }
            mapRatings.remove(uuid)
        }
    }

    @Test
    fun playerDataDeniedWhenAllowWithoutDataDisabled() {
        val originalConf = Main.conf
        var target: mindustry.gen.Player? = null

        withoutLogErrors {
            try {
                Main.conf = originalConf.copy(
                    feature = originalConf.feature.copy(
                        playerData = originalConf.feature.playerData.copy(allowWithoutData = false)
                    )
                )
                breakDatabase()

                val joined = joinPlayer()
                target = joined
                awaitPumped(3000L) { false }
                assertNull(
                    playerDataOf(joined.uuid()),
                    "allowWithoutData ê° false ë©´ ìì ë°ì´í°ê° ë±ë¡ëì§ ììì¼ í©ëë¤."
                )
                assertFalse(
                    allowPlaceBlock(joined),
                    "allowWithoutData ê° false ë©´ ë¸ë¡ ì¤ì¹ê° ê±°ë¶ëì´ì¼ í©ëë¤."
                )

                // The filter must be deciding on allowWithoutData, not on some other rule.
                Main.conf = Main.conf.copy(
                    feature = Main.conf.feature.copy(
                        playerData = Main.conf.feature.playerData.copy(allowWithoutData = true)
                    )
                )
                assertNull(playerDataOf(joined.uuid()))
                assertTrue(
                    allowPlaceBlock(joined),
                    "Without player data the filter must fall through to allowWithoutData."
                )
                Main.conf = Main.conf.copy(
                    feature = Main.conf.feature.copy(
                        playerData = Main.conf.feature.playerData.copy(allowWithoutData = false)
                    )
                )

                restoreDatabase()

                assertTrue(
                    awaitPumped(40000L) { playerDataOf(joined.uuid()) != null },
                    "DB ê° ë³µêµ¬ëë©´ ì¤ì  ë°ì´í°ê° ë±ë¡ëì´ì¼ í©ëë¤."
                )
            } finally {
                Main.conf = originalConf
                if (defaultDatabase == null) restoreDatabase()
                target?.let { leavePlayer(it) }
            }
        }
    }

    @Test
    fun vanillaAdminJoinsIntoAdminGroup() {
        val target = createPlayer()
        val uuid = target.uuid()
        Vars.netServer.admins.adminPlayer(uuid, target.usid())

        try {
            val data = PluginTest.joinPlayer(target)

            assertEquals(Main.conf.feature.permission.vanillaAdminGroup, data.permission)
            assertTrue(target.admin(), "A vanilla admin should keep the admin flag after joining")
            assertFalse(
                Permission.hasUserEntry(uuid),
                "Joining as a vanilla admin must not write a permission_user.yaml entry: that file is " +
                    "per-server and masks the group column all six servers share"
            )
        } finally {
            leavePlayer(target)
            Vars.netServer.admins.unAdminPlayer(uuid)
        }
    }
    /**
     * 2026-09-08-full-audit-04-8: the PvP end-of-round check crowned the first active team that still
     * had somebody connected, which is the team that was just eliminated once the survivors have left.
     */
    @Test
    fun pvpGameOverCrownsTheTeamThatStillHoldsACore() {
        val teams = Vars.state.teams
        val cruxData = teams.get(Team.crux)
        val shardedData = teams.get(Team.sharded)
        val savedPvp = Vars.state.rules.pvp
        val savedGameOver = Vars.state.gameOver
        val savedInfinite = Vars.state.rules.infiniteResources
        val savedTeams = Groups.player.map { it to it.team() }

        // Other tests leave cores registered on teams this one never mentions (pvpBalanceTest plants
        // one for green and blue), so the board is built from a known-empty state rather than assumed.
        val touched = (teams.active.toList() + cruxData + shardedData).distinct()
        val savedCores = touched.map { it to it.cores.toList() }
        touched.forEach { it.cores.clear() }

        val winners = CopyOnWriteArrayList<Team>()
        val listener = Cons<GameOverEvent> { winners.add(it.winner) }

        val p = newPlayer()

        // Crux has just lost its last core, so it is no longer alive, but its remaining buildings and
        // its connected player keep it in the active list - exactly the state the defect crowned.
        val cruxWall = Blocks.copperWall.newBuilding().create(Blocks.copperWall, Team.crux)
        cruxData.buildings.add(cruxWall)
        if (!teams.active.contains(cruxData)) teams.active.add(cruxData)

        // Sharded survives with a core but has nobody online.
        val shardedCore = (Blocks.coreShard.newBuilding() as CoreBuild).also {
            it.team = Team.sharded
            shardedData.cores.add(it)
        }
        if (!teams.active.contains(shardedData)) teams.active.add(shardedData)

        try {
            Vars.state.rules.pvp = true
            Vars.state.gameOver = false
            // Keep the round's EXP and pvpWinCount writes out of the shared test database; what is
            // under test is which team the event names, not what gameOver then persists for it.
            Vars.state.rules.infiniteResources = true

            // Everyone still connected is on the eliminated team, which is the reproduction: the
            // survivors have left. Any player left on another team would put isWaitingForPlayers at
            // two teams present and the branch under test would never run.
            Groups.player.forEach { it.team(Team.crux) }
            Groups.player.update()

            assertFalse(cruxData.isAlive(), "Precondition: the eliminated team must hold no core")
            assertTrue(shardedData.isAlive(), "Precondition: the surviving team must hold a core")
            assertEquals(
                listOf(Team.sharded),
                teams.getActive().filter { it.isAlive() && it.team != Team.derelict }.map { it.team },
                "Precondition: exactly one team is alive, or this test is measuring leftover state"
            )
            assertTrue(
                Vars.netServer.isWaitingForPlayers,
                "Precondition: fewer than two teams have players online, which is what gated the defect"
            )

            Events.on(GameOverEvent::class.java, listener)

            val destroyedCore = Blocks.coreShard.newBuilding().create(Blocks.coreShard, Team.crux)
            val bullet = Bullet.create()
            bullet.team = Team.sharded
            buildingBulletDestroy(BuildingBulletDestroyEvent(destroyedCore, bullet))

            assertEquals(
                listOf(Team.sharded),
                winners.toList(),
                "The round must be won by the team that still holds a core, not by the one that still has players"
            )
        } finally {
            Events.remove(GameOverEvent::class.java, listener)
            cruxData.buildings.remove(cruxWall)
            shardedData.cores.remove(shardedCore)
            savedCores.forEach { (data, cores) ->
                data.cores.clear()
                cores.forEach { data.cores.add(it) }
            }
            Vars.state.rules.infiniteResources = savedInfinite
            Vars.state.rules.pvp = savedPvp
            Vars.state.gameOver = savedGameOver
            savedTeams.forEach { (entity, team) -> entity.team(team) }
            Groups.player.update()
            leavePlayer(p.first)
        }
    }
    /**
     * 2026-09-08-full-audit-03-1: Arc runs listeners inline, so a config reload fired from the watcher
     * thread reached Mindustry entity writes off the game thread.
     */
    @Test
    fun configFileEventsNeverRunOnTheWatcherThread() {
        val configDir = rootPath.child("config")
        configDir.mkdirs()
        val probe = configDir.child("fleet-watch-probe.yaml")
        probe.writeString("probe: 1", false)

        val delivered = CopyOnWriteArrayList<Thread>()
        val listener = Cons<CustomEvents.ConfigFileModified> { delivered.add(Thread.currentThread()) }
        Events.on(CustomEvents.ConfigFileModified::class.java, listener)

        val pumpingThread = Thread.currentThread()
        val watcher = Thread({ fileWatchService() }, "fleet-test-config-watcher")
        watcher.isDaemon = true
        watcher.start()

        try {
            // Give the watch service time to register the directory before the edit it must notice.
            Thread.sleep(1000)
            probe.writeString("probe: 2", false)

            assertTrue(
                awaitPumped(30000) { delivered.isNotEmpty() },
                "The watcher never delivered a config event, so this test proved nothing"
            )
            assertFalse(
                delivered.contains(watcher),
                "Config events must reach listeners on the game thread, not on the file-watcher thread"
            )
            // The plugin starts its own watcher during loadGame, so asserting only against this test's
            // thread would pass whenever that other watcher won the delivery race. Every delivery has to
            // land on the thread that pumps Core.app, whichever watcher produced it.
            assertEquals(
                listOf(pumpingThread),
                delivered.distinct(),
                "Every config event must be delivered on the thread that pumps the application queue"
            )
        } finally {
            Events.remove(CustomEvents.ConfigFileModified::class.java, listener)
            watcher.interrupt()
            watcher.join(5000)
            probe.delete()
        }
    }
    /**
     * 2026-09-08-full-audit-09-7: unbanning an address that no PlayerInfo carries threw before the
     * coroutine that clears the shared ban table was ever launched.
     */
    @Test
    fun ipUnbanClearsTheSharedBanRowWithoutAPlayerInfo() {
        val ip = "203.0.113.9"
        val info = Administration.PlayerInfo()
        info.id = "fleetunban" + (System.nanoTime() % 100000000L)
        info.lastName = "ghost"
        info.lastIP = ip
        info.names.add("ghost")
        info.ips.add(ip)

        val announced = CopyOnWriteArrayList<String>()
        val listener = Cons<CustomEvents.PlayerUnbanned> { announced.add(it.name) }
        Events.on(CustomEvents.PlayerUnbanned::class.java, listener)

        try {
            runBlocking { createBanInfo(info, "fleet regression") }
            assertTrue(
                runBlocking { checkPlayerBannedByIpOrUuid(info.id, ip) },
                "Precondition: the ban row must exist before the unban"
            )
            assertNull(
                Vars.netServer.admins.findByIP(ip),
                "Precondition: no PlayerInfo may carry the address, which is what made findByIP return null"
            )

            playerIpUnban(PlayerIpUnbanEvent(ip))

            assertEquals(listOf(ip), announced.toList(), "The unban must still be announced")
            assertTrue(
                awaitPumped(15000) { runBlocking { !checkPlayerBannedByIpOrUuid(info.id, ip) } },
                "The row in the shared ban table must be cleared, or every other server stays banned"
            )
        } finally {
            Events.remove(CustomEvents.PlayerUnbanned::class.java, listener)
            runBlocking { removeBanInfoByIP(ip) }
        }
    }

    /**
     * 2026-09-08-full-audit-03-1: Permission.apply writes Mindustry player entities, and /reload calls
     * it from Dispatchers.IO. It has to reach the game thread before it touches a player.
     */
    @Test
    fun permissionApplyDefersPlayerWritesToTheGameThread() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val file = rootPath.child("permission_user.yaml")
        val savedFile = file.readString()
        val originalName = target.first.name()

        try {
            file.writeString("$uuid:\n  name: deferred-rename\n  group: user\n", false)
            Permission.load()

            assertEquals(
                originalName,
                target.first.name(),
                "Permission.apply must not write the player entity inline, or /reload does it from an IO thread"
            )
            assertTrue(
                awaitPumped(5000) { target.first.name() == "deferred-rename" },
                "The rename must land once the game thread runs the posted work"
            )
        } finally {
            file.writeString(savedFile, false)
            Permission.load()
            awaitPumped(2000) { false }
            target.first.name(originalName)
            target.second.name = originalName
            leavePlayer(target.first)
        }
    }
    /**
     * Menu ids are process-wide and menuChoose is client-callable with any id, so a listener that acts
     * for the player it was opened for has to compare the responder. Hub warp zone.
     */
    @Test
    fun onlyTheHubAdminWhoOpenedTheZoneMenuMayAnswerIt() {
        val owner = newPlayer()
        val intruder = newPlayer()
        val tile = Vars.world.tile(30, 30)
        val zonesBefore = pluginData.data.warpZone.size

        try {
            owner.second.status["hub_first"] = "10,10"
            owner.second.status["hub_second"] = "true"
            owner.second.status["hub_ip"] = "127.0.0.1"
            owner.second.status["hub_port"] = "6567"

            tap(TapEvent(owner.first, tile))
            val zoneMenu = Menus.registerMenu { _, _ -> } - 1

            Menus.menuChoose(intruder.first, zoneMenu, 0)
            assertEquals(
                zonesBefore,
                pluginData.data.warpZone.size,
                "A player the hub menu was never shown to must not be able to write a warp zone"
            )

            Menus.menuChoose(owner.first, zoneMenu, 0)
            assertEquals(
                zonesBefore + 1,
                pluginData.data.warpZone.size,
                "The player the menu was opened for must still be able to answer it"
            )
        } finally {
            // The listener persists through scope.launch, so let that land before trimming, then write
            // the trimmed list back rather than leaving the test's zone in the stored blob.
            awaitPumped(2000L) { false }
            while (pluginData.data.warpZone.size > zonesBefore) {
                pluginData.data.warpZone.removeAt(pluginData.data.warpZone.size - 1)
            }
            runBlocking { pluginData.update() }
            owner.second.status.clear()
            leavePlayer(intruder.first)
            leavePlayer(owner.first)
        }
    }
    /**
     * The same class of defect on the end-of-round rating menus: the rating is recorded under the uuid
     * the menu was opened for, so anyone answering it rates in that player's name and locks them out.
     */
    @Test
    fun onlyThePlayerShownTheRatingMenuMayRateTheMap() {
        val originalConf = Main.conf
        val originalStart = mapStartTime
        val originalInfinite = Vars.state.rules.infiniteResources
        val owner = newPlayer()
        val intruder = newPlayer()
        val ownerUuid = owner.first.uuid()
        val ratedBefore = mapRatings.keys.toSet()

        try {
            Main.conf = originalConf.copy(feature = originalConf.feature.copy(mapVote = true))
            Vars.state.rules.infiniteResources = true
            mapStartTime = timeSource.markNow() - 10.minutes

            // Exactly one menu must be built, so the last registered id is unambiguously the owner's.
            players.forEach { if (it.uuid != ownerUuid) mapRatings[it.uuid] = true }
            mapRatings.remove(ownerUuid)

            gameOver(GameOverEvent(Team.crux))
            awaitPumped(5000L) { false }
            val difficultyMenu = Menus.registerMenu { _, _ -> } - 1

            val menusBefore = Menus.registerMenu { _, _ -> }
            Menus.menuChoose(intruder.first, difficultyMenu, 0)
            assertEquals(
                1,
                Menus.registerMenu { _, _ -> } - menusBefore,
                "Answering another player's difficulty menu must not open a rating menu"
            )

            Menus.menuChoose(owner.first, difficultyMenu, 0)
            val ratingMenu = Menus.registerMenu { _, _ -> } - 1

            Menus.menuChoose(intruder.first, ratingMenu, 4)
            assertFalse(
                mapRatings.containsKey(ownerUuid),
                "Another player must not be able to rate the map in the owner's name"
            )

            Menus.menuChoose(owner.first, ratingMenu, 4)
            assertTrue(
                mapRatings.containsKey(ownerUuid),
                "The player the menu was opened for must still be able to rate"
            )
        } finally {
            awaitPumped(2000L) { false }
            mapRatings.keys.toList().forEach { if (it !in ratedBefore) mapRatings.remove(it) }
            Vars.state.rules.infiniteResources = originalInfinite
            mapStartTime = originalStart
            Main.conf = originalConf
            leavePlayer(intruder.first)
            leavePlayer(owner.first)
        }
    }

    /**
     * The record.* status keys are the counters the achievements module persists. Earned on a
     * temporary player object was dropped when that object was merged into the real one.
     */
    @Test
    fun mergingATemporaryPlayerCarriesTheAchievementCounters() {
        val target = newPlayer()
        val temporary = newPlayer()

        try {
            // Real running totals from AchievementEvents.
            target.second.status["record.wave"] = "3"
            temporary.second.status["record.wave"] = "4"
            temporary.second.status["record.crawler.block.destroy"] = "2"
            temporary.second.status["login_consent"] = "token"

            // Not totals: a raw currentTimeMillis stamp, and two windows that are reset to zero.
            target.second.status["record.turret.quill.kill.time"] = "1000"
            temporary.second.status["record.turret.quill.kill.time"] = "2000"
            target.second.status["record.pvp.win.streak.current"] = "3"
            temporary.second.status["record.pvp.win.streak.current"] = "4"
            target.second.status["record.time.noafk"] = "5"
            temporary.second.status["record.time.noafk"] = "6"

            // Burst counts with no suffix to give them away: AchievementEvents resets them to 1 once the
            // paired .time stamp is more than ten seconds old, and QuillKiller reads the count at 5.
            target.second.status["record.turret.quill.kill"] = "3"
            temporary.second.status["record.turret.quill.kill"] = "3"
            target.second.status["record.turret.zenith.kill"] = "10"
            temporary.second.status["record.turret.zenith.kill"] = "20"

            mergeTemporaryPlayerData(temporary.second, target.second)

            assertEquals("7", target.second.status["record.wave"], "A counter both objects hold must be summed")
            assertEquals(
                "2",
                target.second.status["record.crawler.block.destroy"],
                "A counter only the temporary object holds must be carried"
            )
            assertEquals(
                "1000",
                target.second.status["record.turret.quill.kill.time"],
                "A currentTimeMillis stamp must not be summed: the sum is a future time and its window never closes"
            )
            assertEquals(
                "3",
                target.second.status["record.pvp.win.streak.current"],
                "A streak that gets reset to zero must not be summed: two part-runs are not one run"
            )
            assertEquals(
                "5",
                target.second.status["record.time.noafk"],
                "A per-map continuous window must not be summed"
            )
            assertEquals(
                "3",
                target.second.status["record.turret.quill.kill"],
                "A burst count must not be summed: two players mid-burst at three would merge over QuillKiller"
            )
            assertEquals(
                "10",
                target.second.status["record.turret.zenith.kill"],
                "A burst count must not be summed, whatever it is named"
            )
            assertFalse(
                target.second.status.containsKey("login_consent"),
                "Session state must not be carried across: one of these keys is a consent token whose second use deletes the row"
            )
        } finally {
            target.second.status.clear()
            temporary.second.status.clear()
            leavePlayer(temporary.first)
            leavePlayer(target.first)
        }
    }

    /**
     * Every world replacement fires WorldLoadEvent, so clearing the block history there is the only way
     * to catch /vote back and console load, which rewind the world without going through a command site.
     */
    @Test
    fun aWorldLoadClearsTheBlockHistory() {
        val x: Short = 41
        val y: Short = 41

        WorldHistoryBuffer.enqueue(
            time = System.currentTimeMillis(),
            player = "fleet",
            action = "place",
            x = x,
            y = y,
            tile = "copper-wall",
            rotate = 0,
            team = Team.sharded.name,
            value = null
        )
        assertEquals(
            "copper-wall",
            WorldHistoryBuffer.getLastBlock(x, y),
            "Precondition: the buffer has to hold the block, or this test proves nothing"
        )

        worldLoad(WorldLoadEvent())

        // The cache is cleared last, so reaching this also proves the TRUNCATE ran.
        assertTrue(
            awaitPumped(10000L) { WorldHistoryBuffer.getLastBlock(x, y) == null },
            "A world load must clear the block history recorded on the map that was just replaced"
        )

        // The queue is the half that used to survive the clear: the row was still pending when the
        // TRUNCATE ran, so the next flush wrote it into the table the world load had just emptied.
        // This flush stands in for that tick, which makes the assertion below independent of timing.
        runBlocking { WorldHistoryBuffer.flush() }
        assertTrue(
            runBlocking { getWorldHistoryByCoordinates(x, y) }.isEmpty(),
            "A flush after a world load must not write back rows recorded on the map that was replaced"
        )
    }

}
