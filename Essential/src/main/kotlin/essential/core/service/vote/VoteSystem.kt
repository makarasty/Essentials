package essential.core.service.vote

import arc.Core
import arc.Events
import arc.files.Fi
import arc.func.Cons
import arc.graphics.Color
import arc.util.Timer
import essential.common.*
import essential.common.database.data.PlayerData
import essential.common.database.data.getPlayerData
import essential.common.database.data.update
import essential.common.event.CustomEvents
import essential.common.permission.Permission
import essential.common.util.findPlayerData
import essential.core.Main.Companion.scope
import essential.core.earnEXP
import essential.core.ModuleRuntime
import essential.core.VoteData
import essential.core.VoteType
import kotlinx.coroutines.launch
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.content.UnitTypes
import mindustry.content.Weathers
import mindustry.game.EventType.GameOverEvent
import mindustry.game.EventType.WorldLoadEvent
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.io.SaveIO
import mindustry.net.Administration
import mindustry.net.Packets
import mindustry.net.WorldReloader
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.minutes


private val yesWords = setOf("y", "yes", "так", "+")
private val noWords = setOf("n", "no", "ні", "-")

private fun isYes(message: String) = message.trim().lowercase() in yesWords
private fun isNo(message: String) = message.trim().lowercase() in noWords

/**
 * The save a passed `vote back` restores: the newest `rollback_*.msav` on disk.
 *
 * Only the plugin's own backups are eligible. They are written by the map backup task, capped by
 * `command.rollback.limit` and deleted on every world load, so the newest one always belongs to the map
 * being played. The engine's `auto_*` autosaves survive a map change, the first one on a new map is not
 * written for `autosaveSpacing` seconds, and `SaveIO.load` performs no map check - so for that window
 * the newest autosave on disk is the previous map, and restoring it would swap the server onto it.
 *
 * The timestamps are compared as longs. Arc's `Seq.min` and `Seq.max` read them through a float, which
 * at the current epoch cannot separate two saves written within about two minutes of each other.
 */
internal fun findVoteBackSave(): Fi? =
    Vars.saveDirectory
        .findAll { f: Fi -> f.name().startsWith("rollback_") && f.name().endsWith(".msav") }
        .maxByOrNull { it.lastModified() }

/**
 * The repeating decay the `vote random` fire outcome leaves behind. It stops when the world is
 * replaced, and when the countdown runs out.
 *
 * The returned task is the one that was scheduled, so cancelling it stops the decay. Written as a
 * `java.util.TimerTask` this still compiled - that class is a [Runnable], so `Timer.schedule` bound its
 * `Runnable` overload, wrapped the object in an arc task of its own and returned that instead - but the
 * wrapper was discarded and the object's own `cancel()` then cancelled a `java.util.Timer` scheduling
 * that had never happened. Nothing could stop the decay, on this map or any map loaded after it.
 */
internal fun scheduleFireDecay(ticks: Int = 600, onSupply: () -> Unit): Timer.Task {
    val task = object : Timer.Task() {
        var tick = ticks
        val listener: Cons<WorldLoadEvent>

        init {
            listener = Cons<WorldLoadEvent> {
                this.cancel()
            }

            Events.on(WorldLoadEvent::class.java, listener)
        }

        override fun cancel() {
            Events.remove(WorldLoadEvent::class.java, listener)
            super.cancel()
        }

        override fun run() {
            tick--
            Groups.unit.each {
                it.health(it.health() / 10)
            }
            Groups.build.each {
                it.health(it.health() / 30)
            }
            if (tick == ticks / 2) {
                onSupply()
            }
            if (tick <= 0) {
                cancel()
            }
        }
    }

    Timer.schedule(task, 0f, 10f)
    return task
}

/**
 * The delayed roll the `vote random` outcome runs three seconds after the vote passes: [outcome] once,
 * and not at all if the world is replaced first.
 *
 * The returned task is the one that was scheduled. `Time.runTask` cannot be used here even though it
 * takes the delay in ticks: it binds `Timer.schedule(Runnable, float)`, which wraps its argument in an
 * arc task of its own, so a [Timer.Task] handed to it is never itself scheduled and its own `cancel()`
 * reaches nothing - the same trap as the fire decay above.
 *
 * [stopped] is not redundant with `cancel()`. Arc drops a one shot task from the timer's list before it
 * posts it to the app thread, so a world load landing in that gap has nothing left to unschedule and the
 * body would run on the new map anyway.
 */
internal fun scheduleRandomOutcome(delaySeconds: Float = 3f, outcome: () -> Unit): Timer.Task {
    val task = object : Timer.Task() {
        var stopped = false
        val listener: Cons<WorldLoadEvent>

        init {
            listener = Cons<WorldLoadEvent> {
                this.cancel()
            }

            Events.on(WorldLoadEvent::class.java, listener)
        }

        override fun cancel() {
            stopped = true
            Events.remove(WorldLoadEvent::class.java, listener)
            super.cancel()
        }

        override fun run() {
            if (stopped) return
            cancel()
            outcome()
        }
    }

    Timer.schedule(task, delaySeconds)
    return task
}

class VoteSystem(val voteData: VoteData) : Timer.Task() {
    private var count = 60
    private var voted = ArrayList<String>()
    private var isAdminVote = false
    private var isCanceled = false
    private var isPvP = Vars.state.rules.pvp

    private var chatFilter: Administration.ChatFilter
    private var gameoverEvent: Cons<GameOverEvent>
    private var worldLoadEvent: Cons<WorldLoadEvent>

    init {
        fun sendMessage(playerData: PlayerData?) {
            if (playerData != null) {
                val bundle = playerData.bundle
                playerData.send("command.vote.starter", voteData.starter.player.plainName())
                playerData.player.sendMessage(
                    when (voteData.type) {
                        VoteType.Kick -> bundle["command.vote.kick.start", voteData.target!!.plainName(), voteData.reason!!]
                        VoteType.Map -> bundle["command.vote.map.start", voteData.map!!.name(), voteData.reason!!]
                        VoteType.GameOver -> {
                            if (!isPvP) {
                                bundle["command.vote.gg.start"]
                            } else {
                                bundle["command.vote.gg.pvp.team"]
                            }
                        }
                        VoteType.Skip -> bundle["command.vote.skip.start", voteData.wave!!]
                        VoteType.Back -> bundle["command.vote.back.start", voteData.reason!!]
                        VoteType.Random -> bundle["command.vote.random.start"]
                        VoteType.Draw -> bundle["command.vote.draw.start"]
                    }
                )
                playerData.send("command.vote.how")
            }
        }

        voted.add(voteData.starter.player.uuid())

        players.forEach {
            if (isPvP) {
                if (voteData.team == it.player.team()) {
                    sendMessage(findPlayerData(it.uuid))
                }
            } else {
                sendMessage(findPlayerData(it.uuid))
            }
        }

        chatFilter = Administration.ChatFilter { player, message ->
            if (!message.startsWith("/")) {
                val data = findPlayerData(player.uuid())
                if (data != null) {
                    val isAdmin = Permission.check(data, "vote.pass")
                    // The starter is pre-seeded into `voted` at construction (so their own vote
                    // counts without them having to speak), which also means the ordinary yes-branch
                    // below - guarded on `!voted.contains` - can never see them. A starter holding
                    // vote.pass needs its own branch to reach the instant pass at all.
                    if (isVoting && isYes(message) && voteData.starter == data && isAdmin && !isAdminVote) {
                        isAdminVote = true
                        data.send("command.vote.voted")
                    } else if (isVoting && isYes(message) && !voted.contains(player.uuid())) {
                        if (Vars.state.rules.pvp && voteData.team == player.team()) {
                            voted.add(player.uuid())
                        } else if (!Vars.state.rules.pvp) {
                            voted.add(player.uuid())
                        }
                        data.send("command.vote.voted")
                    } else if (isVoting && isNo(message) && isAdmin) {
                        isCanceled = true
                    }
                    if (isVoting && (isYes(message) || isNo(message))) {
                        return@ChatFilter null
                    } else {
                        return@ChatFilter message
                    }
                } else {
                    return@ChatFilter message
                }
            } else {
                return@ChatFilter message
            }
        }

        gameoverEvent = Cons<GameOverEvent> {
            this.cancel()
        }

        worldLoadEvent = Cons<WorldLoadEvent> {
            this.cancel()
        }

        Vars.netServer.admins.addChatFilter(chatFilter)
        Events.on(GameOverEvent::class.java, gameoverEvent)
        Events.on(WorldLoadEvent::class.java, worldLoadEvent)
    }

    fun send(message: String, vararg parameter: Any) {
        players.forEach {
            if (voteData.targetUUID != it.uuid) {
                it.send(message, *parameter)
            }
        }
    }

    fun check(): Int {
        val threshold = if (!isPvP) {
            when (players.filterNot { it.afk }.size) {
                1 -> 1
                in 2..4 -> 2
                in 5..6 -> 3
                7 -> 4
                in 8..9 -> 5
                in 10..11 -> 6
                12 -> 7
                else -> 8
            }
        } else {
            when (players.count { a -> a.player.team() == voteData.team && !a.afk }) {
                1 -> 1
                in 2..4 -> 2
                in 5..6 -> 3
                7 -> 4
                in 8..9 -> 5
                in 10..11 -> 6
                12 -> 7
                else -> 8
            }
        }
        // Ruled in answers/9-2.md: a bare `1 -> 2` above would also block a case that works today.
        // Not the solo `map` vote (Commands.kt's `solo` hatch there bypasses VoteSystem entirely
        // when players.size == 1, straight to a direct Vars.world.loadMap with no threshold involved)
        // - it is a lone vote.admin holder (Commands.kt's eligibleVoters <= 3 gate is skipped for
        // that permission) starting any other vote type with nobody else on the server. Their own
        // seeded vote already passes at the table's `1 -> 1`; a bare floor of 2 would make that
        // permanently unreachable, since there is no second player who could ever supply it. The
        // floor exists to stop one person deciding for others, so where `players` (server-wide)
        // holds nobody else, it has nothing to do.
        return if (players.size == 1) threshold else maxOf(2, threshold)
    }

    override fun cancel() {
        isVoting = false
        Vars.netServer.admins.chatFilters.remove(chatFilter)
        Events.remove(GameOverEvent::class.java, gameoverEvent)
        Events.remove(WorldLoadEvent::class.java, worldLoadEvent)
        super.cancel()
    }

    override fun run() {
        if (isVoting) {
            if (Groups.player.find { a -> a.uuid() == voteData.starter.uuid } == null) {
                send("command.vote.canceled.leave")
                this.cancel()
            } else {
                if (count % 10 == 0) {
                    if (isPvP) {
                        Groups.player.forEach {
                            if (it.team() == voteData.team) {
                                val data = findPlayerData(it.uuid())
                                if (data != null && voteData.targetUUID != data.uuid) {
                                    data.send("command.vote.count", count.toString(), check() - voted.size)
                                }
                            }
                        }
                    } else {
                        send("command.vote.count", count.toString(), check() - voted.size)
                        if (voteData.type == VoteType.Kick && Groups.player.find { a -> a.uuid() == voteData.targetUUID } == null) {
                            send("command.vote.kick.target.leave")
                        }
                    }
                }
                count--
                if ((count == 0 && check() <= voted.size) || check() <= voted.size || isAdminVote) {
                    send("command.vote.success")

                    val onlinePlayers = players.joinToString(", ") { it.name }

                    when (voteData.type) {
                        VoteType.Kick -> {
                            val targetUUID = voteData.targetUUID!!
                            val name = Vars.netServer.admins.getInfo(targetUUID).lastName
                            val targetPlayer = Groups.player.find { a -> a.uuid() == targetUUID }
                            if (targetPlayer == null) {
                                Vars.netServer.admins.banPlayerID(targetUUID)
                                send("command.vote.kick.target.banned", name)
                                scope.launch {
                                    val data = getPlayerData(targetUUID)
                                    if (data != null) {
                                        data.isBanned = true
                                        data.update()
                                    }
                                }
                                Events.fire(
                                    CustomEvents.PlayerVoteBanned(
                                        voteData.starter.name,
                                        name,
                                        voteData.reason!!,
                                        onlinePlayers.toString()
                                    )
                                )
                            } else {
                                val data = findPlayerData(targetUUID)
                                if (data != null) {
                                    data.isBanned = true
                                    if (voteData.starter.uuid == targetUUID) {
                                        voteData.starter.status["record.voting.ban"] = "1"
                                        ModuleRuntime.awardVotingBan(voteData.starter)
                                    }
                                }
                                targetPlayer.kick(Packets.KickReason.kick, 60 * 60 * 3000)
                                send("command.vote.kick.target.kicked", name)
                                Events.fire(
                                    CustomEvents.PlayerVoteKicked(
                                        voteData.starter.name,
                                        name,
                                        voteData.reason!!,
                                        onlinePlayers.toString()
                                    )
                                )
                            }
                        }

                        VoteType.Map -> {
                            for (it in players) {
                                earnEXP(Vars.state.rules.waveTeam, it.player, it, true)
                            }
                            isSurrender = true
                            Vars.maps.setNextMapOverride(voteData.map)
                            Events.fire(GameOverEvent(Vars.state.rules.waveTeam))
                        }

                        VoteType.GameOver -> {
                            if (!Permission.check(voteData.starter, "vote.pass")) {
                                voterCooldown[voteData.starter.uuid] = timeSource.markNow().plus(3.minutes)
                            }
                            if (isPvP) {
                                Vars.world.tiles.forEach {
                                    if (it.build != null && it.build.team != null && it.build.team == voteData.team) {
                                        Call.setTile(it, Blocks.air, voteData.team, 0)
                                    }
                                }
                            } else {
                                isSurrender = true
                                Events.fire(GameOverEvent(Vars.state.rules.waveTeam))
                            }
                        }

                        VoteType.Draw -> {
                            if (!Permission.check(voteData.starter, "vote.pass")) {
                                voterCooldown[voteData.starter.uuid] = timeSource.markNow().plus(3.minutes)
                            }
                            val event = CustomEvents.VoteDrawEvent(voteData.starter)
                            Events.fire(event)
                            if (!event.handled) {
                                isSurrender = true
                                Events.fire(GameOverEvent(Team.derelict))
                            }
                        }

                        VoteType.Skip -> {
                            voterCooldown[voteData.starter.uuid] = timeSource.markNow().plus(3.minutes)
                            repeat(voteData.wave!!) {
                                Vars.spawner.spawnEnemies()
                                Vars.state.wave++
                                Vars.state.wavetime = Vars.state.rules.waveSpacing
                            }
                            send("command.vote.skip.done", voteData.wave!!.toString())
                        }

                        VoteType.Back -> {
                            isSurrender = true
                            val savePath: Fi? = findVoteBackSave()

                            if (savePath != null && savePath.exists()) {
                                try {
                                    val mode = Vars.state.rules.mode()
                                    val reloader = WorldReloader()

                                    reloader.begin()
                                    SaveIO.load(savePath)

                                    Vars.state.rules = Vars.state.map.applyRules(mode)
                                    Vars.logic.play()
                                    reloader.end()

                                    savePath.delete()
                                } catch (t: Exception) {
                                    t.printStackTrace()
                                }
                                send("command.vote.back.done")
                            } else {
                                send("command.vote.back.no.file")
                            }
                        }

                        VoteType.Random -> {
                            voterCooldown[voteData.starter.uuid] = timeSource.markNow().plus(7.minutes)
                            nextVoteAvailable = timeSource.markNow().plus(5.minutes)
                            send("command.vote.random.done")
                            send("command.vote.random.is")
                            scheduleRandomOutcome {
                                when (kotlin.random.Random.nextInt(7)) {
                                    0 -> {
                                        send("command.vote.random.unit")
                                        Groups.unit.each {
                                            if (it.team == voteData.starter.player.team()) it.kill()
                                        }
                                        send("command.vote.random.unit.wave")
                                        Vars.logic.runWave()
                                    }

                                    1 -> {
                                        send("command.vote.random.wave")
                                        for (a in 0..5) Vars.logic.runWave()
                                    }

                                    2 -> {
                                        send("command.vote.random.health")
                                        Groups.build.each {
                                            it.health(it.health / 2)
                                        }
                                    }

                                    3 -> {
                                        send("command.vote.random.fill.core")
                                        Vars.content.items().forEach {
                                            if (!it.isHidden) {
                                                Vars.state.teams.cores(voteData.starter.player.team())
                                                    .first().items.add(
                                                        it,
                                                        kotlin.random.Random.nextInt(2000)
                                                    )
                                            }
                                        }
                                    }

                                    4 -> {
                                        send("command.vote.random.storm")
                                        Call.createWeather(
                                            Weathers.rain,
                                            10f,
                                            60 * 60f,
                                            50f,
                                            10f
                                        )
                                    }

                                    5 -> {
                                        send("command.vote.random.fire")
                                        // One broadcast per tile was 40,000 packets to every player in a
                                        // single frame on a 200x200 map; a stride keeps it near a thousand
                                        val step = ceil(sqrt(Vars.world.width() * Vars.world.height() / 1000.0)).toInt().coerceAtLeast(1)
                                        for (x in 0 until Vars.world.width() step step) {
                                            for (y in 0 until Vars.world.height() step step) {
                                                Call.effect(
                                                    Fx.fire,
                                                    (x * 8).toFloat(),
                                                    (y * 8).toFloat(),
                                                    0f,
                                                    Color.red
                                                )
                                            }
                                        }

                                        scheduleFireDecay {
                                            send("command.vote.random.supply")
                                            repeat(2) {
                                                UnitTypes.oct.spawn(
                                                    voteData.starter.player.team(),
                                                    voteData.starter.player.x,
                                                    voteData.starter.player.y
                                                )
                                            }
                                        }

                                    }

                                    else -> {
                                        send("command.vote.random.nothing")
                                    }
                                }
                            }
                        }
                    }

                    this.cancel()
                } else if ((count == 0 && check() > voted.size) || isCanceled) {
                    if (isPvP) {
                        players.forEach {
                            if (it.player.team() == voteData.team) {
                                Core.app.post { it.send("command.vote.failed") }
                            }
                        }
                    } else {
                        send("command.vote.failed")
                    }
                    this.cancel()
                }
            }
        } else {
            this.cancel()
        }
    }
}
