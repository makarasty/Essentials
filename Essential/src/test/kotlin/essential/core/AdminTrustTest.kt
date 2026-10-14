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
import essential.common.rootPath
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.gen.Player
import mindustry.net.NetConnection
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

            val stored = Permission.bindDevice(uuid, usid, back.ip())
            assertTrue(back.admin(), "the Discord confirm binds this session")
            assertEquals("admin", Permission.effectiveGroup(data))
            assertTrue(stored.get(10, TimeUnit.SECONDS), "it completes once the row took the trust")
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
}
