package essential.common.bundle

import java.text.MessageFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class Bundle {
    companion object {
        /**
         * ResourceBundle's documented search order tries the requested locale's candidates, then the
         * JVM default locale's candidates, and only then the base bundle. This ships bundles for ja,
         * ko, uk and zh, so on a ko-locale host a de client was answered in Korean instead of English.
         * A no-fallback control drops that middle step, leaving requested locale then base bundle.
         */
        private val CONTROL: ResourceBundle.Control =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

        private val resolved = ConcurrentHashMap<String, ConcurrentHashMap<Locale, ResourceBundle>>()

        /**
         * Resolves a bundle without the JVM default locale standing in for a missing translation.
         *
         * Memoised: every player message built a Bundle and asked [translated] first, two lookups
         * through ResourceBundle's own cache per message. That cache never expires an entry under
         * this control either, so holding the answer here changes nothing but the cost.
         */
        fun resolve(baseName: String, locale: Locale): ResourceBundle =
            resolved.computeIfAbsent(baseName) { ConcurrentHashMap() }
                .computeIfAbsent(locale) { ResourceBundle.getBundle(baseName, it, CONTROL) }

        private val NO_ARGS = arrayOf<Any>()

        private val translatedLocales = ConcurrentHashMap<Locale, Boolean>()

        /** A parsed pattern for one key in one bundle and locale, shared by every Bundle that asks. */
        private data class FormatKey(val resource: ResourceBundle, val locale: Locale, val key: String)

        private val formats = ConcurrentHashMap<FormatKey, MessageFormat>()

        /**
         * Formats [key] from a pattern parsed once rather than on every message. MessageFormat is not
         * thread-safe and bundles are used from coroutines and web handlers as well as the game thread,
         * so each shared instance formats under its own lock; two threads rarely want the same key.
         */
        private fun format(resource: ResourceBundle, locale: Locale, key: String, args: Array<out Any>): String {
            val format = formats.computeIfAbsent(FormatKey(resource, locale, key)) {
                MessageFormat(resource.getString(key), locale)
            }
            return synchronized(format) { format.format(args) }
        }

        /**
         * Whether a translation of its own ships for [locale], rather than it falling through to the
         * base bundle.
         *
         * English is the base bundle and has no `bundle_en.properties` of its own, so it has to be
         * named here; every other language is answered by asking what [resolve] actually landed on.
         */
        fun translated(locale: Locale): Boolean = translatedLocales.computeIfAbsent(locale) {
            it.language == "en" || resolve("bundles/common/bundle", it).locale.language.isNotEmpty()
        }

        /**
         * Language tags this build ships a translation for, English included.
         *
         * Read off the resources rather than listed by hand: adding a `bundle_xx.properties` is all
         * TRANSLATING.md asks a translator to do, and a list here would be the one step nobody
         * remembers. Walking every locale the JVM knows is not cheap, so it is done once, on the
         * first `/lang` of the process.
         */
        val translations: List<String> by lazy {
            Locale.getAvailableLocales()
                .asSequence()
                .map { resolve("bundles/common/bundle", it).locale }
                .filter { it.language.isNotEmpty() }
                .map { it.toLanguageTag() }
                .plus("en")
                .distinct()
                .sorted()
                .toList()
        }
    }

    var resource: ResourceBundle
    var prefix: String = ""
    var locale: Locale = Locale.getDefault()

    constructor() {
        resource = resolve("bundles/common/bundle", locale)
    }

    constructor(source: ResourceBundle) {
        resource = source
    }

    constructor(baseName: String, locale: Locale) {
        this.locale = locale
        resource = resolve(baseName, locale)
    }

    constructor(languageTag: String) {
        this.locale = Locale.forLanguageTag(languageTag.replace("_", "-"))
        resource = resolve("bundles/common/bundle", locale)
    }

    constructor(languageTag: String, source: ResourceBundle) {
        this.locale = Locale.forLanguageTag(languageTag.replace("_", "-"))
        resource = source
    }

    operator fun get(key: String): String = text(key, NO_ARGS)

    operator fun get(key: String, vararg parameter: Any): String = text(key, parameter)

    private fun text(key: String, args: Array<out Any>): String {
        if (!resource.containsKey(key)) return key
        val text = format(resource, locale, key, args)
        return if (prefix.isEmpty()) text else "$prefix $text"
    }
}
