package essential.core.service.contribution

import arc.struct.IntFloatMap
import arc.struct.IntMap
import arc.struct.IntSet
import arc.util.Log
import arc.util.Timer
import essential.common.database.data.PlayerData
import essential.common.database.data.insertContributions
import essential.common.offlinePlayers
import essential.common.players
import essential.common.util.findPlayerData
import essential.core.Main.Companion.scope
import essential.core.service.contribution.ContributionService.Companion.conf
import kotlinx.coroutines.launch
import ksp.event.Event
import mindustry.Vars
import mindustry.ai.types.CommandAI
import mindustry.content.Items
import mindustry.game.EventType.*
import mindustry.gen.Building
import mindustry.gen.Groups
import mindustry.gen.Unit
import mindustry.type.Category
import mindustry.type.Item
import mindustry.world.Block
import mindustry.world.blocks.ConstructBlock
import mindustry.world.blocks.production.Drill
import mindustry.world.blocks.production.GenericCrafter

// --- Per-game scratch state (cleared on WorldLoadEvent) ---

/** Tile position (Building.pos()) -> owner uuid. Who placed the building. */
private val tileOwner = mutableMapOf<Int, String>()

/** Unit id -> uuid of the player whose factory produced it. */
private val unitProducer = mutableMapOf<Int, String>()

/** Unit id -> uuid of the player currently/last controlling it (direct possession). */
private val unitController = mutableMapOf<Int, String>()

/** Building positions already scored for the one-time factory-build bonus. */
private val scoredFactories = mutableSetOf<Int>()

/**
 * Tile position -> the last per-second output pollProduction measured for it while it was still a live
 * drill or crafter. The engine swaps a building for a ConstructBlock proxy before BlockBuildEndEvent
 * fires for a deconstruction, so by the time that handler runs, tile.build is a ConstructBuild and
 * estimateOutputPerSecond(it) can only ever see 0.0 (task-070) - the real building's state is gone, not
 * just uncomputed. This is the closest thing to it still available: the rate observed at most one poll
 * tick ago, which pollProduction already computes for every scoring building every second anyway.
 */
private val lastOutputPerSecond = mutableMapOf<Int, Double>()

private var timerScheduled = false

private fun resetGameState() {
    tileOwner.clear()
    unitProducer.clear()
    unitController.clear()
    scoredFactories.clear()
    lastOutputPerSecond.clear()
    for (data in players) data.currentContribution = 0.0
    for (data in offlinePlayers) data.currentContribution = 0.0
}

/** Resolve a uuid to its (online or recently-offline) PlayerData. */
private fun ownerData(uuid: String?): PlayerData? {
    if (uuid == null) return null
    return findPlayerData(uuid) ?: offlinePlayers.find { it.uuid == uuid }
}

private fun addScoreData(data: PlayerData?, amount: Double) {
    if (amount == 0.0) return
    data?.let { it.currentContribution += amount }
}

private fun addScore(uuid: String?, amount: Double) = addScoreData(ownerData(uuid), amount)

/** Sum of a block's item build cost. */
private fun resourceCost(block: Block): Int {
    var sum = 0
    block.requirements?.forEach { sum += it.amount }
    return sum
}

/** Sum of a unit type's build cost. */
private fun unitCost(type: mindustry.type.UnitType): Int {
    var sum = 0
    type.getTotalRequirements()?.forEach { sum += it.amount }
    return sum
}

/**
 * "turret" in the exemption list has to mean the block category, not a name substring: real turret
 * names (duo, salvo, lancer, hail...) do not contain the word "turret" - only the two repair turrets do
 * - so a substring test exempted almost no real turret and charged every one its full build cost as a
 * negative score, the opposite of what the config comment promises (task-068). "conveyor", "duct" and
 * "wall" stay a name-substring test: every vanilla block in those categories already carries the word in
 * its name (titanium-conveyor, plastic-duct, copper-wall), so that half was never broken.
 */
private fun isPenaltyExempt(block: Block): Boolean = conf.resourcePenaltyExempt.any {
    if (it == "turret") block.category == Category.turret else block.name.contains(it)
}

/** Stored amount of [item] in the team's first core, 0 if none. */
private fun coreItem(building: Building, item: Item): Int {
    val core = building.team().data().core() ?: return 0
    return core.items?.get(item) ?: 0
}

/** Mining multiplier: 1.0 until the team core holds [coreThreshold] of both copper and lead, then reduced. */
private fun miningMultiplier(building: Building): Double {
    val reached = coreItem(building, Items.copper) >= conf.coreThreshold &&
            coreItem(building, Items.lead) >= conf.coreThreshold
    return if (reached) conf.postThresholdMultiplier else 1.0
}

// --- Event handlers ---

@Event
fun worldLoad(event: WorldLoadEvent) {
    if (!conf.enabled) return
    resetGameState()
}

@Event
fun blockBuildEnd(event: BlockBuildEndEvent) {
    if (!conf.enabled) return
    val unit = event.unit ?: return
    if (!unit.isPlayer) return
    val player = unit.player ?: return
    val tile = event.tile ?: return
    val block = tile.block() ?: return
    val pos = tile.build?.pos() ?: tile.pos()

    if (!event.breaking) {
        // Record ownership.
        tileOwner[pos] = player.uuid()
        // A producer at this position that was removed some other way (killed, an Undo rollback, a raw
        // setBlock) never went through the breaking branch below, so its rate could still be sitting
        // here. Whatever gets built now starts with a clean slate rather than inheriting a dead
        // building's output.
        lastOutputPerSecond.remove(pos)

        // First-build factory bonus.
        val factoryScore = conf.factoryBuildScore[block.name]
        if (factoryScore != null && scoredFactories.add(pos)) {
            addScore(player.uuid(), factoryScore.toDouble())
        }

        // Build resource penalty (skip exempt blocks: conveyors, walls, turrets).
        if (!Vars.state.rules.infiniteResources && !isPenaltyExempt(block)) {
            addScore(player.uuid(), -resourceCost(block) * conf.buildPenaltyMultiplier)
        }
    } else {
        // Self-deconstruction of a resource producer: subtract its last observed per-second output.
        //
        // `block` (tile.block()) is already the ConstructBlock proxy here, not the drill or crafter that
        // is being removed - the engine swaps it in before this event fires (task-070's other half: it is
        // also the wrong block for the exemption check below, not just for the output estimate). The real
        // block the proxy is tearing down is ConstructBuild.current, still set at this point.
        val owner = player.uuid()
        val build = tile.build
        val realBlock = (build as? ConstructBlock.ConstructBuild)?.current ?: block
        if (build != null && !isPenaltyExempt(realBlock)) {
            val perSec = lastOutputPerSecond[pos] ?: 0.0
            if (perSec > 0.0) {
                addScore(owner, -perSec * miningMultiplier(build))
            }
        }
        tileOwner.remove(pos)
        scoredFactories.remove(pos)
        lastOutputPerSecond.remove(pos)
    }
}

@Event
fun unitCreate(event: UnitCreateEvent) {
    if (!conf.enabled) return
    val spawner = event.spawner ?: return
    val producer = tileOwner[spawner.pos()] ?: return
    unitProducer[event.unit.id()] = producer
}

@Event
fun unitControl(event: UnitControlEvent) {
    if (!conf.enabled) return
    // Null on release: InputHandler.unitControl fires this event straight from its clearUnit branch.
    // The field is arc.util.Nullable, which Kotlin does not read, so the non-null type compiled to an
    // assertion that threw on the game thread every time a player let go of a unit.
    val unit = event.unit ?: return
    // Player took direct control of a unit; remember the controller.
    unitController[unit.id()] = event.player.uuid()
}

@Event
fun buildDamage(event: BuildDamageEvent) {
    if (!conf.enabled) return
    val bullet = event.source ?: return
    val owner = bullet.owner as? Unit ?: return
    val building = event.build ?: return

    // Only score damage dealt to enemy buildings.
    if (building.team() == owner.team()) return

    // Value of the damage: scaled by the building's resource cost relative to its max health.
    val maxHp = building.maxHealth()
    if (maxHp <= 0f) return
    // bullet, not event.source: Building.bulletDamageEvent is one reused instance that set()
    // overwrites, so a listener ahead of this one that damages a building swaps it under us.
    val value = (bullet.damage() / maxHp) * resourceCost(building.block)
    if (value <= 0.0) return

    // Identify the controlling player.
    val attacker: String? = when {
        owner.isPlayer -> owner.player?.uuid()
        owner.controller() is CommandAI -> unitController[owner.id()] // best-effort: direct-control history
        else -> null
    }

    addScore(attacker, value.toDouble())

    // Reward the unit's producer with 50% (1.5x total when attacker == producer).
    val producer = unitProducer[owner.id()]
    addScore(producer, value.toDouble() * 0.5)
}

@Event
fun unitDestroy(event: UnitDestroyEvent) {
    if (!conf.enabled) return
    val id = event.unit.id()
    // A controlled unit died: penalize its controller by its production value (resource cost).
    val controller = unitController[id]
    if (controller != null) {
        addScore(controller, -unitCost(event.unit.type()).toDouble())
    }
    unitController.remove(id)
    unitProducer.remove(id)
}

@Event
fun gameOver(event: GameOverEvent) {
    if (!conf.enabled) return
    val mode = when {
        Vars.state.rules.pvp -> "pvp"
        Vars.state.rules.attackMode -> "attack"
        else -> "survival"
    }
    val mapName = Vars.state.map?.plainName()
    // Scores read here, on the game thread, before resetGameState zeroes them. One transaction for the
    // whole game rather than a coroutine per player: forty of those at once queued on a five-connection
    // pool, and a failure was swallowed without a line.
    val scores = (players + offlinePlayers).distinctBy { it.uuid }.map { it to it.currentContribution }
    scope.launch {
        try {
            insertContributions(scores, mode, mapName)
        } catch (e: Exception) {
            Log.err("Failed to save this game's contribution scores (${scores.size} players)", e)
        }
    }
    resetGameState()
}

/**
 * No-arg registration hook (called once by the generated registrar). Schedules the
 * per-second polling loop for mining, item production and power generation.
 */
@Event
fun startSecondTimer() {
    if (timerScheduled) return
    timerScheduled = true
    Timer.schedule({
        if (!conf.enabled || !Vars.state.isPlaying) return@schedule
        pollProduction()
    }, 0f, 1f)
}

/** Estimate ores/items produced per second by a drill or crafter building. */
private fun estimateOutputPerSecond(build: Building): Double {
    when (build) {
        is Drill.DrillBuild -> {
            val drill = build.block as? Drill ?: return 0.0
            val item = build.dominantItem ?: return 0.0
            if (item == Items.coal) return 0.0
            val drillTime = drill.getDrillTime(item)
            if (drillTime <= 0f) return 0.0
            // dominantItems ore tiles produce that many items per drill cycle.
            return (build.dominantItems * 60.0 / drillTime) * build.warmup
        }
        is GenericCrafter.GenericCrafterBuild -> {
            val crafter = build.block as? GenericCrafter ?: return 0.0
            val outputs = crafter.outputItems ?: return 0.0
            if (crafter.craftTime <= 0f) return 0.0
            var total = 0.0
            for (stack in outputs) {
                total += stack.amount * 60.0 / crafter.craftTime
            }
            return total * build.warmup
        }
        else -> return 0.0
    }
}

// internal, not private: task-070/task-072's regression tests drive a real poll tick directly rather
// than waiting on the real one-second Timer, which would make them slow and share state with whatever
// else the suite has scheduled on it.
internal fun pollProduction() {
    // ownerData() resolves a uuid to a PlayerData with two linear scans - players then offlinePlayers.
    // Called once per scoring building here, every second, that cost buildings x players string
    // comparisons every poll tick (task-072). Resolved once per tick into a map instead: building the
    // map costs O(players), and every building's lookup afterwards is O(1) - the same shape tileOwner
    // already uses for position -> uuid, just for the second hop, uuid -> PlayerData.
    val ownerLookup = buildMap<String, PlayerData> {
        for (data in offlinePlayers) put(data.uuid, data)
        for (data in players) put(data.uuid, data) // online overrides offline, same preference as ownerData()
    }

    Groups.build.forEach { build ->
        val pos = build.pos()
        val owner = tileOwner[pos]?.let { ownerLookup[it] }

        // Mining (drills). Also the source for lastOutputPerSecond (task-070): written every tick a
        // drill is alive, whatever it is currently producing, so a self-deconstruction penalty never
        // reads a rate from a building that stopped mining a while before it was torn down.
        if (build is Drill.DrillBuild) {
            val perSec = estimateOutputPerSecond(build)
            lastOutputPerSecond[pos] = perSec
            if (perSec > 0.0) {
                addScoreData(owner, perSec * conf.miningPerOre * miningMultiplier(build))
            }
        }

        // Item production (crafters). Same lastOutputPerSecond bookkeeping as drills above.
        if (build is GenericCrafter.GenericCrafterBuild) {
            val perSec = estimateOutputPerSecond(build)
            lastOutputPerSecond[pos] = perSec
            val crafter = build.block as? GenericCrafter
            val outputs = crafter?.outputItems
            if (crafter != null && outputs != null && crafter.craftTime > 0f && perSec > 0.0) {
                for (stack in outputs) {
                    val stackPerSec = stack.amount * 60.0 / crafter.craftTime * build.warmup
                    if (stackPerSec <= 0.0) continue
                    addScoreData(owner, itemScore(stack.item, build) * stackPerSec)
                }
            }
        }

        // Power generation.
        if (build.block.outputsPower) {
            val prodPerTick = build.getPowerProduction()
            if (prodPerTick > 0f) {
                addScoreData(owner, prodPerTick * 60.0 * conf.powerScoreRatio)
            }
        }
    }
}

/** Score per produced item; titanium switches on the team core titanium threshold. */
private fun itemScore(item: Item, building: Building): Double {
    if (item == Items.titanium) {
        return if (coreItem(building, Items.titanium) >= conf.titaniumThreshold)
            conf.titaniumScoreAfterThreshold.toDouble()
        else
            conf.titaniumScoreBeforeThreshold.toDouble()
    }
    return (conf.itemProduceScore[item.name] ?: 0).toDouble()
}
