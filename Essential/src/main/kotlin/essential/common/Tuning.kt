package essential.common

/*
 * Hardcoded tuning for the shared (essential.common) layer, one object per feature.
 *
 * These values are not in config.yaml. Changing one here changes behaviour for every server that runs
 * this build. Each core service keeps its own Tuning.kt in its package, so a module left out of the
 * build with -PexcludeModules takes its values with it.
 */

/** World-edit history rows on their way from the game thread to the history database. */
internal object WorldHistoryTuning {
    /** Most rows one flush writes in a single batch insert, in rows. */
    const val MAX_BATCH_SIZE = 100

    /** Pause between flushes while rows are waiting, in milliseconds. */
    const val FLUSH_INTERVAL_MS = 200L

    /**
     * How many rows may be waiting to be written before the oldest are dropped.
     *
     * The queue used to be unbounded while its producer is `tap()` on the game thread, so a database
     * that stalled grew it until the server ran out of heap. Blocking the producer instead is not
     * available: that would stall the game thread behind the database. So the buffer keeps the newest
     * rows and drops the oldest, which is the right direction for rollback history - the recent edits
     * are the ones an admin is about to undo.
     *
     * 20000 rows is a few megabytes and roughly forty seconds of one player building flat out.
     */
    const val QUEUE_CAPACITY = 20000

    /** Least time between two warnings about dropped or failed rows, in milliseconds. */
    const val WARN_INTERVAL_MS = 60000L
}

/** R2DBC connection pools: the local H2 world-history pool and the main player database pool. */
internal object DatabasePoolTuning {
    /**
     * Connections in the world-history pool.
     *
     * One steady writer (the WorldHistoryBuffer flush loop), plus readers that do not queue behind it:
     * a /rollback and every tap of an admin in history view. Twenty was headroom nothing used; six
     * leaves room for a rollback and a few viewers at once without anyone waiting on the pool.
     */
    const val HISTORY_MAX_SIZE = 6

    /** Connections the world-history pool opens up front. */
    const val HISTORY_INITIAL_SIZE = 1

    /** Idle time before a world-history connection is closed, in minutes. */
    const val HISTORY_MAX_IDLE_MINUTES = 10L

    /** Longest wait for a free world-history connection, in seconds. */
    const val HISTORY_MAX_ACQUIRE_SECONDS = 10L

    /** Longest wait for a new world-history connection to open, in seconds. */
    const val HISTORY_MAX_CREATE_CONNECTION_SECONDS = 5L

    /** Age at which a world-history connection is retired, in minutes. */
    const val HISTORY_MAX_LIFE_MINUTES = 30L

    /** Connections in the main database pool. [PermissionTuning.OFFLINE_WRITE_LIMIT] is sized against it. */
    const val MAIN_MAX_SIZE = 5

    /** Connections the main pool opens up front. */
    const val MAIN_INITIAL_SIZE = 2

    /** Idle time before a main-pool connection is closed, in minutes. */
    const val MAIN_MAX_IDLE_MINUTES = 10L

    /** Longest wait for a free main-pool connection, in seconds. */
    const val MAIN_MAX_ACQUIRE_SECONDS = 10L

    /** Longest wait for a new main-pool connection to open, in seconds. */
    const val MAIN_MAX_CREATE_CONNECTION_SECONDS = 5L

    /** Age at which a main-pool connection is retired, in minutes. */
    const val MAIN_MAX_LIFE_MINUTES = 10L
}

/** Cross-server routing permissions (warp moves and AFK transfers). */
internal object ServerRoutingTuning {
    /** How long a granted routing permission stays valid when the caller gives no duration, in seconds. */
    const val GRANT_VALID_SECONDS = 60

    /**
     * How many expired rows one grant clears.
     *
     * The table has been growing since the feature landed, so the first grant after this ships meets the
     * whole accumulated backlog. Bounded, that is a short transaction repeated over the next few hundred
     * grants; unbounded, it is one long lock hold on a table five other servers are reading.
     */
    const val PRUNE_LIMIT = 200
}

/** Contribution ranking reads. */
internal object ContributionTuning {
    /** Rows a contribution ranking returns when the caller gives no limit. */
    const val RANKING_SIZE = 10
}

/** The plugin's own log files (essential.common.log). */
internal object LogTuning {
    /** Log lines that may wait for the writer thread before new ones are dropped. */
    const val QUEUE_CAPACITY = 4096

    /** Size at which a log file is rotated, in bytes (2 MiB). */
    const val MAX_LOG_SIZE_BYTES = 2048L * 1024L

    /** Rotated files in a log type's folder at which they are zipped into one archive. */
    const val MAX_LOG_FILES = 20

    /** Least time between two "log lines dropped" warnings, in milliseconds. */
    const val DROP_WARN_INTERVAL_MS = 60000L

    /** Wait before a failed rotation is tried again, in milliseconds. */
    const val ROTATE_RETRY_INTERVAL_MS = 60000L

    /** How long the writer thread waits for a line before checking whether it should stop, in seconds. */
    const val WRITER_POLL_SECONDS = 1L
}

/** Permission file loading. */
internal object PermissionTuning {
    /**
     * Database writes the permission loader runs at once for offline players.
     *
     * The connection pool is five ([DatabasePoolTuning.MAIN_MAX_SIZE]), and the game thread wants one of
     * them for whatever a player is doing while this runs.
     */
    const val OFFLINE_WRITE_LIMIT = 4

    /** Uuids named in the "no player row yet" log line before the list is cut off. */
    const val UNPERSISTED_LOG_SAMPLE = 10
}

/** Player lookup by name, id or uuid (essential.common.util.PlayerLookup). */
internal object PlayerLookupTuning {
    /** Most player rows one lookup query reads; past it the result is reported as truncated. */
    const val SCAN_LIMIT = 5000

    /** Candidates listed when a lookup is ambiguous. */
    const val MAX_CANDIDATES = 10

    /** Characters of a player name shown in a candidate label before it is cut with an ellipsis. */
    const val MAX_NAME_LENGTH = 24
}

/** The config file watcher (essential.common.service.fileWatchService). */
internal object FileWatchTuning {
    /** Size-and-mtime checks made while waiting for a changed file to settle. */
    const val SETTLE_CHECKS = 20

    /** Pause between two settle checks, in milliseconds. */
    const val SETTLE_POLL_MS = 100L
}

/** Per-player cooldowns on commands that query the shared database. */
internal object CommandCooldownTuning {
    /** Least time between two /report or two /ranking from one non-admin player, in seconds. */
    const val DATABASE_COMMAND_SECONDS = 10
}
