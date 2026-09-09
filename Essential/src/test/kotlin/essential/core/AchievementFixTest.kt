package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.pumpApp
import essential.core.service.achievements.Achievement
import essential.core.service.achievements.blockBuildEnd as achievementBlockBuildEnd
import essential.core.service.achievements.buildingBulletDestroy as achievementBuildingBulletDestroy
import essential.core.service.achievements.unitBulletDestroy as achievementUnitBulletDestroy
import mindustry.Vars.state
import mindustry.Vars.world
import mindustry.content.Blocks
import mindustry.content.UnitTypes
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Bullet
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the achievements findings from the 2026-09-10 backlog run that change observable behaviour:
 * task-096 (self-award via a mutating predicate), task-098 (Creator unreachable in its own source mode),
 * task-097 (turret detection by block type), and the task-101/102 attribution-family migration to
 * bullet-owner-based crediting.
 */
class AchievementFixTest {
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

    @AfterTest
    fun drain() {
        pumpApp()
    }

    // task-096: success() must be a pure predicate. Asteroids/Transcendence used to write data.status as a
    // side effect of merely being asked "is this hidden achievement visible" - exactly what /ach does for
    // every hidden achievement on every call - which marked the map cleared without a win ever happening.
    //
    // A real end-to-end test (call success(), assert no mutation) can't distinguish fixed from buggy here:
    // the mutation only ever ran when Achievement.mapHash matched one of two hardcoded MD5s of the
    // operator's actual map files, which aren't in this repo and can't be reproduced (MD5 preimage). Off
    // those specific maps the buggy success() already returned false without mutating, so the test would
    // pass whether the bug was present or fixed. Testing the structural fact the fix actually makes true -
    // neither achievement declares its own success() any more, both fall through to the pure base
    // predicate - is the part that's both true-to-the-fix and independent of any map file.
    @Test
    fun asteroidsAndTranscendenceDoNotOverrideSuccess() {
        listOf(Achievement.Asteroids, Achievement.Transcendence).forEach { achievement ->
            val overridesSuccess = achievement.javaClass.declaredMethods.any {
                it.name == "success" && it.parameterCount == 1
            }
            assertTrue(
                !overridesSuccess,
                "$achievement must not override success() - the override was the mutating predicate that " +
                    "awarded the map for free from a plain visibility check."
            )
        }
    }

    // task-098: Creator's only source (achievementSweep's infiniteResources branch) is exactly the rules
    // mode the base success() used to refuse in, making Creator unreachable end to end.
    @Test
    fun creatorIsReachableInSandboxMode() {
        val (player, data) = newPlayer()
        val wasInfinite = state.rules.infiniteResources
        try {
            state.rules.infiniteResources = true
            data.status["record.time.sandbox"] = Achievement.Creator.value().toString()
            data.achievementStatus.remove("creator")

            assertTrue(
                Achievement.Creator.success(data),
                "Creator must be earnable in the one game mode that ever increments record.time.sandbox."
            )
        } finally {
            state.rules.infiniteResources = wasInfinite
            leavePlayer(player)
        }
    }

    // task-097: NoTurretsClear/DuoTurretSurvival used to gate on a block-name substring that only ever
    // matched the repair point (a support block), never a real weapon turret. Pin the content fact the fix
    // depends on directly, since the achievement's own status write is only reachable through gameover's
    // isWin branch (a full win simulation is deliberately not attempted here) - if Mindustry's
    // turret hierarchy ever changes shape, this fails loudly instead of the achievement silently regressing
    // back to "only repair-turret counts".
    @Test
    fun onlyRealWeaponTurretsExtendBaseTurret() {
        assertTrue(
            Blocks.duo is mindustry.world.blocks.defense.turrets.BaseTurret,
            "duo is a real weapon turret and must be caught by the is-BaseTurret check"
        )
        assertTrue(
            Blocks.scatter is mindustry.world.blocks.defense.turrets.BaseTurret,
            "scatter is a real weapon turret and must be caught by the is-BaseTurret check"
        )
        assertTrue(
            Blocks.repairTurret !is mindustry.world.blocks.defense.turrets.BaseTurret,
            "repair-turret is a support block, not a weapon, and must not fail NoTurretsClear/DuoTurretSurvival"
        )
    }

    // task-097, exercising blockBuildEnd directly: placing a repair-turret must not be indistinguishable
    // from placing a real turret to a caller that can observe the achievement machinery running without
    // throwing - the achievement's own flags are file-private, so this pins that the handler accepts a
    // repair-turret build event and does not crash walking event.tile.block() through the BaseTurret check.
    @Test
    fun blockBuildEndAcceptsARepairTurretPlacementWithoutFailing() {
        val (player, data) = newPlayer()
        val unit = spawnControlledUnit(player, UnitTypes.mono)
        try {
            val tile = world.tile(60, 60)
            tile.setBlock(Blocks.repairTurret, player.team(), 0)
            achievementBlockBuildEnd(EventType.BlockBuildEndEvent(tile, unit, player.team(), false, null))
            tile.setBlock(Blocks.air)
        } finally {
            unit.remove()
            leavePlayer(player)
        }
    }

    // task-101/task-102 attribution family: TurretMultiKill used to gate on the credited player's own
    // controlled unit having a type name containing "turret" - impossible, since turrets are blocks, not
    // units - so it could never fire. It also moved from UnitDestroyEvent (no killer) to
    // UnitBulletDestroyEvent (bullet.owner is the killer), so this fires the new event with a real owner.
    @Test
    fun turretMultiKillAccumulatesFromBulletOwnerWithoutAnImpossibleGate() {
        val (player, data) = newPlayer()
        val shooter = spawnControlledUnit(player, UnitTypes.dagger)
        val victim = UnitTypes.dagger.spawn(Team.crux, player.x, player.y)
        try {
            data.status["record.turret.multikill.current"] = "4"
            data.status.remove("record.turret.multikill")
            data.achievementStatus.remove("turretmultikill")

            val bullet = Bullet.create()
            bullet.owner = shooter
            achievementUnitBulletDestroy(EventType.UnitBulletDestroyEvent(victim, bullet))

            assertEquals(
                "1",
                data.status["record.turret.multikill"],
                "The fifth kill attributed to the same bullet owner must award TurretMultiKill without the " +
                    "player piloting anything named turret."
            )
        } finally {
            shooter.remove()
            victim.remove()
            leavePlayer(player)
        }
    }

    // Same event, but the bullet's owner is not a player (an AI-controlled unit) - must credit nobody
    // rather than falling back to the old "everyone on the other team" behaviour.
    @Test
    fun unitBulletDestroyCreditsNobodyWhenTheOwnerIsNotAPlayer() {
        val (player, data) = newPlayer()
        val aiShooter = UnitTypes.dagger.spawn(Team.sharded, player.x, player.y)
        val victim = UnitTypes.dagger.spawn(Team.crux, player.x, player.y)
        try {
            data.status["record.turret.multikill.current"] = "4"
            data.status.remove("record.turret.multikill")

            val bullet = Bullet.create()
            bullet.owner = aiShooter
            achievementUnitBulletDestroy(EventType.UnitBulletDestroyEvent(victim, bullet))

            assertEquals(
                "4",
                data.status["record.turret.multikill.current"],
                "A kill with no player-controlled bullet owner must not credit an unrelated online player."
            )
        } finally {
            aiShooter.remove()
            victim.remove()
            leavePlayer(player)
        }
    }

    // task-101: CrawlerBlockDestroyer used to check the DESTROYED UNIT's type name against
    // "wall"/"turret"/"factory" inside a UnitDestroyEvent handler, which never carries a block - always
    // false. Moved to BuildingBulletDestroyEvent, whose bullet carries a real owner (a crawler's death
    // explosion is a genuine shootOnDeath Weapon/Bullet, verified by decompiling UnitTypes - see notes).
    @Test
    fun crawlerBlockDestroyerCreditsTheControllingPlayer() {
        val (player, data) = newPlayer()
        val crawler = spawnControlledUnit(player, UnitTypes.crawler)
        try {
            data.status.remove("record.crawler.block.destroy")
            data.achievementStatus.remove("crawlerblockdestroyer")

            val tile = world.tile(61, 61)
            tile.setBlock(Blocks.copperWall, Team.crux, 0)
            val build = tile.build ?: error("copper wall did not build")

            val bullet = Bullet.create()
            bullet.owner = crawler
            achievementBuildingBulletDestroy(EventType.BuildingBulletDestroyEvent(build, bullet))
            tile.setBlock(Blocks.air)

            assertEquals(
                "1",
                data.status["record.crawler.block.destroy"],
                "A block destroyed by the player's own crawler must be credited to that player."
            )
        } finally {
            crawler.remove()
            leavePlayer(player)
        }
    }

    // Same event, but the bullet's owner is not a crawler - must not credit CrawlerBlockDestroyer at all.
    @Test
    fun buildingBulletDestroyIgnoresNonCrawlerOwners() {
        val (player, data) = newPlayer()
        val dagger = spawnControlledUnit(player, UnitTypes.dagger)
        try {
            data.status.remove("record.crawler.block.destroy")

            val tile = world.tile(62, 62)
            tile.setBlock(Blocks.copperWall, Team.crux, 0)
            val build = tile.build ?: error("copper wall did not build")

            val bullet = Bullet.create()
            bullet.owner = dagger
            achievementBuildingBulletDestroy(EventType.BuildingBulletDestroyEvent(build, bullet))
            tile.setBlock(Blocks.air)

            assertNull(
                data.status["record.crawler.block.destroy"],
                "Only a crawler's own bullet should ever credit this counter."
            )
        } finally {
            dagger.remove()
            leavePlayer(player)
        }
    }

    private fun spawnControlledUnit(player: mindustry.gen.Player, type: mindustry.type.UnitType): mindustry.gen.Unit {
        val unit = type.spawn(player.team(), player.x, player.y)
        unit.controller(player)
        player.unit(unit)
        return unit
    }
}
