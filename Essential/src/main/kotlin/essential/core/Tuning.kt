package essential.core

/*
 * Hardcoded tuning for the plugin core (essential.core), one object per feature.
 *
 * These values are not in config.yaml. Changing one here changes behaviour for every server that runs
 * this build. Values that belong to an optional service live in that service's own Tuning.kt, so
 * nothing here may refer to essential.core.service.
 */

/** Plugin start-up and shutdown (Main.kt). */
internal object MainTuning {
    /** Longest wait for the database to initialise before the server gives up starting, in milliseconds. */
    const val DATABASE_INIT_TIMEOUT_MS = 30_000L

    /** Longest wait for online players' data to be saved on shutdown, in milliseconds. */
    const val SHUTDOWN_SAVE_TIMEOUT_MS = 30_000L

    /** Longest wait for pending background writes to drain on shutdown, in milliseconds. */
    const val SHUTDOWN_DRAIN_TIMEOUT_MS = 5_000L

    /** Timeout of the GitHub release check made when autoUpdate is on, in milliseconds. */
    const val UPDATE_CHECK_TIMEOUT_MS = 1000

    /** Threads in Main.threadPool, the shared background executor. */
    const val THREAD_POOL_SIZE = 2
}

/** Experience and levels. */
internal object ExpTuning {
    /** XP the level curve starts from: level n costs BASE_XP + BASE_XP * n^EXPONENT. */
    const val BASE_XP = 750

    /** Growth exponent of the level curve (see [BASE_XP]). */
    const val EXPONENT = 1.06

    /** How far the precomputed table reaches, in levels. Past it the old summation still works. */
    const val TABLE_LEVELS = 1000

    /** XP every online player gets when a wave is cleared. */
    const val WAVE_CLEAR_XP = 500

    /** Exclusive upper bound of the random XP an active player earns each second, before the multiplier. */
    const val PLAY_SECOND_XP_BOUND = 7

    /** Game over only pays match XP once the map has run this long, in minutes. */
    const val MIN_MATCH_MINUTES = 5

    /** Flat XP added on a PvP game over, for the winners and the losers alike. */
    const val PVP_BONUS = 5000

    /** Extra XP the PvP winners get on Erekir. */
    const val EREKIR_PVP_BONUS = 5000
}

/** Player-facing events in CoreEvent.kt. */
internal object CoreEventTuning {
    /**
     * Least time between two tap-log entries for one player, in milliseconds.
     *
     * Tap RPCs have no engine-side rate limit, so the logging side is capped per uuid instead (see the
     * note at lastLoggedTap in CoreEvent.kt).
     */
    const val TAP_LOG_INTERVAL_MS = 100L

    /** World-history rows shown when a player taps a tile in history view. */
    const val TAP_HISTORY_ROWS = 8

    /** Pause between two attempts to load a player's data after the first one failed, in seconds. */
    const val PLAYER_DATA_RETRY_DELAY_SECONDS = 10

    /** Pause before the ban check of a joining player is retried once, in seconds. */
    const val BAN_CHECK_RETRY_DELAY_SECONDS = 5

    /** Least time between two "unit limit reached" broadcasts, in seconds (counted down once a second). */
    const val UNIT_LIMIT_MESSAGE_COOLDOWN_SECONDS = 60

    /** A map must have run this long before map votes are acted on, in minutes. */
    const val MAP_VOTE_MIN_MINUTES = 5

    /** A message of the day longer than this many lines is shown as a full-screen message instead of chat. */
    const val MOTD_INFO_MESSAGE_LINES = 10
}

/** Player commands in Commands.kt. */
internal object CommandsTuning {
    /** Rows on one page of the paged player and list commands. */
    const val LIST_PAGE_SIZE = 6

    /** Time each frame of the /router name animation is shown, in milliseconds. */
    const val ROUTER_FRAME_DELAY_MS = 500

    /** Pause between two passes of the /router name animation, in milliseconds. */
    const val ROUTER_PAUSE_MS = 5000
}

/** Starting a vote from the /vote command. The running vote itself is tuned in service/vote/Tuning.kt. */
internal object VoteStartTuning {
    /** Server-wide wait after a vote starts before the next one may, in minutes. */
    const val COOLDOWN_MINUTES = 2

    /** Server-wide wait after a `vote random` starts, in minutes. */
    const val RANDOM_COOLDOWN_MINUTES = 6

    /** A vote needs more eligible (non-AFK) players than this, unless the starter holds vote.admin. */
    const val SMALL_SERVER_VOTERS = 3

    /** Repeats of the vote task's one-second timer, so the most seconds a vote task runs. */
    const val VOTE_TIMER_REPEATS = 60
}

/** Level effects (the effect service's task, scheduled from ModuleRuntime). */
internal object LevelEffectTuning {
    /** Interval of the level-effect task, in seconds (20 times a second). */
    const val INTERVAL_SECONDS = 0.05f
}

/** Admin undo of world edits (Undo.kt). */
internal object UndoTuning {
    /** How long an undo entry can still be undone, in minutes. */
    const val LIFETIME_MINUTES = 10

    /** Undo entries kept per admin; older ones are dropped. */
    const val STACK_LIMIT = 5

    /** How long an undo confirmation menu stays answerable, in seconds. */
    const val MENU_SECONDS = 60f

    /** Interval of the sweep that drops expired undo entries, in seconds. */
    const val SWEEP_SECONDS = 60f
}

/** Temporary bans (TempBan.kt). */
internal object TempBanTuning {
    /** Interval of the sweep that lifts expired temporary bans, in seconds. */
    const val SWEEP_INTERVAL_SECONDS = 30f
}

/** Server pings, warp blocks and world-edit outlines (Trigger.kt). */
internal object TriggerTuning {
    /** Receive timeout of one server ping, in milliseconds. */
    const val PING_TIMEOUT_MS = 1000

    /** Size of the buffer a ping reply is read into, in bytes. */
    const val PING_BUFFER_BYTES = 512

    /**
     * Most marks the world-edit outline draws along one edge. Uncapped it was one packet per
     * perimeter tile four times a second per selecting player, so a selection dragged across
     * a 500x500 map cost that one client roughly 8000 packets a second until they cleared it.
     */
    const val OUTLINE_MARKS = 32
}

/** The rendered server-list description (ServerDescription.kt). */
internal object ServerDescriptionTuning {
    /**
     * Length past which a rendered description draws a warning, in characters: the server list cuts it
     * there (100 on an unpatched client). Colour tags count too.
     */
    const val LIMIT_CHARS = 300
}
