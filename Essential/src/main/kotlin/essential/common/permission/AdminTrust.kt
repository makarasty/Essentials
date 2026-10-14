package essential.common.permission

import essential.common.PermissionTuning
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The IPs a privileged uuid was confirmed from, shared by every server: players.admin_trust, a JSON
 * list. The client's usid differs per server (it is derived from the address it connects to), so a
 * device binding cannot travel between servers; the IP is the one signal the nine see alike.
 *
 * Only a person creates an entry - Discord's adminAdd or setperm on an online player. A join never
 * does; it only re-stamps an entry that is already there, which is what keeps it from expiring.
 */
internal object AdminTrust {
    @Serializable
    data class Entry(val ip: String, val at: Long, val by: String = "")

    private val json = Json { ignoreUnknownKeys = true }
    private const val DAY_MS = 86_400_000L

    /** The unexpired entries of [stored]. An unreadable value is no trust at all, not an error. */
    fun read(stored: String?, now: Long): List<Entry> {
        if (stored.isNullOrBlank()) return emptyList()
        val entries = runCatching { json.decodeFromString<List<Entry>>(stored) }.getOrDefault(emptyList())
        return entries.filter { now - it.at < PermissionTuning.TRUST_TTL_DAYS * DAY_MS }
    }

    /** [stored] with [ip] stamped at [now]; past [PermissionTuning.TRUST_MAX_ENTRIES] the oldest goes. */
    fun with(stored: String?, ip: String, by: String, now: Long): String {
        val kept = read(stored, now).filter { it.ip != ip } + Entry(ip, now, by)
        return json.encodeToString(kept.sortedBy { it.at }.takeLast(PermissionTuning.TRUST_MAX_ENTRIES))
    }
}
