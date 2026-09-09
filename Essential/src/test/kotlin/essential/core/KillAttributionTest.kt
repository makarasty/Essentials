package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import arc.Events
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.UnitTypes
import mindustry.game.EventType.BuildingBulletDestroyEvent
import mindustry.game.EventType.UnitBulletDestroyEvent
import mindustry.game.Team
import mindustry.gen.Bullet
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-092 and task-083's CoreEvent.kt halves. unitDestroy/blockDestroy used to broadcast a kill or
 * a destroyed building to every connected player, with no attribution - UnitDestroyEvent and
 * BlockDestroyEvent carry no owner at all. Per the attribution rule this codebase
 * follows: switch to the sibling events that pair the kill with a Bullet
 * (UnitBulletDestroyEvent, BuildingBulletDestroyEvent), credit the bullet's player-controlled owner
 * when one is resolvable, and credit nobody - not everybody - when it is not.
 */
class KillAttributionTest {
    @BeforeTest
    fun setup() {
        loadGame(true)
    }

    private var previousPvp = false
    private var previousAttackMode = false

    @AfterTest
    fun restore() {
        Vars.state.rules.pvp = previousPvp
        Vars.state.rules.attackMode = previousAttackMode
    }

    @Test
    fun onlyTheBulletOwnerIsCreditedForAUnitKill() {
        previousPvp = Vars.state.rules.pvp
        Vars.state.rules.pvp = false

        val (killer, killerData) = newPlayer()
        val (bystander, bystanderData) = newPlayer()
        try {
            val victim = UnitTypes.dagger.spawn(Team.crux, 10f, 10f)
            val bullet = Bullet.create()
            bullet.owner = killer.unit()

            Events.fire(UnitBulletDestroyEvent(victim, bullet))

            assertEquals(1, killerData.currentUnitDestroyedCount, "the bullet's owner must be credited")
            assertEquals(
                0,
                bystanderData.currentUnitDestroyedCount,
                "a player who did not fire the bullet must not be credited"
            )
        } finally {
            leavePlayer(killer)
            leavePlayer(bystander)
        }
    }

    @Test
    fun aKillWithNoResolvableOwnerCreditsNobody() {
        previousPvp = Vars.state.rules.pvp
        Vars.state.rules.pvp = false

        val (player, data) = newPlayer()
        try {
            val before = data.currentUnitDestroyedCount
            val victim = UnitTypes.dagger.spawn(Team.crux, 10f, 10f)
            val bullet = Bullet.create() // no owner set - an environmental/ownerless kill

            Events.fire(UnitBulletDestroyEvent(victim, bullet))

            assertEquals(
                before,
                data.currentUnitDestroyedCount,
                "no resolvable owner must credit nobody, not every connected player"
            )
        } finally {
            leavePlayer(player)
        }
    }

    @Test
    fun onlyTheBulletOwnerIsCreditedForABuildingKill() {
        previousAttackMode = Vars.state.rules.attackMode
        Vars.state.rules.attackMode = true
        Vars.state.rules.defaultTeam = Team.sharded

        val (attacker, attackerData) = newPlayer()
        val (bystander, bystanderData) = newPlayer()
        try {
            val build = Blocks.coreShard.newBuilding().create(Blocks.coreShard, Team.crux)
            val bullet = Bullet.create()
            bullet.owner = attacker.unit()

            Events.fire(BuildingBulletDestroyEvent(build, bullet))

            assertEquals(1, attackerData.currentBuildAttackCount, "the bullet's owner must be credited")
            assertEquals(
                0,
                bystanderData.currentBuildAttackCount,
                "a player who did not fire the bullet must not be credited"
            )
        } finally {
            leavePlayer(attacker)
            leavePlayer(bystander)
        }
    }
}
