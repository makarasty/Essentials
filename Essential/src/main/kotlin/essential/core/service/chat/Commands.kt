package essential.core.service.chat

import essential.common.database.data.PlayerData
import essential.common.util.PlayerLookup
import ksp.command.ClientCommand
import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Playerc

class Commands {
    @ClientCommand(name = "me", parameter = "<text...>", description = "Chat with special prefix")
    fun me(playerData: PlayerData, arg: Array<out String>) {
        // This went straight to Call.sendMessage, so none of the registered chat filters saw it: the
        // mute and global-mute check, the word blacklist, the keyboard-layout rewrite, a running vote,
        // and the engine's own anti-spam. filterMessage is the only thing that runs them, and a
        // server-wide mute did not stop a command every player has.
        //
        // The inline blacklist copy that stood here goes with it. It matched the whole message where
        // the registered filter searches inside it, so one blacklist entry caught different text
        // depending on whether it was typed as chat or as /me, and no operator could see which.
        //
        // Two consequences the chain brings with it, both shared with /t: text beginning with "/" is
        // dropped as it is in public chat, and the keyboard-layout filter may claim text beginning
        // with "." and run it as a command instead. That filter is the one to fix, not this command.
        val message = Vars.netServer.admins.filterMessage(playerData.player.self(), arg[0]) ?: return
        Call.sendMessage("[orange]*[]" + Vars.netServer.chatFormatter.format(playerData.player.`as`(), message))
    }

    @ClientCommand(name = "pm", parameter = "<player> <message...>", description = "Send a private message")
    fun pm(playerData: PlayerData, arg: Array<out String>) {
        val target: Playerc = PlayerLookup.online(arg[0], playerData) ?: return

        if (arg.size > 1) {
            // Same defect as /me: a private message reached its target without passing any chat filter,
            // so a muted or globally muted player could still write to anybody. Filtering after the
            // lookup so a message addressed to nobody does not consume a vote or an anti-spam slot.
            val message = Vars.netServer.admins.filterMessage(playerData.player.self(), arg[1]) ?: return
            // sendDirect rather than player.sendMessage: same send, and it records what the sender was
            // told, which is the only half of a private message anything outside this command can see.
            playerData.sendDirect("[green][PM] " + target.plainName() + "[yellow] => [white] " + message)
            target.sendMessage("[blue][PM] [gray][" + playerData.entityId + "][]" + playerData.player.plainName() + "[yellow] => [white] " + message)

            // This part is commented out as it requires access to database and Permission which we don't have proper references for
            /*
            database.getPlayers().stream().filter { p ->
                Permission.INSTANCE.check(p, "pm.other") && p.uuid != player.uuid() && target.uuid() != player.uuid()
            }.forEach { p ->
                p.player.sendMessage("[sky]${player.plainName()}[][yellow] => [pink]${target.plainName()} [white]: ${message}")
            }
            */
        } else {
            playerData.err("command.pm.message")
        }
    }
}
