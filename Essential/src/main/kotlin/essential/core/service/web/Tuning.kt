package essential.core.service.web

/*
 * Hardcoded tuning for the web service. Not in config.yaml; ships with the build, and is left out of it
 * together with the service (-PexcludeModules=web).
 */

/** Ktor server setup (WebServer). */
internal object WebServerTuning {
    /** Requests one remote address may make per refill period before it is rate limited. */
    const val RATE_LIMIT_REQUESTS = 300

    /** Period after which the rate limit is refilled, in minutes. */
    const val RATE_LIMIT_REFILL_MINUTES = 1
}

/** Map previews fetched from the external renderer (MapController). */
internal object MapRenderTuning {
    /** Render requests that may wait in the fetch queue. */
    const val MAX_QUEUED_FETCHES = 32

    /** Render requests sent to the renderer at once. */
    const val CONCURRENT_FETCHES = 3

    /** Delay after start-up before the map image cache is warmed up, in milliseconds. */
    const val CACHE_WARMUP_DELAY_MS = 5000L

    /**
     * Longest an HTTP request waits for a map image that is not cached yet, in seconds. A render job's own
     * deadline is ten minutes, three attempts deep; nothing waiting on an HTTP request should be held for
     * that. The queue worker keeps rendering either way and caches the result.
     */
    const val REQUEST_WAIT_SECONDS = 60

    /** Attempts made to render one map image. */
    const val MAX_ATTEMPTS = 3

    /** Pause between two render attempts, in seconds. */
    const val RETRY_DELAY_SECONDS = 10

    /** Interval between two render job status polls, in milliseconds. */
    const val JOB_POLL_INTERVAL_MS = 2000L

    /** Longest wait for one render job, in milliseconds (10 minutes per job). */
    const val JOB_MAX_WAIT_MS = 600_000L

    /** Connect timeout for submitting a render job, in milliseconds. */
    const val SUBMIT_CONNECT_TIMEOUT_MS = 30000

    /** Read timeout for submitting a render job, in milliseconds. */
    const val SUBMIT_READ_TIMEOUT_MS = 60000

    /** Connect timeout for a direct render (fetchMapImageDirect), in milliseconds. */
    const val DIRECT_CONNECT_TIMEOUT_MS = 30000

    /** Read timeout for a direct render (fetchMapImageDirect), in milliseconds. */
    const val DIRECT_READ_TIMEOUT_MS = 120000

    /** Connect timeout for a render job status poll, in milliseconds. */
    const val POLL_CONNECT_TIMEOUT_MS = 10000

    /** Read timeout for a render job status poll, in milliseconds. */
    const val POLL_READ_TIMEOUT_MS = 10000

    /** Connect timeout for downloading a finished render, in milliseconds. */
    const val RESULT_CONNECT_TIMEOUT_MS = 30000

    /** Read timeout for downloading a finished render, in milliseconds. */
    const val RESULT_READ_TIMEOUT_MS = 120000
}

/** Server statistics and chat history for the web panel (StatisticsController). */
internal object StatisticsTuning {
    /** Interval between two recorded status points, in milliseconds. */
    const val STATUS_SAMPLE_INTERVAL_MS = 60000

    /** Status points kept; the oldest is dropped past this (1440 one-minute samples). */
    const val STATUS_HISTORY_LIMIT = 1440

    /** Chat messages kept for the web panel; the oldest is dropped past this. */
    const val CHAT_HISTORY_LIMIT = 100

    /** Longest chat message accepted from the web panel, in characters. */
    const val WEB_CHAT_MAX_LENGTH = 100
}
