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

    // task-097, exercising blockBuildEnd directly and observing the real outcome. isNoTurretsFailed/
    // isDuoTurretFailed are file-private top-level vars (not reachable through any public API - the only
    // place they surface is gameover's win branch, which needs a full attack-mode win to exercise), so
    // this reads the compiled AchievementEventsKt class's static fields directly via reflection. That is
    // the actual pre-fix-vs-fixed behavioural difference: reverting the fix hunk in blockBuildEnd (back to
    // event.tile.block().name.contains("turret")) makes this fail, because it flips which of the two
    // builds trips each flag.
    @Test
    fun blockBuildEndFlagsRealTurretsNotTheRepairPoint() {
        val (player, data) = newPlayer()
        val unit = spawnControlledUnit(player, UnitTypes.mono)
        try {
            writeAchievementEventsFlag("isNoTurretsFailed", false)
            writeAchievementEventsFlag("isDuoTurretFailed", false)

            val repairTile = world.tile(65, 65)
            repairTile.setBlock(Blocks.repairTurret, player.team(), 0)
            achievementBlockBuildEnd(EventType.BlockBuildEndEvent(repairTile, unit, player.team(), false, null))
            repairTile.setBlock(Blocks.air)

            assertEquals(
                false, readAchievementEventsFlag("isNoTurretsFailed"),
                "Building a repair point (a support block, not a weapon) must not fail NoTurretsClear."
            )
            assertEquals(
                false, readAchievementEventsFlag("isDuoTurretFailed"),
                "Building a repair point must not fail DuoTurretSurvival either."
            )

            val duoTile = world.tile(66, 66)
            duoTile.setBlock(Blocks.duo, player.team(), 0)
            achievementBlockBuildEnd(EventType.BlockBuildEndEvent(duoTile, unit, player.team(), false, null))
            duoTile.setBlock(Blocks.air)

            assertEquals(
                true, readAchievementEventsFlag("isNoTurretsFailed"),
                "Building a real weapon turret (duo) must fail NoTurretsClear."
            )
            assertEquals(
                false, readAchievementEventsFlag("isDuoTurretFailed"),
                "Building duo itself is exempt - only a non-duo turret should fail DuoTurretSurvival."
            )

            val scatterTile = world.tile(67, 67)
            scatterTile.setBlock(Blocks.scatter, player.team(), 0)
            achievementBlockBuildEnd(EventType.BlockBuildEndEvent(scatterTile, unit, player.team(), false, null))
            scatterTile.setBlock(Blocks.air)

            assertEquals(
                true, readAchievementEventsFlag("isDuoTurretFailed"),
                "Building a non-duo real turret (scatter) must fail DuoTurretSurvival."
            )
        } finally {
            writeAchievementEventsFlag("isNoTurretsFailed", false)
            writeAchievementEventsFlag("isDuoTurretFailed", false)
            unit.remove()
            leavePlayer(player)
        }
    }

    private fun achievementEventsField(name: String) =
        Class.forName("essential.core.service.achievements.AchievementEventsKt")
            .getDeclaredField(name)
            .also { it.isAccessible = true }

    private fun readAchievementEventsFlag(name: String): Boolean = achievementEventsField(name).getBoolean(null)

    private fun writeAchievementEventsFlag(name: String, value: Boolean) {
        achievementEventsField(name).setBoolean(null, value)
    }

    // task-101/task-102 attribution family: TurretMultiKill used to gate on the credited player's own
    // controlled unit having a type name containing "turret" - impossible, since turrets are blocks, not
    // units - so it could never fire. It also moved from UnitDestroyEvent (no killer) to
    // UnitBulletDestroyEvent (bullet.owner is the killer). Per the opus reviewer's catch: the achievement is
    // "5+ units simultaneously with a single bullet", so this must count by bullet identity, not lifetime
    // kills - five victims sharing the same Bullet (one splash-damage explosion) is what the test simulates.
    @Test
    fun turretMultiKillNeedsFiveVictimsFromTheSameBullet() {
        val (player, data) = newPlayer()
        val shooter = spawnControlledUnit(player, UnitTypes.dagger)
        val victims = (1..5).map { UnitTypes.dagger.spawn(Team.crux, player.x, player.y) }
        try {
            data.status.remove("record.turret.multikill.current")
            data.status.remove("record.turret.multikill.bullet.current")
            data.status.remove("record.turret.multikill")
            data.achievementStatus.remove("turretmultikill")

            val bullet = Bullet.create()
            bullet.owner = shooter
            victims.forEach { achievementUnitBulletDestroy(EventType.UnitBulletDestroyEvent(it, bullet)) }

            // Achievement.TurretMultiKill.current() reads this same key against value()=5: a literal "1"
            // here (rather than the real count) would pass this assertion while leaving success() unable
            // to ever return true - checking achievementStatus is what actually proves it was awarded.
            assertEquals(
                "5",
                data.status["record.turret.multikill"],
                "Five victims destroyed by the same bullet must record the real count, not a flag."
            )
            assertTrue(
                data.achievementStatus.contains("turretmultikill"),
                "Five victims destroyed by the same bullet must award TurretMultiKill."
            )
        } finally {
            shooter.remove()
            victims.forEach { it.remove() }
            leavePlayer(player)
        }
    }

    // The same five kills, but split across two different bullets (a realistic non-splash spree) - must
    // not award the achievement, since it is specifically about one bullet, not a kill streak.
    @Test
    fun turretMultiKillResetsOnANewBullet() {
        val (player, data) = newPlayer()
        val shooter = spawnControlledUnit(player, UnitTypes.dagger)
        val victims = (1..5).map { UnitTypes.dagger.spawn(Team.crux, player.x, player.y) }
        try {
            data.status.remove("record.turret.multikill.current")
            data.status.remove("record.turret.multikill.bullet.current")
            data.status.remove("record.turret.multikill")
            data.achievementStatus.remove("turretmultikill")

            val firstBullet = Bullet.create()
            firstBullet.owner = shooter
            victims.take(4).forEach { achievementUnitBulletDestroy(EventType.UnitBulletDestroyEvent(it, firstBullet)) }

            val secondBullet = Bullet.create()
            secondBullet.owner = shooter
            achievementUnitBulletDestroy(EventType.UnitBulletDestroyEvent(victims[4], secondBullet))

            assertNull(
                data.status["record.turret.multikill"],
                "Four kills from one bullet plus one from a different bullet is not five from a single bullet."
            )
            assertEquals(
                "1",
                data.status["record.turret.multikill.current"],
                "The new bullet must restart the count rather than continue the old bullet's tally."
            )
        } finally {
            shooter.remove()
            victims.forEach { it.remove() }
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
    // Per the opus reviewer's catch: "5 blocks with a single crawler unit attack" needs the same
    // bullet-identity counting as TurretMultiKill - five blocks sharing one Bullet (a crawler has exactly
    // one weapon, fired once, on death), not five blocks destroyed over a crawler-piloting career.
    @Test
    fun crawlerBlockDestroyerNeedsFiveBlocksFromTheSameAttack() {
        val (player, data) = newPlayer()
        val crawler = spawnControlledUnit(player, UnitTypes.crawler)
        val tiles = listOf(world.tile(70, 70), world.tile(71, 71), world.tile(72, 72), world.tile(73, 73), world.tile(74, 74))
        try {
            data.status.remove("record.crawler.block.destroy")
            data.status.remove("record.crawler.block.destroy.current")
            data.status.remove("record.crawler.block.destroy.bullet.current")
            data.achievementStatus.remove("crawlerblockdestroyer")

            val bullet = Bullet.create()
            bullet.owner = crawler
            tiles.forEach { tile ->
                tile.setBlock(Blocks.copperWall, Team.crux, 0)
                val build = tile.build ?: error("copper wall did not build")
                achievementBuildingBulletDestroy(EventType.BuildingBulletDestroyEvent(build, bullet))
                tile.setBlock(Blocks.air)
            }

            // Same current()-vs-flag trap as TurretMultiKill above: assert both the real count and that
            // the achievement was actually awarded, not just that some status key changed.
            assertEquals(
                "5",
                data.status["record.crawler.block.destroy"],
                "Five blocks destroyed by the same crawler explosion must record the real count, not a flag."
            )
            assertTrue(
                data.achievementStatus.contains("crawlerblockdestroyer"),
                "Five blocks destroyed by the same crawler explosion must award CrawlerBlockDestroyer."
            )
        } finally {
            crawler.remove()
            leavePlayer(player)
        }
    }

    // Per the opus reviewer's catch: the old UnitDestroyEvent-based CrawlerBlockDestroyer required the
    // destroyed content to be on a different team, and that check was dropped along with the rest of the
    // dead branch during the event migration. Without it a player could farm the achievement by
    // crawler-bombing their own team's blocks.
    @Test
    fun crawlerBlockDestroyerDoesNotCreditDestroyingYourOwnTeam() {
        val (player, data) = newPlayer()
        val crawler = spawnControlledUnit(player, UnitTypes.crawler)
        try {
            data.status.remove("record.crawler.block.destroy.current")
            data.status.remove("record.crawler.block.destroy.bullet.current")

            val tile = world.tile(75, 75)
            tile.setBlock(Blocks.copperWall, player.team(), 0)
            val build = tile.build ?: error("copper wall did not build")

            val bullet = Bullet.create()
            bullet.owner = crawler
            achievementBuildingBulletDestroy(EventType.BuildingBulletDestroyEvent(build, bullet))
            tile.setBlock(Blocks.air)

            assertNull(
                data.status["record.crawler.block.destroy.current"],
                "Destroying your own team's block with your own crawler must not progress the achievement."
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
