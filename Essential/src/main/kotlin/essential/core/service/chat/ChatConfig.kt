package essential.core.service.chat

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class ChatConfig(
    @YamlComment(
        "Global default chat format (leave empty to use permission group formats)",
        "Placeholders: {player} for player name, {message} for chat message",
    )
    var chatFormat: String = "",
    @YamlComment("Strict chat filtering (deprecated - not implemented)")
    var strict: StrictConfig = StrictConfig(),
    @YamlComment("Chat blacklist settings")
    var blacklist: BlacklistConfig = BlacklistConfig(),
    @YamlComment(
        "Translate public chat into each reader's language. The original message goes out at once,",
        "the translation follows as a separate line a moment later, only to players who need it.",
    )
    var translate: TranslateConfig = TranslateConfig()
)

@Serializable
data class TranslateConfig(
    @YamlComment("Turn chat translation on")
    var enabled: Boolean = false,
    @YamlComment("Line shown under a message. Placeholders: {player} sender name, {text} translation")
    var format: String = "[lightgray]  ↳ {player}: {text}",
    @YamlComment(
        "Languages whose readers understand each other: a message in one of them is not translated",
        "for a reader of another. Text written only in Cyrillic is taken as one of these without a request.",
    )
    var understood: List<String> = listOf("uk", "ru"),
    @YamlComment(
        "Most languages one message is translated into, picked by how many online players read each.",
        "Readers of the rest get no translation. Each language is one more request.",
    )
    var maxLanguages: Int = 3,
    @YamlComment("Messages with fewer letters than this are not translated (one Chinese character is one letter)")
    var minLetters: Int = 1,
    @YamlComment("Whole messages never translated, case-insensitive; game slang a translator garbles (gg comes back as рр)")
    var skip: List<String> = listOf(
        "gg", "ggwp", "wp", "gl", "hf", "glhf", "ok", "okay", "k", "lol", "xd", "ty", "thx", "np",
        "brb", "afk", "rtv", "y", "n", "yes", "no", "hi", "bye",
    ),
    @YamlComment("Milliseconds to collect messages into one batch before sending them")
    var batchMillis: Long = 500,
    @YamlComment("A translation that took longer than this many seconds is dropped instead of shown late")
    var maxAgeSeconds: Int = 20,
    @YamlComment("Translations remembered, so a repeated phrase costs no request")
    var cacheSize: Int = 2000,
    @YamlComment("Seconds to wait for one backend answer")
    var timeoutSeconds: Int = 8,
    @YamlComment("Seconds a backend is skipped after it fails or hits a limit, unless it says when to come back")
    var cooldownSeconds: Int = 300,
    @YamlComment(
        "Use Google Translate (free, no key, unofficial endpoint). Tried first.",
        "Translation sends nothing anywhere until this or one of the keys below is set.",
    )
    var google: Boolean = false,
    @YamlComment("Groq API key (console.groq.com, free plan). Empty skips Groq. Tried after Google.")
    var groqKey: String = "",
    @YamlComment("Groq models, tried in order")
    var groqModels: List<String> = listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b"),
    @YamlComment(
        "Gemini API key (aistudio.google.com, free tier). Empty skips Gemini. Tried last.",
        "On the free tier Google may use the chat sent to it to train its models.",
    )
    var geminiKey: String = "",
    @YamlComment("Gemini models, tried in order")
    var geminiModels: List<String> = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite"),
) {
    // The generated toString would print both keys into any log line that shows the config.
    override fun toString(): String =
        "TranslateConfig(enabled=$enabled, format=$format, understood=$understood, maxLanguages=$maxLanguages, " +
            "minLetters=$minLetters, skip=$skip, batchMillis=$batchMillis, maxAgeSeconds=$maxAgeSeconds, " +
            "cacheSize=$cacheSize, timeoutSeconds=$timeoutSeconds, cooldownSeconds=$cooldownSeconds, google=$google, " +
            "groqKey=${mask(groqKey)}, groqModels=$groqModels, geminiKey=${mask(geminiKey)}, geminiModels=$geminiModels)"

    private fun mask(key: String) = if (key.isEmpty()) "" else "***"
}

@Serializable
data class StrictConfig(
    @YamlComment("Enable strict filtering (currently unused)")
    var enabled: Boolean = false,
    @YamlComment("Language code for filtering (ko-KR: Korean, en-US: English, etc.)")
    var language: String = Locale.getDefault().toLanguageTag()
)

@Serializable
data class BlacklistConfig(
    @YamlComment("Enable blacklist filtering to block prohibited words in chat")
    var enabled: Boolean = false,
    @YamlComment("Use regex patterns for blacklist entries instead of plain text")
    var regex: Boolean = false
)
