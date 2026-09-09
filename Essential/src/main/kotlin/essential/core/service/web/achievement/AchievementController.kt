package essential.core.service.web.achievement

import essential.common.bundle.Bundle
import essential.common.database.data.getPlayerAchievements
import essential.common.database.data.getPlayerDataByName
import essential.common.players
import essential.common.util.toHString
import essential.core.service.achievements.Achievement
import essential.core.service.web.auth.UserSession
import essential.core.service.web.onGameThread
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.time.Duration.Companion.seconds

@Serializable
data class AchievementInfo(
    val name: String,
    val title: String,
    val description: String,
    val goal: String,
    val current: Int,
    val target: Int,
    val completed: Boolean,
    val hidden: Boolean
)

@Serializable
data class MyInfo(
    val name: String,
    val uuid: String,
    val firstPlayed: String,
    val lastLogin: String,
    val permission: String,
    val level: Int,
    val exp: Int,
    val expMax: Int,
    val blockPlaceCount: Int,
    val blockBreakCount: Int,
    val totalPlayed: String,
    val attendanceDays: Int,
    val pvpWinCount: Int,
    val pvpLoseCount: Int,
    val pvpWinRate: Int,
    val waveClear: Int,
    val attackClear: Int,
    val achievementsCompleted: Int,
    val achievementsTotal: Int,
    val achievements: List<AchievementInfo>
)

/**
 * Resolves the achievements bundle for [locale]. Bundle.resolve, not ResourceBundle.getBundle: the
 * two-arg form falls through the JVM default locale's candidates before reaching the base bundle, so
 * a client whose language ships no file was answered in the host's language rather than English
 * (task-044, answers/3-2.md). Verified against a `bundles/achievements/bundle_en.properties` that
 * does not exist: the catch below is not a live fallback path (the base bundle always exists, so the
 * try cannot throw), and is left as `Bundle.resolve(..., Locale.ENGLISH)` - the correct English
 * fallback - rather than removed, since a future bundle reorganisation could make the base
 * resolution fail for real.
 */
internal fun resolveAchievementBundle(locale: Locale): ResourceBundle = try {
    Bundle.resolve("bundles/achievements/bundle", locale)
} catch (e: MissingResourceException) {
    Bundle.resolve("bundles/achievements/bundle", Locale.ENGLISH)
}

class AchievementController {
    suspend fun getMyInfo(call: ApplicationCall) {
        val session = call.sessions.get<UserSession>()
            ?: return call.respond(HttpStatusCode.Unauthorized)

        val dbData = getPlayerDataByName(session.username)
            ?: return call.respond(HttpStatusCode.NotFound, "Player not found")

        // Prefer live (connected) data so runtime-only achievement progress is accurate
        val data = players.find { it.uuid == dbData.uuid } ?: dbData

        val completed = getPlayerAchievements(dbData).map { it.achievementName.lowercase() }.toSet()

        // Load achievement names/descriptions in the account's language.
        val bundle = resolveAchievementBundle(Locale.forLanguageTag(dbData.languageTag.replace("_", "-")))

        fun localized(prefix: String, key: String, fallback: String): String = try {
            bundle.getString("$prefix.$key")
        } catch (e: MissingResourceException) {
            fallback
        }

        // Hide secret achievements until unlocked
        val visible = Achievement.entries.filter { !it.isHidden || completed.contains(it.name.lowercase()) }

        // The game thread mutates this player's status map as achievements are awarded.
        val progress = onGameThread {
            visible.filterNot { completed.contains(it.name.lowercase()) }
                .associateWith { ach -> runCatching { ach.current(data) }.getOrDefault(0) }
        }

        val achievements = visible.map { ach ->
            val key = ach.name.lowercase()
            val isDone = completed.contains(key)

            val target = ach.value()
            val current = if (isDone) target else progress[ach] ?: 0
            AchievementInfo(
                name = ach.name,
                title = localized("achievement", key, ach.name),
                description = localized("description", key, ""),
                goal = localized("target", key, "").replace("{0}", target.toString()),
                current = current.coerceIn(0, target),
                target = target,
                completed = isDone,
                hidden = ach.isHidden
            )
        }

        val info = MyInfo(
            name = dbData.name,
            uuid = dbData.uuid,
            firstPlayed = dbData.firstPlayed.toString(),
            lastLogin = dbData.lastLoginDate.toString(),
            permission = dbData.permission,
            level = dbData.level,
            exp = dbData.exp,
            expMax = essential.core.Commands.Exp.calculateFullTargetXp(dbData.level).toInt(),
            blockPlaceCount = dbData.blockPlaceCount,
            blockBreakCount = dbData.blockBreakCount,
            totalPlayed = dbData.totalPlayed.toLong().seconds.toHString(),
            attendanceDays = dbData.attendanceDays,
            pvpWinCount = dbData.pvpWinCount.toInt(),
            pvpLoseCount = dbData.pvpLoseCount.toInt(),
            pvpWinRate = run {
                val total = dbData.pvpWinCount + dbData.pvpLoseCount
                if (total > 0) dbData.pvpWinCount * 100 / total else 0
            },
            waveClear = dbData.waveClear,
            attackClear = dbData.attackClear,
            achievementsCompleted = achievements.count { it.completed },
            achievementsTotal = achievements.size,
            achievements = achievements
        )

        call.respond(info)
    }
}

fun Route.achievementRoutes(controller: AchievementController) {
    route("/api/me") {
        authenticate("auth-session") {
            get {
                controller.getMyInfo(call)
            }
        }
    }
}

/** Reflection entry point used by the Web module when achievements are packaged. */
object AchievementWebModule {
    @JvmStatic
    fun registerRoutes(route: Route) {
        route.achievementRoutes(AchievementController())
    }
}
