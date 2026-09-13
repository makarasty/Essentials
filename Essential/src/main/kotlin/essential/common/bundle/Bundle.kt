package essential.common.bundle

import java.text.MessageFormat
import java.util.*

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

        /** Resolves a bundle without the JVM default locale standing in for a missing translation. */
        fun resolve(baseName: String, locale: Locale): ResourceBundle =
            ResourceBundle.getBundle(baseName, locale, CONTROL)

        /**
         * Whether a translation of its own ships for [locale], rather than it falling through to the
         * base bundle.
         *
         * English is the base bundle and has no `bundle_en.properties` of its own, so it has to be
         * named here; every other language is answered by asking what [resolve] actually landed on.
         */
        fun translated(locale: Locale): Boolean =
            locale.language == "en" || resolve("bundles/common/bundle", locale).locale.language.isNotEmpty()

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

    operator fun get(key: String): String {
        if (!resource.containsKey(key)) return key

        return if (prefix.isEmpty()) {
            MessageFormat(resource.getString(key), locale).format(arrayOf<Any>())
        } else {
            "$prefix " + MessageFormat(resource.getString(key), locale).format(arrayOf<Any>())
        }
    }

    operator fun get(key: String, vararg parameter: Any): String {
        if (!resource.containsKey(key)) return key

        return if (prefix.isEmpty()) {
            MessageFormat(resource.getString(key), locale).format(parameter)
        } else {
            "$prefix " + MessageFormat(resource.getString(key), locale).format(parameter)
        }
    }
}
