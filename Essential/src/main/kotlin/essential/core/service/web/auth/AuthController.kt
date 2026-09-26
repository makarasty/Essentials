package essential.core.service.web.auth

import arc.util.Log
import essential.common.database.data.getPlayerDataByAccountID
import essential.core.service.web.WebService.Companion.conf
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class UserSession(val id: String, val accountID: String, val username: String, val issuedAt: Long = 0L)

/** [username] is the wire name for what the login form now collects: the account ID, not the player name. */
@Serializable
data class LoginRequest(val username: String, val password: String)

/**
 * Logout only ever cleared the caller's own cookie, so a copy of that cookie taken beforehand kept
 * working. This records, per user, the moment at which every session issued at or before it is dead;
 * `validate` consults it on every request. Entries older than one session lifetime can no longer revoke
 * anything that is still valid, so they are dropped on the next write.
 *
 * It lives in memory: a plugin restart forgets the revocations, and a cookie revoked before one becomes
 * usable again until its own age expires it.
 */
internal object SessionRevocations {
    private val revokedAt = ConcurrentHashMap<String, Long>()

    /**
     * Keyed by the player row id, which is what `validate` pins a session to. Keying by name would take
     * out every account whose name differs from this one only by case, on the engines whose collation
     * treats them as one.
     */
    fun revoke(session: UserSession) {
        val now = System.currentTimeMillis()
        // merge, not put: a wall clock that steps backwards would otherwise lower an existing mark and
        // un-revoke the sessions it had already killed.
        revokedAt.merge(session.id, now, ::maxOf)
        revokedAt.values.removeIf { it < now - conf.sessionDuration * 1000 }
    }

    fun isRevoked(session: UserSession): Boolean =
        (revokedAt[session.id] ?: 0L) >= session.issuedAt
}

/**
 * Cookie max-age is advice to a browser, and an attacker replaying a copied cookie is free to ignore it,
 * which is why `sessionDuration` bounded nothing the server could see. The session payload is encrypted
 * and HMAC-signed by the transport transformer, so a timestamp carried inside it is something the server
 * can trust, and this is where that config value starts to mean what its comment says.
 *
 * A session minted before this field existed carries `issuedAt == 0` and is treated as expired.
 */
internal fun UserSession.isCurrent(): Boolean =
    issuedAt > 0 &&
        System.currentTimeMillis() - issuedAt < conf.sessionDuration * 1000 &&
        !SessionRevocations.isRevoked(this)

class AuthController {
    private companion object {
        /**
         * Every failed login pays the same BCrypt cost as a successful one. Without this the absence of
         * the hash check is itself the answer: an unknown name returns in microseconds and a known one
         * does not.
         *
         * It hashes a value generated here and never stored, so no submitted password can match it. A
         * hash of something guessable - the empty string above all - would leave a real login one careless
         * reordering away from succeeding against every row that has no account.
         */
        val ABSENT_ACCOUNT_HASH: String = BCrypt.hashpw(
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)),
            BCrypt.gensalt()
        )
        const val AUTH_FAILED = "Invalid account ID or password"
    }

    suspend fun handleLogin(call: ApplicationCall, request: LoginRequest) {
        try {
            val playerData = getPlayerDataByAccountID(request.username)
            val storedHash = playerData?.accountPW
            val accountID = playerData?.accountID

            // One status and one body for every way authentication can fail, and the hash check runs
            // whether or not there is a hash to check. A caller who has not proved who they are learns
            // neither whether the name exists nor what state its account is in. No transaction: this
            // reads an already-loaded field and would otherwise hold a pooled connection for the whole
            // BCrypt round, once per attempt, at 300 attempts a minute.
            val passwordMatches = try {
                BCrypt.checkpw(request.password, storedHash ?: ABSENT_ACCOUNT_HASH)
            } catch (e: IllegalArgumentException) {
                // A stored hash BCrypt cannot parse. Refusing is right; refusing silently is not, because
                // the account's owner can only report that their password stopped working.
                Log.err("Stored password hash for '${request.username}' is not a valid BCrypt hash", e)
                false
            }

            if (playerData == null || storedHash == null || accountID == null || !passwordMatches) {
                call.respond(HttpStatusCode.Unauthorized, AUTH_FAILED)
                return
            }

            // The guard this replaces compared the stored account ID against the password the caller had
            // just submitted, and did so before any authentication, which answered "is this string the
            // account ID" for anyone who cared to ask. Comparing the stored ID against the stored hash is
            // the question it was written to ask, and it is now only reachable by the account's owner.
            if (BCrypt.checkpw(accountID, storedHash)) {
                // An object, not a bare string: the login page reads `message` off a 403 body, and this is
                // now the only moment at which the owner of such an account is ever told.
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("message" to "Your account ID and password are the same. Please change your password.")
                )
                return
            }

            if (playerData.discordID == null) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("message" to "Please link your Discord account first", "discordUrl" to conf.discordUrl)
                )
                return
            }

            // Create session
            val session = UserSession(playerData.id.toString(), accountID, playerData.name, System.currentTimeMillis())
            call.sessions.set(session)

            call.respond(HttpStatusCode.OK, mapOf("username" to playerData.name))
        } catch (e: Exception) {
            Log.err("Login error", e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred during login")
        }
    }
}

fun Route.authRoutes(controller: AuthController) {
    route("/api/auth") {
        post("/login") {
            val loginRequest = call.receive<LoginRequest>()
            controller.handleLogin(call, loginRequest)
        }

        get("/logout") {
            // Clearing the cookie only disarms this caller's own copy of it.
            call.sessions.get<UserSession>()?.let { SessionRevocations.revoke(it) }
            call.sessions.clear<UserSession>()
            call.respond(HttpStatusCode.OK)
        }

        get("/status") {
            // These routes sit outside authenticate("auth-session"), so this is the one place the session
            // gate has to be applied by hand. Without it a revoked or expired cookie still reported a
            // logged-in user and the page painted itself accordingly.
            val session = call.sessions.get<UserSession>()?.takeIf { it.isCurrent() }
            if (session != null) {
                call.respond(mapOf("username" to session.username))
            } else {
                call.respond(HttpStatusCode.Unauthorized)
            }
        }
    }
}
