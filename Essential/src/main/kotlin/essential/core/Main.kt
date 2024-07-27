package essential.core

import arc.ApplicationListener
import arc.Core
import arc.files.Fi
import arc.util.CommandHandler
import arc.util.Http
import arc.util.Log
import com.charleskorn.kaml.Yaml
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import essential.core.Event.actionFilter
import essential.core.Event.findPlayerData
import essential.core.annotation.ClientCommand
import essential.core.annotation.ServerCommand
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Playerc
import mindustry.mod.Plugin
import mindustry.net.Administration
import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.hjson.JsonValue
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation


class Main : Plugin() {
    companion object {
        const val CONFIG_PATH = "config/config.yaml"
        lateinit var conf: Config

        @JvmField
        val root: Fi = Core.settings.dataDirectory.child("mods/Essentials/")

        @JvmField
        val database = DB()

        @JvmField
        val daemon: ExecutorService = ThreadPoolExecutor(
            0, Int.MAX_VALUE,
            16, TimeUnit.MILLISECONDS,
            SynchronousQueue()
        )

        fun currentTime(): String {
            return ZonedDateTime.now().format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL).withLocale(Locale.getDefault()))
        }

        fun <T> createAndReadConfig(name: String, file: InputStream, type: Class<T>): T? {
            if (!root.child("config/$name").exists()) {
                root.child("config/$name").write(file, false)
            }

            val mapper = ObjectMapper(YAMLFactory())
            return try {
                mapper.readValue(root.child("config/$name").file(), type)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    val bundle = Bundle()

    override fun init() {
        bundle.prefix = "[Essential]"
        Log.info(bundle["event.plugin.starting"])

        // 플러그인 설정
        if (!root.child("config/config.yaml").exists()) {
            root.child("config/config.yaml").write(this.javaClass.getResourceAsStream("/config.yaml"), false)
        }

        conf = Yaml.default.decodeFromString(Config.serializer(), root.child(CONFIG_PATH).readString())
        bundle.locale = Locale(conf.plugin.lang)

        if (!root.child("data").exists()) {
            root.child("data").mkdirs()
        }

        // 채팅 금지어 파일 추가
        if (!root.child("chat_blacklist.txt").exists()) {
            root.child("chat_blacklist.txt").writeString("않")
        }

        // DB 설정
        database.load()
        database.connect()
        database.create()

        // 데이터 설정
        PluginData.load()

        // 업데이트 확인
        checkUpdate()

        // 권한 기능 설정
        Permission.load()

        // 설정 파일 감시기능
        daemon.submit(FileWatchService())

        // 이벤트 등록
        for (functions in Event::class.declaredFunctions) {
            val annotation = functions.findAnnotation<essential.core.annotation.Event>()
            if (annotation != null) {
                functions.call(Event)
            }
        }

        // 스레드 등록
        val trigger = Trigger()
        trigger.register()
        daemon.submit(Trigger.Thread())
        daemon.submit(Trigger.UpdateThread())

        Vars.netServer.admins.addActionFilter(Administration.ActionFilter { e ->
            if (e.player == null) return@ActionFilter true
            val data = database.players.find { it.uuid == e.player.uuid() }
            val isHub = PluginData["hubMode"]
            for (it in PluginData.warpBlocks) {
                if (it.mapName == PluginData.currentMap && e.tile != null && it.x.toShort() == e.tile.x && it.y.toShort() == e.tile.y && it.tileName == e.tile.block().name) {
                    return@ActionFilter false
                }
            }

            if (Vars.state.rules.pvp && conf.feature.pvp.autoTeam && e.player.team() == Team.derelict) {
                return@ActionFilter false
            }

            if (data != null) {
                if (e.type == Administration.ActionType.commandUnits) {
                    data.currentControlCount += e.unitIDs.size
                }

                return@ActionFilter when {
                    isHub != null && isHub == Vars.state.map.name() -> {
                        Permission.check(data, "hub.build")
                    }

                    data.strict -> {
                        false
                    }

                    else -> {
                        true
                    }
                }
            }
            return@ActionFilter false
        }.also { listener -> actionFilter = listener })

        Core.app.addListener(object : ApplicationListener {
            override fun dispose() {
                daemon.shutdownNow()
            }
        })

        Log.info(bundle["event.plugin.loaded"])
    }

    override fun registerServerCommands(handler: CommandHandler) {
        val commands = Commands()

        for (functions in commands::class.declaredFunctions) {
            val annotation = functions.findAnnotation<ServerCommand>()
            if (annotation != null) {
                handler.register(annotation.name, annotation.parameter, annotation.description) { args ->
                    if (args.isNotEmpty()) {
                        functions.call(commands, arrayOf(*args))
                    } else {
                        try {
                            functions.call(commands, arrayOf<String>())
                        } catch (e: Exception) {
                            Log.err("arg size - ${args.size}")
                            Log.err("command - ${annotation.name}")
                        }
                    }
                }
            }
        }
    }


    override fun registerClientCommands(handler: CommandHandler) {
        val commands = Commands()

        for (functions in commands::class.declaredFunctions) {
            val annotation = functions.findAnnotation<ClientCommand>()
            if (annotation != null) {
                handler.register(
                    annotation.name,
                    annotation.parameter,
                    annotation.description
                ) { args, player: Playerc ->
                    val data = findPlayerData(player.uuid()) ?: DB.PlayerData()
                    if (Permission.check(data, annotation.name)) {
                        if (args.isNotEmpty()) {
                            functions.call(commands, player, data, arrayOf(*args))
                        } else {
                            functions.call(commands, player, data, arrayOf<String>())
                        }
                    } else {
                        if (annotation.name == "js") {
                            Call.kick(player.con(), Bundle(player.locale())["command.js.no.permission"])
                        } else {
                            player.sendMessage(Vars.netServer.invalidHandler.handle(player.self(),
                                CommandHandler.CommandResponse(
                                    CommandHandler.ResponseType.unknownCommand,
                                    null as CommandHandler.Command?,
                                    annotation.name
                                ))
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkUpdate() {
        if (conf.plugin.autoUpdate) {
            Http.get("https://api.github.com/repos/kieaer/Essentials/releases/latest").timeout(1000)
                .error { _ -> Log.warn(bundle["event.plugin.update.check.failed"]) }
                .block {
                    if (it.status == Http.HttpStatus.OK) {
                        val json = JsonValue.readJSON(it.resultAsString).asObject()
                        PluginData.pluginVersion = JsonValue.readJSON(
                            this::class.java.getResourceAsStream("/plugin.json")!!.reader().readText()
                        ).asObject()["version"].asString()
                        val latest = DefaultArtifactVersion(json.getString("tag_name", PluginData.pluginVersion))
                        val current = DefaultArtifactVersion(PluginData.pluginVersion)

                        when {
                            latest > current -> Log.info(bundle["config.update.new", json["assets"].asArray()[0].asObject()["browser_download_url"].asString(), json["body"].asString()])
                            latest.compareTo(current) == 0 -> Log.info(bundle["config.update.current"])
                            latest < current -> Log.info(bundle["config.update.devel"])
                        }
                    }
                }
        } else {
            Vars.mods.list().forEach { mod ->
                if (mod.meta.name == "Essentials") {
                    PluginData.pluginVersion = mod.meta.version
                    return@forEach
                }
            }
        }
    }
}