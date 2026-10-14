package essential.common.permission

import arc.Core
import arc.files.Fi
import arc.util.Log
import arc.util.Timer
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import essential.common.PermissionTuning
import essential.common.bundle.Bundle
import essential.common.command.CommandRegistry
import essential.common.database.data.PlayerData
import essential.common.database.table.PlayerTable
import essential.common.players
import essential.common.util.findPlayerData
import essential.common.rootPath
import essential.core.Main.Companion.scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import mindustry.Vars
import mindustry.gen.Groups
import mindustry.gen.Playerc
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.*
import java.util.concurrent.CompletableFuture

object Permission {
    // /reload runs load() on Dispatchers.IO while the game thread reads these on every command and
    // every build action, so each one is published rather than left to chance. Main.conf is @Volatile
    // for the same reason. Publication only: the read-modify-writes in setGroup, removeUserEntry and
    // writeUser are still unguarded, and the annotation does not make a third one safe.
    @Volatile private var main: Map<String, RoleConfig> = mapOf()
    @Volatile private var user: Map<String, PermissionData>? = mapOf()
    @Volatile private var userRaw: Map<String, YamlNode> = mapOf()
    @Volatile private var userFileValid = true
    @Volatile private var userFileError: String? = null
    // permission.yaml owns fileDefault and the permission_user.yaml decode reads it through
    // PermissionData.group; the account service owns authDefault and answers for a player whose data
    // could not be loaded. One field carried both, so every reload answered the second question with
    // the first answer: load() runs again on reload and the service inits only once, at boot.
    @Volatile private var fileDefault = "user"
    @Volatile private var authDefault: String? = null
    /**
     * The group of a session nothing better is known for: temporary data, a held-back device. Auth off
     * asks for visitor, and the fleet's permission.yaml has no such group - check() denies every node to
     * a group it does not define, so those sessions could not even /rtv. A missing group is the file's.
     */
    val default: String get() = (authDefault ?: fileDefault).takeIf { it in main } ?: fileDefault
    // permission_user.yaml's modification time at the last load; another server's write changes it.
    @Volatile private var userFileStamp = 0L
    private val mainFile: Fi = rootPath.child("permission.yaml")
    private val userFile: Fi = rootPath.child("permission_user.yaml")
    private val userBackupFile: Fi = rootPath.child("permission_user.yaml.bak")

    private val bundle = Bundle(Locale.getDefault().toLanguageTag())
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    private val userSerializer = MapSerializer(String.serializer(), PermissionData.serializer())
    private val rawSerializer = MapSerializer(String.serializer(), YamlNode.serializer())

    private val comment = """
        #${bundle["permission.wiki"]}
        #${bundle["permission.sort"]}
        #${bundle["permission.notice"]}
        #${bundle["permission.usage"]}
        # name:${bundle["permission.usage.name"]}
        # group:${bundle["permission.usage.group"]}
        # admin:${bundle["permission.usage.admin"]}
        # isAlert:${bundle["permission.usage.isAlert"]}
        # alertMessage:${bundle["permission.usage.alertMessage"]}
        # chatFormat:${bundle["permission.usage.chatFormat"]}
        
        #${bundle["permission.example"]}
        # uuid123:
        #     name: my fun name
        # uuids:
        #     name: "asdfg"
        #     group: "admin"
        #     admin: true
        #     isAlert: true
        #     alertMessage: "Player asdfg has entered the server!"
        #     chatFormat: "[admin] %player.name[orange] >[white] %chat"
        ---
        """.trimIndent()

    init {
        if (!mainFile.exists()) {
            this::class.java.getResourceAsStream("/permission_default.yaml")?.use { input ->
                mainFile.write(input, false)
            }
        }

        if (!userFile.exists()) {
            userFile.writeString(comment)
        }
    }

    fun load() {
        // permission.yaml first: PermissionData.group falls back to the file default at the moment
        // kotlinx.serialization builds each entry, so the user file can only be decoded once the role
        // marked `default: true` has been read out of permission.yaml.
        //
        // Both are built as locals and published at the end. `main` is @Volatile and the inheritance
        // walk below adds to the RoleConfig lists inside it, so assigning it before the walk would
        // reliably hand the game thread a role whose inherited nodes are not in it yet - the reload
        // window is exactly when commands are flying. A parse failure keeps the map that was already
        // loaded, as it always did, and on that path the walk really does run over the live map.
        var nextDefault = "user"
        val parsed = try {
            if (mainFile.exists()) {
                yaml.decodeFromString(MapSerializer(String.serializer(), RoleConfig.serializer()), mainFile.readString())
            } else {
                mapOf()
            }
        } catch (e: Exception) {
            Log.warn("Failed to parse permission.yaml: ${e.message}")
            null
        }
        // On the failure path this is the live, already-published map, and the walk below must stay
        // idempotent for that to be safe: every mutation in it is guarded by
        // `!roleConfig.permission.contains(permission)`, so re-walking an expanded map writes nothing
        // at all. An unguarded mutation added there would start editing a map the game thread is
        // reading, from Dispatchers.IO.
        val roles = parsed ?: main

        for ((name, roleConfig) in roles) {
            if (nextDefault == "user" && roleConfig.default == true) {
                nextDefault = name
            }

            var inheritance: String? = roleConfig.inheritance
            val walked = mutableSetOf(name)
            while (true) {
                val next = inheritance ?: break
                if (!walked.add(next)) {
                    Log.warn("[Permission] role '$name' inherits in a circle through '$next'. The chain is cut there; fix the 'inheritance:' lines in permission.yaml.")
                    break
                }
                val inheritedRole = roles[next] ?: break
                for (permission in inheritedRole.permission) {
                    // equals, not contains: a substring test also excludes killall, kickall and any
                    // later node with those three letters in it, and does it silently.
                    if (!permission.equals("all", true) && !roleConfig.permission.contains(permission)) {
                        roleConfig.permission.add(permission)
                    }
                }
                inheritance = inheritedRole.inheritance
            }
        }

        main = roles
        fileDefault = nextDefault
        val previousEntries = user?.keys.orEmpty()

        userFileStamp = userFile.lastModified()
        readUserFile()
        apply(previousEntries)
    }

    /** Read permission_user.yaml into [user] and [userRaw]; false, with the reason kept, when it does not parse. */
    private fun readUserFile(): Boolean {
        try {
            if (userFile.exists()) {
                val raw = userFile.readString()
                // Remove YAML comments and whitespace to check if there's any real content
                val stripped = raw.lineSequence()
                    .filter { line -> !line.trimStart().startsWith("#") }
                    .joinToString("\n")
                    .trim()
                if (stripped.isEmpty() || stripped == "---") {
                    // Treat comment-only or effectively empty files as empty map
                    user = mapOf()
                    userRaw = mapOf()
                } else {
                    user = yaml.decodeFromString(userSerializer, raw)
                    userRaw = yaml.decodeFromString(rawSerializer, raw)
                }
            } else {
                user = mapOf()
                userRaw = mapOf()
            }
            userFileValid = true
            userFileError = null
            return true
        } catch (e: Exception) {
            userFileValid = false
            userFileError = if (e is YamlException) "line ${e.line}: ${e.message}" else e.message.orEmpty()
            Log.warn(bundle["permission.user.file.invalid", userFileError!!])
            return false
        }
    }

    /** [previous] are the uuids the file named before this load; an entry dropped since is a demotion too. */
    fun apply(previous: Set<String> = emptySet()) {
        // Writes Mindustry player entities - admin() and name() - so it is only correct on the game
        // thread, and /reload calls Permission.load() on Dispatchers.IO. The hop is here rather than at
        // that caller so a later caller cannot get it wrong, and `user` is read inside the work rather
        // than captured, so a setperm landing while the work is queued is not reverted by a stale copy.
        val work = Runnable {
            val loaded = user ?: return@Runnable
            val online = players.associateBy { it.uuid }
            val offline = LinkedHashMap<String, PermissionData>()
            for ((uuid, permissionData) in loaded) {
                val player = online[uuid]
                if (player == null) {
                    offline[uuid] = permissionData
                } else {
                    // A file edit is an explicit group change: a promotion binds this session only from a
                    // trusted IP, like a join, and a demotion revokes.
                    player.permission = permissionData.group
                    recognise(player)
                    applyGroup(uuid, permissionData.group, player)
                    if (permissionData.name.isNotEmpty() && !heldBack(uuid)) {
                        player.name = permissionData.name
                        player.player.name(permissionData.name)
                    }
                }
            }
            // The entry's group was already written into the row, so a dropped entry falls back to it.
            for (uuid in previous - loaded.keys) {
                // Temporary data carries the stand-in default group, not the row's: demoting on it would
                // drop a real admin's binding during a database stall.
                val data = online[uuid]?.takeUnless { it.temporary } ?: continue
                applyGroup(uuid, data.permission, data)
            }
            if (offline.isNotEmpty()) applyOffline(offline)
        }
        if (Core.app.isOnMainThread) work.run() else Core.app.post(work)
    }

    /**
     * Write the file's groups onto the rows of the players it names that are not online.
     *
     * Bounded rather than unbounded: every boot calls [load], six servers share one database, and
     * this used to open one coroutine and one transaction per entry, so a permission_user.yaml with a
     * few thousand entries queued a few thousand connection acquisitions against a pool of five.
     * Most of them timed out and threw, which is how entries went missing silently.
     *
     * Still one transaction per entry, deliberately. One transaction for the whole file would make a
     * single bad row roll back every other entry with it, and would hold each row lock for as long as
     * the whole file takes.
     *
     * Exposed reports how many rows each update changed. Zero means the database has no row for that
     * uuid yet, so nothing was persisted for them - the entry still applies the moment they join,
     * because [get] answers from the file rather than from the row.
     */
    private fun applyOffline(entries: Map<String, PermissionData>) {
        scope.launch {
            val gate = Semaphore(PermissionTuning.OFFLINE_WRITE_LIMIT)
            val unpersisted = entries.map { (uuid, permissionData) ->
                async {
                    gate.withPermit {
                        try {
                            val changed = suspendTransaction {
                                PlayerTable.update({ PlayerTable.uuid eq uuid }) {
                                    it[PlayerTable.permission] = permissionData.group
                                    // An offline revoke, in the same write as the group: [revoke] needs the game thread.
                                    if (!privileged(uuid, permissionData.group)) it[PlayerTable.adminTrust] = null
                                    if (permissionData.name.isNotEmpty()) {
                                        it[PlayerTable.name] = permissionData.name
                                    }
                                }
                            }
                            uuid.takeIf { changed == 0 }
                        } catch (e: CancellationException) {
                            // The scope is cancelled on plugin dispose. Catching this alongside the
                            // rest would log four invented write failures on every shutdown.
                            throw e
                        } catch (e: Exception) {
                            Log.err("[Permission] permission_user.yaml entry for $uuid could not be written", e)
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()

            if (unpersisted.isNotEmpty()) {
                Log.info(
                    "[Permission] permission_user.yaml names ${unpersisted.size} uuid with no player row yet, " +
                        "so their group is not stored in the database; it applies when they join: " +
                        unpersisted.take(PermissionTuning.UNPERSISTED_LOG_SAMPLE).joinToString(", ")
                )
            }
        }
    }

    operator fun get(data: PlayerData): PermissionData {
        val result = PermissionData()

        // An unrecognised device acts as the default group: its chat prefix, no join alert, no file entry.
        val held = heldBack(data)
        val u = user?.get(data.uuid)?.takeUnless { held }
        if (u != null) {
            result.name = u.name.ifEmpty { data.player.name() }
            result.group = u.group
            result.admin = isAdmin(data.uuid, u.group)
            result.isAlert = u.isAlert
            result.alertMessage = u.alertMessage
            result.chatFormat = u.chatFormat
        } else {
            result.name = data.player.name()
            result.group = effectiveGroup(data)
            result.admin = isAdmin(data.uuid, data.permission)
            result.isAlert = false
            result.alertMessage = ""
            result.chatFormat = ""
        }

        val group = main[result.group]
        if (group != null && result.chatFormat.isEmpty()) {
            result.chatFormat = group.chatFormat
        }

        return result
    }

    /**
     * Whether this player wears the admin flag: vanilla's own admin list counts here, and so does an
     * explicit `admin: true` in permission_user.yaml or on the resolved role.
     *
     * This decides a flag, not a permission. [check] decides permissions and deliberately does not
     * call this - see its own documentation for why the two cannot be merged.
     */
    fun isAdmin(uuid: String, fallbackGroup: String): Boolean {
        val entry = user?.get(uuid)
        // A file entry's admin: true names a uuid only; heldBack below is what ties it to a device.
        return (entry?.admin == true || main[entry?.group ?: fallbackGroup]?.admin == true || isVanillaAdmin(uuid)) &&
            !heldBack(uuid, group = fallbackGroup)
    }

    fun isAdminGroup(group: String): Boolean = main[group]?.admin == true

    /** Vanilla admin on this server, and - while online - on the device the admin record is bound to. */
    fun isVanillaAdmin(uuid: String): Boolean =
        Vars.netServer?.admins?.getInfoOptional(uuid)?.admin == true && !heldBack(uuid)

    /**
     * Whether [uuid] is connected with a device ([usid]) this server has not recognised for their
     * privileges. A uuid is only an identifier the client sends, and the Discord bot shows them in full,
     * so a modified client can present an owner's uuid; the usid is per server and is not shown anywhere.
     * While this answers true the session plays as an ordinary player: the [default] group, no admin
     * flag, no file name. The shared group column is left alone, so the real admin is unaffected.
     *
     * Recognised means bound to this device here - by a confirm, or by [recognise] at a join from a
     * fleet-wide trusted IP. A privileged uuid with no device here yet is held back too: there is no
     * trust on first use, because the first device to reach an unvisited server is not evidence of
     * anything. Null [usid] (not online) is never held back.
     *
     * This is the one rule: [check], [isAdmin], [effectiveGroup], the chat format, the file name and the
     * join all ask it, and nothing else decides whether a session acts with its group. [grant] is the
     * only way out of it, reached by a confirm or by [recognise].
     *
     * [group] is the row's group when the caller has it; left out, the online player's data answers
     * (a permission_user.yaml entry wins over either). The defaults read Groups.player, so only the game
     * thread may leave them out.
     */
    fun heldBack(uuid: String, usid: String? = liveUsid(uuid), group: String? = null): Boolean {
        if (usid.isNullOrEmpty()) return false
        val bound = boundDevice(uuid)
        if (bound != null) return bound != usid
        return privileged(uuid, group ?: findPlayerData(uuid)?.permission ?: "")
    }

    /** [heldBack] for [data]'s own session and row. */
    fun heldBack(data: PlayerData): Boolean = heldBack(data.uuid, sessionUsid(data.player), data.permission)

    private fun liveUsid(uuid: String): String? = Groups.player.find { it.uuid() == uuid }?.let(::sessionUsid)

    /** Whether [uuid], whose row says [group], has anything to hold back: a ranked group or a file admin flag. */
    fun privileged(uuid: String, group: String): Boolean = rank(groupOf(uuid, group)) > 0 || user?.get(uuid)?.admin == true

    private fun deviceKey(uuid: String) = "essential-device-$uuid"

    /**
     * The device (usid) this server bound [uuid]'s privileges to, or null when it has none yet. This
     * server's own record first; else a vanilla admin binding, which vanilla never rewrites while the
     * admin flag is set. A non-admin's PlayerInfo.adminUsid is not a binding: vanilla overwrites it on
     * every join with whoever joined last. Neither is fleet-wide trust: that is [AdminTrust], by IP.
     */
    fun boundDevice(uuid: String): String? =
        Core.settings?.getString(deviceKey(uuid), null)?.takeIf { it.isNotEmpty() }
            ?: Vars.netServer?.admins?.getInfoOptional(uuid)?.takeIf { it.admin }?.adminUsid?.takeIf { it.isNotEmpty() }

    private fun bindLocal(uuid: String, usid: String) {
        val settings = Core.settings ?: return
        settings.put(deviceKey(uuid), usid)
        settings.forceSave()
    }

    private fun unbindLocal(uuid: String) {
        val settings = Core.settings ?: return
        if (settings.has(deviceKey(uuid))) {
            settings.remove(deviceKey(uuid))
            settings.forceSave()
        }
    }

    /**
     * Discord's adminAdd for a player online here; ServerAdminer reaches it by name. [grant]s the
     * session, so the player is admin on every server they join from [ip]. Completes with whether the
     * row took the trust - ServerAdminer replies once it has - and never blocks the game thread.
     */
    fun bindDevice(uuid: String, usid: String, ip: String): CompletableFuture<Boolean> =
        grant(uuid, usid, ip, "discord", findPlayerData(uuid))

    /**
     * The one grant. A person vouched for this live session (setperm on an online player, Discord's
     * adminAdd), or the row already trusts the IP it comes from ([recognise]): bind [usid] on this
     * server, stamp [ip] into [data]'s fleet-wide trust, and bring the vanilla flag, the admin flag and
     * the file's display name in line. Completes with whether the row took the trust: false for [data]
     * null or temporary, which binds this server only, and for a plain group, which gets nothing.
     */
    fun grant(uuid: String, usid: String, ip: String, by: String, data: PlayerData?): CompletableFuture<Boolean> {
        val group = groupOf(uuid, data?.permission ?: default)
        if (!privileged(uuid, group)) {
            syncVanillaAdmin(uuid, group, data = data)
            return CompletableFuture.completedFuture(false)
        }
        bindLocal(uuid, usid)
        val stored = if (data != null && !data.temporary) {
            storeTrust(uuid, data, AdminTrust.with(data.adminTrust, ip, by, System.currentTimeMillis()))
        } else {
            CompletableFuture.completedFuture(false)
        }
        syncVanillaAdmin(uuid, group, data = data)
        data?.let { d ->
            d.player.admin(isAdmin(uuid, d.permission))
            // The file's display name was withheld from this session while it was held back.
            assignedName(uuid)?.let { d.name = it; d.player.name(it) }
        }
        // The usid itself stays out of the log: it is the one thing an impostor with this uuid lacks.
        Log.info("[Permission] $uuid bound here from $ip (trusted by $by); privileged on every server they join from that IP")
        return stored
    }

    /**
     * The one revoke: [uuid] loses this server's binding and vanilla admin flag, and their fleet-wide
     * trust - on [data] when it is in memory here, and on the row either way.
     */
    fun revoke(uuid: String, data: PlayerData?) {
        unbindLocal(uuid)
        val admins = Vars.netServer?.admins
        if (admins?.getInfoOptional(uuid)?.admin == true) admins.unAdminPlayer(uuid)
        storeTrust(uuid, data, null)
    }

    /**
     * The only writer of admin_trust: [trust] on [data] and on the snapshot of the row this server last
     * saw, and that one column of [uuid]'s row. The ordinary row save diffs against the snapshot, so it
     * never carries trust - and a save from a copy loaded before another server's demotion cannot write
     * the cleared trust back. Completes with whether a row took it; temporary [data] writes nothing.
     */
    private fun storeTrust(uuid: String, data: PlayerData?, trust: String?): CompletableFuture<Boolean> {
        if (data != null) {
            // Every plain setperm revokes; a row already holding no trust needs no write for it.
            val stored = data.adminTrust == trust && data.dbSnapshot?.adminTrust == trust
            data.adminTrust = trust
            data.dbSnapshot?.adminTrust = trust
            if (data.temporary) return CompletableFuture.completedFuture(false)
            if (stored) return CompletableFuture.completedFuture(true)
        }
        return scope.future {
            suspendTransaction { PlayerTable.update({ PlayerTable.uuid eq uuid }) { it[PlayerTable.adminTrust] = trust } } > 0
        }
    }

    /**
     * The join rule for a privileged session; nothing for anybody else. A device bound here: on, and the
     * IP's trust, if it has one, is re-stamped so it does not expire. Otherwise an IP the row trusts is
     * [grant]ed. Otherwise the session is held back - [heldBack] answers true - and plays as an ordinary
     * player. A join never adds trust and never binds an untrusted device: no trust on first use.
     */
    fun recognise(data: PlayerData) {
        val uuid = data.uuid
        val usid = sessionUsid(data.player) ?: return
        if (data.temporary || !privileged(uuid, data.permission)) return
        val ip = data.player.ip()
        val now = System.currentTimeMillis()
        val trusted = AdminTrust.read(data.adminTrust, now).find { it.ip == ip }
        when {
            boundDevice(uuid) == usid -> if (trusted != null) storeTrust(uuid, data, AdminTrust.with(data.adminTrust, ip, trusted.by, now))
            trusted != null -> grant(uuid, usid, ip, trusted.by, data)
            else -> Log.warn("${data.player.plainName()} $uuid joined from an unrecognised device and IP ($ip); plays as an ordinary player until confirmed with Discord admin add or setperm while online")
        }
    }

    /**
     * Carry an existing vanilla admin binding into this server's own record, so unAdminPlayer cannot
     * lose it later. Never the connected session's device: that would be trust on first use.
     */
    private fun keepVanillaBinding(uuid: String) {
        val settings = Core.settings ?: return
        if (settings.getString(deviceKey(uuid), null) != null) return
        bindLocal(uuid, boundDevice(uuid) ?: return)
    }

    /** The group [data] acts with on this server: their resolved group, or the default one while [heldBack]. */
    fun effectiveGroup(data: PlayerData): String {
        val group = groupOf(data.uuid, data.permission)
        return if (rank(group) > 0 && heldBack(data)) default else group
    }

    /**
     * The usid of a live connection, or null. A placeholder or detached Player (offline PlayerData)
     * answers usid() with "[LOCAL]" or the old session's, which would read as an unrecognised device.
     */
    fun sessionUsid(player: Playerc): String? =
        player.con()?.takeIf { it.isConnected && !it.hasDisconnected }?.usid

    /**
     * Rank of a group, the only ordering permission.yaml allows: 3 for a group holding `all`, 2 for an
     * admin group, 1 for any other group holding a node the plain player groups lack (a moderator), 0
     * for the rest. Inheritance is not an order - the default owner inherits nothing. Every rank above 0 is
     * held back on an unrecognised device.
     */
    fun rank(group: String): Int {
        val role = main[group] ?: return 0
        return when {
            role.permission.contains("all") -> 3
            role.admin == true -> 2
            group !in plainGroups() && role.permission.any { it !in plainNodes() } -> 1
            else -> 0
        }
    }

    /**
     * The groups every player is in: the default one, permission.yaml's `default: true` group (with
     * auth on the default is "user" and that one is visitor), and "user", which new rows are created
     * with (CoreEvent, protect) whatever the default is. With auth off the default is visitor, so comparing
     * against it alone would make every ordinary player privileged.
     */
    private fun plainGroups(): Set<String> = setOf(default, fileDefault, "user")

    private fun plainNodes(): Set<String> = plainGroups().flatMapTo(HashSet()) { main[it]?.permission.orEmpty() }

    /** Whether [sender] ranks strictly above [target], each by the group they act with right now. */
    fun outranks(sender: PlayerData, target: PlayerData): Boolean =
        rank(effectiveGroup(sender)) > rank(effectiveGroup(target))

    /**
     * Bring this server's device binding and vanilla admin flag in line with [group]. [demote] is true
     * for an explicit group change (setperm, adminRemove, undo, a file edit, a row changed elsewhere): a
     * plain group then [revoke]s, which also ends [data]'s fleet-wide trust. A join passes false - its
     * group can be a stand-in (temporary data during a database stall) - and then only clears the
     * vanilla flag for a session on the bound device, as a revoke made elsewhere.
     */
    fun syncVanillaAdmin(uuid: String, group: String, demote: Boolean = true, data: PlayerData? = findPlayerData(uuid)) {
        val admins = Vars.netServer?.admins ?: return
        // A group permission.yaml does not define (parse failure, rename) is not a demotion to anything.
        if (!main.containsKey(group)) return
        val privileged = privileged(uuid, group)
        if (!privileged && demote) return revoke(uuid, data)
        if (privileged) keepVanillaBinding(uuid)
        if (!isAdminGroup(group)) {
            // Guarded: unAdminPlayer saves every PlayerInfo, and this runs on every join.
            if (admins.getInfoOptional(uuid)?.admin == true && (demote || !heldBack(uuid, group = group))) admins.unAdminPlayer(uuid)
            // A plain session has nothing to hold back, so a binding left by a demotion made on another
            // server goes on join too - unless a vanilla flag still needs it (a held-back console admin).
            if (!privileged && admins.getInfoOptional(uuid)?.admin != true) unbindLocal(uuid)
            return
        }
        // Only ever the bound device: a stale adminUsid or an offline promote binds nothing here.
        val bound = boundDevice(uuid)
        if (bound == null) {
            Log.info(bundle["permission.vanilla.admin.deferred", uuid])
            return
        }
        val info = admins.getInfoOptional(uuid)
        if (info == null || !info.admin || info.adminUsid != bound) admins.adminPlayer(uuid, bound)
    }

    /**
     * The name permission_user.yaml assigns to this player, or null when it assigns none - or when the
     * session is on an unrecognised device, which must not wear the bound player's name.
     */
    fun assignedName(uuid: String): String? = user?.get(uuid)?.name?.takeIf { it.isNotEmpty() && !heldBack(uuid) }

    fun groupOf(uuid: String, fallbackGroup: String): String = user?.get(uuid)?.group ?: fallbackGroup

    fun hasUserEntry(uuid: String): Boolean = user?.containsKey(uuid) == true

    val groups: Set<String> get() = main.keys

    fun hasGroup(group: String): Boolean = main.containsKey(group)

    fun userFileProblem(): String? = if (userFileValid) null else userFileError.orEmpty()

    /**
     * Record the default group the account service derives from its configured auth type, or null when
     * no such service is running. Held apart from the permission.yaml default because [load] recomputes
     * that one on every reload while the service that answers this one inits only at boot.
     */
    fun setAuthDefault(group: String?) {
        authDefault = group
    }

    /**
     * [group] for [uuid] here, and in their permission_user.yaml entry when the file has one. Never adds
     * an entry: the shared row is what all nine servers read, and an entry would mask it on every one of
     * them. False when the file does not parse. [data] and [confirmedBy] are [applyGroup]'s.
     */
    fun setGroup(uuid: String, group: String, data: PlayerData? = findPlayerData(uuid), confirmedBy: String? = null): Boolean {
        if (!writeUser { if (it.containsKey(uuid)) it[uuid] = patchGroup(it[uuid], group) }) return false
        applyGroup(uuid, group, data, confirmedBy)
        return true
    }

    fun removeUserEntry(uuid: String, fallbackGroup: String): Boolean {
        if (!writeUser { it.remove(uuid) }) return false
        applyGroup(uuid, fallbackGroup)
        return true
    }

    /**
     * The one place a group change lands on this server, for [data] online here or a row read for an
     * offline player; the caller saves the row. [confirmedBy] names who vouched for a live session in a
     * privileged group: it is then [grant]ed. Otherwise the binding and flags follow the group, and a
     * plain group [revoke]s.
     *
     * Without going near the file: an entry in permission_user.yaml wins over [group] everywhere a
     * permission is decided, so calling this for a uuid whose entry names another group leaves
     * PlayerData.permission saying one thing and every check answering with another. And not free of
     * side effects: for an unknown uuid Administration.unAdminPlayer creates and saves an empty
     * PlayerInfo row.
     */
    fun applyGroup(uuid: String, group: String, data: PlayerData? = findPlayerData(uuid), confirmedBy: String? = null) {
        data?.permission = group
        val usid = data?.player?.let(::sessionUsid)
        if (confirmedBy != null && data != null && usid != null && !data.temporary && privileged(uuid, group)) {
            grant(uuid, usid, data.player.ip(), confirmedBy, data)
            return
        }
        syncVanillaAdmin(uuid, groupOf(uuid, group), data = data)
        data?.player?.admin(isAdmin(uuid, group))
    }

    /**
     * For ServerAdminer's adminAdd and adminRemove, whether or not the player is online anywhere: [group]
     * on [uuid]'s shared row, in their permission_user.yaml entry when they have one, and on this server.
     * [keepAdminLevel] leaves an owner an owner. A plain group clears the fleet-wide trust. Game thread.
     *
     * The future completes with false when the database has no row for [uuid], and exceptionally when
     * the file does not parse or the write failed. It runs on Dispatchers.IO, never on the game thread.
     */
    fun storeGroup(uuid: String, group: String, keepAdminLevel: Boolean): CompletableFuture<Boolean> {
        if (!hasGroup(group)) return CompletableFuture.failedFuture(IllegalArgumentException("permission.yaml has no group $group"))
        val here = findPlayerData(uuid)?.takeUnless { it.temporary }
        // setGroup lands the change here, and a plain group's revoke clears the trust on the row too.
        if (!(keepAdminLevel && isAdminGroup(groupOf(uuid, here?.permission ?: ""))) && !setGroup(uuid, group)) {
            return CompletableFuture.failedFuture(IllegalStateException("permission_user.yaml does not parse: ${userFileProblem()}"))
        }
        Log.info("[Permission] $uuid set to $group on the shared row (Discord)")
        return scope.future {
            suspendTransaction {
                val stored = PlayerTable.select(PlayerTable.permission).where { PlayerTable.uuid eq uuid }
                    .firstOrNull()?.get(PlayerTable.permission) ?: return@suspendTransaction false
                if (!(keepAdminLevel && isAdminGroup(stored))) {
                    PlayerTable.update({ PlayerTable.uuid eq uuid }) { it[PlayerTable.permission] = group }
                }
                true
            }
        }
    }

    /** Runs [refresh] every [PermissionTuning.REFRESH_SECONDS]; once, at server load. */
    fun startRefresh() {
        Timer.schedule({ Core.app.post { refresh() } }, PermissionTuning.REFRESH_SECONDS, PermissionTuning.REFRESH_SECONDS)
    }

    /**
     * Bring the players online here in line with what the other servers changed. Their group and trust
     * live on the shared row, which this server read once at the join, so a demotion made elsewhere
     * stayed unseen here until the player left. And permission_user.yaml is one file symlinked into
     * every server, whose edits no file watcher here sees. Game thread; the read runs on IO.
     */
    fun refresh() {
        if (userFile.lastModified() != userFileStamp) scope.launch { load() }
        val online = players.filter { !it.temporary }
        if (online.isEmpty()) return
        val uuids = online.map { it.uuid }
        // A save landing while the read is out replaces the snapshot; the read is older than it then.
        // By position, not in a map: PlayerData is a data class, so its hash moves with every field.
        val seen = online.map { it.dbSnapshot }
        scope.launch {
            val rows = try {
                suspendTransaction {
                    PlayerTable.select(PlayerTable.uuid, PlayerTable.permission, PlayerTable.adminTrust)
                        .where { PlayerTable.uuid inList uuids }
                        .map { it[PlayerTable.uuid] to (it[PlayerTable.permission] to it[PlayerTable.adminTrust]) }
                        .toList().toMap()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.warn("[Permission] could not re-read the online players' groups: ${e.message}")
                return@launch
            }
            Core.app.post {
                online.forEachIndexed { i, data ->
                    val (group, trust) = rows[data.uuid] ?: return@forEachIndexed
                    if (data.dbSnapshot === seen[i]) adopt(data, group, trust)
                }
            }
        }
    }

    /** What [refresh] read for [data], applied where it differs from the row as this server last saw it. */
    internal fun adopt(data: PlayerData, group: String, trust: String?) {
        val base = data.dbSnapshot ?: return
        if (players.none { it === data }) return
        if (base.adminTrust != trust) {
            base.adminTrust = trust
            data.adminTrust = trust
        }
        if (base.permission == group) return
        base.permission = group
        // Changed elsewhere, and so as explicit as setperm here: a promotion binds only from a trusted
        // IP, like a join, and a demotion revokes.
        data.permission = group
        recognise(data)
        applyGroup(data.uuid, group, data)
        Log.info("[Permission] ${data.name} ${data.uuid} now has group $group on the shared row")
    }

    private fun writeUser(edit: (MutableMap<String, YamlNode>) -> Unit): Boolean {
        // One file symlinked into every server: the copy loaded here can be behind another server's write,
        // and writing it back would undo that write. Read it again; a file that no longer parses refuses.
        if (!readUserFile()) return false
        val current = userRaw

        val next = current.toMutableMap()
        edit(next)

        // An edit that changes nothing still cost the operator their backup and every comment and blank
        // line in the file, because the write re-serialises from the parsed form. Node equality carries
        // the YamlPath, and patchGroup builds its nodes at the root, so the comparison has to be
        // equivalentContentTo rather than ==.
        if (next.keys == current.keys && next.all { (uuid, node) -> current.getValue(uuid).equivalentContentTo(node) }) {
            return true
        }

        val encoded = yaml.encodeToString(rawSerializer, next)
        if (userFile.exists()) userFile.copyTo(userBackupFile)
        userFile.writeString(comment + "\n" + encoded, false)
        userRaw = next
        user = yaml.decodeFromString(userSerializer, encoded)
        return true
    }

    private fun patchGroup(node: YamlNode?, group: String): YamlMap {
        val entries = LinkedHashMap<YamlScalar, YamlNode>()
        var replaced = false
        (node as? YamlMap)?.entries?.forEach { (key, value) ->
            if (key.content == "group") {
                entries[key] = YamlScalar(group, YamlPath.root)
                replaced = true
            } else {
                entries[key] = value
            }
        }
        if (!replaced) entries[YamlScalar("group", YamlPath.root)] = YamlScalar(group, YamlPath.root)
        return YamlMap(entries, YamlPath.root)
    }

    /**
     * Report permission nodes that nothing will ever look at.
     *
     * In this fork the `e` prefix belongs to the command name, not to the permission:
     * `/evote` is answered by the node `vote`. A permission file carried over from the
     * old fork therefore grants nothing at all, and does it silently. Only nodes that
     * become known once the prefix is dropped are reported, so a node belonging to a
     * disabled module stays quiet.
     *
     * Deliberately narrow, and this is the part to read before widening it. It does not
     * report a granted node that is simply wrong rather than prefixed, and it does not
     * look the other way at all - at a node the code asks for that no group holds, which
     * is the direction that silently disables a feature for everyone but the owner. Both
     * are covered by PermissionNodeInventoryTest, at build time, where a config that
     * cannot work stops the jar instead of printing a line on six live servers.
     *
     * A boot-time version needs [known] built *after* client-command registration.
     * ServerLoadEvent fires before it: ServerLauncher.init adds NetServer as a listener
     * and fires the event before returning, and HeadlessApplication.mainLoop calls each
     * listener's init() in one pass afterwards, so NetServer.init - which is what runs
     * mods.eachClass(Mod::registerClientCommands) - has not happened yet. Handed the set
     * that exists at that moment, an unreachable-node warning would name almost every
     * command this plugin has, on every start.
     */
    fun validate(known: Set<String>) {
        for (node in main.values.flatMap { it.permission }.toSet()) {
            if (node == "all" || node in known) continue
            val stripped = node.removePrefix(CommandRegistry.PREFIX)
            if (stripped != node && stripped in known) {
                Log.warn("[Permission] '$node' is not a permission node, nobody gets anything from it. Use '$stripped': the '${CommandRegistry.PREFIX}' prefix is part of the command name, not of the permission.")
            }
        }
    }

    /**
     * Commands every group may run, whatever permission.yaml says.
     *
     * permission.yaml is written once, on the boot that finds it missing, and never gains a node
     * afterwards - so a command added in a later build is denied to everybody on every server that
     * has been running for a while. For most commands that is the safe direction. `/lang` only
     * changes which language the server answers its caller in, and a player who cannot read the
     * server has no way to ask an operator for the node.
     */
    private val ALWAYS_ALLOWED = setOf("lang")

    /**
     * Whether the group [data] resolves to holds the node [command], or the wildcard `all`. A group
     * name no role in permission.yaml defines answers false.
     *
     * The rule, written down once because two mechanisms in this file both use the word admin:
     * a permission is decided by the group the player resolves to - permission_user.yaml when it
     * names them, otherwise their PlayerData.permission row - by the nodes permission.yaml gives
     * that group, and by nothing else. [isAdmin] is not consulted here.
     *
     * That is deliberate. A vanilla admin flag grants no plugin permissions at all: the join no longer
     * imports a console `admin add` into a group, because that flag cannot be told apart from one this
     * plugin set before a fleet-wide demotion. Admin is granted by setperm or Discord's adminAdd.
     *
     * The unbounded version is what a short-circuit on [isAdmin] here would be, and it is much wider
     * than it looks: the test would pass for every string, so it grants not what the admin group
     * holds but every node that exists, including the ones no group holds at all - `js`, `setperm`,
     * `unban`, `ws`. It is `all` by another name, reachable from the bare console `admin add`.
     * ClientCommandTest pins that making somebody a vanilla admin mid-session does not retroactively
     * hand them `/js`.
     *
     * So `admin add` grants nothing, and the next join on the bound device clears the flag again:
     * [syncVanillaAdmin] calls unAdminPlayer whenever the resolved group is not an admin group.
     */
    fun check(data: PlayerData, command: String): Boolean {
        if (command in ALWAYS_ALLOWED) return true
        // The debug line is interpolated before Log.debug can drop it, so it is only built at debug level.
        val groupName = effectiveGroup(data)
        val group = main[groupName]
        val passed = group != null && (group.permission.contains(command) || group.permission.contains("all"))
        if (Log.level == Log.LogLevel.debug) {
            Log.debug("[Permission] ${data.name} > group: $groupName -> command: $command -> $passed")
        }
        return passed
    }

    @Serializable
    data class PermissionData(
        var name: String = "",
        var group: String = fileDefault,
        var admin: Boolean = false,
        var isAlert: Boolean = false,
        var alertMessage: String = "",
        var chatFormat: String = "",
    )

    @Serializable
    data class RoleConfig(
        val admin: Boolean? = null,
        val inheritance: String? = null,
        val permission: MutableList<String> = mutableListOf(),
        val default: Boolean? = null,
        val chatFormat: String = "",
    )
}