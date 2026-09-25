package essential.core.service.effect

import arc.Core
import arc.graphics.Color
import arc.graphics.Colors
import arc.util.Timer
import essential.common.database.data.PlayerData
import essential.common.players
import essential.core.Main.Companion.conf
import mindustry.Vars
import mindustry.content.Fx
import mindustry.entities.Effect
import mindustry.gen.Call
import mindustry.gen.Playerc
import kotlin.random.Random

/**
 * Hex effect colours parsed once instead of 20 times a second per player. Game thread only (effect()
 * runs from the task's Core.app.post), and shared safely: Call.effect only reads the colour's rgba.
 */
private val hexColorCache = HashMap<String, Color>()

class EffectSystem : Timer.Task() {
    class EffectPos(
        val player: Playerc,
        val effect: Effect,
        val rotate: Float,
        val color: Color,
        val random: IntRange? = null,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f
    )

    var buffer = ArrayList<EffectPos>()

    /** Which player the next pass starts at when the ceiling cuts it short. */
    private var groupCursor = 0

    @Volatile
    private var pending = false

    fun effect(data: PlayerData) {
        val effectColor = data.effectColor
        val color = if (effectColor != null) {
            Colors.get(effectColor) ?: hexColorCache.getOrPut(effectColor) { Color.valueOf(effectColor) }
        } else {
            data.player.color()
        }

        fun runEffect(effect: Effect) {
            buffer.add(EffectPos(data.player, effect, 0f, color))
        }

        fun runEffect(effect: Effect, size: Float) {
            buffer.add(EffectPos(data.player, effect, size, color))
        }

        fun runEffectAtRotate(effect: Effect, rotate: Float) {
            buffer.add(EffectPos(data.player, effect, rotate, color))
        }

        fun runEffectRandom(effect: Effect, range: IntRange) {
            buffer.add(EffectPos(data.player, effect, 0f, color, range))
        }

        fun runEffectRandomRotate(effect: Effect) {
            buffer.add(
                EffectPos(
                    data.player,
                    effect,
                    Random.nextFloat() * 360f,
                    color
                )
            )
        }

        fun runEffectAtRotateAndColor(
            effect: Effect,
            rotate: Float,
            customColor: Color
        ) {
            buffer.add(EffectPos(data.player, effect, rotate, customColor))
        }

        fun runEffectAtOffset(
            effect: Effect,
            offsetX: Float,
            offsetY: Float,
            rotate: Float,
            customColor: Color
        ) {
            buffer.add(
                EffectPos(data.player, effect, rotate, customColor, offsetX = offsetX, offsetY = offsetY)
            )
        }

        // If the unit is destroyed
        if (data.player.unit() == null) return

        val level = data.effectLevel?.toInt() ?: data.level

        when (level) {
            in 0..9 -> {}
            in 10..19 -> runEffect(Fx.freezing)
            in 20..29 -> runEffect(Fx.overdriven)
            in 30..39 -> {
                runEffect(Fx.burning)
                runEffect(Fx.melting)
            }

            in 40..49 -> runEffect(Fx.steam)
            in 50..59 -> runEffect(Fx.shootSmallSmoke)
            in 60..69 -> runEffect(Fx.mine)
            in 70..79 -> runEffect(Fx.explosion)
            in 80..89 -> runEffect(Fx.hitLaser)
            in 90..99 -> runEffect(Fx.crawlDust)
            in 100..109 -> runEffect(Fx.mineImpact)
            in 110..119 -> {
                runEffect(Fx.vapor)
                runEffect(Fx.hitBulletColor)
            }

            in 120..129 -> {
                runEffect(Fx.vapor)
                runEffect(Fx.hitBulletColor)
                runEffect(Fx.hitSquaresColor)
            }

            in 130..139 -> {
                runEffect(Fx.vapor)
                runEffect(Fx.hitLaserBlast)
            }

            in 140..149 -> {
                runEffect(Fx.smokePuff)
                runEffect(Fx.hitBulletColor)
            }

            in 150..159 -> {
                runEffect(Fx.smokePuff)
                runEffect(Fx.hitBulletColor)
                runEffect(Fx.hitSquaresColor)
            }

            in 160..169 -> {
                runEffect(Fx.smokePuff)
                runEffect(Fx.hitLaserBlast)
            }

            in 170..179 -> {
                runEffect(Fx.placeBlock, 1.8f)
                runEffect(Fx.spawn)
            }

            in 180..189 -> {
                runEffect(Fx.placeBlock, 1.8f)
                runEffect(Fx.spawn)
                runEffect(Fx.hitLaserBlast)
            }

            in 190..199 -> {
                runEffect(Fx.placeBlock, 1.8f)
                runEffect(Fx.spawn)
                runEffect(Fx.circleColorSpark)
            }

            in 200..209 -> {
                val f = Fx.dynamicWave
                runEffect(f, 0.5f)
                runEffect(f, 3f)
                runEffect(f, 7f)
                runEffect(f, 5f)
                runEffect(f, 9f)
                runEffectRandom(Fx.hitLaserBlast, (-16..16))
                runEffectRandom(Fx.hitSquaresColor, (-16..16))
                runEffectRandom(Fx.vapor, (-4..4))
            }

            in 210..219 -> {
                runEffect(Fx.dynamicSpikes, 7f)
                runEffectRandom(Fx.hitSquaresColor, (-4..4))
                runEffectRandom(Fx.vapor, (-4..4))
            }

            in 220..229 -> {
                runEffect(Fx.dynamicSpikes, 7f)
                runEffectRandom(Fx.circleColorSpark, (-4..4))
                runEffectRandom(Fx.vapor, (-4..4))
            }

            in 230..239 -> {
                runEffect(Fx.dynamicSpikes, 7f)
                runEffectRandom(Fx.circleColorSpark, (-4..4))
                runEffectRandom(Fx.hitLaserBlast, (-4..4))
                runEffectRandom(Fx.smokePuff, (-4..4))
            }

            in 240..249 -> {
                runEffect(Fx.dynamicExplosion, 0.8f)
                runEffectRandom(Fx.hitLaserBlast, (-16..16))
                runEffectRandom(Fx.vapor, (-16..16))
            }

            in 250..259 -> {
                runEffect(Fx.dynamicExplosion, 0.8f)
                runEffectRandom(Fx.hitLaserBlast, (-4..4))
                runEffectRandom(Fx.smokePuff, (-4..4))
            }

            in 260..269 -> {
                runEffect(Fx.dynamicExplosion, 0.8f)
                runEffectRandom(Fx.hitLaserBlast, (-4..4))
                runEffectRandom(Fx.hitLaserBlast, (-4..4))
                runEffectRandom(Fx.smokePuff, (-4..4))
                buffer.add(
                    EffectPos(
                        data.player,
                        Fx.shootSmokeSquareBig,
                        listOf(0f, 90f, 180f, 270f).random(),
                        Color.HSVtoRGB(252f, 164f, 0f, 0.22f),
                        (-1..1)
                    )
                )
            }

            in 270..279 -> {
                runEffectRandomRotate(Fx.shootSmokeSquare)
                runEffect(Fx.hitLaserBlast)
                runEffect(Fx.colorTrail, 4f)
            }

            in 280..289 -> {
                runEffectRandomRotate(Fx.shootSmokeSquare)
                runEffect(Fx.hitLaserBlast)
                runEffect(Fx.dynamicWave, 2f)
            }

            in 290..299 -> {
                runEffectAtRotate(Fx.shootSmokeSquare, 0f)
                runEffectAtRotate(Fx.shootSmokeSquare, 45f)
                runEffectAtRotate(Fx.shootSmokeSquare, 90f)
                runEffectAtRotate(Fx.shootSmokeSquare, 135f)
                runEffectAtRotate(Fx.shootSmokeSquare, 180f)
                runEffectAtRotate(Fx.shootSmokeSquare, 225f)
                runEffectAtRotate(Fx.shootSmokeSquare, 270f)
                runEffectAtRotate(Fx.shootSmokeSquare, 315f)
                runEffect(Fx.breakProp)
                runEffect(Fx.vapor)
            }

            in 300..399 -> {
                var rot = data.player.unit().rotation
                val customColor = Color.HSVtoRGB(252f, 164f, 0f, 0.22f)
                rot += 180f
                runEffectAtRotateAndColor(
                    Fx.shootSmokeSquareBig,
                    rot,
                    customColor
                )
                rot += 40f
                runEffectAtRotateAndColor(Fx.shootTitan, rot, customColor)
                rot += 25f
                runEffectAtRotateAndColor(Fx.colorSpark, rot, customColor)
                rot -= 105f
                runEffectAtRotateAndColor(Fx.shootTitan, rot, customColor)
                rot -= 25f
                runEffectAtRotateAndColor(Fx.colorSpark, rot, customColor)
                runEffect(Fx.mineHuge)
            }

            in 400..499 -> {
                val rot = data.player.unit().rotation
                val rad1 = (rot + 90f) * 0.017453292f
                val rad2 = (rot - 90f) * 0.017453292f
                val rPixels = 1.3f * Vars.tilesize

                val x1 = rPixels * kotlin.math.cos(rad1)
                val y1 = rPixels * kotlin.math.sin(rad1)
                val x2 = rPixels * kotlin.math.cos(rad2)
                val y2 = rPixels * kotlin.math.sin(rad2)

                val customColor = Color.valueOf("ffaaff")

                runEffectAtOffset(Fx.shootSmall, x1, y1, rot, customColor)
                runEffectAtOffset(Fx.shootSmall, x2, y2, rot, customColor)
                runEffectAtOffset(Fx.shootBig, 0f, 0f, rot + 180f, customColor)
                runEffectAtOffset(Fx.mineHuge, 0f, 0f, 2f, customColor)
            }

            in 500..1000 -> {
                val rot = data.player.unit().rotation
                val rPixels = 1.3f * Vars.tilesize

                val rad1 = (rot + 60f) * 0.017453292f
                val rad2 = (rot - 60f) * 0.017453292f
                val rad3 = (rot + 110f) * 0.017453292f
                val rad4 = (rot - 110f) * 0.017453292f

                val x1 = rPixels * kotlin.math.cos(rad1)
                val y1 = rPixels * kotlin.math.sin(rad1)
                val x2 = rPixels * kotlin.math.cos(rad2)
                val y2 = rPixels * kotlin.math.sin(rad2)
                val x3 = rPixels * kotlin.math.cos(rad3)
                val y3 = rPixels * kotlin.math.sin(rad3)
                val x4 = rPixels * kotlin.math.cos(rad4)
                val y4 = rPixels * kotlin.math.sin(rad4)

                val customColor = Color.valueOf("ffaaff")

                runEffectAtOffset(Fx.shootSmall, x1, y1, rot, customColor)
                runEffectAtOffset(Fx.shootSmall, x2, y2, rot, customColor)
                runEffectAtOffset(Fx.shootBig, x3, y3, rot + 190f, customColor)
                runEffectAtOffset(Fx.shootBig, x4, y4, rot - 190f, customColor)
                runEffectAtOffset(Fx.mineHuge, 0f, 0f, rot - 190f, customColor)
            }
        }
    }

    override fun run() {
        if (!Vars.state.isPlaying) return
        if (!conf.feature.level.effect.enabled) {
            this.cancel()
            return
        }
        // Without this a stalled game thread drains every pass it missed in a single frame, which is
        // the packet burst this ceiling exists to prevent.
        if (pending) return

        // Arc posts a Timer task body to the application thread, so run() is already on the game loop
        // and this post buys a frame rather than a thread hop - which is what the ceiling above counts.
        pending = true
        Core.app.post {
            pending = false
            emit()
        }
    }

    private fun emit() {
        if (!Vars.state.isPlaying || !conf.feature.level.effect.enabled) return

        val target = ArrayList<Playerc>()
        val groups = ArrayList<List<EffectPos>>()
        players.forEach {
            if (it.effectVisibility) {
                buffer = ArrayList()
                effect(it)
                if (buffer.isNotEmpty()) groups.add(buffer)

                if (it.player.unit() != null && it.player.unit().health > 0f) {
                    if (conf.feature.level.effect.moving && it.player.unit().moving()) {
                        target.add(it.player)
                    } else if (!conf.feature.level.effect.moving) {
                        target.add(it.player)
                    }
                }
            }
        }
        buffer = ArrayList()

        nextSlice(groups, target.size).forEach {
            val unit = it.player.unit() ?: return@forEach
            val x = unit.x + it.offsetX + (it.random?.random() ?: 0)
            val y = unit.y + it.offsetY + (it.random?.random() ?: 0)
            target.forEach { p ->
                Call.effect(p.con(), it.effect, x, y, it.rotate, it.color)
            }
        }
    }

    /**
     * The effects this pass may send to [targets] viewers without going over [ceiling] packets.
     *
     * The cost is the product of two player counts: sixty players at level 200 buffer 480 effects,
     * which uncapped is 28,800 packets in one 50 ms tick.
     *
     * [groups] holds one entry per emitting player, and the ceiling is applied to whole groups,
     * because a tier draws a shape out of four or five effects and half a shape looks broken rather
     * than thinned. The next pass resumes at the group this one stopped at, so everyone is shown,
     * just not in the same tick.
     */
    internal fun nextSlice(
        groups: List<List<EffectPos>>,
        targets: Int,
        ceiling: Int = conf.feature.level.effect.maxPacketsPerRun
    ): List<EffectPos> {
        if (targets <= 0 || groups.isEmpty()) return emptyList()

        val allowed = (ceiling / targets).coerceAtLeast(1)
        if (groups.sumOf { it.size } <= allowed) return groups.flatten()

        val slice = ArrayList<EffectPos>(allowed)
        var index = groupCursor % groups.size
        var visited = 0
        while (visited < groups.size) {
            val group = groups[index]
            // Always send at least one group, even when that single group is over the ceiling.
            if (slice.isNotEmpty() && slice.size + group.size > allowed) break
            slice.addAll(group)
            index = (index + 1) % groups.size
            visited++
        }
        groupCursor = index
        return slice
    }
}
