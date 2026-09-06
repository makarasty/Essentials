import PluginTest.Companion.clientCommand
import PluginTest.Companion.createPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.player
import PluginTest.Companion.serverCommand
import PluginTest.Companion.setPermission
import essential.common.database.data.checkRoutingPermission
import essential.common.database.data.consumeRoutingPermission
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.database.data.createTemporaryPlayerData
import essential.common.database.data.getPlayerData
import essential.common.database.data.setAchievement
import essential.common.database.data.plugin.WarpBlock
import essential.common.database.databaseClose
import essential.common.database.databaseInit
import essential.common.database.defaultDatabase
import essential.common.database.table.ServerRoutingTable
import essential.common.event.CustomEvents
import essential.common.mapStartTime
import essential.common.players
import essential.common.permission.Permission
import essential.common.pluginData
import essential.common.rootPath
import essential.common.systemTimezone
import essential.common.timeSource
import essential.core.Main
import essential.core.connectPacket
import essential.core.gameOver
import essential.core.loadJoinedPlayerData
import essential.core.mapRatings
import essential.core.playerDataRetries
import essential.core.service.achievements.AchievementHooks
import essential.core.swapTemporaryPlayerData
import essential.core.tap
import arc.Core
import arc.Events
import arc.backend.headless.HeadlessApplication
import arc.func.Cons
import arc.util.Log
import arc.util.TaskQueue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.EventType.ConnectPacketEvent
import mindustry.game.EventType.GameOverEvent
import mindustry.game.EventType.PlayerJoin
import mindustry.game.EventType.TapEvent
import mindustry.game.EventType.WorldLoadEvent
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.net.Administration
import mindustry.net.NetConnection
import mindustry.ui.Menus
import mindustry.net.Packets
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.CopyOnWriteArrayList
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

    private fun awaitCondition(timeoutMs: Long = 3000L, intervalMs: Long = 50L, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
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
        val field = HeadlessApplication::class.java.getDeclaredField("runnables")
        field.isAccessible = true
        (field.get(Core.app) as TaskQueue).run()
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
        try {
            val field = HeadlessApplication::class.java.getDeclaredField("runnables")
            field.isAccessible = true
            (field.get(Core.app) as TaskQueue).run()
        } catch (_: Exception) {
        }
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
        for (team in testTeams) {
            val tile = PluginTest.randomTile()
            tile.setNet(mindustry.content.Blocks.coreShard, team, 0)
        }
        
        val activeTeams = Vars.state.teams.active.filter { testTeams.contains(it.team) }.toList()
        assertEquals(4, activeTeams.size, "Need 4 teams for test")
        
        val winRates = listOf(1.0, 0.75, 0.5, 0.25)
        
        // Fill teams with 2 players each
        for (i in activeTeams.indices) {
            val team = activeTeams[i].team
            val rate = winRates[i]
            repeat(2) {
                val p = newPlayer()
                // Force join the specific team for initial setup
                p.first.team(team)
                p.second.pvpWinCount = (rate * 100).toInt().toShort()
                p.second.pvpLoseCount = ((1.0 - rate) * 100).toInt().toShort()
            }
        }

        // The teams should be sorted by win rate: D, C, B, A (lowest first)
        val sortedActiveTeams = activeTeams.sortedBy { teamData ->
            val teamPlayers = players.filter { it.player.team() == teamData.team }
            if (teamPlayers.isEmpty()) 0.5 else teamPlayers.map {
                val total = it.pvpWinCount + it.pvpLoseCount
                if (total == 0) 0.5 else it.pvpWinCount.toDouble() / total
            }.average()
        }
        
        val teamLowest = sortedActiveTeams[0].team
        val teamSecondLowest = sortedActiveTeams[1].team

        // Add one more player - should go to teamLowest (lowest win rate)
        val p9 = newPlayer()
        assertEquals(teamLowest, p9.first.team(), "Player 9 should go to lowest win rate team")

        // Add one more player - should go to teamLowest (it can have up to 2 more than others)
        val p10 = newPlayer()
        assertEquals(teamLowest, p10.first.team(), "Player 10 should go to lowest win rate team")

        // Now teamLowest has 4 players (2 initial + 2 new), others have 2. 
        // min=2, Lowest=4. 4 < 2 + 2 is false.
        // Next player should go to teamSecondLowest
        val p11 = newPlayer()
        assertEquals(teamSecondLowest, p11.first.team(), "Player 11 should go to second lowest win rate team")

        clientCommand.handleMessage("/status", player)

        // Verify final counts
        val finalCounts = activeTeams.map { teamData ->
            players.count { it.player.team() == teamData.team }
        }
        // The one with lowest win rate should have 4, one with second lowest should have 3, others 2.
        assertTrue(finalCounts.contains(4))
        assertTrue(finalCounts.contains(3))
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

            tap(TapEvent(testPlayer, tile))

            assertTrue(
                awaitCondition {
                    val dbData = runBlocking { getPlayerData(testPlayerData.uuid) }
                    dbData != null && dbData.exp == 99999 && dbData.totalPlayed == 88888 && dbData.blockPlaceCount == 77777 && !dbData.isConnected
                },
                "WarpBlock 탭 후 대상 서버 연결 전에 playerData가 DB에 즉시 저장되어야 합니다."
            )
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
        } finally {
            leavePlayer(target)
            Vars.netServer.admins.unAdminPlayer(uuid)
        }
    }
}
