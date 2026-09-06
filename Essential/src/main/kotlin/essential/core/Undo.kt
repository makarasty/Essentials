package essential.core

import arc.util.Timer
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.database.data.getPlayerData
import essential.common.permission.Permission
import essential.common.timeSource
import essential.common.util.findPlayerData
import essential.core.Main.Companion.scope
import kotlinx.coroutines.launch
import mindustry.Vars
import mindustry.game.EventType.MenuOptionChooseEvent
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.net.Packets
import mindustry.ui.Menus
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark

class UndoEntry(
    val description: String,
    val targetUuid: String,
    val undoLabelKey: String,
    val alternativeKey: String?,
    val alternative: ((String) -> Unit)?,
    val revert: (String) -> Unit
) {
    var expiresAt: TimeMark = timeSource.markNow().plus(Undo.LIFETIME)

    val expired: Boolean get() = expiresAt.hasPassedNow()
}

object Undo {
    val LIFETIME = 10.minutes
    const val CONSOLE = "console"
    private const val LIMIT = 5
    private const val MENU_SECONDS = 60f

    private val stacks = ConcurrentHashMap<String, ArrayDeque<UndoEntry>>()
    private val pending = ConcurrentHashMap<String, UndoEntry>()

    val menuId: Int by lazy { Menus.registerMenu { _, _ -> } }

    fun label(uuid: String): String {
        val data = findPlayerData(uuid)
        if (data != null) return "[${data.entityId}] ${data.name}"
        val info = Vars.netServer.admins.playerInfo.get(uuid)
        return "[$uuid] ${info?.lastName ?: uuid}"
    }

    fun record(
        admin: PlayerData?,
        action: String,
        targetUuid: String,
        targetLabel: String,
        undoLabelKey: String = "command.undo.button.undo",
        alternativeKey: String? = null,
        alternative: ((String) -> Unit)? = null,
        revert: (String) -> Unit
    ) {
        val bundle = admin?.bundle ?: Bundle()
        val entry = UndoEntry(
            bundle["command.undo.action.$action", targetLabel],
            targetUuid,
            undoLabelKey,
            alternativeKey,
            alternative,
            revert
        )
        val stack = stacks.getOrPut(admin?.uuid ?: CONSOLE) { ArrayDeque() }
        synchronized(stack) {
            stack.addFirst(entry)
            while (stack.size > LIMIT) stack.removeLast()
        }
        if (admin != null) show(admin, entry)
    }

    fun stack(uuid: String): List<UndoEntry> {
        val stack = stacks[uuid] ?: return emptyList()
        synchronized(stack) {
            stack.removeAll { it.expired }
            return stack.toList()
        }
    }

    fun take(uuid: String, index: Int): UndoEntry? {
        val stack = stacks[uuid] ?: return null
        synchronized(stack) {
            stack.removeAll { it.expired }
            val entry = stack.getOrNull(index - 1) ?: return null
            stack.remove(entry)
            return entry
        }
    }

    fun onMenuChoose(event: MenuOptionChooseEvent) {
        if (event.menuId != menuId) return
        val adminUuid = event.player.uuid()
        val entry = pending.remove(adminUuid) ?: return
        Call.hideFollowUpMenu(event.player.con(), menuId)
        when (event.option) {
            0 -> {
                drop(adminUuid, entry)
                entry.revert(entry.targetUuid)
            }

            2 -> {
                drop(adminUuid, entry)
                entry.alternative?.invoke(entry.targetUuid)
            }
        }
    }

    fun unban(uuid: String) {
        val admins = Vars.netServer.admins
        admins.unbanPlayerID(uuid)
        admins.playerInfo.get(uuid)?.lastIP?.let { admins.unbanPlayerIP(it) }
        update(uuid) { it.banExpireDate = null }
    }

    fun ban(uuid: String) {
        Vars.netServer.admins.banPlayer(uuid)
        Groups.player.find { it.uuid() == uuid }?.kick(Packets.KickReason.banned)
    }

    fun liftKick(uuid: String) {
        val admins = Vars.netServer.admins
        admins.kickedIPs.remove(uuid)
        admins.playerInfo.get(uuid)?.let { info ->
            info.lastKicked = 0
            info.ips.each { admins.kickedIPs.remove(it) }
        }
        admins.save()
    }

    fun mute(uuid: String, muted: Boolean) = update(uuid) { it.chatMuted = muted }

    fun strict(uuid: String, strict: Boolean) = update(uuid) { it.strictMode = strict }

    fun permission(uuid: String, group: String) {
        update(uuid) { it.permission = group }
        Permission.setGroup(uuid, group)
    }

    fun team(uuid: String, team: Team) {
        Groups.player.find { it.uuid() == uuid }?.team(team)
    }

    private fun drop(adminUuid: String, entry: UndoEntry) {
        val stack = stacks[adminUuid] ?: return
        synchronized(stack) { stack.remove(entry) }
    }

    private fun show(admin: PlayerData, entry: UndoEntry) {
        val connection = admin.player.con() ?: return
        val bundle = admin.bundle
        val adminUuid = admin.uuid
        val buttons = arrayOf(bundle[entry.undoLabelKey], bundle["command.undo.button.ok"])
        val options = if (entry.alternativeKey == null) {
            arrayOf(buttons)
        } else {
            arrayOf(buttons, arrayOf(bundle[entry.alternativeKey]))
        }

        pending[adminUuid] = entry
        Call.followUpMenu(connection, menuId, bundle["command.undo.menu.title"], entry.description, options)
        Timer.schedule({
            if (pending.remove(adminUuid, entry)) {
                Groups.player.find { it.uuid() == adminUuid }?.let { Call.hideFollowUpMenu(it.con(), menuId) }
            }
        }, MENU_SECONDS)
    }

    private fun update(uuid: String, edit: (PlayerData) -> Unit) {
        scope.launch {
            val data = findPlayerData(uuid) ?: getPlayerData(uuid) ?: return@launch
            edit(data)
            data.update()
        }
    }
}
