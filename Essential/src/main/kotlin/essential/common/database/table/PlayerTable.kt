package essential.common.database.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object PlayerTable : Table("players") {
    val id = uinteger("id").autoIncrement()
    val name = varchar("name", 256).uniqueIndex("name")
    val uuid = varchar("uuid", 25).uniqueIndex("uuid")
    val languageTag = varchar("language_tag", 10).default("en")
    val blockPlaceCount = integer("block_place_count").default(0)
    val blockBreakCount = integer("block_break_count").default(0)
    val level = integer("level").default(0)
    val exp = integer("exp").default(0)
    val firstPlayed = datetime("first_played").defaultExpression(CurrentDateTime)
    val lastPlayed = datetime("last_played").defaultExpression(CurrentDateTime)
    val totalPlayed = integer("total_played").default(0)
    val attackClear = integer("attack_clear").default(0)
    val waveClear = integer("wave_clear").default(0)
    val pvpWinCount = short("pvp_win_count").default(0)
    val pvpLoseCount = short("pvp_lose_count").default(0)
    val pvpEliminatedCount = short("pvp_eliminated_count").default(0)
    val pvpMvpCount = short("pvp_mvp_count").default(0)
    val permission = varchar("permission", 50).default("default")
    val accountID = varchar("account_id", 50).nullable().default(null)
    val accountPW = varchar("account_pw", 256).nullable().default(null)
    val discordID = varchar("discord_id", 50).nullable().default(null).uniqueIndex("discord_id")
    val chatMuted = bool("chat_muted").default(false)
    val effectVisibility = bool("effect_visibility").default(false)
    val effectLevel = short("effect_level").nullable().default(null)
    val effectColor = varchar("effect_color", 20).nullable().default(null)
    val hideRanking = bool("hide_ranking").default(false)
    val strictMode = bool("strict_mode").default(false)
    val lastLoginDate = datetime("last_login_date").defaultExpression(CurrentDateTime)
    val lastLogoutDate = datetime("last_logout_date").nullable().default(null)
    val lastPlayedWorldName = varchar("last_played_world_name", 50).nullable().default(null)
    val lastPlayedWorldMode = varchar("last_played_world_mode", 50).nullable().default(null)
    val isConnected = bool("is_connected").default(false)

    /**
     * Which server [isConnected] is true on, so that server's next boot can clear what its previous
     * life left behind without touching the players online on the other five. Nullable: a row written
     * before this column existed belongs to no server, and claiming it would be the same mistake.
     */
    val connectedServer = varchar("connected_server", 100).nullable().default(null)
    val isBanned = bool("is_banned").default(false)
    val banExpireDate = datetime("ban_expire_date").nullable().default(null)
    val attendanceDays = integer("attendance_days").default(0)

    /**
     * The `record.*` half of [essential.common.database.data.PlayerData.status] as a JSON object.
     *
     * The achievement counters live in that map. Until this column existed they were held in memory
     * only, so every counter restarted at zero when the player left, when the server restarted, or
     * when the player moved to another server on the same database.
     *
     * Deliberately not called `status`: a nullable legacy `status` column still exists on databases
     * upgraded through the v4 scripts, and reusing the name would make Exposed emit an ALTER to
     * make it NOT NULL on every boot, over a column that still holds pre-fork content.
     *
     * Nullable with a null default, the same shape as [PluginTable.hubMapName], because MySQL refuses
     * a literal default on a TEXT column: `TEXT NOT NULL DEFAULT '{}'` is error 1101 there, and it
     * would be emitted both by the create and by the add-missing-columns pass at boot. A row that
     * predates the column reads as null and starts from an empty map.
     */
    val statusData = text("status_data").nullable().default(null)

    override val primaryKey = PrimaryKey(id)
}
