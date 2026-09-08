package essential.core.service.web

import arc.Core
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs [block] on the game thread and waits for what it returns.
 *
 * Ktor dispatches handlers on Netty's worker threads, and Arc's `Seq` and `EntityGroup` are plain
 * array-backed collections with no synchronisation. A handler that walks one of them while the game
 * thread adds or removes entries gets a torn read, not an error. Every read of live game state from
 * this module goes through here, and what comes back is a snapshot the caller owns.
 *
 * Keep the block short and free of blocking IO: it runs inside a server frame.
 */
internal suspend fun <T> onGameThread(block: () -> T): T = suspendCancellableCoroutine { continuation ->
    Core.app.post {
        try {
            continuation.resume(block())
        } catch (e: Throwable) {
            continuation.resumeWithException(e)
        }
    }
}
