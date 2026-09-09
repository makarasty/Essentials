package essential.core

import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.waitUntil
import essential.common.permission.Permission
import essential.common.rootPath
import mindustry.Vars
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two undos that reported success while doing the wrong thing.
 *
 * Both are silent on a running server: one leaves the two permission stores disagreeing until
 * somebody notices the group never moved, the other lifts bans nobody asked to lift.
 */
class UndoConsistencyTest {
    companion object {
        private var done = false
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    @Test
    fun undo_permissionLeavesTheRowAloneWhenTheFileWriteIsRefused() {
        val userFile = rootPath.child("permission_user.yaml")
        val backupFile = rootPath.child("permission_user.yaml.bak")
        val good = userFile.readString()

        val target = newPlayer()
        val uuid = target.first.uuid()
        val before = target.second.permission

        try {
            // A tab in the indentation: YAML forbids it, so the file no longer parses and every write
            // through Permission is refused until the operator repairs it.
            userFile.writeString("$uuid:\n\tname: broken\n", false)
            Permission.load()
            assertNotNull(Permission.userFileProblem(), "the file must be seen as unparseable, or this test proves nothing")

            Undo.permission(uuid, "owner", false)

            assertFalse(
                waitUntil(3000) { target.second.permission == "owner" },
                "the row must not be moved while permission_user.yaml refuses the matching write: half an undo is worse than none, and once the file parses again it is the file that decides"
            )
            assertEquals(before, target.second.permission, "the group should be exactly what it was")
        } finally {
            userFile.writeString(good, false)
            if (backupFile.exists()) backupFile.delete()
            Permission.load()
        }
    }

    @Test
    fun undo_banKeepsAnUnrelatedIpBanOnAnotherAddressOfTheSamePlayer() {
        val admins = Vars.netServer.admins
        val target = newPlayer().first
        val uuid = target.uuid()
        val current = target.con.address
        val earlier = "203.0.113.7"

        val info = admins.getInfo(uuid)

        try {
            // Order matters: banPlayerIP walks every known player and sets banned = true on any whose
            // ips hold the address, so banning first and adding the address second is what keeps this
            // player un-banned going in. The other order makes Undo.ban's banPlayerID a no-op and the
            // test then undoes a ban that was never placed.
            admins.banPlayerIP(earlier)
            info.ips.add(earlier)
            assertTrue(admins.bannedIPs.contains(earlier), "the earlier ip ban must be in place first")
            assertFalse(admins.isIDBanned(uuid), "and the player must not be banned yet")

            val ipBanned = Undo.ban(uuid)
            assertTrue(ipBanned, "the ban should place an ip ban on the address in use, or this test proves nothing")
            assertTrue(admins.bannedIPs.contains(current), "control: the ban placed an ip ban on the current address")

            Undo.unban(uuid, ipBanned)

            assertTrue(
                admins.bannedIPs.contains(earlier),
                "undoing one ban must leave an ip ban it never placed alone; unbanPlayerID strips every address in the player's info, so the others have to be put back"
            )
            assertFalse(
                admins.bannedIPs.contains(current),
                "and the ip ban that ban did place must still be lifted"
            )
            assertFalse(
                admins.isIDBanned(uuid),
                "and the undone ban must not survive as a uuid ban: putting the kept addresses back through banPlayerIP re-bans everyone holding them, this player included"
            )

            // A second undo of the same ban is ordinary - the stack is per admin and entries live ten
            // minutes - and by now unbanPlayerID returns early without stripping anything, so a put-back
            // that does not check for the address doubles it. A doubled ban survives its own unban.
            Undo.unban(uuid, ipBanned)
            assertEquals(
                1,
                admins.bannedIPs.count { it == earlier },
                "undoing the same ban twice must not leave the kept address in the ban list twice"
            )
        } finally {
            admins.unbanPlayerIP(earlier)
            admins.unbanPlayerIP(current)
            admins.unbanPlayerID(uuid)
            info.ips.remove(earlier, false)
        }
    }
}
