package essential.core

import arc.Core
import arc.Events
import arc.func.Prov
import arc.graphics.Color
import arc.util.Align
import arc.util.Log
import arc.util.Time
import arc.util.Timer
import arc.util.io.FastDeflaterOutputStream
import essential.common.bundle.Bundle
import essential.common.event.CustomEvents
import essential.common.database.data.PlayerData
import essential.common.database.data.cleanupExpiredRoutingPermissions
import essential.common.database.data.grantRoutingPermission
import essential.common.database.data.plugin.WarpBlock
import essential.common.database.data.plugin.WarpCount
import essential.common.database.data.plugin.WarpTotal
import essential.common.database.data.plugin.WarpZone
import essential.common.permission.Permission
import essential.common.players
import essential.common.pluginData
import essential.common.rootPath
import essential.common.systemTimezone
import essential.common.util.changeTeam
import essential.core.Main.Companion.conf
import essential.core.Main.Companion.scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Playerc
import mindustry.io.SaveIO
import mindustry.io.SaveOptions
import mindustry.net.Host
import mindustry.net.NetworkIO
import mindustry.world.Tile
import java.io.ByteArrayOutputStream
import java.lang.Thread.currentThread
import java.lang.Thread.sleep
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.function.Consumer
import kotlin.math.floor
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import essential.common.database.data.update

/** Mindustry colour tags, stripped before a name is re-coloured. Compiled once: [Trigger] used to
 *  build this inside the one-second loop, so it was recompiled per animated player per second. */
private val colorTag = Regex("\\[(.*?)]")

/** The animated name's palette, one colour tag per character in turn. */
private val RAINBOW = arrayOf(
    "[#ff0000]", "[#ff7f00]", "[#ffff00]", "[#7fff00]", "[#00ff00]", "[#00ff7f]",
    "[#00ffff]", "[#007fff]", "[#0000ff]", "[#8000ff]", "[#ff00ff]"
)

object Trigger {
    fun pingHostImpl(address: String, port: Int, listener: Consumer<Host>) {
        val packetSupplier: Prov<DatagramPacket> = Prov<DatagramPacket> { DatagramPacket(ByteArray(512), 512) }

        try {
            DatagramSocket().use { socket ->
                val s: Long = Time.millis()
                socket.send(DatagramPacket(byteArrayOf(-2, 1), 2, InetAddress.getByName(address), port))
                socket.soTimeout = 1000
                val packet: DatagramPacket = packetSupplier.get()
                socket.receive(packet)
                val buffer = ByteBuffer.wrap(packet.data)
                val host =
                    NetworkIO.readServerData(Time.timeSinceMillis(s).toInt(), packet.address.hostAddress, buffer)
                host.port = port
                listener.accept(host)
            }
        } catch (_: Exception) {
            listener.accept(Host(0, null, null, null, 0, 0, 0, null, null, 0, null, null))
        }
    }

    /**
     * The distinct ip:port this cycle pings. A remote server configured as both a warp block
     * and a warp count used to be pinged once per list, and every ping blocks the cycle for
     * the socket's full second when the target does not answer.
     */
    fun pingTargets(
        warpBlock: List<WarpBlock>,
        warpCount: List<WarpCount>,
        warpZone: List<WarpZone>
    ): Set<Pair<String, Int>> {
        val targets = LinkedHashSet<Pair<String, Int>>()
        warpBlock.forEach { targets += it.ip to it.port }
        warpCount.forEach { targets += it.ip to it.port }
        warpZone.forEach { targets += it.ip to it.port }
        return targets
    }

    /**
     * Most marks the world-edit outline draws along one edge. Uncapped it was one packet per
     * perimeter tile four times a second per selecting player, so a selection dragged across
     * a 500x500 map cost that one client roughly 8000 packets a second until they cleared it.
     */
    const val OUTLINE_MARKS = 32

    /** Coordinates the outline marks along one edge, thinned to at most [OUTLINE_MARKS] of them. */
    fun outlineMarks(min: Int, max: Int): IntProgression = min..max step (max - min) / OUTLINE_MARKS + 1

    /**
     * What counts as this player having moved. `NetClient.sync` sends `0f, 0f` for the aim of a
     * player with no unit and the server assigns that straight into mouseX/mouseY, so the
     * pointer of a player who can never respawn is pinned - reading it would make the afk
     * counter unescapable for exactly the players this fix is about. The camera they can still
     * pan is the signal that survives.
     */
    fun activityMark(data: PlayerData): Float {
        val con = data.player.con()
        return if (data.player.unit() == null && con != null) con.viewX + con.viewY
        else data.player.mouseX() + data.player.mouseY()
    }

    /**
     * A player with no unit is dead on a team with no core to respawn from - every pvp loser
     * this file moves to Team.derelict is in that state permanently - and can never move or
     * mine again, so reading a null unit as activity exempted the idlest players on the
     * server from afk handling for good.
     */
    fun isAfkCandidate(data: PlayerData): Boolean {
        val unit = data.player.unit()
        return (unit == null || (!unit.moving() && !unit.mining())) &&
            !Permission.check(data, "afk.admin") &&
            data.mousePosition == activityMark(data)
    }

    /**
     * Where an afk player is sent, or null to kick them instead. The config documents an empty
     * server as "disable teleport" and ships empty, while the reader tested for null, so the
     * shipped default hopped the player to host "" on port 6567 rather than kicking. A value
     * that will not parse gets the same answer: `parts[1].toInt()` used to throw out of a
     * Timer body, which arc runs on the game thread with no handler above it, so one typo in
     * this field took the server down the first time anybody idled.
     */
    fun afkTarget(server: String?): Pair<String, Int>? {
        val parts = (server ?: return null).trim().split(":")
        val host = parts[0].trim()
        if (host.isEmpty()) return null
        if (parts.size == 1) return host to 6567
        val port = parts[1].trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            Log.warn("feature.afk.server is \"$server\", which carries no usable port; afk players are kicked instead")
            return null
        }
        return host to port
    }

    private fun mapBackups() =
        Vars.saveDirectory.findAll { f -> f.name().startsWith("rollback_") && f.name().endsWith(".msav") }

    private val backupLock = Any()

    /** Bumped by every world load, so a backup still compressing when the map changed is dropped. */
    private var backupGeneration = 0

    /**
     * Snapshots the world into a `rollback_*.msav` for `/vote back`. Serialising has to happen here on
     * the game thread, with the world standing still; compressing and writing the file does not, and on
     * a built-up map that was most of the time `SaveIO.save` held the tick for. Returns the write, or
     * null when backups are off.
     */
    fun saveMapBackup(): Job? {
        if (!conf.command.rollback.enabled || !conf.command.rollback.mapBackup) return null

        val raw = ByteArrayOutputStream(1 shl 20)
        SaveIO.write(raw, SaveOptions())
        val generation = synchronized(backupLock) { backupGeneration }
        val name = "rollback_${System.currentTimeMillis()}.msav"
        val limit = conf.command.rollback.limit

        return scope.launch(Dispatchers.IO) {
            // Written under another name and renamed, so findVoteBackSave never picks a half-written file.
            val tmp = Vars.saveDirectory.child("$name.tmp")
            try {
                FastDeflaterOutputStream(tmp.write(false, 8192)).use { raw.writeTo(it) }
                // Only the check and the rename under the lock: discardMapBackups takes it on the game
                // thread, and the prune below is a directory listing that does not need to hold it.
                synchronized(backupLock) {
                    if (generation != backupGeneration) return@launch
                    tmp.moveTo(Vars.saveDirectory.child(name))
                }
                mapBackups().sortedBy { it.lastModified() }.dropLast(limit).forEach { it.delete() }
            } catch (e: Exception) {
                Log.err("Map backup failed", e)
            } finally {
                tmp.delete()
            }
        }
    }

    /** Deletes every map backup: each one is of the map being replaced, and `SaveIO.load` checks no map. */
    fun discardMapBackups() = synchronized(backupLock) {
        backupGeneration++
        mapBackups().forEach { it.delete() }
        Vars.saveDirectory.child("rollback.msav").delete()
    }

    class PingThread: Runnable {
        /**
         * A warp entry that is permanently broken - a zone one tile wide, a host that will never
         * resolve - fails every three seconds for as long as it is configured. Mindustry keeps
         * every log file it rotates, so printing the same trace 1200 times an hour would cost an
         * operator real disk. One instance per writing thread, so neither needs to be volatile.
         */
        private class FailureLog {
            private var last: String? = null
            private var loggedAt = 0L

            /** A cycle that succeeded lets the next failure of the same shape print at once. */
            fun ok() {
                last = null
            }

            fun report(e: Exception) {
                val signature = "${e::class.qualifiedName}:${e.stackTrace.firstOrNull()}"
                val now = System.currentTimeMillis()
                if (signature != last || now - loggedAt > 300000) {
                    last = signature
                    loggedAt = now
                    Log.err(e)
                }
            }
        }

        private val pingFailures = FailureLog()
        private val drawFailures = FailureLog()
        private var lastWarpZoneWarning: String? = null
        private var lastWarpZoneWarningAt = 0L
        private var lastResolveWarning: String? = null
        private var lastResolveWarningAt = 0L

        /**
         * calculateCenter is O(width * height) with a sort on top, and it is a pure function of the
         * two stored positions, so it is computed once per zone rather than once per cycle on the
         * game thread. Written only from [draw].
         */
        private val centers = HashMap<Pair<Int, Int>, Pair<Int, Int>>()

        private fun calculateCenter(startTile: Tile, endTile: Tile): Pair<Int, Int> {
            data class Point(val x: Int, val y: Int)

            data class Tile(val coordinates: Point, val areaValue: Float)

            fun calculateAreaValue(x: Int, y: Int): Double {
                return (x + y) / 2.0
            }

            fun findMedianCoordinates(
                startPoint: mindustry.world.Tile,
                endPoint: mindustry.world.Tile
            ): Pair<Int, Int> {
                val regionWidth =
                    if (endPoint.x > startPoint.x) endPoint.x - startPoint.x else startPoint.x - endPoint.x
                val regionHeight =
                    if (endPoint.y > startPoint.y) endPoint.y - startPoint.y else startPoint.y - endPoint.y
                val totalTiles = regionWidth * regionHeight
                val tiles = mutableListOf<Tile>()

                fun search(y: Int) {
                    if (endPoint.x > startPoint.x) {
                        for (x in startPoint.x until endPoint.x) {
                            val areaValue = calculateAreaValue(x, y)
                            val tile = Tile(Point(x, y), areaValue.toFloat())
                            tiles.add(tile)
                        }
                    } else {
                        for (x in endPoint.x until startPoint.x) {
                            val areaValue = calculateAreaValue(x, y)
                            val tile = Tile(Point(x, y), areaValue.toFloat())
                            tiles.add(tile)
                        }
                    }
                }

                if (endPoint.y > startPoint.y) {
                    for (y in startPoint.y until endPoint.y) {
                        search(y)
                    }
                } else {
                    for (y in endPoint.y until startPoint.y) {
                        search(y)
                    }
                }

                tiles.sortBy { it.areaValue }

                val medianIndex = totalTiles / 2
                val medianTile = tiles[medianIndex]

                return Pair(medianTile.coordinates.x, medianTile.coordinates.y)
            }

            return findMedianCoordinates(startTile, endTile)
        }

        /**
         * The four warp lists are plain ArrayLists that /warp adds to, removes from and clears from
         * another thread, and ArrayList iterators are fail-fast, so this thread reads a copy rather
         * than the live list. A copy taken while the list is shrinking can carry nulls, because the
         * size and the backing array are not read together; that is what the filter drops.
         */
        private fun <T : Any> snapshot(list: List<T>): List<T> = ArrayList<T?>(list).filterNotNull()

        override fun run() {
            try {
                while (currentThread().isInterrupted.not()) {
                    // The warp display is decorative; a failure in one cycle must not end the
                    // round for everyone on the server.
                    try {
                        val startedAt = System.nanoTime()
                        val data = pluginData.data
                        val warpCount = snapshot(data.warpCount)
                        val warpTotal = snapshot(data.warpTotal)
                        val warpZone = snapshot(data.warpZone)
                        val warpBlock = snapshot(data.warpBlock)

                        // The only game read left on this thread, and only to decide whether this
                        // cycle pings at all. Vars.state.map is a plain field and name() is an
                        // ObjectMap lookup, so a map change during it can also throw - which is why
                        // it sits inside the cycle catch rather than outside it. Either way the
                        // next cycle corrects the answer three seconds later.
                        val mapName = Vars.state.map.name()
                        val onThisMap = warpCount.any { it.mapName == mapName } ||
                            warpTotal.any { it.mapName == mapName } ||
                            warpZone.any { it.mapName == mapName } ||
                            warpBlock.any { it.mapName == mapName }

                        if (onThisMap) {
                            val targets = pingTargets(warpBlock, warpCount, warpZone)
                            val serverInfo = getServerInfo(targets)
                            val resolved = targets.associate { it.first to resolve(it.first) }
                            var total = 0
                            for (a in serverInfo) {
                                total += a.players
                            }

                            // Labels have to outlive the cycle that drew them, and the cycle
                            // lasts as long as its pings take rather than the three seconds the
                            // sleep alone suggests. Capped, because a dead resolver has no timeout
                            // of its own and nothing ever clears a label early.
                            val elapsed = ((System.nanoTime() - startedAt) / 1e9).coerceIn(0.0, 12.0)

                            // Everything the drawing needs reads Groups.player, Vars.state,
                            // Vars.world or the settings map. Arc hands out one of two pooled Seq
                            // iterators per group (Seq.SeqIterable.iterator), so a walk from here
                            // concurrent with the game thread's own walk shares one index, and
                            // Core.settings is a plain HashMap the game thread iterates while it
                            // saves. The pings are this thread's job; the drawing is not.
                            val cycle = Cycle(
                                warpCount, warpTotal, warpZone, warpBlock,
                                serverInfo, resolved, total, elapsed.toFloat() + 3f
                            )
                            Core.app.post { draw(cycle) }
                        }

                        pingFailures.ok()
                    } catch (e: Exception) {
                        pingFailures.report(e)
                    }

                    sleep(3000)
                }

                runBlocking {
                    cleanupExpiredRoutingPermissions()
                }
            } catch (_: InterruptedException) {
                currentThread().interrupt()
            } catch (e: Exception) {
                Log.err(e)
            }
        }

        /** One cycle's ping results, handed over for the game thread to draw from. */
        private class Cycle(
            val warpCount: List<WarpCount>,
            val warpTotal: List<WarpTotal>,
            val warpZone: List<WarpZone>,
            val warpBlock: List<WarpBlock>,
            val serverInfo: Set<Host>,
            /** Configured host to the address behind it, resolved once for the whole cycle. */
            val resolved: Map<String, String?>,
            val total: Int,
            val labelLife: Float,
        )

        private fun Cycle.hostFor(ip: String, port: Int): Host? {
            val address = resolved[ip]
            return serverInfo.find { (it.address == ip || (address != null && it.address == address)) && it.port == port }
        }

        /**
         * Draws one cycle's results. Runs on the game thread: every read below is live game state,
         * and the writes are tile edits, labels and a settings entry the engine owns.
         */
        private fun draw(cycle: Cycle) = try {
            drawCycle(cycle)
            drawFailures.ok()
        } catch (e: Exception) {
            // This body used to run on the ping thread under the catch above. On the game thread
            // there is nothing over it: arc's TaskQueue.run calls the runnable bare, so a throw
            // here would unwind the update loop and stop the server - which is the defect commit
            // d023e36e closed for the ping thread.
            drawFailures.report(e)
        }

        private fun drawCycle(cycle: Cycle) {
            val data = pluginData.data
            val mapName = Vars.state.map.name()

            if (Vars.state.isPlaying) {
                for (value in cycle.warpCount) {
                    if (mapName == value.mapName) {
                        // Resolved like the block and zone loops beside it: a counter configured by
                        // hostname never matched the address its ping answered from.
                        val info = cycle.hostFor(value.ip, value.port)
                        if (info != null) {
                            // Off-map (a map re-saved smaller than when this warp was configured)
                            // must still skip the update below, even though nothing here reads the
                            // tile itself anymore.
                            if (value.tile == null) continue

                            val updated = WarpCount(mapName, value.pos, value.ip, value.port)
                            // Written and never read: the digit-count number this sized has no drawing
                            // routine anywhere in this repository. Kept anyway - WarpCount serialises
                            // into PluginData, the row six servers share, and removing a field changes
                            // that shape (task-119, answers/6-3.md).
                            updated.numberSize = info.players.toString().length
                            updated.players = info.players
                            // The position this entry had in the copy is not necessarily its
                            // position in the live list, so the write back finds it by identity.
                            val index = data.warpCount.indexOfFirst { it === value }
                            if (index != -1) data.warpCount[index] = updated
                        }
                    }
                }

                val memory = mutableListOf<Pair<Playerc, Triple<String, Float, Float>>>()
                val stale = mutableListOf<WarpBlock>()
                for (value in cycle.warpBlock) {
                    if (mapName == value.mapName) {
                        // Out of bounds means the loaded map is a different one that happens to
                        // share this name, not that the block was broken. Six servers share one
                        // plugin_data row, so removing here would wipe another server's warp blocks.
                        val tile = Vars.world.tile(value.x, value.y) ?: continue
                        // A non-air block with no building is either scenery or a block being
                        // replaced right now, and setBlock assigns the block before it assigns the
                        // building. Neither is proof the entry is stale.
                        val build = tile.build
                        if (build == null) {
                            if (tile.block() == Blocks.air) stale.add(value)
                        } else {
                            var margin = 0f
                            var isDup = false
                            val x = build.getX()

                            when (value.size) {
                                1 -> margin = 8f
                                2 -> {
                                    margin = 16f
                                    isDup = true
                                }

                                3 -> margin = 16f
                                4 -> {
                                    margin = 24f
                                    isDup = true
                                }

                                5 -> margin = 24f
                                6 -> {
                                    margin = 32f
                                    isDup = true
                                }

                                7 -> margin = 32f
                            }

                            var y = build.getY() + if (isDup) margin - 8 else margin

                            val info = cycle.hostFor(value.ip, value.port)
                            if (info != null) {
                                if (isDup) y += 4
                                Groups.player.forEach { a ->
                                    memory.add(
                                        a to Triple(
                                            "${info.mapname}\n[white][yellow]${info.players}[] ${Bundle(a.locale)["event.server.warp.players"]}",
                                            x,
                                            y
                                        )
                                    )
                                }
                            } else {
                                Groups.player.forEach { a ->
                                    memory.add(
                                        a to Triple(
                                            Bundle(a.locale)["event.server.warp.offline"],
                                            x,
                                            y
                                        )
                                    )
                                }
                            }
                            value.online = info != null

                            if (isDup) margin -= 4
                            Groups.player.forEach { a ->
                                memory.add(a to Triple(value.description, x, build.getY() - margin))
                            }
                        }
                    }
                }

                if (stale.isNotEmpty()) {
                    // By identity, not by equals: WarpBlock is a data class, so two entries
                    // describing the same block are equal and removeAll would take both.
                    data.warpBlock.removeAll { b -> stale.any { it === b } }
                }

                for (value in cycle.warpZone) {
                    if (mapName == value.mapName) {
                        val start = value.startTile
                        val finish = value.finishTile
                        if (start == null || finish == null) {
                            // A zone stored on a larger map of this name renders nothing here,
                            // while getServerInfo keeps pinging its address every three seconds.
                            // Its own rate-limit fields, so a permanently broken zone cannot hide
                            // a real exception from the cycle catch above.
                            val signature = "warpzone:${value.mapName}:${value.start}:${value.finish}"
                            val now = System.currentTimeMillis()
                            if (signature != lastWarpZoneWarning || now - lastWarpZoneWarningAt > 300000) {
                                lastWarpZoneWarning = signature
                                lastWarpZoneWarningAt = now
                                Log.warn("Warp zone for ${value.ip}:${value.port} has no tiles on the current map, skipping")
                            }
                            continue
                        }
                        val center = centers.getOrPut(value.start to value.finish) {
                            calculateCenter(start, finish)
                        }
                        val info = cycle.hostFor(value.ip, value.port)

                        // todo 중앙 정렬 안됨
                        for (a in Groups.player) {
                            memory.add(
                                a to Triple(
                                    if (info != null) "[yellow]${info.players}[] ${Bundle(a.locale)["event.server.warp.players"]}"
                                    else Bundle(a.locale)["event.server.warp.offline"],
                                    (center.first * 8).toFloat(),
                                    (center.second * 8).toFloat()
                                )
                            )
                        }
                    }
                }

                for (m in memory) {
                    Call.label(m.first.con(), m.second.first, cycle.labelLife, m.second.second, m.second.third)
                }

                // The warp total display had a broadcast tile-erase here (Call.setTile, every client)
                // to clear space for a digit count nothing in this repository draws. Deleted: it
                // networked-deleted whatever a player had built at the warp anchor whenever a remote
                // server's player count changed (task-119, refuted as a display desync - the real
                // defect was this destructive edit; see answers/6-3.md). WarpTotal.numberSize, the
                // field it was clearing space for, stays: it still serialises into the shared
                // PluginData row and costs nothing unused.
            }

            if (conf.feature.count) {
                Core.settings.put("totalPlayers", cycle.total + Groups.player.size())
            }
        }

        /**
         * The address behind a warp target's host, or null when it does not resolve. Looked up once
         * per cycle: it used to run inside the comparison loop, so one warp block cost one DNS
         * lookup - which has no timeout of its own - per pinged server per cycle.
         */
        private fun resolve(ip: String): String? = try {
            InetAddress.getByName(ip).hostAddress
        } catch (_: UnknownHostException) {
            val now = System.currentTimeMillis()
            if (ip != lastResolveWarning || now - lastResolveWarningAt > 300000) {
                lastResolveWarning = ip
                lastResolveWarningAt = now
                Log.warn("Could not resolve the warp target $ip")
            }
            null
        } catch (_: Exception) {
            null
        }

        private fun getServerInfo(targets: Set<Pair<String, Int>>): MutableSet<Host> {
            val total = mutableSetOf<Host>()

            for (a in targets) {
                pingHostImpl(a.first, a.second) {
                    if (it.name != null) {
                        total.add(it)
                    }
                }
            }

            return total
        }
    }

    fun register() {
        var colorOffset = 0
        fun rainbow(name: String): String {
            val stringBuilder = StringBuilder(name.length * 10)
            for (i in name.indices) {
                var colorIndex = (i + colorOffset) % RAINBOW.size
                if (colorIndex < 0) {
                    colorIndex += RAINBOW.size
                }
                stringBuilder.append(RAINBOW[colorIndex]).append(name[i])
            }
            colorOffset--
            return stringBuilder.toString()
        }

        // 맵 백업 시간
        var rollbackCount = conf.command.rollback.time
        var messageCount = conf.feature.motd.time
        var messageOrder = 0
        var dpsBlockCalculateTick = 0
        var trackTick = 0

        Events.run(EventType.Trigger.update) {
            // removeIf rather than filter + removeAll: this runs 60 times a second and the list it
            // was building is empty on all but the tick a player actually drops.
            if (players.removeIf { it.player.con() == null || it.player.con().hasDisconnected }) return@run
            trackTick++
            // Read once per tick, not once per player per warp zone. Null when no zone is defined,
            // which is what skips the walk entirely on the servers that have none.
            val warpZones = pluginData.data.warpZone
            val warpMapName = if (warpZones.isEmpty()) null else Vars.state.map.name()
            for (data in players) {
                recordPvpDefeat(data)

                // data is the entry being iterated, so there is nothing to look up; parsed once a tick.
                val frozenAt = data.status["freeze"]
                if (frozenAt != null) {
                    val split = frozenAt.toString().split("/")
                    val x = split[0].toFloat()
                    val y = split[1].toFloat()
                    val player = data.player
                    player[x] = y
                    Call.setPosition(player.con(), x, y)
                    Call.setCameraPosition(player.con(), x, y)
                }

                if (data.status.containsKey("chars_text") && Time.globalTime.toInt() % 30 == 0) {
                    val text = Commands.charsPlacing[data.uuid]
                    if (text != null) {
                        val startX = (data.player.mouseX() / 8f).toInt()
                        val startY = (data.player.mouseY() / 8f).toInt()
                        var x = startX
                        var y = startY
                        for (line in text) {
                            for (char in line) {
                                if (char == '#' && Vars.world.tile(x, y) != null) {
                                    Call.effect(data.player.con(), Fx.placeBlock, x * 8f + 4f, y * 8f + 4f, 1f, Color.green)
                                }
                                x++
                            }
                            y--
                            x = startX
                        }
                    }
                }

                val weSelection = worldEditSelection[data.uuid]
                // trackTick rather than Time.globalTime for the reason spelled out at the /track
                // gate below: globalTime is a float that stops resolving single ticks after a few
                // days up, and the same int then passes this test on consecutive frames.
                if (weSelection != null && trackTick % 15 == 0) {
                    if ((weSelection.selecting && weSelection.startX != -1 && weSelection.startY != -1) || weSelection.selectionComplete) {
                        val startX = weSelection.startX
                        val startY = weSelection.startY
                        // The pointer arrives from the client and NetServer only rejects NaN and
                        // infinity, so an out-of-range one used to set the loop bound below.
                        val endX = (if (weSelection.selecting) (data.player.mouseX() / 8f).toInt() else weSelection.endX)
                            .coerceIn(0, Vars.world.width() - 1)
                        val endY = (if (weSelection.selecting) (data.player.mouseY() / 8f).toInt() else weSelection.endY)
                            .coerceIn(0, Vars.world.height() - 1)

                        val minX = minOf(startX, endX)
                        val maxX = maxOf(startX, endX)
                        val minY = minOf(startY, endY)
                        val maxY = maxOf(startY, endY)

                        // Top & Bottom edges
                        for (x in outlineMarks(minX, maxX)) {
                            Call.effect(data.player.con(), Fx.fire, x * 8f + 4f, minY * 8f + 4f, 0f, Color.orange)
                            Call.effect(data.player.con(), Fx.fire, x * 8f + 4f, maxY * 8f + 4f, 0f, Color.orange)
                        }
                        // Left & Right edges
                        for (y in outlineMarks(minY, maxY)) {
                            Call.effect(data.player.con(), Fx.fire, minX * 8f + 4f, y * 8f + 4f, 0f, Color.orange)
                            Call.effect(data.player.con(), Fx.fire, maxX * 8f + 4f, y * 8f + 4f, 0f, Color.orange)
                        }
                        // Both progressions start at their min, so this is the one corner sampling
                        // can miss - and during a drag it is the one under the cursor.
                        Call.effect(data.player.con(), Fx.fire, maxX * 8f + 4f, maxY * 8f + 4f, 0f, Color.orange)
                    }
                }

                // One label per player, to every tracking player: the cost is the product of two player
                // counts. Fifteen ticks is the longest gate the label's own half second lifetime allows.
                // The counter is local rather than Time.globalTime, which the neighbouring gates use:
                // globalTime is a float that never resets, so after a few days of uptime it coarsens and
                // the gate would fire in bursts with seconds of silence between them.
                if (data.mouseTracking && trackTick % 15 == 0) {
                    Groups.player.forEach { player ->
                        Call.label(
                            data.player.con(),
                            player.name,
                            Time.delta / 2,
                            player.mouseX,
                            player.mouseY
                        )
                    }
                }

                // A player with no unit (dead on a team that has no core left to respawn from) keeps
                // unit() at null indefinitely, and tileOn() is nullable in its own right.
                // Last thing this loop does for a player, so both guards are a plain continue.
                val unitTile = data.player.unit()?.tileOn() ?: continue
                if (warpMapName == null) continue
                for (two in warpZones) {
                    if (two.mapName != warpMapName || two.click) continue
                    val start = two.startTile ?: continue
                    val finish = two.finishTile ?: continue
                    if (isUnitInside(unitTile, start, finish)) {
                        Log.info(Bundle()["log.warp.move", data.player.plainName(), two.ip, two.port.toString()])

                        val hubMapName = pluginData.hubMapName
                        scope.launch {
                            data.lastPlayedWorldName = Vars.state.map.plainName()
                            data.lastPlayedWorldMode = Vars.state.rules.modeName
                            data.lastLogoutDate = Clock.System.now().toLocalDateTime(systemTimezone)
                            data.isConnected = false
                            data.update()

                            if (hubMapName != null && warpMapName == hubMapName) {
                                val targetServerName = "${two.ip}:${two.port}"
                                val hubConnectionTime = Instant.fromEpochMilliseconds(data.player.con().connectTime).toLocalDateTime(systemTimezone)
                                grantRoutingPermission(data.player.uuid(), hubMapName, targetServerName, two.port, hubConnectionTime)
                            }
                            val transfer = CustomEvents.ServerTransfer(data.player, two.ip, two.port)
                            Events.fire(transfer)
                            if (!transfer.handled) {
                                Call.connect(data.player.con(), transfer.ip, transfer.port)
                            }
                        }
                        break
                    }
                }
            }

            if (dpsTile != null) {
                if (dpsTile!!.build != null && dpsTile!!.block() != null) {
                    dpsBlocks += (100000000f - dpsTile!!.build.health)
                    dpsTile!!.build.maxHealth(100000000f)
                    dpsTile!!.build.health(100000000f)
                } else {
                    dpsTile = null
                }
            }

            if (dpsBlockCalculateTick == 60) {
                if (dpsTile != null) {
                    if (maxDps == null) {
                        maxDps = 0f
                    } else if (dpsBlocks > maxDps!!) {
                        maxDps = dpsBlocks
                    }
                    for (data in players) {
                        Call.label(
                            data.player.con(),
                            data.bundle["command.dps", maxDps!!, dpsBlocks],
                            1f,
                            dpsTile!!.worldx(),
                            dpsTile!!.worldy()
                        )
                    }
                } else {
                    maxDps = null
                }
                dpsBlocks = 0f
                dpsBlockCalculateTick = 0
            } else {
                dpsBlockCalculateTick += 1
            }
        }

        Timer.schedule({
            if (unitLimitMessageCooldown > 0) unitLimitMessageCooldown--

            players.removeIf { it.player.con() == null || it.player.con().hasDisconnected }

            players.forEach {
                it.totalPlayed++
                it.currentPlayTime++

                if (it.animatedName) {
                    val name = it.name.replace(colorTag, "")
                    it.player.name(rainbow(name))
                } else if (conf.feature.name.restoreStored && !it.status.containsKey("router")) {
                    // Off by default: this used to overwrite the player's nickname every
                    // second with whatever the database remembered, and no amount of
                    // reconnecting or config reloading could win against it.
                    it.player.name(it.name)
                }

                // 잠수 플레이어 카운트
                if (isAfkCandidate(it)) {
                    it.afkTime++
                    if (it.afkTime == conf.feature.afk.time.toUShort()) {
                        it.afk = true
                        if (conf.feature.afk.enabled) {
                            val target = afkTarget(conf.feature.afk.server)
                            if (target == null) {
                                val kickedName = it.player.plainName()
                                players.forEach { data ->
                                    if (data.uuid != it.uuid) {
                                        data.send("event.player.afk.other", kickedName)
                                    }
                                }
                                it.player.kick(it.bundle["event.player.afk"])
                            } else {
                                val (host, port) = target

                                val currentMapName = Vars.state.map.name()
                                val hubMapName = pluginData.hubMapName
                                scope.launch {
                                    it.lastPlayedWorldName = Vars.state.map.plainName()
                                    it.lastPlayedWorldMode = Vars.state.rules.modeName
                                    it.lastLogoutDate = Clock.System.now().toLocalDateTime(systemTimezone)
                                    it.isConnected = false
                                    it.update()

                                    if (hubMapName != null && currentMapName == hubMapName) {
                                        val targetServerName = "$host:$port"
                                        val hubConnectionTime = Instant.fromEpochMilliseconds(it.player.con().connectTime).toLocalDateTime(systemTimezone)
                                        grantRoutingPermission(it.player.uuid(), hubMapName, targetServerName, port, hubConnectionTime)
                                        Log.debug("Granted routing permission for ${it.player.plainName()} to $targetServerName (AFK)")
                                    }
                                    val transfer = CustomEvents.ServerTransfer(it.player, host, port)
                                    Events.fire(transfer)
                                    if (!transfer.handled) {
                                        Call.connect(it.player.con(), transfer.ip, transfer.port)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    it.afkTime = 0u
                    it.afk = false
                    it.mousePosition = activityMark(it)
                }

                val randomResult = (Random.nextInt(7) * it.expMultiplier)
                it.exp += randomResult.toInt()
                it.currentExp += randomResult.toInt()
                Commands.Exp[it]

                if (conf.feature.level.display) {
                    val message = "${it.exp}/${floor(Commands.Exp.calculateFullTargetXp(it.level)).toInt()}"
                    Call.infoPopup(it.player.con(), message, Time.delta, Align.left, 0, 0, 300, 0)
                }
            }
        }, 0f, 1f)

        Timer.schedule({
            if (Vars.state.rules.pvp) {
                players.forEach {
                    registerPvpPlayer(it)
                }
            }

            if (rollbackCount <= 0) {
                saveMapBackup()

                rollbackCount = conf.command.rollback.time
            } else {
                rollbackCount -= 60
            }

            if (conf.feature.motd.enabled) {
                messageCount += 60
                if (messageCount >= conf.feature.motd.time) {
                    // One read per language present, not one per player: a full server used to do
                    // dozens of exists()+readString() calls on the game thread for the same file.
                    // Scoped to this pass, so an edited file is still picked up next time round.
                    // Empty string stands for "no file for this language": getOrPut treats a null
                    // value as absent and would read the disk again for every player without one.
                    val perLocale = HashMap<String, String>()
                    players.forEach {
                        val message = perLocale.getOrPut(it.player.locale()) {
                            if (rootPath.child("messages/${it.player.locale()}.txt").exists()) {
                                rootPath.child("messages/${it.player.locale()}.txt").readString()
                            } else if (rootPath.child("messages").list().isNotEmpty()) {
                                val file = rootPath.child("messages/en.txt")
                                if (file.exists()) file.readString() else ""
                            } else {
                                ""
                            }
                        }.ifEmpty { null }
                        if (message != null) {
                            val c = message.lines()

                            if (c.size <= messageOrder) {
                                messageOrder = 0
                            }
                            it.player.sendMessage(c[messageOrder])
                        }
                    }
                    messageOrder++
                    messageCount = 0
                }
            }
        }, 0f, 60f)

        Events.on(EventType.ServerLoadEvent::class.java) {
            if (conf.feature.level.effect.enabled) {
                ModuleRuntime.scheduleLevelEffects()
            }
            coreListeners.forEach {
                Core.app.addListener(it)
            }
        }
    }

    fun registerPvpPlayer(data: PlayerData) {
        if (!Vars.state.rules.pvp) return
        val player = data.player
        val connection = player.con() ?: return
        if (connection.hasDisconnected) return
        val team = player.team()
        if (data.uuid !in pvpPlayer && data.uuid !in pvpSpecters
            && team != Team.derelict && team.data().hasCore()
            && !(Vars.state.rules.waves && team == Vars.state.rules.waveTeam)
        ) {
            pvpPlayer[data.uuid] = team
        }
    }

    /**
     * Counts a loss the moment a registered player's team runs out of cores. No unit check: a player
     * whose unit died with the last core has none, and that is exactly the loss this has to see.
     * The player goes into pvpSpecters either way, which is what keeps gameOver from counting the
     * same loss a second time.
     */
    fun recordPvpDefeat(data: PlayerData) {
        if (!Vars.state.rules.pvp || data.uuid in pvpSpecters) return
        val player = data.player
        val connection = player.con() ?: return
        if (connection.hasDisconnected) return
        val team = player.team()
        if (team == Team.derelict || pvpPlayer[data.uuid] != team || team.data().hasCore()) return

        pvpPlayer.remove(data.uuid)
        pvpSpecters.add(data.uuid)
        data.pvpLoseCount++
        if (conf.feature.pvp.spector) {
            player.changeTeam(Team.derelict)
        }

        val score = data.currentPlayTime + 5000
        data.exp += (score * data.expMultiplier).toInt()
        data.send("event.exp.earn.defeat", data.currentExp + score)
    }
}
