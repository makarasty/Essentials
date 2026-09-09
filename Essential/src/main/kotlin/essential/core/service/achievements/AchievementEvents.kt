package essential.core.service.achievements

import arc.Core
import arc.util.Log
import arc.util.Timer
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.database.data.getPlayerAchievements
import essential.common.event.CustomEvents
import essential.common.offlinePlayers
import essential.common.players
import essential.common.pluginData
import essential.common.systemTimezone
import essential.common.util.findPlayerData
import kotlinx.datetime.daysUntil
import kotlinx.datetime.monthsUntil
import kotlinx.datetime.toLocalDateTime
import essential.core.Main.Companion.scope
import kotlinx.coroutines.launch
import ksp.event.Event
import mindustry.Vars.state
import mindustry.content.Blocks
import mindustry.content.Planets
import mindustry.content.UnitTypes
import mindustry.game.EventType.*
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Unit
import mindustry.world.blocks.defense.turrets.BaseTurret
import mindustry.world.blocks.power.PowerGraph
import java.util.*
import kotlin.time.Clock

private var isNoMiningFailed = false
private var isNoPowerFailed = false
private var isLowPowerFailed = false
private var isNoTurretsFailed = false
private var isFlareOnlyFailed = false
private var isDuoTurretFailed = false

/**
 * Players who left the current pvp game, with the team they left on, consumed by [gameover].
 *
 * The award cannot be made in the leave handler: whether the team lost is only known at game over,
 * and by then the player is out of `players`.
 */
private val pvpLeavers = LinkedHashMap<String, Pair<PlayerData, Team>>()

/** Executes achievement initialization after the core player-data load flow. */
object AchievementHooks {
    fun processPlayerDataLoad(playerData: PlayerData) {
        if (playerData.temporary) return

        scope.launch {
            val completed = try {
                getPlayerAchievements(playerData).map { it.achievementName }
            } catch (e: Exception) {
                Log.err("Failed to load achievements for ${playerData.name}", e)
                return@launch
            }

            Core.app.post {
                completed.forEach { name ->
                    if (!playerData.achievementStatus.contains(name)) playerData.achievementStatus.add(name)
                }

                for (achievement in Achievement.entries) {
                    if (achievement.isHidden) continue
                    try {
                        if (achievement.success(playerData)) {
                            achievement.set(playerData)
                        }
                    } catch (e: Exception) {
                        Log.err("Failed to evaluate achievement ${achievement.name} for ${playerData.name}", e)
                    }
                }
            }
        }
    }

    fun awardVotingBan(playerData: PlayerData) {
        Achievement.VotingBan.set(playerData)
    }
}

@Event
fun blockBuildEnd(event: BlockBuildEndEvent) {
    val unit = event.unit ?: return
    if (unit.isPlayer) {
        val player = unit.player
        val data: PlayerData? = if (player != null) findPlayerData(player.uuid()) else null
        if (data != null) {
            if (Achievement.Builder.success(data)) {
                Achievement.Builder.set(data)
            }
            if (Achievement.Deconstructor.success(data)) {
                Achievement.Deconstructor.set(data)
            }

            // Check for a water extractor built on water tiles
            if (!event.breaking && event.tile.block().name == "water-extractor" && event.tile.floor().isLiquid) {
                val count = data.status.getOrDefault("record.build.waterextractor", "0").toInt() + 1
                data.status["record.build.waterextractor"] = count.toString()
                if (Achievement.WaterExtractor.success(data)) {
                    Achievement.WaterExtractor.set(data)
                }
            }

            // Check for power nodes for LowPowerClear achievement
            if (!event.breaking && (event.tile.block().name == "power-node-large" || event.tile.block().name == "surge-tower")) {
                // Power node large / surge tower has capacity > 2k, mark the achievement as unachievable
                isLowPowerFailed = true
            }

            // Check for turrets for NoTurretsClear achievement. No real weapon turret's block name
            // contains "turret" - repair-turret (a support block, not a weapon, extends Block directly)
            // is the only block that ever matched, so this used to fail the achievement for building a
            // repair point and never for an actual turret. Every weapon turret extends BaseTurret.
            if (!event.breaking && event.tile.block() is BaseTurret) {
                isNoTurretsFailed = true
            }

            // Check for power generators for NoPowerClear achievement
            if (!event.breaking && (
                        event.tile.block().name.contains("generator") ||
                                event.tile.block().name.contains("solar-panel") ||
                                event.tile.block().name.contains("rtg") ||
                                event.tile.block().name.contains("reactor")
                        )
            ) {
                isNoPowerFailed = true
            }

            // Check for duo turrets for DuoTurretSurvival achievement. Same defect as NoTurretsClear
            // above: only repair-turret ever matched the "turret" substring, so any real non-duo turret
            // went undetected.
            if (!event.breaking && event.tile.block() != Blocks.duo && event.tile.block() is BaseTurret) {
                isDuoTurretFailed = true
            }
        }
    }
}

@Event
fun gameover(event: GameOverEvent) {
    // Calculate PvP contribution points for each player
    if (state.rules.pvp) {
        val teamContributions = mutableMapOf<Team, Int>()
        val playerContributions = mutableMapOf<String, Int>()

        // Calculate the total contribution for each team and individual players
        players.forEach { data ->
            val contribution = data.currentUnitDestroyedCount * 10 +
                    data.currentBuildDestroyedCount * 5 +
                    data.currentBuildAttackCount * 3

            playerContributions[data.uuid] = contribution

            val team = data.player.team()
            teamContributions[team] = (teamContributions[team] ?: 0) + contribution
        }

        // Check for PvP contribution achievement
        players.forEach { data ->
            val playerContribution = playerContributions[data.uuid] ?: 0
            val teamContribution = teamContributions[data.player.team()] ?: 0
            val otherPlayersContribution = teamContribution - playerContribution

            // If a player's team lost, and player's contribution was more than double the rest of the team
            if (event.winner != data.player.team() &&
                data.player.team() != Team.derelict &&
                playerContribution > otherPlayersContribution * 2 &&
                otherPlayersContribution > 0
            ) {

                data.status["record.pvp.contribution"] = "1"
                if (Achievement.PvPContribution.success(data)) {
                    Achievement.PvPContribution.set(data)
                }
            }

            // Track PvP win streak
            if (event.winner === data.player.team()) {
                val streak = data.status.getOrDefault("record.pvp.win.streak.current", "0").toInt() + 1
                data.status["record.pvp.win.streak.current"] = streak.toString()

                if (streak >= 5) {
                    data.status["record.pvp.win.streak"] = "1"
                    if (Achievement.PvPWinStreak.success(data)) {
                        Achievement.PvPWinStreak.set(data)
                    }
                }

                // Track PvP wins on Serpulo
                if (state.rules.planet === Planets.serpulo) {
                    val winCount = data.status.getOrDefault("record.pvp.win.serpulo", "0").toInt() + 1
                    data.status["record.pvp.win.serpulo"] = winCount.toString()
                    if (Achievement.SerpuloPvPWin.success(data)) {
                        Achievement.SerpuloPvPWin.set(data)
                    }

                    // Update both planets win count
                    if (data.status.getOrDefault("record.pvp.win.erekir", "0").toInt() > 0) {
                        val bothCount = data.status.getOrDefault("record.pvp.win.both", "0").toInt() + 1
                        data.status["record.pvp.win.both"] = bothCount.toString()
                        if (Achievement.BothPlanetsPvPWin.success(data)) {
                            Achievement.BothPlanetsPvPWin.set(data)
                        }
                    }
                } else if (state.rules.planet === Planets.erekir) {
                    // Track PvP wins on Erekir
                    val winCount = data.status.getOrDefault("record.pvp.win.erekir", "0").toInt() + 1
                    data.status["record.pvp.win.erekir"] = winCount.toString()
                    if (Achievement.ErekirPvPWin.success(data)) {
                        Achievement.ErekirPvPWin.set(data)
                    }

                    // Update both planets win count
                    if (data.status.getOrDefault("record.pvp.win.serpulo", "0").toInt() > 0) {
                        val bothCount = data.status.getOrDefault("record.pvp.win.both", "0").toInt() + 1
                        data.status["record.pvp.win.both"] = bothCount.toString()
                        if (Achievement.BothPlanetsPvPWin.success(data)) {
                            Achievement.BothPlanetsPvPWin.set(data)
                        }
                    }
                }
            } else {
                // Reset win streak on loss
                data.status["record.pvp.win.streak.current"] = "0"

                // Track PvP defeat streak for other players
                val defeatStreak = data.status.getOrDefault("record.pvp.defeat.streak.current", "0").toInt() + 1
                data.status["record.pvp.defeat.streak.current"] = defeatStreak.toString()

                if (defeatStreak >= 5) {
                    data.status["record.pvp.defeat.streak"] = "1"
                    if (Achievement.PvPDefeatStreak.success(data)) {
                        Achievement.PvPDefeatStreak.set(data)
                    }
                }
            }
        }

        // Check for PvP underdog achievement
        players.forEach { data ->
            if (event.winner === data.player.team()) {
                // Count players on each team
                val teamCounts = mutableMapOf<Team, Int>()
                Groups.player.forEach { player ->
                    val team = player.team()
                    teamCounts[team] = (teamCounts[team] ?: 0) + 1
                }

                val winnerTeamCount = teamCounts[data.player.team()] ?: 0
                val largestEnemyTeamCount = teamCounts.filter { it.key != data.player.team() }
                    .maxByOrNull { it.value }?.value ?: 0

                // If the enemy team had 3 or more players than the winner team
                if (largestEnemyTeamCount >= winnerTeamCount + 3 && data.player.team() != Team.derelict) {
                    data.status["record.pvp.underdog"] = "1"
                    if (Achievement.PvPUnderdog.success(data)) {
                        Achievement.PvPUnderdog.set(data)
                    }
                }
            }
        }
    }

    players.forEach { data ->
        if (Achievement.Eliminator.success(data)) {
            Achievement.Eliminator.set(data)
        }
        if (Achievement.Lord.success(data)) {
            Achievement.Lord.set(data)
        }

        if (Achievement.Aggressor.success(data)) {
            Achievement.Aggressor.set(data)
        }
        val isWin = if (state.rules.attackMode) {
            event.winner === data.player.team() && state.rules.waveTeam.cores().isEmpty
        } else {
            event.winner === data.player.team() && !state.teams.playerCores().isEmpty
        }

        if (isWin) {
            // Asteroids/Transcendence identify their map by the MD5 of the loaded map file. That check
            // used to live inside success() and wrote data.status as a side effect of merely being asked
            // whether the achievement is hidden - see Achievement.kt. It now runs only here, on a real win.
            if (Achievement.mapHash == "7b032cc7815022be644d00a877ae0388") {
                data.status["record.map.clear.asteroids"] = "1"
            }
            if (Achievement.Asteroids.success(data)) {
                Achievement.Asteroids.set(data)
            }

            if (Achievement.mapHash == "f355b3d91d5d8215e557ff045b3864ef") {
                data.status["record.map.clear.transcendence"] = "1"
            }
            if (Achievement.Transcendence.success(data)) {
                Achievement.Transcendence.set(data)
            }

            // Check if all maps have been cleared
            if (data.status.containsKey("record.map.clear.asteroids") &&
                data.status.containsKey("record.map.clear.transcendence")
            ) {
                data.status["record.map.clear.all"] = "1"
                if (Achievement.AllMaps.success(data)) {
                    Achievement.AllMaps.set(data)
                }
            }

            // Increment map clear count for MapClearMaster achievement
            val clearCount = data.status.getOrDefault("record.map.clear.count", "0").toInt() + 1
            data.status["record.map.clear.count"] = clearCount.toString()
            if (Achievement.MapClearMaster.success(data)) {
                Achievement.MapClearMaster.set(data)
            }

            // Check for SoloMapClear achievement
            if (Groups.player.size() == 1 && state.rules.attackMode) {
                data.status["record.map.clear.solo"] = "1"
                if (Achievement.SoloMapClear.success(data)) {
                    Achievement.SoloMapClear.set(data)
                }
            }

            // Check for NoMiningClear achievement
            if (!isNoMiningFailed && state.rules.attackMode) {
                data.status["record.map.clear.nomining"] = "1"
                if (Achievement.NoMiningClear.success(data)) {
                    Achievement.NoMiningClear.set(data)
                }
            }

            // Check for NoPowerClear achievement
            if (!isNoPowerFailed && state.rules.attackMode) {
                data.status["record.map.clear.nopower"] = "1"
                if (Achievement.NoPowerClear.success(data)) {
                    Achievement.NoPowerClear.set(data)
                }
            }

            // Check for NoTurretsClear achievement
            if (!isNoTurretsFailed && state.rules.attackMode) {
                data.status["record.map.clear.noturrets"] = "1"
                if (Achievement.NoTurretsClear.success(data)) {
                    Achievement.NoTurretsClear.set(data)
                }
            }

            // Check for LowPowerClear achievement
            if (!isLowPowerFailed && state.rules.attackMode) {
                data.status["record.map.clear.lowpower"] = "1"
                if (Achievement.LowPowerClear.success(data)) {
                    Achievement.LowPowerClear.set(data)
                }
            }

            // Check for FlareOnlyClear achievement
            if (!isFlareOnlyFailed && state.rules.attackMode) {
                data.status["record.map.clear.flareonly"] = "1"
                if (Achievement.FlareOnlyClear.success(data)) {
                    Achievement.FlareOnlyClear.set(data)
                }
            }
        } else {
            // Reset defeat streak on win
            data.status["record.pvp.defeat.streak.current"] = "0"
        }
    }

    for ((data, team) in pvpLeavers.values) {
        if (event.winner != team) {
            val leaveCount = data.status.getOrDefault("record.pvp.leave.lose", "0").toInt() + 1
            data.status["record.pvp.leave.lose"] = leaveCount.toString()
            if (Achievement.LeaveAndLosePvP.success(data)) {
                Achievement.LeaveAndLosePvP.set(data)
            }
            // Nothing else will save this player: their row was written when they left, before this
            // counter moved, and the map entry below is the last reference to the object.
            scope.launch { data.update() }
        }
    }
    pvpLeavers.clear()
}

@Event
fun wave(event: WaveEvent) {
    players.forEach { data ->
        val value = data.status.getOrDefault("record.wave", "0").toInt() + 1
        data.status["record.wave"] = value.toString()
        if (Achievement.Defender.success(data)) {
            Achievement.Defender.set(data)
        }

        // DuoTurretSurvival progress
        if (!isDuoTurretFailed) {
            val duoWaves = data.status.getOrDefault("record.wave.duo", "0").toInt() + 1
            data.status["record.wave.duo"] = duoWaves.toString()
            if (Achievement.DuoTurretSurvival.success(data)) {
                Achievement.DuoTurretSurvival.set(data)
            }
        }
    }
}

@Event
fun achievementClear(event: CustomEvents.AchievementClear) {
    val bundle = Bundle(Bundle.resolve("bundles/achievements/bundle", Locale.forLanguageTag(event.playerData.player.locale().replace("_", "-"))))

    event.playerData.send(bundle, "event.achievement.success", bundle["achievement." + event.achievement.toString().lowercase()])
    players.forEach { data ->
        val b = Bundle(
            Bundle.resolve(
                "bundles/achievements/bundle",
                Locale.forLanguageTag(data.player.locale().replace("_", "-"))
            )
        )

        data.send(
            b,
            "event.achievement.success.other",
            event.playerData.name,
            b["achievement." + event.achievement.toString().lowercase()]
        )
    }
}

@Event
fun playerChat(event: PlayerChatEvent) {
    if (!event.message.startsWith("/")) {
        val data: PlayerData? = findPlayerData(event.player.uuid())
        if (data != null) {
            val value = data.status.getOrDefault("record.time.chat", "0").toInt() + 1
            data.status["record.time.chat"] = value.toString()
            if (Achievement.Chatter.success(data)) {
                Achievement.Chatter.set(data)
            }

            // Check for the Korean New Year message
            if (event.message.contains("새해 복")) {
                data.status["record.chat.newyear"] = "1"
                if (Achievement.NewYear.success(data)) {
                    Achievement.NewYear.set(data)
                }
            }

            // If the chat sender has "owner" permission, award MeetOwner to everyone
            if (data.permission == "owner") {
                players.forEach { otherPlayer ->
                    otherPlayer.status["record.time.meetowner"] = "60"
                    if (Achievement.MeetOwner.success(otherPlayer)) {
                        Achievement.MeetOwner.set(otherPlayer)
                    }
                }
            }
        }
    } else if (event.message.startsWith("/apm")) {
        // Display the current APM for testing
        val data: PlayerData? = findPlayerData(event.player.uuid())
        if (data != null) {
            // Use the new APMTracker to get detailed APM info
            val apmInfo = APMTracker.getAPMInfo(data)
            data.send(apmInfo)
        }
    }
}

@Event
fun unitChange(event: UnitChangeEvent) {
    if (event.player != null && event.unit != null) {
        val data: PlayerData? = findPlayerData(event.player.uuid())
        if (data != null) {
            if (state.rules.planet === Planets.serpulo && event.unit.type.name.equals("quad", true)) {
                data.status["record.unit.serpulo.quad"] = "1"
                if (Achievement.SerpuloQuad.success(data)) {
                    Achievement.SerpuloQuad.set(data)
                }
            }

            // Reset unit-specific achievement tracking when changing units
            data.status["record.turret.quill.kill.time"] = "0"
            data.status["record.turret.zenith.kill.time"] = "0"

            // Check for FlareOnlyClear achievement - fail if the player controls non-flare unit
            if (!event.unit.type.name.equals(
                    "flare",
                    true
                ) && event.unit.type.name != "alpha" && event.unit.type.name != "beta" && event.unit.type.name != "gamma"
            ) {
                isFlareOnlyFailed = true
            }
        }
    }
}

/**
 * TurretMultiKill, QuillKiller, ZenithKiller, OmuraHorizonKiller and ExplosionKiller (task-101, and the
 * attribution family task-102/083/092 belong to; the rule below is the one shared with
 * CoreEvent.kt's half). All five used to live in a UnitDestroyEvent handler that looped every
 * online player and credited whoever was on a different team than the kill, because UnitDestroyEvent
 * carries no killer at all (EventType.java only gives it a `unit` field - verified via javap).
 *
 * UnitBulletDestroyEvent does carry a killer for the actual majority of unit deaths - anything killed by a
 * weapon has a `Bullet`, and `Bullet.owner` names who fired it - so these five move here and credit only
 * the bullet's controlling player. Same attribution ContributionEvents.kt:160 already established for a
 * different counter family. A unit that dies to something other than a bullet (environmental damage,
 * poison, etc.) credits nobody rather than everybody: a deliberate narrowing, not an invented heuristic.
 */
@Event
fun unitBulletDestroy(event: UnitBulletDestroyEvent) {
    // Neither field carries an arc.util.Nullable annotation, but the pre-fix unitDestroy defensively
    // null-checked event.unit anyway despite the same lack of annotation on that event - matching that
    // caution here rather than trusting an unannotated Java platform type.
    val victim = event.unit ?: return
    val bullet = event.bullet ?: return
    val owner = bullet.owner as? Unit ?: return
    val player = owner.player ?: return
    if (victim.team() == player.team()) return
    val data = findPlayerData(player.uuid()) ?: return

    // Check for TurretMultiKill achievement: "destroy 5+ units simultaneously with a single bullet". No
    // unit's type name has ever contained "turret" (turrets are blocks), so the old
    // playerUnit.type.name.contains("turret") gate was always false and this could never fire; dropped
    // rather than replaced, since the achievement's own text has nothing to do with turrets. "Simultaneously
    // with a single bullet" means what it says: count by bullet id, resetting on a new bullet, rather than
    // accumulating lifetime kills (a splash-damage bullet can hit several units in the one explosion).
    val multiKillBulletId = bullet.id()
    val lastMultiKillBulletId = data.status.getOrDefault("record.turret.multikill.bullet", "-1").toIntOrNull()
    val multiKillCount = if (multiKillBulletId == lastMultiKillBulletId) {
        data.status.getOrDefault("record.turret.multikill.current", "0").toInt() + 1
    } else {
        1
    }
    data.status["record.turret.multikill.bullet"] = multiKillBulletId.toString()
    data.status["record.turret.multikill.current"] = multiKillCount.toString()
    if (multiKillCount >= 5) {
        data.status["record.turret.multikill"] = "1"
        if (Achievement.TurretMultiKill.success(data)) {
            Achievement.TurretMultiKill.set(data)
        }
    }

    // Check for QuillKiller achievement. Unreachable regardless of the dropped gate above:
    // mindustry.content.UnitTypes carries no "quill" unit in this engine version (v159.7, checked against
    // the full field list), so this can never match. Left as a name comparison rather than a type constant
    // because there is no UnitTypes.quill to reference.
    if (victim.type.name.equals("quill", true)) {
        val currentTime = System.currentTimeMillis()
        val lastKillTime = data.status.getOrDefault("record.turret.quill.kill.time", "0").toLong()
        val killCount = if (currentTime - lastKillTime < 10000) {
            data.status.getOrDefault("record.turret.quill.kill", "0").toInt() + 1
        } else {
            1
        }

        data.status["record.turret.quill.kill"] = killCount.toString()
        data.status["record.turret.quill.kill.time"] = currentTime.toString()

        if (killCount >= 5 && Achievement.QuillKiller.success(data)) {
            Achievement.QuillKiller.set(data)
        }
    }

    // Check for ZenithKiller achievement. UnitTypes.zenith is real, so this is matched by type rather than
    // by name.
    if (victim.type == UnitTypes.zenith) {
        val currentTime = System.currentTimeMillis()
        val lastKillTime = data.status.getOrDefault("record.turret.zenith.kill.time", "0").toLong()
        val killCount = if (currentTime - lastKillTime < 10000) {
            data.status.getOrDefault("record.turret.zenith.kill", "0").toInt() + 1
        } else {
            1
        }

        data.status["record.turret.zenith.kill"] = killCount.toString()
        data.status["record.turret.zenith.kill.time"] = currentTime.toString()

        if (killCount >= 30 && Achievement.ZenithKiller.success(data)) {
            Achievement.ZenithKiller.set(data)
        }
    }

    // Check for OmuraHorizonKiller achievement: "5+ horizon units simultaneously with a single bullet from
    // an Omura". owner.type is the unit that actually fired the shot - what "your controlled unit" means -
    // not whatever the player happens to be piloting when this listener runs. Same bullet-identity fix as
    // TurretMultiKill above: "simultaneously with a single bullet" is counted by bullet id.
    if (owner.type == UnitTypes.omura && victim.type.name.equals("horizon", true)) {
        val comboBulletId = bullet.id()
        val lastComboBulletId = data.status.getOrDefault("record.omura.horizon.kill.bullet", "-1").toIntOrNull()
        val comboKillCount = if (comboBulletId == lastComboBulletId) {
            data.status.getOrDefault("record.omura.horizon.kill.current", "0").toInt() + 1
        } else {
            1
        }
        data.status["record.omura.horizon.kill.bullet"] = comboBulletId.toString()
        data.status["record.omura.horizon.kill.current"] = comboKillCount.toString()
        if (comboKillCount >= 5) {
            data.status["record.omura.horizon.kill"] = "1"
            if (Achievement.OmuraHorizonKiller.success(data)) {
                Achievement.OmuraHorizonKiller.set(data)
            }
        }
    }

    // Check for ExplosionKiller achievement: "destroy 10+ units with explosion damage from your controlled
    // unit". The pre-fix code checked whether the DESTROYED unit (event.unit) was a crawler - i.e. credited
    // whoever's bullet happened to kill a crawler, which is unrelated to explosion damage and nothing to
    // do with "your controlled unit". A crawler's only weapon is its shootOnDeath explosion (verified by
    // decompiling mindustry.content.UnitTypes), so "explosion damage from your controlled unit" means the
    // credited player is piloting the crawler that just exploded - checked via owner.type, matching the
    // buildingBulletDestroy attribution below for the same unit.
    if (owner.type == UnitTypes.crawler) {
        val explosionKillCount = data.status.getOrDefault("record.explosion.kill.current", "0").toInt() + 1
        data.status["record.explosion.kill.current"] = explosionKillCount.toString()
        if (explosionKillCount >= 10) {
            data.status["record.explosion.kill"] = "1"
            if (Achievement.ExplosionKiller.success(data)) {
                Achievement.ExplosionKiller.set(data)
            }
            data.status["record.explosion.kill.current"] = "0"
        }
    }
}

/**
 * CrawlerBlockDestroyer: "destroy 5 blocks with a single crawler unit attack". The old implementation lived
 * inside [unitDestroy] and checked `event.unit.type.name` - the DESTROYED UNIT's type - against
 * "wall"/"turret"/"factory". A UnitDestroyEvent never carries a block: those substrings can only ever
 * match a unit's own type name, which they never do, so the achievement could not fire.
 *
 * A crawler's death explosion is a real `Weapon` with `shootOnDeath = true` (verified against the
 * decompiled `mindustry.content.UnitTypes` crawler definition, v159.7), so the block it destroys dies to a
 * genuine `Bullet` whose `owner` is the crawler - unlike [UnitDestroyEvent], which the rest of this file
 * already established carries no killer at all (task-102/083). `BuildingBulletDestroyEvent` is the correct
 * hook: it hands us the block and the bullet that killed it, and the bullet's owner is real attribution
 * the engine actually provides, not one this fix invents.
 */
@Event
fun buildingBulletDestroy(event: BuildingBulletDestroyEvent) {
    // Neither field carries an arc.util.Nullable annotation, but matching unitBulletDestroy's caution
    // against an unannotated Java platform type rather than trusting it.
    val build = event.build ?: return
    val bullet = event.bullet ?: return
    val owner = bullet.owner as? Unit ?: return
    if (owner.type != UnitTypes.crawler) return
    val player = owner.player ?: return
    // Without this, a player could farm the achievement by crawler-bombing their own team's or
    // derelict's blocks - the old UnitDestroyEvent-based code required event.unit.team() != player.team()
    // and that check was dropped along with the rest of that dead branch. Restored here.
    if (build.team() == player.team()) return
    val data = findPlayerData(player.uuid()) ?: return

    // "Destroy 5 blocks with a single crawler unit attack": a crawler has exactly one weapon (the
    // shootOnDeath explosion, fired once), so each crawler death is one attack and every block it
    // destroys in that blast shares the same Bullet instance. Counting by bullet id, not lifetime kills,
    // is what "with a single ... attack" actually means - reset rather than accumulate across attacks.
    val bulletId = bullet.id()
    val lastBulletId = data.status.getOrDefault("record.crawler.block.destroy.bullet", "-1").toIntOrNull()
    val count = if (bulletId == lastBulletId) {
        data.status.getOrDefault("record.crawler.block.destroy.current", "0").toInt() + 1
    } else {
        1
    }
    data.status["record.crawler.block.destroy.bullet"] = bulletId.toString()
    data.status["record.crawler.block.destroy.current"] = count.toString()

    if (count >= Achievement.CrawlerBlockDestroyer.value()) {
        data.status["record.crawler.block.destroy"] = "1"
        if (Achievement.CrawlerBlockDestroyer.success(data)) {
            Achievement.CrawlerBlockDestroyer.set(data)
        }
    }
}

@Event
fun updateSecond() {
    Timer.schedule({ achievementSweep() }, 0f, 1f)
}

/**
 * Groups.player, Groups.build and PlayerData.status all belong to the game loop. Arc's Timer already
 * posts task bodies to the application thread (Timer.update calls task.app.post), so in production the
 * caller is the game thread and this post only defers the work by a frame. It is left in place as cheap
 * insurance for any future caller that is not on that thread.
 */
internal fun achievementSweep() {
    Core.app.post {
        for (data in players) {
            // Track time played on different planets
            if (state.rules.planet === Planets.serpulo) {
                val value = data.status.getOrDefault("record.time.serpulo", "0").toInt() + 1
                data.status["record.time.serpulo"] = value.toString()
                if (Achievement.Serpulo.success(data)) {
                    Achievement.Serpulo.set(data)
                }
            } else if (state.rules.planet === Planets.erekir) {
                val value = data.status.getOrDefault("record.time.erekir", "0").toInt() + 1
                data.status["record.time.erekir"] = value.toString()
                if (Achievement.Erekir.success(data)) {
                    Achievement.Erekir.set(data)
                }
            } else if (state.rules.infiniteResources) {
                val value = data.status.getOrDefault("record.time.sandbox", "0").toInt() + 1
                data.status["record.time.sandbox"] = value.toString()
                if (Achievement.Creator.success(data)) {
                    Achievement.Creator.set(data)
                }
            }

            // Track time played on one map for LongPlayNoAfk achievement
            if (!data.afk) {
                val mapTime = data.status.getOrDefault("record.time.noafk", "0").toInt() + 1
                data.status["record.time.noafk"] = mapTime.toString()
                if (Achievement.LongPlayNoAfk.success(data)) {
                    Achievement.LongPlayNoAfk.set(data)
                }
            }

            // WarpServerDisconnect tracking - require 30 seconds of all warp servers being offline
            if (pluginData.data.warpBlock.isNotEmpty() && pluginData.data.warpBlock.all { !it.online }) {
                val warpOfflineTime = data.status.getOrDefault("record.warp.disconnect.duration", "0").toInt() + 1
                data.status["record.warp.disconnect.duration"] = warpOfflineTime.toString()
                if (warpOfflineTime >= 30) {
                    data.status["record.warp.disconnect"] = "1"
                    if (Achievement.WarpServerDisconnect.success(data)) {
                        Achievement.WarpServerDisconnect.set(data)
                    }
                }
            } else {
                data.status["record.warp.disconnect.duration"] = "0"
            }

            // APM calculation is now handled by APMTracker
        }

        // Check if any player unit is mining
        if (!isNoMiningFailed) {
            for (player in Groups.player) {
                if (player.team() == Team.sharded && player.unit()?.mining() == true) {
                    isNoMiningFailed = true
                    break
                }
            }
        }

        // Check team power metrics
        var totalPowerProduced = 0f
        var hasPower = false
        val checkedGraphs = mutableSetOf<PowerGraph>()
        for (build in Groups.build) {
            if (build.team() == Team.sharded && build.power != null) {
                val graph = build.power.graph
                if (graph != null) {
                    if (graph.lastPowerProduced > 0f || graph.lastPowerStored > 0f) {
                        hasPower = true
                    }
                    if (!checkedGraphs.contains(graph)) {
                        checkedGraphs.add(graph)
                        totalPowerProduced += graph.lastPowerProduced
                    }
                }
            }
        }

        if (hasPower) {
            isNoPowerFailed = true
        }
        if (totalPowerProduced > 2000f) {
            isLowPowerFailed = true
        }

        // Check for owner presence
        var isOwnerMeet = false
        for (data in players) {
            if (data.permission == "owner") {
                isOwnerMeet = true
                break
            }
        }

        if (isOwnerMeet) {
            for (data in players) {
                val value = data.status.getOrDefault("record.time.meetowner", "0").toInt() + 1
                data.status["record.time.meetowner"] = value.toString()
                if (Achievement.MeetOwner.success(data)) {
                    Achievement.MeetOwner.set(data)
                }
            }
        }
    }
}

@Event
fun playerJoin(event: PlayerJoin) {
    // Someone who came back and played the game out did not leave and lose it.
    pvpLeavers.remove(event.player.uuid())

    val data: PlayerData? = findPlayerData(event.player.uuid())
    if (data != null) {
        // Check for attendance achievement
        if (Achievement.Attendance.success(data)) {
            Achievement.Attendance.set(data)
        }

        // Calculate absence duration for Loyal achievements
        val lastLogout = data.lastLogoutDate
        if (lastLogout != null) {
            val current = Clock.System.now().toLocalDateTime(systemTimezone)
            val daysAbsent = lastLogout.date.daysUntil(current.date)
            val monthsAbsent = lastLogout.date.monthsUntil(current.date)

            if (daysAbsent >= 5) {
                data.status["record.login.loyal"] = "1"
                if (Achievement.Loyal.success(data)) {
                    Achievement.Loyal.set(data)
                }
            }

            if (monthsAbsent >= 6) {
                data.status["record.login.loyal.sixmonths"] = "1"
                if (Achievement.LoyalSixMonths.success(data)) {
                    Achievement.LoyalSixMonths.set(data)
                }
            }

            if (monthsAbsent >= 18) {
                data.status["record.login.loyal.oneyearsixmonths"] = "1"
                if (Achievement.LoyalOneYearSixMonths.success(data)) {
                    Achievement.LoyalOneYearSixMonths.set(data)
                }
            }
        }

        // Reset map-specific achievement flags
        data.status.remove("record.map.clear.nomining.failed")
        data.status.remove("record.map.clear.nopower.failed")
        data.status.remove("record.map.clear.noturrets.failed")
        data.status.remove("record.map.clear.lowpower.failed")
        data.status.remove("record.map.clear.flareonly.failed")

        // Reset time tracking for LongPlayNoAfk achievement
        data.status["record.time.noafk"] = "0"
        data.status["record.warp.disconnect.duration"] = "0"
    }
}

@Event
fun playerLeave(event: PlayerLeave) {
    if (!state.rules.pvp) return

    // Core's own playerLeave handler is registered first and has already moved this player out of
    // `players` and into `offlinePlayers`, so the lookup has to allow for both.
    val uuid = event.player.uuid()
    val data = findPlayerData(uuid) ?: offlinePlayers.find { it.uuid == uuid } ?: return

    // Derelict is not a side anyone can lose with, so leaving on it is not leaving a team behind.
    val team = event.player.team()
    if (team == Team.derelict) return

    // Keyed by uuid, so leaving several times in the same game still counts once.
    pvpLeavers[uuid] = data to team
}

@Event
fun worldLoadEnd(event: WorldLoadEndEvent) {
    // A game that ends without a GameOverEvent must not carry its leavers into the next one.
    pvpLeavers.clear()
    isNoMiningFailed = false
    isNoPowerFailed = false
    isLowPowerFailed = false
    isNoTurretsFailed = false
    isFlareOnlyFailed = false
    isDuoTurretFailed = false
}
