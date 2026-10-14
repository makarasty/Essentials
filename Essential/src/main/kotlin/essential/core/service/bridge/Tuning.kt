package essential.core.service.bridge

/*
 * Hardcoded tuning for the server bridge. Not in config.yaml; ships with the build, and is left out of it
 * together with the service (-PexcludeModules=bridge).
 */

internal object BridgeTuning {
    /** Longest line either side reads from the bridge socket, in characters (8 KiB). */
    const val MAX_LINE_LENGTH = 8 * 1024

    /** Longest crash report the server accepts from a client, in bytes (64 KiB). */
    const val MAX_CRASH_REPORT_LENGTH = 64 * 1024

    /** Lines the client may queue for sending before new ones are refused. */
    const val CLIENT_QUEUE_CAPACITY = 128

    /** Reconnects the client tries after a failed connection before it gives up. */
    const val MAX_RECONNECT_ATTEMPTS = 5

    /** Pause before the client reconnects, in seconds. */
    const val RECONNECT_DELAY_SECONDS = 5f

    /** Timeout for the client's TCP connect, in milliseconds. */
    const val CONNECT_TIMEOUT_MS = 5_000

    /** Read timeout while either side runs the authentication handshake, in milliseconds. */
    const val AUTH_READ_TIMEOUT_MS = 10_000

    /** How long the client's writer waits for a queued line before checking the connection, in milliseconds. */
    const val WRITER_POLL_MS = 100L

    /** Clients the bridge server accepts at once; more are turned away. */
    const val MAX_CLIENTS = 32

    /** Threads the bridge server keeps for client connections. */
    const val SERVER_CORE_THREADS = 2

    /** Most threads the bridge server runs for client connections. */
    const val SERVER_MAX_THREADS = 8

    /** Idle time before a thread above [SERVER_CORE_THREADS] is ended, in seconds. */
    const val SERVER_THREAD_KEEP_ALIVE_SECONDS = 60L

    /** Connections waiting for a server thread before new ones are rejected. */
    const val SERVER_QUEUE_CAPACITY = 32
}
