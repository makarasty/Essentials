import arc.ApplicationCore
import arc.Core
import arc.Events
import arc.Settings
import arc.backend.headless.HeadlessApplication
import arc.files.Fi
import arc.func.Cons
import arc.graphics.Camera
import arc.graphics.Color
import arc.struct.ObjectMap
import arc.struct.Seq
import arc.util.CommandHandler
import arc.util.Log
import arc.util.TaskQueue
import arc.util.Time
import arc.util.Timer
import essential.common.bundle
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.database.data.checkPlayerBanned
import essential.common.database.data.getPlayerData
import essential.common.database.LEGACY_BASELINE_VERSION
import essential.common.database.data.getPluginData
import essential.common.database.databaseClose
import essential.common.database.defaultDatabase
import essential.common.database.worldHistoryDatabase
import essential.common.offlinePlayers
import essential.common.players
import essential.common.rootPath
import essential.core.CoreConfig
import essential.core.Main
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.Vars.*
import mindustry.content.UnitTypes
import mindustry.core.*
import mindustry.ctype.ContentType
import mindustry.game.EventType
import mindustry.game.EventType.ServerLoadEvent
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Playerc
import mindustry.maps.Map
import mindustry.mod.Mod
import mindustry.net.Net
import mindustry.net.NetConnection
import mindustry.world.Block
import mindustry.world.Tile
import net.datafaker.Faker
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.io.File
import java.lang.Thread.sleep
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The whole suite runs in one JVM on one engine, so the plugin's global state - the world, `Groups.player`,
 * the plugin's own `players` and `offlinePlayers` lists, `pluginData`, permission state, the H2 file -
 * is shared by every test class rather than rebuilt per class. The contract that makes that survivable
 * has one hook, [loadGame], which every test class already calls from its `@BeforeTest`.
 *
 * **What a class may assume on entry.** The first [loadGame] call a class makes resets the shared state
 * for it: every player left connected by the previous class is disconnected, both `players` and
 * `offlinePlayers` are emptied, the world is [testMap] at [testMap]'s size with the game playing,
 * `limitMapArea` is off and no team has build AI. So a class starts on the harness map with nobody
 * online. It may assume nothing else - not the database contents, not `pluginData`, not `Main.conf`,
 * not the engine's `Settings`, and not `state.rules` beyond the two fields named above - because those
 * are not reset and other classes write them.
 *
 * **What a class must leave on exit.** Nothing. The reset is on entry on purpose: the class that has to
 * be cleaned up after is exactly the one that died halfway through and never reached its own teardown.
 * A class that restores what it changed still helps the run, but no other class depends on it doing so.
 *
 * **What that costs you.** Two things follow from the reset being per class rather than per test method.
 * A class holding a player across its own test methods (the `private var done` idiom) keeps that player
 * for the whole class, and interference between its own methods is its own to solve. And a class whose
 * `@BeforeTest` calls [loadGame] on every method still resets once, on the first, because the reset is
 * keyed to the calling class rather than to the call.
 *
 * **What [stopPlugin] undoes, and what it does not.** It closes the databases, deletes the H2 files and
 * takes the plugin's arc event listeners back off, because `Main.init` registers them and a plugin that
 * a real server loads once has no unload path - without that, every later [loadPlugin] stacked another
 * whole set, one `Events.fire` ran the plugin's handler once per historical load, and the achievement
 * sweep ran once a second per load. It does not stop the two threads `Main.init` starts or remove the
 * `Administration.ActionFilter` it installs; those still accumulate across a run.
 *
 * **What is not the harness's job.** State the plugin leaks that a real server would leak too - a
 * listener a running server never removes, a task it never cancels - is a production defect the suite
 * would be hiding by cleaning up after it. Report those rather than adding them here. The listener
 * teardown above is not one of those: it undoes a registration only a test can cause twice.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PluginTest {
    companion object {
        private lateinit var main: Main
        private val r = Random()
        lateinit var player: Playerc
        lateinit var path: Fi
        val serverCommand: CommandHandler = CommandHandler("")
        val clientCommand: CommandHandler = CommandHandler("/")

        private var gameLoaded = false
        private var pluginLoaded = false

        private val baseLogHandler: Log.LogHandler = Log.logger

        var testMap: Map? = null

        private var currentTestClass: String? = null

        /**
         * The test class that called into the harness, so [loadGame] can tell a new class apart from a
         * second call by the class already running.
         *
         * PluginTest's own tests call [loadGame] too and their frames carry this class's name, so the
         * walk steps over the harness's own frames and reads what is behind them. For PluginTest itself
         * that is a JDK reflection frame whose identity varies with the JDK and with whether the
         * accessor has been inflated yet, and treating two of those as two classes would fire a reset in
         * the middle of a test, so anything that is not a project frame is reported as PluginTest.
         */
        private fun callerTestClass(): String {
            val frames = Thread.currentThread().stackTrace
            var i = frames.indexOfFirst { it.className.substringBefore('$') == "PluginTest" }
            if (i < 0) return "PluginTest"
            while (i < frames.size && frames[i].className.substringBefore('$') == "PluginTest") i++
            val caller = frames.getOrNull(i) ?: return "PluginTest"
            val name = caller.className.substringBefore('$')
            val foreign = caller.fileName == null ||
                listOf("java.", "jdk.", "sun.", "org.junit.", "kotlin.").any { name.startsWith(it) }
            return if (foreign) "PluginTest" else name
        }

        /**
         * Puts the engine back into the state [loadGame] leaves behind on a cold boot, so the class
         * about to start does not inherit the last one's world or its players.
         *
         * Disconnecting a leftover player is best effort, because a class that died halfway is exactly
         * the case this exists for and one unhappy player must not fail the class that is cleaning up
         * after it. The world is not best effort: `world.loadMap` empties the world before it reads the
         * map back, so a read that failed silently would hand every later class a 0x0 world.
         */
        private fun resetSharedState() {
            val leftovers = Groups.player.toList()
            if (leftovers.isNotEmpty()) {
                leftovers.forEach { leaving ->
                    runCatching {
                        leaving.unit()?.takeIf { it.isValid }?.remove()
                        NetServer.onDisconnect(leaving, "test class boundary")
                        Events.fire(EventType.PlayerLeave(leaving))
                    }
                    runCatching { leaving.remove() }
                }
                runCatching { Groups.player.update() }
                waitUntil(3000) { players.isEmpty() && Groups.player.size() == 0 }
            }
            // Outside that guard on purpose: a class that ran while the plugin was stopped still leaves
            // rows in both lists, and the reload below runs Groups.clear(), which would otherwise leave
            // the plugin holding players the engine no longer has.
            players.clear()
            offlinePlayers.clear()

            // Reloaded only when the world is not already the one the cold boot leaves behind. loadMap
            // fires WorldLoadEvent, which starts the plugin's async map work, and the three classes that
            // repoint defaultDatabase straight after loadGame would take that work on the database they
            // have just swapped in.
            val map = testMap
            if (map != null && (state.map !== map || world.width() != map.width || world.height() != map.height)) {
                world.loadMap(map)
                logic.play()
                state.set(GameState.State.playing)
            }
            check(world.width() > 0 && world.height() > 0) {
                "class-entry reset left an empty world: ${map?.name()} came back ${world.width()}x${world.height()}"
            }
            state.rules.limitMapArea = false
            Team.all.forEach { t -> state.rules.teams.get(t).buildAi = false }
        }

        @OptIn(ExperimentalPathApi::class)
        fun loadGame(loadPlugin: Boolean = false, deleteConfig: Boolean = true, logHandler: (String) -> Unit = {}, force: Boolean = false) {
            if (gameLoaded && !force) {
                val caller = callerTestClass()
                if (caller != currentTestClass) {
                    currentTestClass = caller
                    resetSharedState()
                }
                if (loadPlugin) loadPlugin()
                return
            }
            currentTestClass = callerTestClass()
            Core.settings = Settings()
            Core.settings.dataDirectory = Fi("")
            path = Core.settings.dataDirectory

            path.child("maps").deleteDirectory()
            path.child("scripts").deleteDirectory()
            if (deleteConfig) {
                path.child("config").deleteDirectory()
                Main.conf = CoreConfig()
            }

            path.child("locales").writeString("en", false)
            path.child("version.properties")
                .writeString("modifier=release\ntype=official\nnumber=7\nbuild=custom build", false)

            if (!path.child("maps").exists()) {
                path.child("maps").mkdirs()

                ZipFile(Paths.get("src", "test", "resources", "maps.zip").toFile().absolutePath).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        if (entry.isDirectory) {
                            File(path.child("maps").absolutePath(), entry.name).mkdirs()
                        } else {
                            zip.getInputStream(entry).use { input ->
                                File(path.child("maps").absolutePath(), entry.name).outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            }

            if (!path.child("scripts").exists()) {
                path.child("scripts").mkdirs()
                listOf("global.js", "base.js").forEach { script ->
                    val contents = checkNotNull(PluginTest::class.java.getResourceAsStream("/scripts/$script")) {
                        "oh no.. Anuke delete scripts/$script from dependencies.jar"
                    }.bufferedReader().use { it.readText() }
                    path.child("scripts/$script").writeString(contents, false)
                }
            }

            if (loadPlugin) {
                path.child("mods/Essentials").deleteDirectory()
                path.child("config/mods/Essentials").deleteDirectory()
            }

            try {
                val begins = booleanArrayOf(false)
                val exceptionThrown = arrayOf<Throwable?>(null)
                Log.useColors = false

                val core: ApplicationCore = object : ApplicationCore() {
                    override fun setup() {
                        // Reset to the pristine logger first so prior tests' handlers don't stack.
                        Log.logger = baseLogHandler
                        val originalLogger = Log.logger
                        Log.logger = Log.LogHandler { level, text ->
                            originalLogger.log(level, text)
                            logHandler(text)
                            if (level == Log.LogLevel.err) {
                                throw RuntimeException("Error detected in logs: $text")
                            }
                        }
                        headless = true
                        net = Net(null)
                        tree = FileTree()
                        Vars.init()
                        world = object : World() {
                            override fun getDarkness(x: Int, y: Int): Float {
                                return 0F
                            }
                        }
                        content.createBaseContent()
                        mods.loadScripts()
                        content.createModContent()

                        add(Logic().also { logic = it })
                        add(NetServer().also { netServer = it })

                        content.init()

                        mods.eachClass(Mod::init)

                        if (mods.hasContentErrors()) {
                            for (mod in mods.list()) {
                                if (mod.hasContentErrors()) {
                                    for (cont in mod.erroredContent) {
                                        throw RuntimeException(
                                            "error in file: " + cont.minfo.sourceFile.path(),
                                            cont.minfo.baseError
                                        )
                                    }
                                }
                            }
                        }
                    }

                    override fun init() {
                        super.init()
                        if (loadPlugin) loadPlugin()

                        begins[0] = true
                        testMap = maps.loadInternalMap("serpulo/groundZero")
                        Thread.currentThread().interrupt()
                    }
                }
                HeadlessApplication(core) { throwable: Throwable? -> exceptionThrown[0] = throwable }
                while (!begins[0]) {
                    if (exceptionThrown[0] != null) {
                        fail(exceptionThrown[0]!!.stackTraceToString())
                    }
                    sleep(10)
                }

                val block: Block? = content.getByName(ContentType.block, "build2")
                assertEquals("build2", block?.name, "2x2 construct block doesn't exist?")

                // Reset status
                Time.setDeltaProvider { 1f }
                logic.reset()
                state.set(GameState.State.menu)

                // Avoid load errors
                Version.build = 146
                Version.revision = 1

                path.child("locales").delete()
                path.child("version.properties").delete()

                Core.settings.put("debugMode", true)

                netClient = NetClient()
                Core.camera = Camera()

                // Load map
                world.loadMap(testMap)
                logic.play()
                state.set(GameState.State.playing)
                state.rules.limitMapArea = false
                Team.all.forEach { t -> state.rules.teams.get(t).buildAi = false }

                gameLoaded = true
            } catch (r: Throwable) {
                fail(r.stackTraceToString())
            }
        }

        /** arc keeps its listeners in one private static map, and nothing public can enumerate them. */
        @Suppress("UNCHECKED_CAST")
        private fun eventListenerTable(): ObjectMap<Any, Seq<Cons<*>>> {
            val field = Events::class.java.getDeclaredField("events")
            field.isAccessible = true
            return field.get(null) as ObjectMap<Any, Seq<Cons<*>>>
        }

        /** Same story for arc's timer: the scheduled tasks are reachable only through this field. */
        @Suppress("UNCHECKED_CAST")
        private fun scheduledTasks(): List<Timer.Task> {
            val field = Timer::class.java.getDeclaredField("tasks")
            field.isAccessible = true
            val timer = Timer.instance()
            return synchronized(timer) { (field.get(timer) as Seq<Timer.Task>).toList() }
        }

        // Written from the application thread on the cold path, read from the JUnit thread.
        @Volatile
        private var listenerBaseline: kotlin.collections.Map<Any, Int>? = null

        @Volatile
        private var timerBaseline: kotlin.collections.Set<Timer.Task>? = null

        fun loadPlugin(force: Boolean = false) {
            if (pluginLoaded && !force) return

            // Main.init() registers the plugin's listeners and schedules its repeating timer tasks, and
            // has no unload path, because a real server loads a plugin once and then exits. stopPlugin()
            // therefore has to take both off itself: without this every later load stacked another whole
            // set, one Trigger.update or TapEvent ran the plugin's handler once per load, and the
            // achievement sweep ran once a second per load instead of once a second.
            // Recorded only when no generation is live: a forced reload over a running plugin would
            // otherwise make that generation's registrations the baseline and leave them for good.
            if (listenerBaseline == null) {
                listenerBaseline = eventListenerTable().associate { it.key to it.value.size }
                timerBaseline = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Timer.Task, Boolean>())
                    .apply { addAll(scheduledTasks()) }
            }

            Main.conf = Main.conf.copy(
                module = Main.conf.module.copy(
                    achievement = true,
                    bridge = true,
                    chat = true,
                    discord = true,
                    protect = true,
                    web = true
                )
            )

            main = Main()

            main.init()

            main.registerClientCommands(clientCommand)
            main.registerServerCommands(serverCommand)

            Events.fire(ServerLoadEvent())
            pluginLoaded = true
        }

        fun stopPlugin() {
            Log.logger = baseLogHandler
            listenerBaseline?.let { baseline ->
                eventListenerTable().forEach { entry -> entry.value.truncate(baseline[entry.key] ?: 0) }
                listenerBaseline = null
            }
            timerBaseline?.let { baseline ->
                scheduledTasks().forEach { task -> if (task !in baseline) runCatching { task.cancel() } }
                timerBaseline = null
            }
            runBlocking {
                listOfNotNull(defaultDatabase, worldHistoryDatabase).forEach { db ->
                    try {
                        suspendTransaction(db = db) { exec("SHUTDOWN") }
                    } catch (_: Throwable) {
                    }
                }
            }
            databaseClose()

            // Two directories, because in this suite they are two. Database.kt opens H2 at a literal
            // ./config/mods/Essentials/data/, while rootPath is Core.settings.dataDirectory +
            // mods/Essentials - the same directory on a real server, whose data directory is config/,
            // and a different one here, where loadGame sets the data directory to the working
            // directory. Walking only rootPath meant this deleted nothing for the life of the suite:
            // PluginTest.dbUpgradeTest_20 boots src/test/resources/database-v3.mv.db through the
            // legacy scripts, which create no unique indexes, SchemaUtils.create skips tables that
            // already exist and the boot declines every index repair by design - so every class after
            // this one inherited a schema with no unique index on players.uuid, players.name or
            // player_achievements (player_id, achievement_name).
            //
            // The literals in Database.kt are correct where they are and are not touched: where a live
            // server opens its database is the operator's.
            for (dataDir in listOf(
                rootPath.child("data").file().toPath(),
                Paths.get("config", "mods", "Essentials", "data")
            )) {
                if (!Files.exists(dataDir)) continue
                try {
                    Files.walk(dataDir).use { stream ->
                        stream.filter { path ->
                            val name = path.fileName.toString()
                            (name.startsWith("database") || name.startsWith("worldHistory")) &&
                            path != dataDir
                        }.sorted(Comparator.reverseOrder()).forEach { path ->
                            // Said out loud, because a delete that quietly did nothing is the defect
                            // this loop was just repaired for: the next class boots on a database this
                            // one meant to destroy, and the symptom surfaces as somebody else's
                            // precondition failing three classes later. On Windows a file still held
                            // open by a connection pool an earlier databaseInit replaced without
                            // disposing is exactly how that happens.
                            //
                            // Windows-shaped, and knowingly so: a POSIX filesystem unlinks a file H2
                            // still holds open, so there the delete succeeds, this stays quiet, and an
                            // H2 kept alive by DB_CLOSE_DELAY=-1 goes on writing to an inode with no
                            // name. That is a different hazard and not one this line can see. It has
                            // never fired on this machine - zero across all 64 classes.
                            if (!path.toFile().delete() && Files.exists(path)) {
                                Log.warn("[test] stopPlugin could not delete $path; the next class will boot on it")
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            TransactionManager.defaultDatabase = null
            pluginLoaded = false
        }

        fun updateTick(times: Int) {
            Team.all.forEach { t -> state.rules.teams.get(t).buildAi = false }
            repeat(times) {
                logic.update()
            }
        }

        fun updateTick(times: Int, codes: () -> Unit) {
            Team.all.forEach { t -> state.rules.teams.get(t).buildAi = false }
            repeat(times) {
                logic.update()
                codes()
            }
        }

        private fun getSaltString(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890"
            val salt = StringBuilder()
            while (salt.length < 25) {
                val index = (r.nextFloat() * chars.length).toInt()
                salt.append(chars[index])
            }
            return salt.toString()
        }

        /**
         * 플레이어 생성
         * @return 플레이어
         */
        @OptIn(ExperimentalTime::class)
        fun createPlayer(): Player {
            val player = Player.create()
            val faker = Faker(Locale.ENGLISH)
            val ip = r.nextInt(255).toString() + "." + r.nextInt(255) + "." + r.nextInt(255) + "." + r.nextInt(255)

            player.reset()
            player.con = object : NetConnection(ip) {
                override fun send(`object`: Any?, reliable: Boolean) {
                    return
                }

                override fun close() {
                    return
                }
            }
            val name = faker.name().lastName() + Clock.System.now().toEpochMilliseconds()
            player.name(name)
            player.con.uuid = getSaltString()
            player.con.usid = getSaltString()
            player.set(r.nextInt(300).toFloat(), r.nextInt(500).toFloat())
            player.color.set(Color.rgb(r.nextInt(255), r.nextInt(255), r.nextInt(255)))
            player.color.a = r.nextFloat()
            player.team(Team.sharded)
            player.unit(UnitTypes.dagger.spawn(r.nextInt(300).toFloat(), r.nextInt(500).toFloat()))
            player.add()
            netServer.admins.getInfo(player.uuid())
            netServer.admins.updatePlayerJoined(player.uuid(), player.con.address, player.name)
            Groups.player.update()

            assertNotNull(player)
            return player
        }

        fun randomTile(): Tile {
            val random = Random()
            return world.tile(random.nextInt(100), random.nextInt(100))
        }

        /**
         * DB 에 계정이 등록된 플레이어 생성
         * @return 1번째 값에 플레이어, 2번째 값에 플레이어 정보
         */
        fun newPlayer(): Pair<Player, PlayerData> {
            val player = createPlayer()
            return Pair(player, joinPlayer(player))
        }

        /**
         * 이미 만들어진 플레이어를 서버에 접속시킴
         * @param player 플레이어
         * @return 플레이어 정보
         */
        fun joinPlayer(player: Player): PlayerData {
            Events.fire(EventType.PlayerJoin(player))
            var data: PlayerData? = null
            val deadline = System.currentTimeMillis() + 15000
            while (data == null && System.currentTimeMillis() < deadline) {
                pumpApp()
                sleep(16)
                data = players.find { it.uuid == player.uuid() }
            }
            return data ?: fail("Player ${player.uuid()} was not registered within timeout")
        }

        /**
         * 대상 플레이어가 서버에서 나갔다고 하기
         * @param player 플레이어
         */
        fun leavePlayer(player: Playerc) {
            // Player.unit() is nullable and PlayerComp.update() nulls it the moment the unit stops being
            // valid, which any tick of the simulation can do. Dereferencing it made a dead unit a test
            // failure in the teardown of a test that had already passed.
            player.unit()?.takeIf { it.isValid }?.remove()
            NetServer.onDisconnect(player.self(), "Player leaved")
            Events.fire(EventType.PlayerLeave(player.self()))
            player.remove()
            Groups.player.update()

            // Wait for database save time
            while (Groups.player.find { a -> a.uuid() == player.uuid() } != null) {
                sleep(10)
            }

            sleep(500)
        }

        /**
         * 현재 유저의 권한을 변경함
         * @param group 그룹명 (visitor, user, admin, owner)
         * @param admin 관리자 유무 (true, false)
         */
        fun setPermission(group: String, admin: Boolean) {
            serverCommand.handleMessage("setperm ${player.name()} $group")
            if (admin) {
                serverCommand.handleMessage("admin ${player.name()}")
            }
        }

        /**
         * 대상 플레이어의 권한을 변경함
         * @param player 플레이어
         * @param group 그룹명 (visitor, user, admin, owner)
         * @param admin 관리자 유무 (true, false)
         */
        fun setPermission(player: Playerc, group: String, admin: Boolean) {
            serverCommand.handleMessage("setperm ${player.name()} $group")
            if (admin) {
                serverCommand.handleMessage("admin ${player.name()}")
            }
        }

        fun err(key: String, vararg parameters: Any): String {
            return "[scarlet]" + Bundle().get(key, *parameters)
        }

        fun log(msg: String, vararg parameters: Any): String {
            return Bundle().get(msg, *parameters)
        }

        /**
         * Runs everything queued with Core.app.post, since the headless main loop is stopped in tests.
         */
        // Looked up once: observeMessages pumps in a tight loop, and a getDeclaredField per turn there
        // is most of the loop's cost.
        private val runnablesField = try {
            HeadlessApplication::class.java.getDeclaredField("runnables").apply { isAccessible = true }
        } catch (_: Exception) {
            null
        }

        fun pumpApp() {
            val queue = try {
                runnablesField?.get(Core.app) as? TaskQueue ?: return
            } catch (_: Exception) {
                return
            }
            queue.run()
        }

        /**
         * Every distinct value [data]'s message slot took while this waited, oldest first, stopping as
         * soon as one of them satisfies [until].
         *
         * PlayerData keeps only the newest message it was sent, so an assertion written as
         * `waitUntil { lastReceivedMessage == expected }` loses to any broadcast that lands behind the
         * reply it was waiting for - an achievement announcement, a join notice - and reads as if the
         * reply never came. Polling without sleeping and keeping what was seen makes the assertion about
         * what arrived, and makes the failure message say what arrived instead.
         */
        fun observeMessages(data: PlayerData, timeoutMs: Long = 5000, until: (String) -> Boolean): List<String> {
            val seen = LinkedHashSet<String>()
            var last = data.lastReceivedMessage
            seen.add(last)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                if (seen.any(until)) break
                if (System.currentTimeMillis() >= deadline) break
                pumpApp()
                val now = data.lastReceivedMessage
                if (now != last) {
                    seen.add(now)
                    last = now
                }
            }
            return seen.toList()
        }

        /**
         * Waits until the given condition becomes true or the timeout elapses.
         */
        fun waitUntil(timeoutMs: Long = 2000, intervalMs: Long = 16, condition: () -> Boolean): Boolean {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                pumpApp()
                if (condition()) return true
                sleep(intervalMs)
            }
            pumpApp()
            return condition()
        }

        /**
         * Wait for a player's lastReceivedMessage to satisfy the predicate.
         * Returns the final message that satisfied the predicate (or the latest one when timed out).
         */
        fun waitForMessage(
            data: PlayerData,
            timeoutMs: Long = 2000,
            intervalMs: Long = 16,
            predicate: (String) -> Boolean
        ): String {
            val start = System.currentTimeMillis()
            var last = data.lastReceivedMessage
            while (System.currentTimeMillis() - start < timeoutMs) {
                pumpApp()
                val msg = data.lastReceivedMessage
                if (msg != last && predicate(msg)) return msg
                if (predicate(msg)) return msg
                last = msg
                sleep(intervalMs)
            }
            return data.lastReceivedMessage
        }
    }

    @AfterTest
    fun resetEnv() {
        System.clearProperty("test")
    }

    @Test
    fun dbFreshVersionTest_19() {
        // stopPlugin() deletes the H2 files, so the next boot creates the database from scratch and
        // plugin_data is inserted by createPluginData(). That row used to start at version 0, which
        // sent the following start into the legacy upgrade path - scripts that rename tables this
        // database never had. A database this build just created is at the current baseline.
        if (Core.app != null) stopPlugin()
        loadGame(deleteConfig = false)
        Main.conf = CoreConfig()

        // stopPlugin() only deletes the files when a plugin was running, and a filtered run may start
        // here with a database left behind by an earlier run. Delete them outright, as dbUpgradeTest_20
        // does, so the next boot really does create the database.
        val dataDir = Paths.get("config", "mods", "Essentials", "data")
        dataDir.toFile().mkdirs()
        Files.walk(dataDir).use { stream ->
            stream.filter { path ->
                path.fileName.toString().startsWith("database") ||
                path.fileName.toString().startsWith("worldHistory")
            }.sorted(Comparator.reverseOrder()).forEach { path ->
                path.toFile().delete()
            }
        }

        loadPlugin(force = true)

        runBlocking {
            val data = getPluginData()
            assertNotNull(data, "plugin_data row should exist after a fresh start")
            assertEquals(LEGACY_BASELINE_VERSION, data.databaseVersion)
        }

        stopPlugin()
    }

    @Test
    fun dbUpgradeTest_20() {
        if (Core.app != null) stopPlugin()
        loadGame(deleteConfig = false)
        Main.conf = CoreConfig()

        val dataDir = Paths.get("config", "mods", "Essentials", "data")
        dataDir.toFile().mkdirs()

        Files.walk(dataDir).use { stream ->
            stream.filter { path ->
                path.fileName.toString().startsWith("database") ||
                path.fileName.toString().startsWith("worldHistory")
            }.sorted(Comparator.reverseOrder()).forEach { path ->
                path.toFile().delete()
            }
        }

        val file = Paths.get("src", "test", "resources", "database-v3.mv.db").toFile()
        val target = dataDir.resolve("database.mv.db").toFile()
        file.copyTo(target, true)

        loadGame(deleteConfig = false, logHandler = {
            val alreadyUpgraded = bundle["database.upgrade.upToDate", "7"]
            if (it.contains(alreadyUpgraded)) {
                fail("Upgrade logic not executed")
            }
        })

        loadPlugin()

        runBlocking {
            val uuid = "UPQJIWNSHAQAAAAAAAAAAA=="
            val player = getPlayerData(uuid)
            assertNotNull(player)
            assertEquals(56, player.exp)
            assertEquals(uuid, player.uuid)
            assertFalse(checkPlayerBanned(player.player))
            assertEquals(0, player.blockPlaceCount)
        }

        stopPlugin()
    }

    @Test
    fun verifyAllModulesTrueTest() {
        if (Core.app != null) stopPlugin()
        loadGame(loadPlugin = true)

        assertTrue(Main.conf.module.achievement, "achievement module should be true")
        assertTrue(Main.conf.module.bridge, "bridge module should be true")
        assertTrue(Main.conf.module.chat, "chat module should be true")
        assertTrue(Main.conf.module.discord, "discord module should be true")
        assertTrue(Main.conf.module.protect, "protect module should be true")
        assertTrue(Main.conf.module.web, "web module should be true")

        stopPlugin()
    }

    @Test
    fun configUpgradeTest() {
        loadGame(loadPlugin = false)

        val configDir = rootPath.child("config")
        configDir.mkdirs()

        val webService = optionalServiceClass("essential.core.service.web.WebService") ?: return
        val chatService = optionalServiceClass("essential.core.service.chat.ChatService") ?: return
        val originalWebConf = serviceConf(webService)
        val originalChatConf = serviceConf(chatService)

        val oldConfigs = listOf(
            "config.yaml",
            "config_bridge.yaml",
            "config_chat.yaml",
            "config_discord.yaml",
            "config_protect.yaml",
            "config_web.yaml"
        )

        for (configName in oldConfigs) {
            val resourceStream = Companion::class.java.getResourceAsStream("/$configName")
            assertNotNull(resourceStream, "Resource /$configName should exist")
            val destFile = configDir.child(configName)
            destFile.write(resourceStream, false)
            resourceStream.close()
        }

        try {
            val webConf = reloadServiceConf(webService)
            val chatConf = reloadServiceConf(chatService)

            assertEquals(32148, webConf.javaClass.getMethod("getPort").invoke(webConf))

            val updatedWebContent = configDir.child("config_web.yaml").readString()
            assertTrue(updatedWebContent.contains("sessionSecret"), "config_web.yaml should be upgraded with sessionSecret")
            assertTrue(updatedWebContent.contains("enableWebSocket"), "config_web.yaml should be upgraded with enableWebSocket")

            assertEquals("%player.name[orange] >[white] %chat", chatConf.javaClass.getMethod("getChatFormat").invoke(chatConf))

            val updatedChatContent = configDir.child("config_chat.yaml").readString()
            assertTrue(updatedChatContent.contains("strict"), "config_chat.yaml should be upgraded with strict")
            assertTrue(updatedChatContent.contains("blacklist"), "config_chat.yaml should be upgraded with blacklist")
        } finally {
            for (configName in oldConfigs) configDir.child(configName).delete()
            setServiceConf(webService, originalWebConf)
            setServiceConf(chatService, originalChatConf)
        }
    }

    private fun optionalServiceClass(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()

    private fun serviceCompanion(serviceClass: Class<*>): Any = serviceClass.getField("Companion").get(null)

    private fun serviceConf(serviceClass: Class<*>): Any {
        val companion = serviceCompanion(serviceClass)
        return companion.javaClass.getMethod("getConf").invoke(companion)
    }

    private fun reloadServiceConf(serviceClass: Class<*>): Any {
        val companion = serviceCompanion(serviceClass)
        val conf = companion.javaClass.getMethod("reloadConf").invoke(companion)
        setServiceConf(serviceClass, conf)
        return conf
    }

    private fun setServiceConf(serviceClass: Class<*>, conf: Any) {
        val companion = serviceCompanion(serviceClass)
        companion.javaClass.methods
            .first { method -> method.name == "setConf" && method.parameterCount == 1 }
            .invoke(companion, conf)
    }
}
