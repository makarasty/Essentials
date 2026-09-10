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
import essential.common.database.defaultConnectionPool
import essential.common.database.defaultDatabase
import essential.common.database.worldHistoryConnectionPool
import essential.common.database.worldHistoryDatabase
import essential.common.isCheated
import essential.common.isSurrender
import essential.common.nextVoteAvailable
import essential.common.offlinePlayers
import essential.common.players
import essential.common.rootPath
import essential.common.timeSource
import essential.common.voterCooldown
import essential.core.Commands
import essential.core.CoreConfig
import essential.core.Main
import essential.core.dpsBlocks
import essential.core.dpsTile
import essential.core.isGlobalMute
import essential.core.mapRatings
import essential.core.mapVotes
import essential.core.maxDps
import essential.core.playerDataRetries
import essential.core.pvpPlayer
import essential.core.pvpSpecters
import essential.core.unitLimitMessageCooldown
import essential.core.worldEditSelection
import kotlinx.coroutines.Job
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
            resetPluginState()
        }

        /**
         * Puts the plugin's own global state back to what a fresh JVM would hold, because the class
         * boundary is the suite's stand-in for a server boot and the plugin has no unload path.
         *
         * Only state that *gates* later behaviour is reset. A leftover `isGlobalMute` silences every
         * later class's chat; a `nextVoteAvailable` left in the future blocks the vote commands
         * outright; a `dpsTile` pointing into a world that has been reloaded is healed to 100000000
         * health once a second by `Trigger`. None of those failures name the class that caused them.
         *
         * **Seven of these are also cleared by the plugin itself**, in `CoreEvent`'s `worldLoad` and
         * `gameOver` handlers - `isCheated`, `isSurrender`, `mapRatings`, `worldEditSelection`,
         * `dpsTile`, `pvpSpecters`, `pvpPlayer`. That is not redundant here, and the reason is worth
         * stating because the class doc forbids cleaning up after production: [resetSharedState]
         * reloads the world **only when the map differs**, and it almost never does between two
         * classes on [testMap], so neither event fires at a class boundary. The plugin clears them on
         * a real server; the harness does not give it the chance to.
         *
         * Deliberately not reset:
         * - `isVoting`. It is not a flag, it is the run gate of a live `Timer.Task`: `VoteSystem.run`
         *   opens `if (isVoting)` and the only path to its own `cancel()` - which removes its chat
         *   filter and its two event listeners - is inside that branch. Clearing it from outside
         *   freezes the task instead of ending it, and a later class starting a vote would then be
         *   killed by the zombie's next tick. Left alone, an orphaned vote notices its starter is gone
         *   and cancels itself, which is the behaviour that already exists and works.
         * - `isNotTargetMap`. `Main` derives it from `pluginData.data.warpBlock` on every
         *   `WorldLoadEvent`, `pluginData` is deliberately not reset, and this function runs *after*
         *   [resetSharedState]'s `world.loadMap`. Forcing `false` would be a clobber, not a restore,
         *   and would re-enable the warp scan in the action filter for every later class.
         * - `pluginData`, which is `lateinit` and is reassigned by every `Main.init()`. Clearing it
         *   without a plugin reload would leave the in-memory mirror pointing at a row the H2 delete
         *   in [stopPlugin] has already destroyed, and reloading the plugin per class costs the whole
         *   suite minutes. A class that needs a clean one calls `stopPlugin(); loadGame(true)`.
         * - counters nothing branches on (`gameOverCount`, `playerNumber`, `mapStartTime`). They show
         *   up in `/status` output and in nothing that decides anything.
         * - the shared **world**. A class that changes the wave, the core's items, the weather or a
         *   tile leaves all of it for the next class, because of the same conditional reload above.
         *   That is a real gap and it is not closed here: an unconditional `world.loadMap` per class
         *   fires `WorldLoadEvent`, which [resetSharedState] documents three classes as needing it not
         *   to. Recorded rather than papered over.
         */
        private fun resetPluginState() {
            isGlobalMute = false
            isCheated = false
            isSurrender = false
            unitLimitMessageCooldown = 0
            nextVoteAvailable = timeSource.markNow()
            voterCooldown.clear()
            dpsTile = null
            dpsBlocks = 0f
            maxDps = null
            mapVotes.clear()
            mapRatings.clear()
            pvpSpecters.clear()
            pvpPlayer.clear()
            worldEditSelection.clear()
            Commands.charsPlacing.clear()
            // Jobs, not data. The job itself re-checks `isPlayerOnline` after each delay, so the
            // window it can still write in is narrow - a leave that lands inside a load - but a
            // coroutine belonging to a class that has finished has nothing left to do either way.
            playerDataRetries.values.forEach { job -> runCatching { job.cancel() } }
            playerDataRetries.clear()
        }

        /**
         * Error-log text a test has declared it is about to cause. See [expectingErrors].
         *
         * Copy-on-write because the plugin logs from `Dispatchers.IO`, the ping thread and the arc
         * main thread, and the guard below reads this on whichever of those the log came from.
         */
        private val expectedErrors = java.util.concurrent.CopyOnWriteArrayList<String>()

        /**
         * The harness's log guard: **a test must not pass while the plugin is logging errors it did
         * not expect**, so an error-level line throws unless a test has said it is coming.
         *
         * Before the opt-in existed, every deliberate error path in the plugin was untestable - the
         * code logged, this threw, and the test failed for a reason unrelated to its assertion. That
         * is a large part of why the error paths this audit found defective had no coverage.
         *
         * **It is live for the whole run.** It used to guard only the classes before the first
         * `stopPlugin()` - installed here on the cold-boot path, which runs once per JVM, and taken
         * off again by `stopPlugin` restoring the pristine logger, which nothing undid. Chip T
         * measured that split as 39 tests of 360 guarded. [stopPlugin] now reinstalls it, on its
         * last line, and the cost was measured over a full suite with all three databases up:
         * exactly one test failed, `WorldHistoryBufferBoundsTest.aFailedWriteKeepsItsRowsForTheNextFlush`,
         * which declares its deliberate error with [expectingErrors] and is the only opt-in here.
         *
         * **The limit durability does not fix, and you must not read past it.** The throw lands on
         * whichever thread logged. A `Log.err` from a coroutine worker throws on that worker and
         * prints `Exception in thread "DefaultDispatcher-worker-N"` - the test thread never sees it
         * and the test passes. One inside a `runCatching` or a broad `catch` in the plugin is
         * swallowed outright. Both are real here, not hypothetical: the certifying run detected
         * undeclared errors in `PermissionOfflineApplyTest` and `FeatureTest` and failed neither, and
         * the plugin's own `CoroutineExceptionHandler` (`Main.kt:66-68`) logs a second error line when
         * the first throw escapes a `scope.launch`, so one defect can appear twice and still fail
         * nothing.
         *
         * So the property enforced is: *a test must not pass while the plugin logs an **undeclared
         * error on the JUnit thread**.* Anything logged off-thread reaches the console and nobody
         * else. The shape that would close that is to collect unexpected errors at log time and
         * assert on the list at the end of the test, on the JUnit thread; it is a bigger change than
         * this wave and is recorded in `answers/T-2.md`.
         */
        fun errorGuardingHandler(base: Log.LogHandler, tap: (String) -> Unit): Log.LogHandler =
            Log.LogHandler { level, text ->
                base.log(level, text)
                tap(text)
                if (level == Log.LogLevel.err && expectedErrors.none { text.contains(it) }) {
                    throw RuntimeException("Error detected in logs: $text")
                }
            }

        /**
         * Runs [body] with the guard above suspended for error lines containing any of [substrings].
         * Every other error line still fails the test, and the allowance ends with the block even if
         * it throws.
         *
         * Use it to test an error path, not to silence one that surprised you:
         *
         *     expectingErrors("Failed to load map") {
         *         clientCommand.handleMessage("/changemap nonexistent", player)
         *         assertEquals(err("command.changemap.not.found"), playerData.lastReceivedMessage)
         *     }
         *
         * The allowance is global for the duration of the block, because the log line it is waiting
         * for usually arrives on another thread. Keep the block tight for that reason.
         *
         * **And keep it wide enough to contain the log.** The substrings are removed in a
         * `finally`, so an error logged by work the block *started* but did not await - a
         * `scope.launch`, a `Core.app.post` - arrives after the allowance is gone and throws
         * anyway. If a test fails on an error it plainly declared, that is where to look: wait
         * for the observable effect inside the block rather than outside it.
         */
        fun <T> expectingErrors(vararg substrings: String, body: () -> T): T {
            // A blank substring matches every line, so one of those would turn the guard off
            // globally for the block - the "silence one that surprised you" use this exists to
            // refuse.
            require(substrings.isNotEmpty()) { "expectingErrors needs at least one substring to allow" }
            require(substrings.all { it.isNotBlank() }) { "an expected-error substring cannot be blank" }
            expectedErrors.addAll(substrings)
            try {
                return body()
            } finally {
                substrings.forEach { expectedErrors.remove(it) }
            }
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
                        // baseLogHandler explicitly, so a handler an earlier test left installed is
                        // replaced rather than wrapped.
                        Log.logger = errorGuardingHandler(baseLogHandler, logHandler)
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
                            // precondition failing three classes later.
                            //
                            // A reviewer predicted this would fire routinely, and the reasoning is
                            // worth keeping because every link of it is true but the last: the four
                            // live-database classes null defaultDatabase before calling stopPlugin, so
                            // the SHUTDOWN above reaches only worldHistory; their databaseInit has
                            // already replaced defaultConnectionPool without disposing the H2 pool an
                            // earlier class opened; and DB_CLOSE_DELAY=-1 is set. On Windows, deleting
                            // a file somebody still holds open fails.
                            //
                            // Measured, it does not. ConcurrentInsertRaceTest booting H2 and then
                            // SharedMariaDbTest repointing at a real MariaDB - 13 tests, none skipped,
                            // so the repoint really happened - leaves this directory empty and prints
                            // this line zero times, as do three whole-suite runs across all 64
                            // classes. The leaked pool does not in fact hold the file. So there is no
                            // SHUTDOWN-by-URL here: the hardening would be for a failure nobody has
                            // been able to produce, and if it ever does happen this line now says so
                            // instead of the run going quietly wrong.
                            //
                            // One asymmetry it cannot see, recorded rather than fixed: a POSIX
                            // filesystem unlinks a file H2 still holds open, so there the delete
                            // succeeds, this stays quiet, and a database kept alive by
                            // DB_CLOSE_DELAY=-1 goes on writing to an inode with no name. CI only
                            // runs shadowJar, so that is a dev-machine question today.
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

            // Last, not first. This line is what keeps the error guard alive for every class
            // after the first stopPlugin(): it used to restore the pristine logger here and
            // nothing put the guard back, so the guard protected four classes and nothing else.
            // It goes at the END because everything above is teardown - an error logged on this
            // thread while the databases shut down or the data directory is walked would
            // otherwise throw out of the middle of stopPlugin, leaving pluginLoaded true,
            // TransactionManager.defaultDatabase un-nulled and the H2 files undeleted, which
            // (per the comment above) surfaces three classes later as somebody else's
            // precondition failing rather than as a fault here.
            Log.logger = errorGuardingHandler(baseLogHandler) {}
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

        /** Replies `Core.app.post` has queued and [pumpApp] has not run yet. */
        private fun postedWorkPending(): Int = try {
            (runnablesField?.get(Core.app) as? TaskQueue)?.size() ?: 0
        } catch (_: Exception) {
            0
        }

        /**
         * Transactions the plugin has in flight, over both pools, including the ones still waiting for
         * a connection. The main pool is `maxSize(5)`, so a handful of un-awaited commands is enough to
         * make the next one queue behind them for seconds.
         *
         * `ConnectionPool.getMetrics()` is an `Optional`, and reading an empty one as zero would make
         * [drainPostedWork] return instantly while claiming to have waited - a drain that cannot see the
         * database is worse than no drain, because it looks like one. It is not empty here:
         * r2dbc-pool 1.0.2's constructor builds it as `Optional.ofNullable(pool.metrics())` over the
         * reactor-pool instance, which returns itself, and neither pool in `Database.kt` is built with a
         * configuration that could suppress it. So the absent case is a misconfiguration nobody has
         * introduced yet, and it throws rather than reading as idle if somebody does.
         *
         * A null pool is skipped rather than treated as an error, and that asymmetry is deliberate:
         * [stopPlugin] nulls both, and between a `stopPlugin()` and the next `loadGame` there is no
         * plugin to fire a command at, so there is no in-flight reply to wait for. The blindness is
         * the same shape as the empty-Optional one - `databaseWorkPending` reads zero without being
         * able to see anything - but its window contains no work by construction rather than by luck.
         */
        private fun databaseWorkPending(): Int =
            listOfNotNull(defaultConnectionPool, worldHistoryConnectionPool).sumOf { pool ->
                val metrics = pool.metrics.orElseThrow {
                    IllegalStateException("connection pool exposes no metrics: drainPostedWork cannot see the database")
                }
                metrics.acquiredSize() + metrics.pendingAcquireSize()
            }

        /**
         * Waits for the asynchronous work the calling test started, so its answer belongs to it and not
         * to whichever test runs next.
         *
         * Most of this plugin's commands open with `scope.launch` and answer either by writing the
         * sender's message slot from that coroutine or through a `Core.app.post`. A test that fires one
         * and returns without waiting leaves both behind: the queued reply lands in the next test's
         * slot the first time that test pumps, and the coroutine goes on holding one of the main pool's
         * five connections while the next test's own command queues behind it. Both were measured in
         * one failure - `client_temporaryPlayerIsNotRegistered` spent its whole five-second window
         * collecting four `/ranking` pages and an `/unban` `player.not.found` that three earlier,
         * assertion-free command calls had left in flight, and never saw its own `/mute` reply.
         *
         * The wait is for two consecutive idle polls rather than one, because a `scope.launch` that has
         * been dispatched but has not yet acquired its connection reads as idle on the first.
         *
         * **What that second poll is worth, exactly, and what it is not.** [waitUntil] sleeps
         * `intervalMs` (16 ms) between turns, so the rule tolerates a gap of about 16 ms between the
         * coroutine releasing its connection and its `Core.app.post` landing. That is a grace window
         * sized to dispatch latency and nothing more. Three shapes fall outside it, all of them real
         * in this codebase, so do not read this as a quiescence barrier:
         *
         * - **Release, then work, then post.** `/ranking` closes its transaction at `Commands.kt:1450`
         *   and posts at `:1471` and `:1521`, after a sort over the whole result set and a page build.
         *   On the test map that is microseconds; nothing bounds it at 16 ms in general.
         * - **Launch, then acquire.** Nothing orders `handleMessage` returning against the first poll,
         *   so both idle polls can fall before the coroutine has been dispatched at all, and the drain
         *   returns `true` having waited for nothing. `assertTrue(drainPostedWork())` cannot catch
         *   that: it is indistinguishable from having waited successfully.
         * - **Neither posts nor queries.** `/meme router` (`Commands.kt:1215`) loops on a 500 ms
         *   `delay` renaming the player, touching no database and posting nothing.
         *   `ClientCommandTest.client_meme` fires it and never clears its status key, so it outlives
         *   the test and the drain reads idle throughout.
         *
         * **It is a wait, not a barrier.** Work started after it returns is not covered. Returns false
         * when it gave up, which a caller may assert on and [ClientCommandTest]'s per-test teardown
         * deliberately does not.
         *
         * `postedWorkPending`'s read of `TaskQueue.size()` is unsynchronised where `TaskQueue.post` is
         * synchronised. It is reliable here only because [waitUntil] calls [pumpApp] - which does take
         * the queue's monitor - immediately before every check; a caller polling it without pumping
         * first can read a stale size.
         *
         * **It is also not installed anywhere but [ClientCommandTest].** The arc queue is process-global,
         * so the leak crosses class boundaries as well as test boundaries; the harness-level home for
         * this would be one call at the top of [resetSharedState], which every class already runs. That
         * is deliberately not done here - it changes teardown for all 90 classes and would need its own
         * full-suite measurement to ship honestly - and it is recorded in the run notes instead.
         */
        fun drainPostedWork(timeoutMs: Long = 5000): Boolean {
            var wasIdle = false
            return waitUntil(timeoutMs) {
                val idle = postedWorkPending() == 0 && databaseWorkPending() == 0
                val settled = idle && wasIdle
                wasIdle = idle
                settled
            }
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

    /**
     * The class-entry contract for plugin state, asserted rather than described.
     *
     * Every one of these carried into the next class before this existed, and each of them decides
     * something: a leftover `isGlobalMute` silences chat, a `nextVoteAvailable` in the future blocks
     * the vote commands, a stale `dpsTile` is healed once a second by `Trigger` in a world that has
     * since been reloaded, and a live retry coroutine writes into the next class.
     */
    @OptIn(ExperimentalTime::class)
    @Test
    fun pluginStateResetTest() {
        loadGame()

        isGlobalMute = true
        isCheated = true
        isSurrender = true
        unitLimitMessageCooldown = 99
        nextVoteAvailable = timeSource.markNow() + 10.minutes
        voterCooldown["probe"] = timeSource.markNow()
        dpsTile = randomTile()
        dpsBlocks = 42f
        maxDps = 42f
        mapVotes["probe"] = testMap!!
        mapRatings["probe"] = true
        pvpSpecters.add("probe")
        pvpPlayer["probe"] = Team.sharded
        worldEditSelection["probe"] = Commands.WorldEditSelection()
        Commands.charsPlacing["probe"] = arrayOf("probe")
        val retry = Job()
        playerDataRetries["probe"] = retry

        resetPluginState()

        assertFalse(isGlobalMute, "isGlobalMute carried into the next class")
        assertFalse(isCheated, "isCheated carried into the next class")
        assertFalse(isSurrender, "isSurrender carried into the next class")
        assertEquals(0, unitLimitMessageCooldown, "unitLimitMessageCooldown carried into the next class")
        assertTrue(
            nextVoteAvailable.elapsedNow().isPositive() || nextVoteAvailable.elapsedNow() == Duration.ZERO,
            "nextVoteAvailable is still in the future: the next class cannot start a vote"
        )
        assertTrue(voterCooldown.isEmpty(), "voterCooldown carried into the next class")
        assertNull(dpsTile, "dpsTile carried into the next class")
        assertEquals(0f, dpsBlocks, "dpsBlocks carried into the next class")
        assertNull(maxDps, "maxDps carried into the next class")
        assertTrue(mapVotes.isEmpty(), "mapVotes carried into the next class")
        assertTrue(mapRatings.isEmpty(), "mapRatings carried into the next class")
        assertTrue(pvpSpecters.isEmpty(), "pvpSpecters carried into the next class")
        assertTrue(pvpPlayer.isEmpty(), "pvpPlayer carried into the next class")
        assertTrue(worldEditSelection.isEmpty(), "worldEditSelection carried into the next class")
        assertTrue(Commands.charsPlacing.isEmpty(), "a pending /chars placement carried into the next class")
        assertTrue(playerDataRetries.isEmpty(), "a player-data retry job carried into the next class")
        assertTrue(retry.isCancelled, "the retry job was dropped from the map but left running")

        // The wiring, not just the body. Everything above pins resetPluginState(); this pins the
        // one line that makes it happen at all - the call at the end of resetSharedState(). Without
        // it, deleting that call leaves the suite green while all sixteen fields carry over again.
        isGlobalMute = true
        currentTestClass = "a.different.TestClass"
        loadGame()
        assertFalse(isGlobalMute, "the class-entry reset did not run: loadGame reached a new class without it")
    }

    /**
     * The error guard and its opt-in, asserted against a handler this test installs itself rather
     * than against whichever one the run happens to be holding - the installed handler depends on
     * whether any class has called [stopPlugin] yet, and a test whose outcome depends on class order
     * is not a test.
     *
     * No `_NN` suffix, unlike the database tests above: that suffix encodes run order under
     * `@FixMethodOrder(NAME_ASCENDING)`, which sorts the whole name, and neither this test nor
     * [pluginStateResetTest] depends on running at any particular point.
     */
    @Test
    fun errorGuardTest() {
        loadGame()
        val previous = Log.logger
        try {
            // baseLogHandler, not `previous`: whichever handler the run is holding may itself be a
            // guard, and nesting one guard inside another would throw from the base call instead.
            Log.logger = errorGuardingHandler(baseLogHandler) {}

            assertFailsWith<RuntimeException>("an error the test did not declare must fail it") {
                Log.err("guard probe: undeclared")
            }

            expectingErrors("guard probe: declared") {
                Log.err("guard probe: declared")
                Log.warn("guard probe: a warning is not an error")
            }

            assertFailsWith<RuntimeException>("the allowance must not outlive its block") {
                Log.err("guard probe: declared")
            }

            // The allowance is by substring and must not let a different error through with it.
            assertFailsWith<RuntimeException>("an allowance must not cover an unrelated error") {
                expectingErrors("guard probe: declared") {
                    Log.err("guard probe: something else")
                }
            }
        } finally {
            Log.logger = previous
        }
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
