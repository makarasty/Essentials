package essential.common.permission

import arc.files.Fi
import arc.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import essential.common.bundle.Bundle
import essential.common.command.CommandRegistry
import essential.common.database.data.PlayerData
import essential.common.database.table.PlayerTable
import essential.common.players
import essential.common.rootPath
import essential.core.Main.Companion.scope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import mindustry.Vars
import mindustry.gen.Groups
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.*

object Permission {
    private var main: Map<String, RoleConfig> = mapOf()
    private var user: Map<String, PermissionData>? = mapOf()
    private var userRaw: Map<String, YamlNode> = mapOf()
    private var userFileValid = true
    private var userFileError: String? = null
    var default = "user"
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
            mainFile.write(this::class.java.getResourceAsStream("/permission_default.yaml")!!, false)
        }

        if (!userFile.exists()) {
            userFile.writeString(comment)
        }
    }

    fun load() {
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

        try {
            main = if (mainFile.exists()) {
                yaml.decodeFromString(MapSerializer(String.serializer(), RoleConfig.serializer()), mainFile.readString())
            } else {
                mapOf()
            }
        } catch (e: Exception) {
            Log.warn("Failed to parse permission.yaml: ${e.message}")
        }

        for ((name, roleConfig) in main) {
            if (default == "user" && roleConfig.default == true) {
                default = name
            }

            var inheritance: String? = roleConfig.inheritance
            while (inheritance != null) {
                val inheritedRoleConfig = main[inheritance]
                inheritedRoleConfig?.let { inheritedRole ->
                    for (permission in inheritedRole.permission) {
                        if (!permission.contains("all", true) && !roleConfig.permission.contains(permission)) {
                            roleConfig.permission.add(permission)
                        }
                    }
                    inheritance = inheritedRole.inheritance
                } ?: run {
                    inheritance = null
                }
            }
        }

        apply()
    }

    fun apply() {
        if (user != null) {
            for ((uuid, permissionData) in user!!) {
                val player = players.find { e -> e.uuid == uuid }
                if (player == null) {
                    scope.launch {
                        suspendTransaction {
                            PlayerTable.update({ PlayerTable.uuid eq uuid }) {
                                it[PlayerTable.permission] = permissionData.group
                                if (permissionData.name.isNotEmpty()) {
                                    it[PlayerTable.name] = permissionData.name
                                }
                            }
                        }
                    }
                } else {
                    player.permission = permissionData.group
                    player.player.admin(isAdmin(uuid, permissionData.group))
                    if (permissionData.name.isNotEmpty()) {
                        player.name = permissionData.name
                        player.player.name(permissionData.name)
                    }
                }
            }
        }
    }

    operator fun get(data: PlayerData): PermissionData {
        val result = PermissionData()

        val u = user?.get(data.uuid)
        if (u != null) {
            result.name = u.name.ifEmpty { data.player.name() }
            result.group = u.group
            result.admin = isAdmin(data.uuid, u.group)
            result.isAlert = u.isAlert
            result.alertMessage = u.alertMessage
            result.chatFormat = u.chatFormat
        } else {
            result.name = data.player.name()
            result.group = data.permission
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

    fun isAdmin(uuid: String, fallbackGroup: String): Boolean {
        val entry = user?.get(uuid)
        return entry?.admin == true || main[entry?.group ?: fallbackGroup]?.admin == true || isVanillaAdmin(uuid)
    }

    fun isAdminGroup(group: String): Boolean = main[group]?.admin == true

    fun isVanillaAdmin(uuid: String): Boolean =
        Vars.netServer?.admins?.getInfoOptional(uuid)?.admin == true

    fun syncVanillaAdmin(uuid: String, group: String) {
        val admins = Vars.netServer?.admins ?: return
        if (!isAdminGroup(group)) {
            admins.unAdminPlayer(uuid)
            return
        }
        if (isVanillaAdmin(uuid)) return
        val usid = Groups.player.find { it.uuid() == uuid }?.usid() ?: admins.getInfoOptional(uuid)?.adminUsid
        if (usid.isNullOrEmpty()) {
            Log.info(bundle["permission.vanilla.admin.deferred", uuid])
            return
        }
        admins.adminPlayer(uuid, usid)
    }

    /** The name permission_user.yaml assigns to this player, or null when it assigns none. */
    fun assignedName(uuid: String): String? = user?.get(uuid)?.name?.takeIf { it.isNotEmpty() }

    fun groupOf(uuid: String, fallbackGroup: String): String = user?.get(uuid)?.group ?: fallbackGroup

    fun hasUserEntry(uuid: String): Boolean = user?.containsKey(uuid) == true

    val groups: Set<String> get() = main.keys

    fun hasGroup(group: String): Boolean = main.containsKey(group)

    fun userFileProblem(): String? = if (userFileValid) null else userFileError.orEmpty()

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

    private fun applyGroup(uuid: String, group: String) {
        syncVanillaAdmin(uuid, group)

        players.find { data -> data.uuid == uuid }?.let { data ->
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

    fun check(data: PlayerData, command: String): Boolean {
        val group = main[this[data].group]
        return if (group != null) {
            val passed = group.permission.contains(command) || group.permission.contains("all")
            Log.debug("[Permission] ${data.name} > group: ${this[data].group} -> command: $command -> $passed")
            passed
        } else {
            Log.debug("[Permission] ${data.name} > group: ${this[data].group} -> command: $command -> false")
            false
        }
    }

    @Serializable
    data class PermissionData(
        var name: String = "",
        var group: String = default,
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