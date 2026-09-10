import PluginTest.Companion.clientCommand
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import arc.struct.Seq
import essential.core.OwnedMenus
import essential.core.Undo
import mindustry.Vars
import mindustry.gen.Player
import mindustry.ui.Menus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The two claims about menu ids that nothing else in this tree can make.
 *
 * `Call.menu` shows a **new** dialog on the client every time (`UI.showMenu`), and answering one
 * hides only that one (`UI.lambda$showMenu$27` is `cb.get(opt); dialog.hide()`) - there is no
 * hide-by-id for plain menus, `Menus` exposes it only for follow-up ones. So a dialog the player
 * never answered is still on their screen and is revealed the moment whatever was stacked on top of
 * it goes away, and it can still be clicked. What that click reaches is decided entirely by the id
 * the dialog was shown with.
 *
 * `1be2d26d` closed the listener leak by giving each player one shared id, which made that click
 * reach whatever that player had registered **most recently**, with the revealed dialog's own option
 * indices. Index 0 is "ban" on every confirm menu, "close" on `/info`'s main menu and "<-" on the
 * paging menus, so the most natural click in the interface issued a permanent ban - and it shipped
 * green, because `menuChoose` is only ever driven forward in this suite and a revealed dialog cannot
 * be simulated without a second id to reveal it with. [olderDialogsIdDoesNotDriveTheNewerListener] is
 * that second id.
 */
class OwnedMenuTest {
    companion object {
        private var done = false

        private fun menuListenerCount(): Int {
            val field = Menus::class.java.getDeclaredField("menuListeners")
            field.isAccessible = true
            return (field.get(null) as Seq<*>).size
        }

        /**
         * The id the block opened its owned menu under.
         *
         * The other menu tests take this from the growth of `Menus.menuListeners`, which can no
         * longer answer it: a slot the pool recycles registers nothing with the engine at all, so
         * the list does not move. `OwnedMenus.allocationCount` counts the same event one level up -
         * a menu handed to a player - and the "exactly one" is kept, for the same reason it was
         * written: if a second owned menu is opened in the window, "the id this block took" is
         * ambiguous and a click on the wrong one reaches a different listener silently, because
         * `menuChoose` only range-checks.
         */
        private fun ownedMenuOpenedBy(what: String, block: () -> Unit): Int {
            val before = OwnedMenus.allocationCount
            block()
            // Counted on allocations, not on the id list: a block that allocated the same slot twice
            // - which happens whenever a menu is answered inside the block, freeing its slot for the
            // next one - yields one id for two menus, and the gate would pass on the wrong one.
            assertEquals(
                1, (OwnedMenus.allocationCount - before).toInt(),
                "$what should have opened exactly one owned menu, otherwise this test proves nothing"
            )
            return OwnedMenus.idsAllocatedAfter(before).last()
        }
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    private fun admin(): Player = newPlayer().first.also { setPermission(it, "admin", true) }

    /** By uuid: `/info` only opens on an unambiguous online hit synchronously. See InfoMenuTest. */
    private fun openInfo(admin: Player, target: Player): Int =
        ownedMenuOpenedBy("/info") { clientCommand.handleMessage("/info ${target.uuid()}", admin) }

    private fun choose(player: Player, menu: Int, option: Int, what: String = "option $option"): Int =
        ownedMenuOpenedBy(what) { Menus.menuChoose(player, menu, option) }

    /**
     * The regression `1be2d26d` introduced, driven backwards the way a real client does.
     *
     * One admin, one command twice, no concurrency and no second player. Against a re-applied
     * `1be2d26d` the final click bans the target permanently and records an Undo entry that makes it
     * look deliberate; that is the red this test exists to produce.
     */
    @Test
    fun olderDialogsIdDoesNotDriveTheNewerListener() {
        val admin = admin()
        val target = newPlayer().first
        val uuid = target.uuid()
        val ip = target.con.address
        val ipBannedBefore = Vars.netServer.admins.bannedIPs.contains(ip)

        // Dialog 1. Never answered, so on a real client it is still on screen underneath everything
        // that follows, and still reachable by a click.
        val stale = openInfo(admin, target)

        // Dialog 2, walked to the ban confirmation and then cancelled. Cancel matches no branch and
        // re-registers nothing - that is exactly why it is the dangerous step: it leaves the ban
        // confirmation as the newest thing this admin has registered.
        val second = openInfo(admin, target)
        val durations = choose(admin, second, 1, "the ban menu")
        val confirm = choose(admin, durations, 6, "the permanent-ban confirmation")

        Menus.menuChoose(admin, confirm, 1) // cancel
        assertFalse(Vars.netServer.admins.isIDBanned(uuid), "cancel must not ban")

        // The admin now clicks index 0 on the revealed dialog 1 - "close" on /info's main menu.
        val undoBefore = Undo.stack(admin.uuid()).size
        Menus.menuChoose(admin, stale, 0)

        assertFalse(
            Vars.netServer.admins.isIDBanned(uuid),
            "closing a stale /info dialog must not issue the ban the admin had just cancelled"
        )
        assertEquals(
            undoBefore, Undo.stack(admin.uuid()).size,
            "...and must not leave an Undo entry saying the admin asked for one"
        )

        // The ban the stale click must not reach was genuinely armed: clicking index 0 on the
        // confirmation's own id does issue it. Without this the assertions above could pass on a
        // chain that had quietly stopped working three steps earlier.
        Menus.menuChoose(admin, confirm, 0)
        assertTrue(
            Vars.netServer.admins.isIDBanned(uuid),
            "the confirmation's own id must still ban, or the test above proves nothing"
        )

        // Last, and deliberately not first: the assertions above are about what a click does, which
        // is the thing that matters and the thing a future design is free to secure some other way -
        // follow-up menus, for instance, replace by id on the client instead of stacking, so a shared
        // id would be inert. This one pins the mechanism this fix actually uses. If it ever fails
        // while everything above passes, that is a design change and not a regression: read it,
        // then replace it with whatever the new mechanism makes checkable. Do not just delete it.
        assertNotEquals(
            stale, second, "two dialogs open at once for one player must not share a menu id"
        )

        Vars.netServer.admins.unbanPlayerID(uuid)
        if (!ipBannedBefore) Vars.netServer.admins.unbanPlayerIP(ip)
    }

    /**
     * A slot is claimed by `register`, not by the show that follows it, and the two are not the same
     * instant. `/info` registers its listener at the top and shows it only after resolving the
     * target - which for an offline target is a database round-trip on `Dispatchers.IO` and a
     * `Core.app.post` later. If a slot were free during that window the next command would take it,
     * and `/info`'s late show would put its dialog under somebody else's listener: an admin running
     * `/info offlineA` then `/info onlineB` would ban B by clicking A's menu.
     *
     * Driven directly rather than through the two commands, because the point is the window itself
     * and reproducing it through `/info` means racing a coroutine.
     */
    @Test
    fun aClaimedMenuIdIsNotHandedOutAgainBeforeItIsShown() {
        val (player, data) = newPlayer()
        try {
            val claimed = OwnedMenus.register(data) { _, _ -> }
            val next = OwnedMenus.register(data) { _, _ -> }
            assertNotEquals(
                claimed, next,
                "a registered menu must keep its id until it is shown, or a command that resolves its " +
                    "target slowly has its dialog opened under the next command's listener"
            )
        } finally {
            leavePlayer(player)
        }
    }

    /**
     * The leak `1be2d26d` was right about. `Menus.registerMenu` appends to a process-wide `Seq` and
     * the engine exposes no unregister at all, so a listener registered per menu opened - and the
     * `PlayerData` its closure captured - was retained for the life of the server.
     *
     * Twenty unanswered `/players` really are twenty dialogs stacked on that client, and each one
     * does need an id of its own, or answering the wrong one drives the wrong listener. The claim is
     * not that opening menus is free: it is that the ids come back. Once their owner is gone, the
     * next player's twenty opens must register nothing new with the engine.
     */
    @Test
    fun ownedMenuIdsAreReusedOnceTheirOwnerIsGone() {
        fun twentyUnansweredMenusThenLeave() {
            val (player, _) = newPlayer()
            try {
                setPermission(player, "owner", true)
                repeat(20) { clientCommand.handleMessage("/players", player) }
            } finally {
                leavePlayer(player)
            }
        }

        // The first round sets the high-water mark. It is allowed to register ids, and it should:
        // twenty stacked dialogs need twenty ids.
        twentyUnansweredMenusThenLeave()
        val before = menuListenerCount()

        // The second round runs with the first player gone, so every id they held is answerable by
        // nobody and all twenty are available again. This round must register nothing at all.
        //
        // Measured the other way round first, with one warm-up call before the snapshot, and it was
        // off by exactly one: the warm-up consumed one of the recyclable ids, so the twenty after it
        // needed a twenty-first. That was the pool being right - the count tracks dialogs open at
        // once, and there really were twenty-one - and the test being wrong about its own arithmetic.
        twentyUnansweredMenusThenLeave()

        assertEquals(
            before, menuListenerCount(),
            "owned menu ids must come back once nothing can answer on them, rather than being " +
                "registered afresh per menu opened - the engine never prunes that list"
        )
    }
}
