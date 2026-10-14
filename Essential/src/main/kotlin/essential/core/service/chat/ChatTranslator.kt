package essential.core.service.chat

import arc.Core
import arc.util.Log
import arc.util.Strings
import essential.common.players
import essential.common.util.findPlayerData
import essential.core.Main.Companion.scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import mindustry.gen.Player
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/** One text to translate into one language: (text, target language). */
internal typealias Ask = Pair<String, String>

/**
 * Public chat translated into each reader's language, off the game thread.
 *
 * Chat never waits on this. [submit] runs inside the chat formatter, so all it does is read who is online
 * and drop a job into a bounded queue; a full queue loses its oldest job, not the message. One worker
 * drains the queue in batches, asks the backends in order, and posts the finished lines back to the game
 * thread. A backend that fails or hits a limit is skipped for a while and the next one takes the batch;
 * when every one is down the message simply stays untranslated.
 */
object ChatTranslator {
    /** Only `record.*` keys of `status` are saved, so this is what lets `/tr` outlive a reconnect. */
    const val OFF_KEY = "record.translate.off"

    /**
     * Languages a player has written in, so understands: a Ukrainian on an English client would otherwise
     * get an English line under every Ukrainian message. Saved like [OFF_KEY]; not a number, so the
     * record-merging code that sums counters skips it.
     */
    const val READS_KEY = "record.translate.reads"

    internal data class Translation(val text: String, val source: String)

    /** [readers] maps a target language to the uuids reading it, most-read language first. */
    private class Job(val sender: String, val senderUuid: String, val text: String, val readers: Map<String, List<String>>, val at: Long)

    /** A 429 or 503: the backend asked us to stay away for [seconds]. */
    private class Limited(val seconds: Long, message: String) : IOException(message)

    private val conf get() = ChatService.conf.translate
    private val jobs = Channel<Job>(256, BufferOverflow.DROP_OLDEST)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER) // Google's block answers with a redirect to /sorry
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val coolUntil = ConcurrentHashMap<String, Long>()

    // Touched by the worker alone, one batch at a time.
    private val cache = object : LinkedHashMap<Ask, Translation>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Ask, Translation>) = size > conf.cacheSize
    }

    private val worker by lazy { scope.launch { work() } }

    /** Called on the game thread with a public message that already passed every chat filter. */
    fun submit(sender: Player, message: String) {
        val c = conf
        if (!c.enabled) return
        val text = clean(message)
        if (!worthTranslating(text, c.minLetters, c.skip)) return
        val cyrillic = cyrillicOnly(text)
        val understood = c.understood.map(::base)
        if (cyrillic) learn(sender.uuid(), understood)

        val readers = HashMap<String, MutableList<String>>()
        for (data in players) {
            if (data.uuid == sender.uuid() || data.status.containsKey(OFF_KEY)) continue
            val lang = targetLanguage(data.languageChoice ?: data.player.locale()) ?: continue
            if (cyrillic && (base(lang) in understood || reads(data.status[READS_KEY]).any { it in understood })) continue
            readers.getOrPut(lang) { mutableListOf() }.add(data.uuid)
        }
        if (readers.isEmpty()) return

        val ordered = readers.entries.sortedByDescending { it.value.size }.associateTo(LinkedHashMap()) { it.key to it.value.toList() }
        jobs.trySend(Job(sender.plainName().replace("[", "[["), sender.uuid(), text, ordered, System.currentTimeMillis()))
        worker.start()
    }

    private suspend fun work() {
        while (true) {
            val batch = mutableListOf(jobs.receive())
            delay(conf.batchMillis)
            while (true) batch += jobs.tryReceive().getOrNull() ?: break
            try {
                process(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.warn("Chat translation failed: @", e.toString())
            }
        }
    }

    private fun process(batch: List<Job>) {
        val c = conf
        val understood = c.understood.map(::base)
        val now = System.currentTimeMillis()
        val fresh = batch.filter { now - it.at < c.maxAgeSeconds * 1000L }

        // The most-read language first: its answer also says what language the text is in, so the second
        // round skips readers who already understand it instead of spending a request and a slot on them.
        val first = fresh.associateWith { it.readers.keys.first() }
        val found = translate(fresh.map { it.text to first.getValue(it) }.distinct(), c)
        for (job in fresh) found[job.text to first.getValue(job)]?.source?.takeIf { it.isNotBlank() }?.let { learn(job.senderUuid, listOf(base(it))) }
        val second = fresh.flatMap { job ->
            val primary = found[job.text to first.getValue(job)] ?: return@flatMap emptyList()
            val budget = c.maxLanguages - if (worthShowing(job.text, primary, first.getValue(job), understood)) 1 else 0
            job.readers.keys.drop(1)
                .filter { needs(primary.source, it, understood) }
                .take(budget.coerceAtLeast(0))
                .map { job.text to it }
        }.distinct()
        found += translate(second, c)

        val lines = mutableListOf<Triple<String, String, String>>()
        for (job in fresh) for ((lang, uuids) in job.readers) {
            val t = found[job.text to lang] ?: continue
            if (!worthShowing(job.text, t, lang, understood)) continue
            val line = c.format.replace("{player}", job.sender).replace("{text}", t.text.replace("[", "[["))
            uuids.forEach { lines += Triple(it, line, t.source) }
        }
        if (lines.isEmpty()) return
        Core.app.post {
            // Looked up again here: a reader may have left, or typed /tr, while the request was out.
            for ((uuid, line, source) in lines) {
                val data = findPlayerData(uuid) ?: continue
                if (data.status.containsKey(OFF_KEY)) continue
                if (understands(reads(data.status[READS_KEY]), source, understood)) continue
                data.sendDirect(line)
            }
        }
    }

    /** Cache first, then each backend in order for whatever is still missing. */
    private fun translate(asks: List<Ask>, c: TranslateConfig): MutableMap<Ask, Translation> {
        val found = HashMap<Ask, Translation>()
        for (ask in asks) cache[ask]?.let { found[ask] = it }
        for ((name, backend) in backends(c)) {
            val missing = asks.filter { it !in found }
            if (missing.isEmpty()) break
            if ((coolUntil[name] ?: 0L) > System.currentTimeMillis()) continue
            try {
                backend(missing, found)
            } catch (e: Exception) {
                val seconds = (e as? Limited)?.seconds ?: c.cooldownSeconds.toLong()
                coolUntil[name] = System.currentTimeMillis() + seconds * 1000
                Log.warn("Chat translation: @ failed, skipping it for @ s: @", name, seconds, e.toString())
            }
        }
        found.forEach { (ask, t) -> cache[ask] = t }
        return found
    }

    private fun backends(c: TranslateConfig) = buildList<Pair<String, (List<Ask>, MutableMap<Ask, Translation>) -> Unit>> {
        if (c.google) add("google" to ::google)
        if (c.groqKey.isNotBlank()) c.groqModels.forEach { m -> add("groq $m" to { a, f -> groq(m, c.groqKey, a, f) }) }
        if (c.geminiKey.isNotBlank()) c.geminiModels.forEach { m -> add("gemini $m" to { a, f -> gemini(m, c.geminiKey, a, f) }) }
    }

    // The Chrome extension's endpoint: no key, several texts per request, and a detected language for each
    // one. translate_a/single?client=gtx is the better-known one and was already answering 429 in 2026-10.
    private fun google(asks: List<Ask>, found: MutableMap<Ask, Translation>) {
        for ((lang, group) in asks.groupBy { it.second }) for (chunk in group.chunked(50)) {
            val body = chunk.joinToString("&") { "q=" + URLEncoder.encode(it.first, Charsets.UTF_8) }
            val url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=" + URLEncoder.encode(lang, Charsets.UTF_8)
            val items = parseGoogle(post(url, body, "application/x-www-form-urlencoded"), chunk.size)
            chunk.forEachIndexed { i, ask -> found[ask] = items[i] }
        }
    }

    private fun groq(model: String, key: String, asks: List<Ask>, found: MutableMap<Ask, Translation>) {
        for (chunk in asks.chunked(40)) {
            val body = buildJsonObject {
                put("model", model)
                put("temperature", 0.2)
                putJsonObject("response_format") { put("type", "json_object") }
                if (model.startsWith("openai/gpt-oss")) put("reasoning_effort", "low")
                putJsonArray("messages") {
                    addJsonObject { put("role", "system"); put("content", PROMPT) }
                    addJsonObject { put("role", "user"); put("content", llmInput(chunk)) }
                }
            }
            val answer = json.parseToJsonElement(
                post("https://api.groq.com/openai/v1/chat/completions", body.toString(), "application/json", "Authorization" to "Bearer $key")
            )
            val content = answer.jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw IOException("no content in ${answer.toString().take(200)}")
            parseLlm(content, chunk, found)
        }
    }

    private fun gemini(model: String, key: String, asks: List<Ask>, found: MutableMap<Ask, Translation>) {
        for (chunk in asks.chunked(40)) {
            val body = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", PROMPT) } } }
                putJsonArray("contents") {
                    addJsonObject { put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", llmInput(chunk)) } } }
                }
                putJsonObject("generationConfig") { put("responseMimeType", "application/json"); put("temperature", 0.2) }
            }
            val answer = json.parseToJsonElement(
                post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", body.toString(), "application/json", "x-goog-api-key" to key)
            )
            val parts = answer.jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?: throw IOException("no content in ${answer.toString().take(200)}")
            val content = parts.map { it.jsonObject }
                .filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
                .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
            parseLlm(content, chunk, found)
        }
    }

    private fun post(url: String, body: String, type: String, vararg headers: Pair<String, String>): String {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(conf.timeoutSeconds.toLong()))
            .header("Content-Type", type)
            .header("User-Agent", "Mozilla/5.0")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val code = response.statusCode()
        if (code == 429 || code == 503) {
            val retry = response.headers().firstValue("retry-after").orElse(null)?.toDoubleOrNull()
            throw Limited(retry?.let { ceil(it).toLong().coerceAtLeast(1) } ?: conf.cooldownSeconds.toLong(), "HTTP $code")
        }
        if (code !in 200..299) throw IOException("HTTP $code ${response.body().take(200)}")
        return response.body()
    }

    private const val PROMPT = """You translate the in-game chat of Mindustry, a factory-building and tower-defence game.
The user message is a JSON array of items {"id", "text", "to"}. For each item, detect the language of "text" and translate it into the language whose code is in "to".
Write the way a player writes in chat: short and informal, no explanations, no quotes around it. Keep player names, numbers and emoji as they are. Blocks, units and items take the names the game's own localization uses in that language.
Answer only with JSON: {"items":[{"id":0,"src":"<ISO 639-1 code of the source language>","text":"<translation>"}]}, one entry per input item, with the same ids."""

    private fun llmInput(asks: List<Ask>): String = buildJsonArray {
        asks.forEachIndexed { i, (text, to) -> addJsonObject { put("id", i); put("text", text); put("to", to) } }
    }.toString()

    // Pure helpers below, internal for the tests.

    private val url = Regex("""https?://\S+""")
    private val spaces = Regex("""\s+""")

    /** Color tags, links and the game's icon glyphs (private use area) out; none of them translate. */
    internal fun clean(message: String): String =
        Strings.stripColors(message).replace(url, " ").filterNot { it in ''..'' }.replace(spaces, " ").trim()

    internal fun worthTranslating(text: String, minLetters: Int, skip: List<String>): Boolean {
        if (text.codePoints().filter(Character::isLetter).count() < minLetters.coerceAtLeast(1)) return false
        val bare = text.trim { !it.isLetterOrDigit() }
        return skip.none { it.equals(bare, ignoreCase = true) }
    }

    internal fun cyrillicOnly(text: String): Boolean =
        text.codePoints().filter(Character::isLetter).allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.CYRILLIC }

    /** A Mindustry locale (`uk_UA`, `zh_TW`, `in_ID`) as the code the backends take. */
    internal fun targetLanguage(locale: String?): String? {
        val parts = locale.orEmpty().lowercase().split('_', '-')
        return when (val lang = parts[0]) {
            "" -> null
            "zh" -> if (parts.getOrNull(1) in setOf("tw", "hk", "mo", "hant")) "zh-TW" else "zh-CN"
            "in" -> "id"
            "iw" -> "he"
            else -> lang
        }
    }

    private fun base(lang: String) = lang.substringBefore('-').lowercase()

    private fun learn(uuid: String, langs: Collection<String>) {
        val data = findPlayerData(uuid) ?: return
        val known = reads(data.status[READS_KEY])
        if (!known.containsAll(langs)) data.status[READS_KEY] = (known + langs).sorted().joinToString(",")
    }

    internal fun reads(stored: String?): Set<String> = stored.orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    /** Whether someone who has written in [reads] already understands text in [source]. */
    internal fun understands(reads: Set<String>, source: String, understood: List<String>): Boolean {
        if (source.isBlank()) return false
        val from = base(source)
        return from in reads || (from in understood && reads.any { it in understood })
    }

    /** Whether a reader of [target] needs text written in [source]; unknown source means ask anyway. */
    internal fun needs(source: String, target: String, understood: List<String>): Boolean {
        if (source.isBlank()) return true
        val from = base(source)
        val to = base(target)
        return from != to && !(from in understood && to in understood)
    }

    internal fun worthShowing(original: String, t: Translation, target: String, understood: List<String>): Boolean =
        t.text.isNotBlank() && !t.text.equals(original, ignoreCase = true) && needs(t.source, target, understood)

    internal fun parseGoogle(body: String, expected: Int): List<Translation> {
        val items = json.parseToJsonElement(body).jsonArray
        if (items.size != expected) throw IOException("expected $expected translations, got ${items.size}")
        return items.map { item ->
            if (item is JsonArray) Translation(item[0].jsonPrimitive.content, item.getOrNull(1)?.jsonPrimitive?.contentOrNull.orEmpty())
            else Translation(item.jsonPrimitive.content, "")
        }
    }

    /** Takes what the model got right; an item it dropped or mangled stays missing for the next backend. */
    internal fun parseLlm(content: String, asks: List<Ask>, found: MutableMap<Ask, Translation>) {
        // JSON mode or not, a model sometimes still wraps the answer in a ```json fence.
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end < start) throw IOException("no JSON in ${content.take(200)}")
        val items = json.parseToJsonElement(content.substring(start, end + 1)).jsonObject["items"]?.jsonArray
            ?: throw IOException("no items in ${content.take(200)}")
        for (item in items) {
            val o = item as? JsonObject ?: continue
            val ask = o["id"]?.jsonPrimitive?.intOrNull?.let(asks::getOrNull) ?: continue
            val text = o["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
            found[ask] = Translation(text, o["src"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
}
