package essential.core.service.chat

/*
 * Hardcoded tuning for the chat service. Not in config_chat.yaml; ships with the build, and is left out of it
 * together with the service (-PexcludeModules=chat).
 */

internal object ChatTuning {
    /** Translation jobs waiting for the worker; a full queue drops its oldest job, never the message. */
    const val TRANSLATE_QUEUE_SIZE = 256

    /** Messages one player may have translated in a burst before [TRANSLATE_SENDER_REFILL_MS] applies. */
    const val TRANSLATE_SENDER_BURST = 5

    /** One more translated message per player every this many milliseconds, up to the burst. */
    const val TRANSLATE_SENDER_REFILL_MS = 6_000L

    /** Senders whose rate is tracked before the idle ones are forgotten. */
    const val TRANSLATE_TRACKED_SENDERS = 1024

    /** Backend requests per server per UTC day; past it chat stays untranslated until midnight. */
    const val TRANSLATE_DAILY_REQUESTS = 5_000

    /** Ceiling on the translation cache, whatever `translate.cacheSize` says. */
    const val TRANSLATE_CACHE_MAX = 20_000

    /** A translation may be twice its original plus this many characters; the rest is cut. */
    const val TRANSLATE_EXTRA_CHARS = 20

    /** Largest backend answer read, in bytes; a longer one is a failure. */
    const val TRANSLATE_MAX_RESPONSE_BYTES = 256 * 1024

    /** Seconds to open a connection to a backend. */
    const val TRANSLATE_CONNECT_TIMEOUT_SECONDS = 5L

    /** Upper bound on `translate.timeoutSeconds`, so a typo cannot park the worker for minutes. */
    const val TRANSLATE_MAX_TIMEOUT_SECONDS = 30

    /** Upper bound on `translate.batchMillis`. */
    const val TRANSLATE_MAX_BATCH_MILLIS = 5_000L

    /** Texts in one Google request. */
    const val TRANSLATE_GOOGLE_CHUNK = 50
}
