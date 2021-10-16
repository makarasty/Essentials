package remake

import arc.Core
import arc.math.Mathf
import arc.struct.ObjectMap
import arc.struct.Seq
import arc.util.CommandHandler
import arc.util.Log
import arc.util.Strings
import arc.util.async.Threads
import com.neovisionaries.i18n.CountryCode
import essentials.Main
import essentials.PluginData
import essentials.data.Config
import essentials.event.feature.Permissions
import essentials.internal.Tool
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.Team
import mindustry.gen.*
import mindustry.gen.Unit
import mindustry.type.UnitType
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.abs
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties


class Commands(handler:CommandHandler) {
    init {
        handler.removeCommand("help")
        
        handler.register("chars", "<Text...>", "Make pixel texts") { a, p: Playerc -> Work(a, p).chars() }
        handler.register("color", "Enable color nickname") { a, p: Playerc -> Work(a, p).color() }
        handler.register("killall", "Kill all enemy units") { a, p: Playerc -> Work(a, p).killall() }
        handler.register("help", "[page]", "Show command lists") { a, p: Playerc -> Work(a, p).help() }
        handler.register("info", "Show your information") { a, p: Playerc -> Work(a, p).info() }
        handler.register("hub", "<zone/block/count/total> [ip] [parameters...]", "Create a server-to-server warp zone.") { a, p: Playerc -> Work(a, p).hub() }
        handler.register("kill", "[player]", "Kill player.") { a, p: Playerc -> Work(a, p).kill() }
        handler.register("login", "<id> <password>", "Access your account") { a, p: Playerc -> Work(a, p).login() }
        handler.register("maps", "[page]", "Show server maps") { a, p: Playerc -> Work(a, p).maps() }
        handler.register("me", "<text...>", "broadcast * message") { a, p: Playerc -> Work(a, p).me() }
        handler.register("motd", "Show server motd.") { a, p: Playerc -> Work(a, p).motd() }
        handler.register("players", "[page]", "Show players list") { a, p: Playerc -> Work(a, p).players() }
        handler.register("meme", "<type>", "Router") { a, p: Playerc -> Work(a, p).meme() }
        handler.register("register", "<accountid> <password>", "Register account") { a, p: Playerc -> Work(a, p).register() }
        handler.register("spawn", "<unit/block> <name> [amount/rotate]", "Spawn mob in player position") { a, p: Playerc -> Work(a, p).spawn() }
        handler.register("status", "Show server status") { a, p: Playerc -> Work(a, p).status() }
        handler.register("team", "<team_name>", "Change team") { a, p: Playerc -> Work(a, p).team() }
        handler.register("time", "Show server time") { a, p: Playerc -> Work(a, p).time() }
        handler.register("tp", "<player>", "Teleport to other players") { a, p: Playerc -> Work(a, p).tp() }
        handler.register("weather", "<rain/snow/sandstorm/sporestorm> <seconds>", "Change map light") { a, p: Playerc -> Work(a, p).weather() }
        handler.register("mute", "<Player_name>", "Mute player") { a, p: Playerc -> Work(a, p).mute() }
        handler.register("unmute", "<Player_name>", "Unmute player") { a, p: Playerc -> Work(a, p).unmute() }
        handler.register("config", "<name> [value]", "Edit server config") { a, p: Playerc -> Work(a, p).config() }
        handler.register("gg", "[delay]", "Force gameover") { a, p: Playerc -> Work(a, p).gg() }
        handler.register("effect", "[effects]", "effects") { a, p: Playerc -> Work(a, p).effect() }
        handler.register("god", "[Player_name]", "Force gameover") { a, p: Playerc -> Work(a, p).god() }
        handler.register("random", "", "Random events") { a, p: Playerc -> Work(a, p).random() }
        handler.register("pause", "Pause server") { a, p: Playerc -> Work(a, p).pause() }
        //handler.register("eval", "[code]", "Execute JavaScript codes") { a, p: Playerc -> Work(a, p).eval() }
        handler.register("search", "[value]", "Search player data") { a, p: Playerc -> Work(a, p).search() }
    }

    private inner class Work(val arg: Array<String>, val player: Playerc){
        val data : DB.PlayerData = DB.players.find { it.uuid == player.uuid() }

        fun chars(){
            // 블록으로 글자 표시
            // arg: <글자>
            if(Vars.world != null) {
                var t = Vars.world.tile(player.tileX(), player.tileY())
                val letters = ObjectMap<String, IntArray>()
                letters.put("A", intArrayOf(0, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 1, 1, 1))
                letters.put("B", intArrayOf(1, 1, 1, 1, 1, 1, 0, 1, 0, 1, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0))
                letters.put("C", intArrayOf(0, 1, 1, 1, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1))
                letters.put("D", intArrayOf(1, 1, 1, 1, 1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0))
                letters.put("E", intArrayOf(1, 1, 1, 1, 1, 1, 0, 1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 0, 1))
                letters.put("F", intArrayOf(1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 0))
                letters.put("G", intArrayOf(0, 1, 1, 1, 0, 1, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1, 1, 1))
                letters.put("H", intArrayOf(1, 1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1))
                letters.put("I", intArrayOf(1, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 1))
                letters.put("J", intArrayOf(1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 0))
                letters.put("K", intArrayOf(1, 1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1))
                letters.put("L", intArrayOf(1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1))
                letters.put("M", intArrayOf(1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 1, 1))
                letters.put("N", intArrayOf(1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1))
                letters.put("O", intArrayOf(0, 1, 1, 1, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0))
                letters.put("P", intArrayOf(1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 0, 0, 0))
                letters.put("Q", intArrayOf(0, 1, 1, 1, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0, 0, 0, 0, 0, 1))
                letters.put("R", intArrayOf(1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 1))
                letters.put("S", intArrayOf(1, 1, 1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 1, 1))
                letters.put("T", intArrayOf(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0))
                letters.put("U", intArrayOf(1, 1, 1, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0))
                letters.put("V", intArrayOf(1, 1, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0, 0))
                letters.put("W", intArrayOf(1, 1, 1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0))
                letters.put("X", intArrayOf(1, 0, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1))
                letters.put("Y", intArrayOf(1, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0))
                letters.put("Z", intArrayOf(1, 0, 0, 1, 1, 1, 0, 1, 0, 1, 1, 1, 0, 0, 1))
                letters.put("0", intArrayOf(1, 1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 1, 1, 1, 1))
                letters.put("1", intArrayOf(0, 1, 0, 1, 1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 1))
                letters.put("2", intArrayOf(1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 0, 0, 1, 1, 1))
                letters.put("3", intArrayOf(1, 1, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1))
                letters.put("4", intArrayOf(1, 0, 1, 1, 0, 1, 1, 1, 1, 0, 0, 1, 0, 0, 1))
                letters.put("5", intArrayOf(1, 1, 1, 1, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1, 1))
                letters.put("6", intArrayOf(1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 0, 1, 1, 1, 1))
                letters.put("7", intArrayOf(1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1))
                letters.put("8", intArrayOf(1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1))
                letters.put("9", intArrayOf(1, 1, 1, 1, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1))
                letters.put("!", intArrayOf(1, 1, 1, 1, 0, 1))
                letters.put("?", intArrayOf(0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 1, 0, 0, 0, 0))
                letters.put(" ", intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

                // TODO 숫자 1~9, ? 가 올바르게 작동 안됨
                val texts = arg[0].toCharArray()
                for (i in texts) {
                    val pos = Seq<IntArray>()
                    val target = letters[i.uppercaseChar().toString()]
                    var xv = 0
                    var yv = 0
                    when (target.size) {
                        20 -> {
                            xv = 5
                            yv = 4
                        }
                        15 -> {
                            xv = 5
                            yv = 3
                        }
                        18 -> {
                            xv = 6
                            yv = 3
                        }
                        25 -> {
                            xv = 5
                            yv = 5
                        }
                        6 -> {
                            xv = 6
                            yv = 1
                        }
                        10 -> {
                            xv = 2
                            yv = 5
                        }
                    }
                    for (y in 0 until yv) {
                        for (x in 0 until xv) {
                            pos.add(intArrayOf(y, -x))
                        }
                    }
                    for (a in 0 until pos.size) {
                        val tar = Vars.world.tile(t.x + pos[a][0], t.y + pos[a][1])
                        if (target[a] == 1) {
                            Call.constructFinish(tar, Blocks.copperWall, Nulls.unit, 0.toByte(), Team.sharded, false)
                        } else if (tar != null) {
                            Call.deconstructFinish(tar, Blocks.air, Nulls.unit)
                        }
                    }
                    t = Vars.world.tile(t.x + (xv + 1), t.y.toInt())
                }
            }
        }

        fun killall(){
            // 모든 또는 특정 유닛 파괴
            // arg: [팀]
            if(arg.isEmpty()){
                for(a in Team.all.indices) {
                    Groups.unit.each { u: Unit -> if(player.team() != u.team) u.kill() }
                }
            } else {
                when (arg[0].lowercase()){
                    "derelict" -> Groups.unit.each { u: Unit -> if(Team.derelict != u.team) u.kill() }
                    "sharded" -> Groups.unit.each { u: Unit -> if(Team.sharded != u.team) u.kill() }
                    "crux" -> Groups.unit.each { u: Unit -> if(Team.crux != u.team) u.kill() }
                    "green" -> Groups.unit.each { u: Unit -> if(Team.green != u.team) u.kill() }
                    "purple" -> Groups.unit.each { u: Unit -> if(Team.purple != u.team) u.kill() }
                    "blue" -> Groups.unit.each { u: Unit -> if(Team.blue != u.team) u.kill() }
                    else -> {
                        player.sendMessage("Incorrect team name! available team is derelict, sharded, crux, green, purple, blue")
                    }
                }
            }

        }

        fun help(){
            // 명령어 도움말 표시
            if(arg.isNotEmpty() && !Strings.canParseInt(arg[0])) {
                player.sendMessage("Page value must be number!")
                return
            }
            val temp = Seq<String>()
            for(a in 0 until Vars.netServer.clientCommands.commandList.size) {
                val command = Vars.netServer.clientCommands.commandList[a]
                if(Permissions.check(player, command.text)) {
                    temp.add("[orange] /${command.text} [white]${command.paramText} [lightgray]- ${command.description}\n")
                }
            }
            val result = StringBuilder()
            val per = 8
            var page = if(arg.isNotEmpty()) abs(Strings.parseInt(arg[0])) else 1
            val pages = Mathf.ceil(temp.size.toFloat() / per)
            page--

            if(page >= pages || page < 0) {
                player.sendMessage("[scarlet]'page' must be a number between[orange] 1[] and[orange] ${pages}[scarlet].")
                return
            }

            result.append(Strings.format("[orange]-- Commands Page[lightgray] ${page + 1}[gray]/[lightgray]${pages}[orange] --\n"))
            for(a in per * page until (per * (page + 1)).coerceAtMost(temp.size)) {
                result.append(temp[a])
            }
            player.sendMessage(result.toString().substring(0, result.length - 1))
        }

        fun info(){
            val result = DB.players.find { it.uuid.equals(player.uuid()) }
            val texts = """
                name: ${result.name}
                placecount: ${result.placecount}
                breakcount: ${result.breakcount}
                level: ${result.level}
                exp: ${result.exp}
                joinDate: ${result.joinDate}
                playtime: ${result.playtime}
                attackclear: ${result.attackclear}
                pvpwincount: ${result.pvpwincount}
                pvplosecount: ${result.pvplosecount}
            """.trimIndent()
            Call.infoMessage(player.con(), texts)
        }

        fun color(){
            // 무지개 닉네임 기능
        }

        fun login(){
            // 계정에 로그인 할 때 사용
            // login <id> <pw>
        }

        fun register(){
            // 계정을 등록할 때 사용
            // reg <pw> <pw_repeat>
            if(arg.size != 2){
                player.sendMessage("Usage: /reg <password> <password repeat>")
            } else if(arg[1] == arg[2]) {
                player.sendMessage("Password repeat value isn't same.")
            } else {
                val data = DB.PlayerData

                val ip = player.ip()
                val isLocal = try {
                    val address = InetAddress.getByName(ip)
                    if(address.isAnyLocalAddress || address.isLoopbackAddress) {
                        true
                    } else {
                        NetworkInterface.getByInetAddress(address) != null
                    }
                } catch(e: SocketException) {
                    false
                } catch(e: UnknownHostException) {
                    false
                }

                data.countryCode = if (isLocal) {
                    Locale.getDefault().isO3Country
                } else {
                    val res = Tool.ipre.IPQuery(ip)
                    val code = CountryCode.getByCode(res.countryShort)
                    if(code == null) Locale.getDefault().isO3Country else code.toLocale().isO3Country
                }

                data.name = player.name()
                data.uuid = player.uuid()
                data.joinDate = System.currentTimeMillis().toInt()
                data.id = player.name()
                data.pw = arg[1]

                DB.createData(data)
                Log.info("${player.name()} data created.")
            }
        }

        fun maps(){
            // 서버에 있는 맵 기능 표시
            val list = Vars.maps.all()
            val build = StringBuilder()

            val page = if(arg.isNotEmpty()) arg[0].toInt() else 0

            val buffer = Mathf.ceil(list.size.toFloat() / 6)
            val pages = if(buffer > 1.0) buffer - 1 else 0

            if(page > pages || page < 0) {
                player.sendMessage("[scarlet]'page' must be a number between[orange] 1[] and[orange] $pages[scarlet].")
                return
            }
            build.append("[green]==[white] Server maps page ").append(page).append("/").append(pages).append(" [green]==[white]\n")
            for(a in 6 * page until (6 * (page + 1)).coerceAtMost(list.size)) {
                build.append("[gray]").append(a).append("[] ").append(list[a].name()).append("\n")
            }

            player.sendMessage(build.toString())
        }

        fun me(){
            // 특수 포맷으로 채팅 표시
            Call.sendMessage("*${player.name()}: ${arg[0]}")
        }

        fun motd(){
            // 서버 motd 표시
            // todo countryCode
            val motd = if(Main.pluginRoot.child("motd/${data.countryCode}.txt").exists()) {
                Main.pluginRoot.child("motd/${data.countryCode}.txt").readString()
            } else {
                val file = Main.pluginRoot.child("motd/" + Config.locale.toString() + ".txt")
                if(file.exists()) file.readString() else ""
            }
            val count = motd.split("\r\n|\r|\n").toTypedArray().size
            if(count > 10) Call.infoMessage(player.con(), motd) else player.sendMessage(motd)

        }

        fun players(){
            // 서버에 있는 플레이어 목록 표시
            val message = StringBuilder()
            val page = if(arg.isNotEmpty()) arg[0].toInt() else 0

            val buffer = Mathf.ceil(Groups.player.size().toFloat() / 6)
            val pages = if(buffer > 1.0) buffer - 1 else 0

            if(pages < page) {
                player.sendMessage("[scarlet]페이지 쪽수는 최대 [orange]$pages[] 까지 있습니다")
            } else {
                message.append("[green]==[white] 현재 서버 플레이어 목록. [sky]페이지 [orange]$page[]/[orange]$pages\n")

                val players: Seq<Playerc> = Seq<Playerc>()
                Groups.player.each { e: Playerc -> players.add(e) }

                for(a in 6 * page until (6 * (page + 1)).coerceAtMost(Groups.player.size())) {
                    message.append("[gray]${players.get(a).id()}[white] ${
                        players.get(a).name()
                    }\n")
                }

                player.sendMessage(message.toString().dropLast(1))
            }
        }

        fun spawn(){
            // 몹 스폰
            val type = arg[0]
            val name = arg[1]
            val parameter = if(arg.size == 3) arg[2].toIntOrNull() else 1

            when {
                type.equals("unit", true) -> {
                    val unit = Vars.content.units().find { unitType: UnitType -> unitType.name == name }
                    if(unit != null) {
                        if(parameter != null) {
                            if(name != "block") {
                                for(a in 1..parameter) {
                                    val baseUnit = unit.create(player.team())
                                    baseUnit.set(player.x, player.y)
                                    baseUnit.add()
                                }
                            } else {
                                player.sendMessage("Block isn't unit. don't spawn it.")
                            }
                        } else {
                            player.sendMessage("system.mob.not-number")
                        }
                    } else {
                        val names = StringBuilder()
                        Vars.content.units().each {
                            names.append("${it.name}, ")
                        }
                        player.sendMessage("Avaliable unit names: ${names.dropLast(2)}")
                    }
                }
                type.equals("block", true) -> {
                    if (Vars.content.blocks().find { it.name == name } != null){
                        Call.constructFinish(player.tileOn(), Vars.content.blocks().find { it.name.equals(name, true) }, player.unit(), parameter?.toByte() ?: 0, player.team(), null)
                    } else {
                        val names = StringBuilder()
                        Vars.content.blocks().each {
                            names.append("${it.name}, ")
                        }
                        player.sendMessage("Avaliable block names: ${names.dropLast(2)}")
                    }
                }
                else -> {
                    return
                }
            }
        }

        fun status(){
            // 서버 상태 표시
            val bans = Vars.netServer.admins.banned.size
            val ipbans = Vars.netServer.admins.bannedIPs.size

            player.sendMessage("""
                [#DEA82A]Server status[]
                [#2B60DE]========================================[]
                ${Core.graphics.framesPerSecond} TPS, ${Groups.player.size()} players online.
                Total [scarlet]${bans +ipbans}[]($bans/$ipbans) players banned.
                World playtime: ${PluginData.playtime}
                Server uptime: ${PluginData.uptime}
                Plugin version: ${PluginData.pluginVersion}
            """.trimIndent())
        }

        fun team(){
            // 팀 변경
            when(arg[0]) {
                "derelict" -> player.team(Team.derelict)
                "sharded" -> player.team(Team.sharded)
                "crux" -> player.team(Team.crux)
                "green" -> player.team(Team.green)
                "purple" -> player.team(Team.purple)
                "blue" -> player.team(Team.blue)
            }
        }

        fun time(){
            // 서버 시간 표시
            val now = LocalDateTime.now()
            val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            player.sendMessage("Server time: ${now.format(dateTimeFormatter)}")
        }

        fun weather(){
            // 날씨 기능
        }

        fun mute(){
            // 특정 플레이어 채팅 금지 기능
            val other = Groups.player.find { p: Playerc -> p.name().equals(arg[0], ignoreCase = true) }
            if(other == null) {
                player.sendMessage("Target player not found!")
            } else {
                val target = PluginData[other.uuid()]
                target!!.mute = true
                player.sendMessage("Target player ${target.name} is muted.")
            }
        }

        fun unmute(){
            // 특정 플레이어 채팅 금지 해제
            val other = Groups.player.find { p: Playerc -> p.name().equals(arg[0], ignoreCase = true) }
            if(other == null) {
                player.sendMessage("Target player not found!")
            } else {
                val target = PluginData[other.uuid()]
                target!!.mute = false
                player.sendMessage("Target player ${target.name} is unmuted.")
            }
        }

        fun effect(){
            // 효과 표시 기능
            //Effect effect, float x, float y, float rotation, Color color
            //Call.effect()
        }

        fun god(){
            // 무적 기능
            player.unit().health(99999999f)
            player.sendMessage("Set high player unit health.")
        }

        fun random(){
            // 무작위 이벤트 기능
        }

        fun pause(){
            // 서버 일시정지 기능
            val pause = arg[0] == "on"
            Vars.state.serverPaused = pause
            player.sendMessage(if(pause) "Game paused" else "Game unpaused")
        }

        fun hub() {
            // 서버간 이동 기능
        }

        fun gg(){
            // 강제 게임오버 기능
            Call.gameOver(Team.crux)
        }

        fun kill(){
            // 자신 또는 특정 플레이어 유닛을 파괴하는 기능
            if(arg.isEmpty()) {
                player.unit().kill()
            } else {
                val other = Groups.player.find { p: Playerc -> p.name().equals(arg[0], ignoreCase = true) }
                if(other == null) player.sendMessage("Target player not found!") else other.unit().kill()
            }

        }

        fun meme(){
            // 밈
            when(arg[0]){
                "router" -> {
                    val zero = arrayOf(
                        """
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040][][#404040]
                            """, """
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][][#404040][]
                            """, """
                            [stat][#404040][][#404040]
                            [stat][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][stat][][stat]
                            """, """
                            [stat][#404040][][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            """, """
                            [#404040][stat][][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            """
                    )
                    val loop = arrayOf(
                        """
                            [#6B6B6B][stat][#6B6B6B]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][]
                            [stat][#404040][]
                            [stat][#404040][]
                            [#6B6B6B][stat][#404040][][#6B6B6B]
                            """, """
                            [#6B6B6B][stat][#6B6B6B]
                            [#6B6B6B][stat][#404040][][#6B6B6B]
                            [stat][#404040][]
                            [#404040][]
                            [stat][#404040][]
                            [stat][#404040][]
                            [#6B6B6B][stat][#404040][][#6B6B6B]
                            [#6B6B6B][stat][#6B6B6B]
                            """, """
                            [#6B6B6B][#585858][stat][][#6B6B6B]
                            [#6B6B6B][#828282][stat][#404040][][][#6B6B6B]
                            [#585858][stat][#404040][][#585858]
                            [stat][#404040][]
                            [stat][#404040][]
                            [#585858][stat][#404040][][#585858]
                            [#6B6B6B][stat][#404040][][#828282][#6B6B6B]
                            [#6B6B6B][#585858][stat][][#6B6B6B]
                            """, """
                            [#6B6B6B][#585858][#6B6B6B]
                            [#6B6B6B][#828282][stat][][#6B6B6B]
                            [#585858][#6B6B6B][stat][#404040][][#828282][#585858]
                            [#585858][stat][#404040][][#585858]
                            [#585858][stat][#404040][][#585858]
                            [#585858][#6B6B6B][stat][#404040][][#828282][#585858]
                            [#6B6B6B][stat][][#828282][#6B6B6B]
                            [#6B6B6B][#585858][#6B6B6B]
                            """, """
                            [#6B6B6B][#585858][#6B6B6B]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#585858][#6B6B6B][stat][][#828282][#585858]
                            [#585858][#6B6B6B][stat][#404040][][#828282][#585858]
                            [#585858][#6B6B6B][stat][#404040][][#828282][#585858]
                            [#585858][#6B6B6B][stat][][#828282][#585858]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#6B6B6B][#585858][#6B6B6B]
                            """, """
                            [#6B6B6B][#585858][#6B6B6B]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#585858][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][stat][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][stat][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][#828282][#585858]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#6B6B6B][#585858][#6B6B6B]
                            """, """
                            [#6B6B6B][#585858][#6B6B6B]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#585858][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][#828282][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][#828282][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][#828282][#585858]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#6B6B6B][#585858][#6B6B6B]
                            """
                    )
                    if(data.status.containsKey("router")){
                        data.status.put("router", false)
                    } else {
                        Core.app.post {
                            data.status.put("router", true)
                            while (!player.isNull) {
                                for (d in loop) {
                                    player.name(d)
                                    Threads.sleep(500)
                                }
                                if (!(data.status.get("router") as Boolean)) {
                                    data.status.remove("router")
                                    break
                                }
                                Threads.sleep(5000)
                                for (i in loop.indices.reversed()) {
                                    player.name(loop[i])
                                    Threads.sleep(500)
                                }
                                for (d in zero) {
                                    player.name(d)
                                    Threads.sleep(500)
                                }
                            }
                        }
                    }
                }
                "music" -> {
                    // Router 의 밈
                }
            }
        }

        fun tp(){
            // 플레이어에게 이동하는 기능
            val other = if (arg[0].toIntOrNull() != null) {
                Groups.player.find { e -> e.id == arg[0].toInt() }
            } else {
                Groups.player.find { e -> e.name().contains(arg[0]) }
            }

            if(other == null) {
                player.sendMessage("Target player not found!")
                return
            }
            Call.setPosition(player.con(), other.x, other.y)
        }

        fun config(){
            // 설정 편집 기능
            // /config 이름 값
            val property = remake.Config::class.memberProperties.find { it.name == arg[0] }
            if (property is KMutableProperty<*>) {
                property.setter.call(arg[0], arg[1])
                player.sendMessage("Set ${arg[0]} to ${arg[1]}")
            }
        }

        fun search(){
            // 플레이어 데이터 검색 기능
        }
    }
}