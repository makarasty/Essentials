package essential.common.permission

import arc.Core
import arc.files.Fi
import arc.util.Log
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
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.*

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
    val default: String get() = authDefault ?: fileDefault
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
        } catch (e: Exception) {
            userFileValid = false
            userFileError = if (e is YamlException) "line ${e.line}: ${e.message}" else e.message.orEmpty()
            Log.warn(bundle["permission.user.file.invalid", userFileError!!])
        }

        apply()
    }

    fun apply() {
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
                    player.permission = permissionData.group
                    if (rank(permissionData.group) > 0 || permissionData.admin) bindSession(uuid)
                    player.player.admin(isAdmin(uuid, permissionData.group))
                    if (permissionData.name.isNotEmpty() && !heldBack(uuid)) {
                        player.name = permissionData.name
                        player.player.name(permissionData.name)
                    }
                }
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
        val held = heldBack(data.uuid, sessionUsid(data.player))
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
        // A file entry's admin: true names a uuid only, so it also needs this server's device binding.
        val entryAdmin = entry?.admin == true && boundDevice(uuid) != null
        return (entryAdmin || main[entry?.group ?: fallbackGroup]?.admin == true || isVanillaAdmin(uuid)) &&
            !heldBack(uuid)
    }

    fun isAdminGroup(group: String): Boolean = main[group]?.admin == true

    /** Vanilla admin on this server, and - while online - on the device the admin record is bound to. */
    fun isVanillaAdmin(uuid: String): Boolean =
        Vars.netServer?.admins?.getInfoOptional(uuid)?.admin == true && !heldBack(uuid)

    /**
     * Whether [uuid] is connected with a device ([usid]) other than the one this server bound their
     * admin record to. A uuid is only an identifier the client sends, and the Discord bot shows them in
     * full, so a modified client can present an owner's uuid; the usid is per server and is not shown
     * anywhere. While this answers true the session runs with the [default] group and no admin flag.
     * The shared group column is left alone, so the real admin on the bound device is unaffected, and
     * adminAdd from Discord rebinds a new device. Null [usid] (not online) is never held back.
     *
     * The default reads Groups.player, so only the game thread may leave it out.
     */
    fun heldBack(uuid: String, usid: String? = Groups.player.find { it.uuid() == uuid }?.let(::sessionUsid)): Boolean {
        if (usid.isNullOrEmpty()) return false
        val bound = boundDevice(uuid) ?: return false
        return bound != usid
    }

    private fun deviceKey(uuid: String) = "essential-device-$uuid"

    /**
     * The device (usid) this server bound [uuid]'s privileges to, or null when it has none yet. This
     * server's own record first; else a vanilla admin binding, which vanilla never rewrites while the
     * admin flag is set. A non-admin's PlayerInfo.adminUsid is not a binding: vanilla overwrites it on
     * every join with whoever joined last.
     */
    fun boundDevice(uuid: String): String? =
        Core.settings?.getString(deviceKey(uuid), null)?.takeIf { it.isNotEmpty() }
            ?: Vars.netServer?.admins?.getInfoOptional(uuid)?.takeIf { it.admin }?.adminUsid?.takeIf { it.isNotEmpty() }

    /**
     * Bind [uuid] to [usid] on this server, replacing an earlier device. Only for a decision made by a
     * person - ServerAdminer's adminAdd from Discord reaches it by name - never from a join.
     */
    fun bindDevice(uuid: String, usid: String) {
        Core.settings.put(deviceKey(uuid), usid)
        Core.settings.forceSave()
        val data = findPlayerData(uuid)
        if (isAdminGroup(groupOf(uuid, data?.permission ?: default))) Vars.netServer?.admins?.adminPlayer(uuid, usid)
        data?.let { it.player.admin(isAdmin(uuid, it.permission)) }
    }

    /**
     * Trust on first use: a privileged [uuid] with no device on this server yet is bound to the one
     * they are connected with right now. Offline, nothing is bound; that happens at their next own join.
     */
    private fun bindSession(uuid: String) {
        val settings = Core.settings ?: return
        if (settings.getString(deviceKey(uuid), null) != null) return
        // An existing vanilla admin binding is carried over, so unAdminPlayer cannot lose it later.
        val usid = boundDevice(uuid) ?: Groups.player.find { it.uuid() == uuid }?.let(::sessionUsid) ?: return
        if (usid.isEmpty()) return
        settings.put(deviceKey(uuid), usid)
        settings.forceSave()
    }

    /** The group [data] acts with on this server: their resolved group, or the default one while [heldBack]. */
    fun effectiveGroup(data: PlayerData): String {
        val group = groupOf(data.uuid, data.permission)
        return if (rank(group) > 0 && heldBack(data.uuid, sessionUsid(data.player))) default else group
    }

    /**
     * The usid of a live connection, or null. A placeholder or detached Player (offline PlayerData)
     * answers usid() with "[LOCAL]" or the old session's, which would read as an unrecognised device.
     */
    fun sessionUsid(player: Playerc): String? =
        player.con()?.takeIf { it.isConnected && !it.hasDisconnected }?.usid

    /**
     * Nodes that act on other players or on the server. A group holding one is privileged: it ranks
     * above plain players, and like an admin group it is bound to a device.
     */
    private val PRIVILEGED_NODES = setOf(
        "admin", "afk.admin", "chat.admin", "changename", "exp", "god", "info.other", "js", "kick.admin",
        "kickall", "kill.other", "mute", "nextmap.admin", "pm.other", "rollback", "setperm", "strict",
        "team.other", "undo", "unban", "unmute", "vote.admin", "vote.pass", "vote.reset", "ws",
    )

    /**
     * Rank of a group, the only ordering permission.yaml allows: 2 for a group holding `all`, 1 for an
     * admin group or any non-default group holding a [PRIVILEGED_NODES] node (a moderator), 0 for the
     * rest. Inheritance is not an order - the default owner inherits nothing. Every rank above 0 is
     * held back on an unrecognised device.
     */
    fun rank(group: String): Int {
        val role = main[group] ?: return 0
        return when {
            role.permission.contains("all") -> 2
            role.admin == true -> 1
            group != default && role.permission.any { it in PRIVILEGED_NODES } -> 1
            else -> 0
        }
    }

    /** Whether [sender] ranks strictly above [target], each by the group they act with right now. */
    fun outranks(sender: PlayerData, target: PlayerData): Boolean =
        rank(effectiveGroup(sender)) > rank(effectiveGroup(target))

    /**
     * Bring this server's device binding and vanilla admin flag in line with [group]. [demote] is true
     * only for an explicit group change (setperm, adminRemove, undo): that alone may drop a binding. A
     * join passes false - its group can be a stand-in (temporary data during a database stall) - and
     * then only clears the vanilla flag for a session on the bound device, as a revoke made elsewhere.
     */
    fun syncVanillaAdmin(uuid: String, group: String, demote: Boolean = true) {
        val admins = Vars.netServer?.admins ?: return
        // A group permission.yaml does not define (parse failure, rename) is not a demotion to anything.
        if (!main.containsKey(group)) return
        if (rank(group) > 0 || user?.get(uuid)?.admin == true) {
            bindSession(uuid)
        } else if (demote) {
            Core.settings?.let { if (it.has(deviceKey(uuid))) { it.remove(deviceKey(uuid)); it.forceSave() } }
        }
        if (!isAdminGroup(group)) {
            // Guarded: unAdminPlayer saves every PlayerInfo, and this runs on every join.
            if (admins.getInfoOptional(uuid)?.admin == true && (demote || !heldBack(uuid))) admins.unAdminPlayer(uuid)
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

    fun setGroup(uuid: String, group: String): Boolean {
        if (!writeUser { it[uuid] = patchGroup(it[uuid], group) }) return false

        val map = user.orEmpty().toMutableMap()
        map[uuid] = (map[uuid] ?: PermissionData()).also { it.group = group }
        user = map

        applyGroup(uuid, group)
        return true
    }

    fun removeUserEntry(uuid: String, fallbackGroup: String): Boolean {
        if (!writeUser { it.remove(uuid) }) return false

        val map = user.orEmpty().toMutableMap()
        map.remove(uuid)
        user = map

        applyGroup(uuid, fallbackGroup)
        return true
    }

    /**
     * Apply [group] to a player that permission_user.yaml carries no entry for, without going near the
     * file. An entry in that file wins over [group] everywhere a permission is actually decided -
     * [isAdmin], [groupOf] and [get] all prefer it - so calling this for a uuid that has one leaves
     * PlayerData.permission saying one thing and every check answering with another.
     *
     * It is not free of side effects: [syncVanillaAdmin] reaches Mindustry own admin database, and for
     * an unknown uuid Administration.unAdminPlayer creates and saves an empty PlayerInfo row there.
     */
    fun applyGroup(uuid: String, group: String) {
        syncVanillaAdmin(uuid, group)

        findPlayerData(uuid)?.let { data ->
            data.permission = group
            data.player.admin(isAdmin(uuid, group))
        }
    }

    private fun writeUser(edit: (MutableMap<String, YamlNode>) -> Unit): Boolean {
        if (!userFileValid) {
            Log.warn(bundle["permission.user.file.invalid", userFileError.orEmpty()])
            return false
        }

        val next = userRaw.toMutableMap()
        edit(next)

        // An edit that changes nothing still cost the operator their backup and every comment and blank
        // line in the file, because the write re-serialises from the parsed form. Node equality carries
        // the YamlPath, and patchGroup builds its nodes at the root, so the comparison has to be
        // equivalentContentTo rather than ==.
        if (next.keys == userRaw.keys && next.all { (uuid, node) -> userRaw.getValue(uuid).equivalentContentTo(node) }) {
            return true
        }

        if (userFile.exists()) userFile.copyTo(userBackupFile)
        userFile.writeString(comment + "\n" + yaml.encodeToString(rawSerializer, next), false)
        userRaw = next
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
     * That is deliberate and it is not an oversight, because the bounded version of "a vanilla admin
     * should get plugin permissions" already ships: the join handler in core/CoreEvent.kt puts a
     * vanilla admin who is not already in an admin group into `feature.permission.vanillaAdminGroup`
     * - default `admin` - which moves the row, so [check] then grants them exactly what that group
     * holds and nothing else. The operator names the group, and it is a group like any other.
     *
     * The unbounded version is what a short-circuit on [isAdmin] here would be, and it is much wider
     * than it looks: the test would pass for every string, so it grants not what the admin group
     * holds but every node that exists, including the ones no group holds at all - `js`, `setperm`,
     * `unban`, `ws`. It is `all` by another name, reachable from the bare console `admin add`.
     * ClientCommandTest pins that making somebody a vanilla admin mid-session does not retroactively
     * hand them `/js`.
     *
     * So the honest reading of the gap: `admin add` grants nothing **for the rest of that session**,
     * and the promotion happens on their next join. The other direction is wired and is destructive -
     * [syncVanillaAdmin] calls unAdminPlayer whenever the resolved group is not an admin group, so a
     * setperm into a non-admin group strips a vanilla admin's flag.
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