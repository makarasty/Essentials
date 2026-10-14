package essential.core

import PluginTest.Companion.createPlayer
import PluginTest.Companion.joinPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.pumpApp
import PluginTest.Companion.serverCommand
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import arc.Core
import essential.common.PermissionTuning
import essential.common.database.data.PlayerData
import essential.common.database.data.createTemporaryPlayerData
import essential.common.database.data.getPlayerData
import essential.common.database.runFlywayMigration
import essential.common.permission.AdminTrust
import essential.common.permission.Permission
import essential.common.database.table.PlayerTable
import essential.common.database.thisServerId
import essential.common.rootPath
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.gen.Player
import mindustry.net.NetConnection
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One confirm trusts an IP on every server; nothing else creates trust, and a demotion ends it. */
class AdminTrustTest {
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

    /** A session of [uuid] from [ip] on device [usid]; another server sees the same IP and another usid. */
    private fun join(uuid: String, usid: String, ip: String): Pair<Player, PlayerData> {
        val p = createPlayer()
        p.con = object : NetConnection(ip) {
            override fun send(`object`: Any?, reliable: Boolean) = Unit
            override fun close() = Unit
        }
        p.con.uuid = uuid
        p.con.usid = usid
        return p to joinPlayer(p)
    }

    private fun freshUuid() = "t" + UUID.randomUUID().toString().replace("-", "").take(23)

    /** What another server knows of [uuid]: nothing local, only the shared row. */
    private fun forgetThisServer(uuid: String) {
        Core.settings.remove("essential-device-$uuid")
        Vars.netServer.admins.unAdminPlayer(uuid)
    }

    private fun storedTrust(uuid: String): String? = runBlocking { getPlayerData(uuid) }?.adminTrust

    @Test
    fun theTrustListIsCappedAndExpires() {
        val now = 100L * 86_400_000L
        var stored: String? = null
        for (i in 1..5) stored = AdminTrust.with(stored, "10.0.0.$i", "test", now + i)
        assertEquals(listOf("10.0.0.3", "10.0.0.4", "10.0.0.5"), AdminTrust.read(stored, now + 6).map { it.ip })
        val later = now + PermissionTuning.TRUST_TTL_DAYS * 86_400_000L + 10
        assertTrue(AdminTrust.read(stored, later).isEmpty(), "past the TTL nothing is trusted")
        assertTrue(AdminTrust.read("not json", now).isEmpty())
    }

    @Test
    fun aConfirmMakesThePlayerAdminOnAnotherServerFromTheSameIp() {
        val ip = "198.51.100.21"
        val (owner, _) = join(freshUuid(), "deviceOnA", ip)
        val uuid = owner.uuid()
        setPermission(owner, "owner", false)
        assertTrue(waitUntil(10000) { storedTrust(uuid)?.contains(ip) == true }, "setperm on an online player trusts their IP")
        leavePlayer(owner)

        forgetThisServer(uuid)
        val (back, backData) = join(uuid, "deviceOnB", ip)
        try {
            assertEquals("deviceOnB", Permission.boundDevice(uuid), "the trusted IP binds this server's device")
            assertTrue(back.admin())
            assertTrue(Permission.check(backData, "js"), "with the owner's nodes")
        } finally {
            leavePlayer(back)
        }
    }

    @Test
    fun anImpostorFromAnotherIpIsHeldBackAndPlaysAsAnOrdinaryPlayer() {
        val (owner, _) = join(freshUuid(), "realDevice", "198.51.100.22")
        val uuid = owner.uuid()
        setPermission(owner, "owner", false)
        leavePlayer(owner)

        forgetThisServer(uuid)
        val (impostor, data) = join(uuid, "copiedUuid", "203.0.113.99")
        try {
            assertTrue(Permission.heldBack(uuid))
            assertNull(Permission.boundDevice(uuid), "an unvisited server binds nothing on first use")
            assertFalse(impostor.admin())
            assertEquals(Permission.default, Permission.effectiveGroup(data))
            assertFalse(Permission.check(data, "js"))
            assertTrue(Permission.check(data, "help"), "but plays with the default group's nodes")
            assertEquals("owner", data.permission, "the shared row is untouched")
        } finally {
            leavePlayer(impostor)
        }
    }

    /** An offline promotion trusts nothing: the next join is held back until somebody confirms it. */
    @Test
    fun anOfflinePromotionBindsNothingOnFirstUse() {
        val (target, _) = newPlayer()
        val uuid = target.uuid()
        val usid = target.usid()
        leavePlayer(target)
        serverCommand.handleMessage("setperm $uuid admin")
        assertTrue(waitUntil(10000) { runBlocking { getPlayerData(uuid)?.permission } == "admin" })

        val (back, data) = join(uuid, usid, "198.51.100.23")
        try {
            assertNull(Permission.boundDevice(uuid), "no trust on first use")
            assertFalse(back.admin())
            assertEquals(Permission.default, Permission.effectiveGroup(data))

            assertNull(PluginTest.confirmFromDiscord(back), "it completes once the row took the trust")
            assertTrue(back.admin(), "the Discord confirm binds this session")
            assertEquals("admin", Permission.effectiveGroup(data))
            assertTrue(storedTrust(uuid)?.contains("198.51.100.23") == true, "and trusts its IP fleet-wide")
        } finally {
            leavePlayer(back)
        }
    }

    @Test
    fun aDemotionClearsTheTrustAndTheBinding() {
        val ip = "198.51.100.24"
        val (admin, data) = join(freshUuid(), "adminDevice", ip)
        val uuid = admin.uuid()
        try {
            setPermission(admin, "admin", false)
            assertTrue(waitUntil(10000) { storedTrust(uuid) != null })
            setPermission(admin, "user", false)
            assertNull(data.adminTrust)
            assertNull(Permission.boundDevice(uuid))
            assertFalse(Vars.netServer.admins.getInfo(uuid).admin)
            assertTrue(waitUntil(10000) { runBlocking { getPlayerData(uuid) }?.let { it.permission == "user" && it.adminTrust == null } == true })
        } finally {
            leavePlayer(admin)
        }

        forgetThisServer(uuid)
        serverCommand.handleMessage("setperm $uuid admin")
        assertTrue(waitUntil(10000) { runBlocking { getPlayerData(uuid)?.permission } == "admin" })
        val (back, _) = join(uuid, "adminDevice2", ip)
        try {
            assertFalse(back.admin(), "the old IP is no longer trusted after the demotion")
        } finally {
            leavePlayer(back)
        }
    }

    /**
     * A save of the row from this server's copy never writes trust back: the join's re-stamp went to
     * the row on its own, so a demotion made elsewhere afterwards stays a demotion.
     */
    @Test
    fun aRowSaveDoesNotWriteBackTrustClearedElsewhere() {
        val ip = "198.51.100.28"
        val (first, _) = join(freshUuid(), "deviceA", ip)
        val uuid = first.uuid()
        setPermission(first, "admin", false)
        assertTrue(waitUntil(10000) { storedTrust(uuid) != null })
        leavePlayer(first)

        val (back, data) = join(uuid, "deviceA", ip)
        try {
            assertTrue(back.admin())
            assertTrue(waitUntil(10000) { storedTrust(uuid) == data.adminTrust }, "the join's re-stamp is written by itself")

            val elsewhere = runBlocking { getPlayerData(uuid) }!!
            elsewhere.permission = "user"
            elsewhere.adminTrust = null
            runBlocking { elsewhere.update() }

            data.exp += 1
            assertTrue(runBlocking { data.update() })
            val row = runBlocking { getPlayerData(uuid) }!!
            assertNull(row.adminTrust, "the save from the stale copy left the cleared trust alone")
            assertEquals("user", row.permission)
        } finally {
            leavePlayer(back)
        }
    }

    /** ServerAdminer's offline adminAdd and adminRemove write the shared row and say when it landed. */
    @Test
    fun storeGroupWritesTheSharedRow() {
        val (owner, _) = newPlayer()
        val ownerUuid = owner.uuid()
        setPermission(owner, "owner", false)
        leavePlayer(owner)
        assertTrue(waitUntil(10000) { runBlocking { getPlayerData(ownerUuid)?.permission } == "owner" })
        assertTrue(Permission.storeGroup(ownerUuid, "admin", true).get(10, TimeUnit.SECONDS))
        assertEquals("owner", runBlocking { getPlayerData(ownerUuid) }?.permission, "adminAdd leaves an owner an owner")

        val (plain, _) = newPlayer()
        val uuid = plain.uuid()
        leavePlayer(plain)
        assertTrue(Permission.storeGroup(uuid, "admin", true).get(10, TimeUnit.SECONDS))
        assertEquals("admin", runBlocking { getPlayerData(uuid) }?.permission)
        assertTrue(Permission.storeGroup(ownerUuid, "user", false).get(10, TimeUnit.SECONDS))
        val demoted = runBlocking { getPlayerData(ownerUuid) }!!
        assertEquals("user", demoted.permission)
        assertNull(demoted.adminTrust, "adminRemove clears the trust")

        assertFalse(Permission.storeGroup("noSuchUuidAtAll12345", "admin", true).get(10, TimeUnit.SECONDS), "no row is not a success")
    }

    /** A demotion written on another server reaches the session here at the next refresh. */
    @Test
    fun aDemotionElsewhereReachesAnOnlineSessionHere() {
        val (admin, data) = join(freshUuid(), "device", "198.51.100.25")
        val uuid = admin.uuid()
        try {
            setPermission(admin, "admin", false)
            assertTrue(waitUntil(10000) { runBlocking { getPlayerData(uuid) }?.let { it.permission == "admin" && it.adminTrust != null } == true })
            assertTrue(waitUntil(10000) { data.dbSnapshot?.adminTrust != null }, "this server's own save has landed")

            val elsewhere = runBlocking { getPlayerData(uuid) }!!
            elsewhere.permission = "user"
            elsewhere.adminTrust = null
            runBlocking { elsewhere.update() }

            Permission.refresh()
            data.exp += 1 // a live session's fields move while the read is out; the refresh must still find it
            assertTrue(waitUntil(10000) { pumpApp(); data.permission == "user" }, "the shared row's group is adopted")
            assertNull(data.adminTrust)
            assertFalse(admin.admin())
            assertNull(Permission.boundDevice(uuid))
        } finally {
            leavePlayer(admin)
        }
    }

    /** Auth off asks for visitor; a permission.yaml without it falls back to its own default group. */
    @Test
    fun aMissingDefaultGroupFallsBackToTheFileDefault() {
        val mainFile = rootPath.child("permission.yaml")
        val good = mainFile.readString()
        val before = Permission.default
        val (admin, _) = join(freshUuid(), "device", "198.51.100.26")
        val uuid = admin.uuid()
        setPermission(admin, "admin", false)
        leavePlayer(admin)
        try {
            mainFile.writeString(
                "owner:\n  admin: true\n  permission:\n    - all\n" +
                    "admin:\n  inheritance: user\n  admin: true\n  permission:\n    - strict\n" +
                    "user:\n  default: true\n  permission:\n    - rtv\n",
                false
            )
            Permission.load()
            Permission.setAuthDefault("visitor")
            assertEquals("user", Permission.default)

            val (stand, _) = newPlayer()
            try {
                val temporary = createTemporaryPlayerData(stand).also {
                    it.temporary = true
                    it.permission = Permission.default
                }
                assertTrue(Permission.check(temporary, "rtv"), "temporary data plays as an ordinary player")
            } finally {
                leavePlayer(stand)
            }

            forgetThisServer(uuid)
            val (held, heldData) = join(uuid, "otherDevice", "203.0.113.98")
            try {
                assertTrue(Permission.heldBack(uuid))
                assertTrue(Permission.check(heldData, "rtv"), "a held-back session plays as an ordinary player")
                assertFalse(Permission.check(heldData, "strict"))
            } finally {
                leavePlayer(held)
            }
        } finally {
            mainFile.writeString(good, false)
            Permission.load()
            Permission.setAuthDefault(before)
        }
    }

    /** V11 on H2: the boot already ran it, so Flyway is at 11 and the column round-trips. */
    @Test
    fun theH2SchemaCarriesAdminTrust() {
        assertEquals("11", runFlywayMigration("h2", "", "", ""))
        val (target, data) = newPlayer()
        try {
            data.adminTrust = AdminTrust.with(null, "198.51.100.27", "test", System.currentTimeMillis())
            assertTrue(runBlocking { data.update() })
            val stored = assertNotNull(runBlocking { getPlayerData(target.uuid()) })
            assertEquals(listOf("198.51.100.27"), AdminTrust.read(stored.adminTrust, System.currentTimeMillis()).map { it.ip })
        } finally {
            leavePlayer(target)
        }
    }

    private fun claimRow(uuid: String, server: String) = runBlocking {
        suspendTransaction {
            PlayerTable.update({ PlayerTable.uuid eq uuid }) {
                it[PlayerTable.isConnected] = true
                it[PlayerTable.connectedServer] = server
            }
        }
    }

    private fun claimedBy(uuid: String): String? =
        runBlocking { getPlayerData(uuid) }?.takeIf { it.isConnected }?.connectedServer

    /** Another server's join after this one: refused at once, and still refused after this side's refresh. */
    @Test
    fun aJoinElsewhereAfterThisOneRefusesTheConfirm() {
        val (player, data) = join(freshUuid(), "device", "198.51.100.30")
        val uuid = player.uuid()
        try {
            assertTrue(waitUntil(10000) { claimedBy(uuid) == thisServerId }, "every join claims the row")
            claimRow(uuid, "other-server")
            val refused = assertNotNull(PluginTest.confirmFromDiscord(player))
            assertTrue("other-server" in refused, refused)
            assertFalse(player.admin())
            assertEquals("user", data.permission, "the refusal changes no group")
            assertNull(Permission.boundDevice(uuid))

            Permission.refresh()
            assertTrue(waitUntil(10000) { pumpApp(); claimedBy(uuid) == thisServerId }, "the refresh claims the row back")
            assertTrue("other-server" in assertNotNull(PluginTest.confirmFromDiscord(player)), "and remembers the other session")
        } finally {
            leavePlayer(player)
        }
    }

    /** Another server's session before this join: the join sees its claim, and claims over it. */
    @Test
    fun aSessionElsewhereBeforeThisJoinRefusesTheConfirm() {
        val (first, _) = join(freshUuid(), "device", "198.51.100.31")
        val uuid = first.uuid()
        leavePlayer(first)
        claimRow(uuid, "other-server")
        val (back, _) = join(uuid, "device", "198.51.100.31")
        try {
            assertTrue(waitUntil(10000) { claimedBy(uuid) == thisServerId }, "the later join claims the row")
            val refused = assertNotNull(PluginTest.confirmFromDiscord(back))
            assertTrue("other-server" in refused && "reconnect" in refused, refused)
        } finally {
            leavePlayer(back)
        }
    }

    /** A crashed server's claim: claimed over at the join, and a reconnect starts clean. */
    @Test
    fun aStaleClaimGoesWithAReconnect() {
        val (first, _) = join(freshUuid(), "device", "198.51.100.37")
        val uuid = first.uuid()
        leavePlayer(first)
        claimRow(uuid, "crashed-server")
        val (stuck, _) = join(uuid, "device", "198.51.100.37")
        assertNotNull(PluginTest.confirmFromDiscord(stuck), "the stale claim is indistinguishable from a live one")
        leavePlayer(stuck)
        assertNull(claimedBy(uuid), "the leave released this server's own claim")

        val (back, _) = join(uuid, "device", "198.51.100.37")
        try {
            assertNull(PluginTest.confirmFromDiscord(back), "after a reconnect the confirm goes through")
            assertTrue(back.admin())
        } finally {
            leavePlayer(back)
        }
    }

    /** A leave here does not clear a claim another server made since. */
    @Test
    fun aLeaveReleasesOnlyThisServersClaim() {
        val (player, _) = join(freshUuid(), "device", "198.51.100.38")
        val uuid = player.uuid()
        assertTrue(waitUntil(10000) { claimedBy(uuid) == thisServerId })
        claimRow(uuid, "other-server")
        leavePlayer(player)
        assertEquals("other-server", claimedBy(uuid))
    }

    /** The group goes to the row before anything is granted; when it does not save, nothing is. */
    @Test
    fun aGroupThatDoesNotSaveGrantsNothing() {
        val (player, data) = join(freshUuid(), "device", "198.51.100.39")
        val uuid = player.uuid()
        try {
            assertTrue(waitUntil(10000) { claimedBy(uuid) == thisServerId })
            runBlocking { suspendTransaction { PlayerTable.deleteWhere { PlayerTable.uuid eq uuid } } }
            val refused = assertNotNull(PluginTest.confirmFromDiscord(player))
            assertTrue("not saved" in refused, refused)
            assertEquals("user", data.permission, "the group is put back")
            assertNull(Permission.boundDevice(uuid))
            assertFalse(player.admin())
        } finally {
            leavePlayer(player)
        }
    }

    /** An undo only restores while the group is still the one it set, and confirms only the same device. */
    @Test
    fun anUndoChecksTheGroupAndTheDevice() {
        val (player, data) = join(freshUuid(), "device", "198.51.100.40")
        val uuid = player.uuid()
        try {
            setPermission(player, "owner", false)
            assertFalse(Undo.permission(uuid, "user", false, "admin"), "the group was changed again since")
            assertEquals("owner", data.permission)

            setPermission(player, "user", false)
            assertTrue(Undo.permission(uuid, "admin", false, "user", "anotherDevice"))
            assertEquals("admin", data.permission)
            assertNull(Permission.boundDevice(uuid), "a different device is not confirmed by the undo")
            assertFalse(player.admin())
        } finally {
            leavePlayer(player)
        }
    }

    /** ServerAdminer's admins action lists the admin-level groups from the shared rows. */
    @Test
    fun theAdminListComesFromTheGroups() {
        val (owner, _) = join(freshUuid(), "device", "198.51.100.41")
        val (plain, _) = join(freshUuid(), "device", "198.51.100.42")
        try {
            setPermission(owner, "owner", false)
            assertTrue(waitUntil(10000) { runBlocking { getPlayerData(owner.uuid())?.permission } == "owner" })
            val listed = Permission.adminLevelPlayers().get(10, TimeUnit.SECONDS)
            assertEquals("owner", listed.find { it["uuid"] == owner.uuid() }?.get("group"))
            assertNull(listed.find { it["uuid"] == plain.uuid() })
        } finally {
            leavePlayer(owner)
            leavePlayer(plain)
        }
    }

    /** The person confirming sees who it is about: server, player, IP - marked when new - and group. */
    @Test
    fun theConfirmNamesTheSessionAndANewIp() {
        val (player, _) = join(freshUuid(), "device", "198.51.100.32")
        try {
            val before = assertNotNull(Permission.describeSession(player.uuid()))
            assertTrue("198.51.100.32 NEW IP (not trusted before)" in before, before)
            assertTrue("group user" in before && player.plainName() in before, before)
            assertNull(PluginTest.confirmFromDiscord(player))
            val after = assertNotNull(Permission.describeSession(player.uuid()))
            assertFalse("NEW IP" in after, after)
            assertTrue("group admin" in after, after)
        } finally {
            leavePlayer(player)
        }
    }

    /** A held-back owner still ranks as the owner: an admin here cannot act on that account. */
    @Test
    fun aTargetRanksByItsStoredGroup() {
        val (owner, _) = join(freshUuid(), "realDevice", "198.51.100.33")
        val uuid = owner.uuid()
        val (admin, adminData) = join(freshUuid(), "adminDevice", "198.51.100.34")
        setPermission(owner, "owner", false)
        setPermission(admin, "admin", false)
        leavePlayer(owner)
        val (impostor, impostorData) = join(uuid, "copied", "203.0.113.97")
        try {
            assertTrue(Permission.heldBack(uuid))
            assertFalse(Permission.outranks(adminData, impostorData), "held back, but still the owner's account")
            PluginTest.clientCommand.handleMessage("/setperm $uuid user", admin)
            assertEquals("owner", impostorData.permission)
        } finally {
            leavePlayer(impostor)
            leavePlayer(admin)
        }
    }

    /** Bindings and vanilla flags from before fleet-wide trust go once, and only once. */
    @Test
    fun theOldBindingsAreDroppedOnce() {
        val admins = Vars.netServer.admins
        val settings = Core.settings
        try {
            settings.remove("essential-trust-reset")
            settings.put("essential-device-oldTofuUuid", "someDevice")
            admins.adminPlayer("oldVanillaUuid", "someDevice")
            Permission.dropTofuBindings()
            assertFalse(settings.has("essential-device-oldTofuUuid"))
            assertFalse(admins.getInfo("oldVanillaUuid").admin)

            settings.put("essential-device-oldTofuUuid", "someDevice")
            Permission.dropTofuBindings()
            assertTrue(settings.has("essential-device-oldTofuUuid"), "a binding grant makes later is kept")
        } finally {
            settings.remove("essential-device-oldTofuUuid")
        }
    }

    /** Undoing the demotion of an admin online here gives them back their rights, not a held-back session. */
    @Test
    fun undoingADemotionConfirmsTheOnlineSession() {
        val (admin, data) = join(freshUuid(), "device", "198.51.100.35")
        val uuid = admin.uuid()
        try {
            setPermission(admin, "admin", false)
            setPermission(admin, "user", false)
            assertNull(Permission.boundDevice(uuid))
            assertNotNull(Undo.take(Undo.CONSOLE, null)).revert(uuid)
            assertEquals("admin", data.permission)
            assertEquals(admin.usid(), Permission.boundDevice(uuid))
            assertTrue(admin.admin())
            assertFalse(Permission.heldBack(uuid))
        } finally {
            leavePlayer(admin)
        }
    }

    /** A reload rewrites only the offline rows whose entry changed, and sees an entry another server dropped. */
    @Test
    fun aReloadAppliesOnlyWhatChanged() {
        val userFile = rootPath.child("permission_user.yaml")
        val good = userFile.readString()
        val (offline, _) = newPlayer()
        val offlineUuid = offline.uuid()
        leavePlayer(offline)
        val (online, onlineData) = join(freshUuid(), "device", "198.51.100.36")
        val onlineUuid = online.uuid()
        try {
            userFile.writeString(good.trimEnd() + "\n$offlineUuid:\n  group: admin\n$onlineUuid:\n  group: user\n  admin: true\n", false)
            Permission.load()
            pumpApp()
            assertTrue(waitUntil(10000) { runBlocking { getPlayerData(offlineUuid)?.permission } == "admin" })
            assertNull(PluginTest.confirmFromDiscord(online, "user"), "the file's admin flag, confirmed here")
            assertTrue(online.admin())

            val row = runBlocking { getPlayerData(offlineUuid) }!!
            row.permission = "user"
            runBlocking { row.update() }
            Permission.load()
            pumpApp()
            Thread.sleep(500)
            assertEquals("user", runBlocking { getPlayerData(offlineUuid)?.permission }, "an unchanged entry is not rewritten")

            // Another server drops the online player's entry; this one then writes the file itself.
            Thread.sleep(20)
            userFile.writeString(good.trimEnd() + "\n$offlineUuid:\n  group: admin\n", false)
            Permission.setGroup(offlineUuid, "admin")
            assertEquals(online.usid(), Permission.boundDevice(onlineUuid), "re-read, not applied yet")
            Permission.refresh()
            assertTrue(waitUntil(10000) { pumpApp(); Permission.boundDevice(onlineUuid) == null }, "the dropped entry is a demotion")
            assertFalse(online.admin())
            assertNull(onlineData.adminTrust)
        } finally {
            userFile.writeString(good, false)
            Permission.load()
            pumpApp()
            leavePlayer(online)
        }
    }
}
