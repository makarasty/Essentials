package essential.core

import arc.ApplicationListener
import arc.Core
import arc.Events
import arc.util.CommandHandler
import arc.util.Http
import arc.util.Log
import essential.common.*
import essential.common.command.CommandRegistry
import essential.common.config.Config
import essential.common.database.WorldHistoryBuffer
import essential.common.database.data.createPluginData
import essential.common.database.data.getPluginData
import essential.common.database.data.migrateMapRatingsFromPluginData
import essential.common.database.data.update
import essential.common.database.databaseInit
import essential.common.log.initLogFiles
import essential.common.log.stopLogWriter
import essential.common.permission.Permission
import essential.common.service.fileWatchService
import essential.common.util.findPlayerData
import essential.core.generated.registerGeneratedClientCommands
import essential.core.generated.registerGeneratedEventHandlers
import essential.core.generated.registerGeneratedServerCommands
import kotlinx.coroutines.*
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mindustry.Vars
import mindustry.Vars.state
import mindustry.game.EventType.WorldLoadEvent
import mindustry.game.Team
import mindustry.mod.Plugin
import mindustry.net.Administration
import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Clock

class Main : Plugin() {
    companion object {
        const val CONFIG_PATH = "config/config.yaml"
        private const val DATABASE_INIT_TIMEOUT_MS = 30_000L
        private const val SHUTDOWN_SAVE_TIMEOUT_MS = 30_000L
        private const val SHUTDOWN_DRAIN_TIMEOUT_MS = 5_000L
        @Volatile
        var conf: CoreConfig = reloadConf()

        fun reloadConf() : CoreConfig {
            return runBlocking {
                val config = Config.load("config", CoreConfig.serializer(), CoreConfig())
                require(config != null) {
                    Log.err(bundle["event.plugin.load.failed"])
                }
                config
            }
        }

        // Without a handler here, an exception thrown inside `scope.launch { ... }` (a pool
        // acquire timeout, a connection drop, a constraint violation) reaches the JVM's default
        // uncaught-exception handler instead of this plugin's own log, and whatever that launch
        // was doing - most often saving a player's data - is lost with no line connecting the
        // loss to its cause.
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { context, throwable ->
                Log.err("Unhandled exception in a background coroutine ($context)", throwable)
            }
        )
        val threadPool: ExecutorService = Executors.newFixedThreadPool(2)

        /**
         * Applies the client command set for the current [conf] to [handler]: every generated
         * command (core and module) registered, then bannedCommands.txt and the vote/votekick
         * toggles removed on top. Called at boot from [registerClientCommands]; also the fix for
         * task-159 (`/reload` in Commands.kt) and task-155/task-171's counterpart on the config.yaml
         * watcher (`configFileModified` in CoreEvent.kt) needs the same re-application, because
         * `conf.feature.vote.enabled`/`enableVotekick` and bannedCommands.txt are read exactly
         * once today, here, and nothing calls this again after boot.
         *
         * Safe to call more than once with no token to track and no delta to compute:
         * `CommandHandler.register` replaces any existing command of the same name rather than
         * appending a second one (`orderedCommands.remove(c -> c.text.equals(text))` runs before
         * every registration), and `CommandHandler.removeCommand` is a no-op on a name that is
         * not currently registered. So re-running the full registration and then re-removing
         * whatever the config currently says to remove reaches the same end state regardless of
         * what state the handler started in - including turning a feature back on: registering
         * again puts the command straight back, there is no leftover state a re-enable has to
         * clean up.
         *
         * A player mid-session is unaffected beyond the command itself: removeCommand only takes
         * the entry out of the handler's own command table and command list, so a command they
         * had used is simply "unknown command" on their next attempt, the same response an
         * unrecognised command has always produced. Nothing about their connection, their data or
         * any other command is touched.
         */
        fun syncClientCommands(handler: CommandHandler) {
            registerGeneratedClientCommands(handler)
            ModuleRuntime.registerClientCommands(handler)
            removeBannedCommands(handler)

            // "vote" and "votekick" are vanilla names, so CommandRegistry renames our own
            // commands to "evote"/"evotekick". Both names go: the resolved (renamed) one and the
            // literal vanilla one, because turning the feature off has to mean the server has no
            // vote command at all - removing only ours would leave vanilla's running the vote an
            // operator just switched off.
            //
            // What that costs the player is the plain "Unknown command. Check /help.": with
            // neither name registered, vanilla's suggester finds nothing within edit distance 3
            // of what they typed, so there is no "did you mean" to hint the feature is disabled
            // rather than missing. Switching enableVotekick off is therefore switching /votekick
            // off for everyone, not moving it to another name.
            if (!conf.feature.vote.enabled) {
                handler.removeCommand(CommandRegistry.registered("vote"))
                handler.removeCommand("vote")
            }
            if (!conf.feature.vote.enableVotekick) {
                handler.removeCommand(CommandRegistry.registered("votekick"))
                handler.removeCommand("votekick")
            }
        }

        private fun removeBannedCommands(handler: CommandHandler) {
            val file = rootPath.child("bannedCommands.txt")
            if (file.exists()) {
                try {
                    val banned: List<String> = Json.decodeFromString(file.readString())
                    for (command in banned) {
                        handler.removeCommand(command)
                    }
                } catch (e: Exception) {
                    Log.err("Failed to load bannedCommands.txt", e)
                }
            }
        }

        /**
         * task-158: bounds [connect] to [timeoutMs] and logs a clear message before rethrowing on
         * timeout, instead of letting init() hang forever with nothing to explain why. [connect]
         * is the real `databaseInit(...)` call in production and a never-completing suspend
         * function in DatabaseInitTimeoutTest, which needs the timeout wiring itself to fail fast
         * rather than waiting out the real 30-second production timeout to prove it works.
         */
        internal suspend fun initDatabaseWithTimeout(timeoutMs: Long, urlForLogging: String, connect: suspend () -> Unit) {
            try {
                withTimeout(timeoutMs) { connect() }
            } catch (e: TimeoutCancellationException) {
                Log.err("Database initialisation did not finish within ${timeoutMs / 1000}s (url: $urlForLogging). The server cannot start without it.", e)
                throw e
            }
        }

        /**
         * task-158's other half: bounds [save] (the per-player shutdown save loop in production)
         * to [timeoutMs] and logs if it did not finish, rather than blocking dispose() forever.
         * Returns whether it finished, for a test to check without waiting on Log output.
         */
        internal suspend fun saveOnShutdownWithTimeout(timeoutMs: Long, save: suspend () -> Unit): Boolean {
            val finished = withTimeoutOrNull(timeoutMs) { save() } != null
            if (!finished) {
                Log.err("Shutdown save did not finish within ${timeoutMs / 1000}s; some online players' data may not have been saved.")
            }
            return finished
        }
    }

    override fun init() = runBlocking {
        // 플러그인 언어 설정 및 태그 추가
        bundle.prefix = "[Essential]"

        Log.debug(bundle["event.plugin.starting"])

        bundle.locale = Locale.forLanguageTag(conf.plugin.lang.replace("_", "-"))
        bundle.resource = ResourceBundle.getBundle("bundles/common/bundle", bundle.locale)

        // 업데이트 확인
        checkUpdate()

        // 기록 및 데이터 폴더 생성
        rootPath.child("log").mkdirs()
        rootPath.child("data").mkdirs()
        initLogFiles()

        // DB 설정
        // init() runs on the main thread (the engine calls Mod::init from inside its own init
        // pass, before any tick is running to log anything), and runBlocking parks that thread
        // until this completes. Nothing below has a bound of its own - the pool's acquire
        // timeout only fires once a connection is being handed out, not while the driver is
        // still trying to open the socket - so an unreachable or silently-dropping host used to
        // hang the whole server with no explanation. A timeout here turns that hang into a
        // log line the operator can act on.
        initDatabaseWithTimeout(DATABASE_INIT_TIMEOUT_MS, conf.plugin.database.url) {
            databaseInit(
                conf.plugin.database.url,
                conf.plugin.database.username,
                conf.plugin.database.password
            )
        }

        // 블록 기록
        WorldHistoryBuffer.start(scope)

        // 플러그인 데이터 설정
            var data = getPluginData()
            if (data == null) {
                data = createPluginData()
            }

            pluginData = data

            try {
                migrateMapRatingsFromPluginData(data)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 권한 기능 설정
            Permission.load()

        // 설정 파일 감시기능
        threadPool.execute {
            fileWatchService()
        }

        // 이벤트 등록
        registerGeneratedEventHandlers()

        // 스레드 등록
        Trigger.register()
        threadPool.execute(Trigger.PingThread())

        Vars.netServer.assigner.let { if (it !is PvpTeamAssigner) Vars.netServer.assigner = PvpTeamAssigner(it) }

        Vars.netServer.admins.addActionFilter(object : Administration.ActionFilter {
            init {
                Events.on(WorldLoadEvent::class.java) {
                    isNotTargetMap =
                        pluginData.data.warpBlock.none { f -> f.mapName == state.map.name() }
                }
            }

            override fun allow(e: Administration.PlayerAction): Boolean {
                if (e.player == null) return true
                val data = findPlayerData(e.player.uuid())
                val isHub = pluginData.hubMapName

                if (!isNotTargetMap) {
                    pluginData.data.warpBlock.forEach {
                        if (it.mapName == state.map.name() && e.tile != null && it.x.toShort() == e.tile.x && it.y.toShort() == e.tile.y && it.tileName == e.tile.block().name) {
                            return false
                        }
                    }
                }

                if (state.rules.pvp && conf.feature.pvp.autoTeam && e.player.team() == Team.derelict) {
                    return false
                }

                if (data != null) {
                    return when {
                        isHub != null && isHub == state.map.name() -> {
                            Permission.check(data, "hub.build")
                        }

                        data.strictMode -> {
                            false
                        }

                        else -> {
                            true
                        }
                    }
                }
                if (isHub != null && isHub == state.map.name()) return false
                return conf.feature.playerData.allowWithoutData
            }
        }.also { listener -> actionFilter = listener })

        Core.app.addListener(object : ApplicationListener {
            override fun dispose() {
                runBlocking {
                    WorldHistoryBuffer.stop()
                    // Background writes still in flight - a leave, a game over, an award - that the
                    // scope.cancel() below would cut off mid-transaction. The history flush loop is
                    // stopped above and the web loops run in Ktor's scope, so what is left here ends on
                    // its own; bounded anyway, for a write stuck waiting on the pool.
                    withTimeoutOrNull(SHUTDOWN_DRAIN_TIMEOUT_MS) {
                        scope.coroutineContext.job.children.toList().joinAll()
                    }
                    stopLogWriter()
                    // Also on the main thread, one suspending write per online player, in
                    // sequence, with nothing bounding the total. A supervisor with a shutdown
                    // deadline kills the process regardless; this just makes sure the operator
                    // sees that some sessions were not saved instead of the process dying
                    // silently mid-loop.
                    saveOnShutdownWithTimeout(SHUTDOWN_SAVE_TIMEOUT_MS) {
                        players.forEach { data ->
                            try {
                                data.isConnected = false
                                data.lastLogoutDate = Clock.System.now().toLocalDateTime(systemTimezone)
                                data.update()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
                scope.cancel()
                threadPool.shutdownNow()
            }
        })

        ModuleRuntime.initEnabledServices()

        Log.info(bundle["event.plugin.loaded"])
    }

    override fun registerServerCommands(handler: CommandHandler) {
        registerGeneratedServerCommands(handler)
        // Module commands register after the core ones; banning has to come after both, or a
        // module's own registration (which replaces unconditionally, see CommandHandler.register)
        // silently un-bans whatever name it declares.
        ModuleRuntime.registerServerCommands(handler)
        removeBannedCommands(handler)
    }

    override fun registerClientCommands(handler: CommandHandler) {
        syncClientCommands(handler)

        // A boot-time counterpart to Permission's KDoc requirement: `known` has to be built
        // after client-command registration, which syncClientCommands above just did. CoreEvent.kt's
        // serverLoad still runs this too early (ServerLoadEvent fires before NetServer.init()
        // registers client commands) and never sees plugin names there; that call is now
        // redundant rather than wrong, and can be removed.
        Permission.validate(knownPermissionNodes())
    }

    /**
     * Duplicated from CoreEvent.kt's `serverLoad`, which is not this file's to edit: the
     * sub-nodes below are asked for directly in command bodies rather than carried by a
     * registered command name, so CommandRegistry never sees them.
     */
    private fun knownPermissionNodes(): Set<String> = hashSetOf(
        "admin", "afk.admin", "chat.admin", "hub.build", "info.other", "kick.admin",
        "kill.other", "nextmap.admin", "pm.other", "pvp.spector", "team.other",
        "vote.admin", "vote.back", "vote.draw", "vote.gg", "vote.kick", "vote.pass",
        "vote.map", "vote.random", "vote.random.bypass", "vote.reset", "vote.skip",
    ) + CommandRegistry.declaredNames()

    private fun checkUpdate() {
        if (conf.plugin.autoUpdate) {
            Http.get("https://api.github.com/repos/makarasty/Essentials/releases/latest").timeout(1000)
                .error { _ -> Log.warn(bundle["event.plugin.update.check.failed"]) }
                .block {
                    if (it.status == Http.HttpStatus.OK) {
                        val json = Json { ignoreUnknownKeys = true; isLenient = true }
                        val jsonObject = json.parseToJsonElement(it.resultAsString).jsonObject
                        
                        val tagName = jsonObject["tag_name"]?.jsonPrimitive?.content ?: PLUGIN_VERSION
                        val latest = DefaultArtifactVersion(tagName)
                        val current = DefaultArtifactVersion(PLUGIN_VERSION)
                        
                        // Parse assets array and get download URL from first asset
                        val browserDownloadUrl = jsonObject["assets"]?.jsonArray?.getOrNull(0)?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.content ?: ""
                        val body = jsonObject["body"]?.jsonPrimitive?.content ?: ""

                        when {
                            latest > current -> Log.info(bundle["config.update.new", browserDownloadUrl, body])
                            latest.compareTo(current) == 0 -> Log.info(bundle["config.update.current"])
                            latest < current -> Log.info(bundle["config.update.devel"])
                        }
                    }
                }
        }
    }
}
