package essential.core.service.chat

import essential.core.service.chat.ChatTranslator.Translation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decisions that keep chat translation cheap and quiet: what is worth a request at all, which readers
 * need a line, and how a backend answer is read. None of it needs a running server.
 */
class ChatTranslatorTest {
    private val understood = listOf("uk", "ru")
    private val skip = TranslateConfig().skip

    @Test
    fun cleanDropsColorsLinksAndIcons() {
        assertEquals("go to core", ChatTranslator.clean("[red]go[] to  core https://example.com/x"))
    }

    @Test
    fun onlyTextWithLettersAndNotSlangIsTranslated() {
        assertTrue(ChatTranslator.worthTranslating("好", 1, skip), "one Chinese character can carry a whole word")
        assertTrue(ChatTranslator.worthTranslating("where is the core?", 1, skip))
        assertFalse(ChatTranslator.worthTranslating("123 :) !!", 1, skip), "nothing to translate without letters")
        assertFalse(ChatTranslator.worthTranslating("GG!", 1, skip), "a translator turns gg into рр")
        assertFalse(ChatTranslator.worthTranslating("hey", 4, skip), "below minLetters")
    }

    @Test
    fun cyrillicOnlyIgnoresDigitsAndPunctuation() {
        assertTrue(ChatTranslator.cyrillicOnly("привіт, 2 хв!"))
        assertFalse(ChatTranslator.cyrillicOnly("привет gg"))
        assertFalse(ChatTranslator.cyrillicOnly("你好"))
    }

    @Test
    fun clientLocalesBecomeBackendCodes() {
        assertEquals("uk", ChatTranslator.targetLanguage("uk_UA"))
        assertEquals("zh-CN", ChatTranslator.targetLanguage("zh_CN"))
        assertEquals("zh-TW", ChatTranslator.targetLanguage("zh_TW"))
        assertEquals("id", ChatTranslator.targetLanguage("in_ID"))
        assertEquals("en", ChatTranslator.targetLanguage("en"))
        assertEquals(null, ChatTranslator.targetLanguage(""))
        assertEquals(null, ChatTranslator.targetLanguage(null))
    }

    @Test
    fun readersWhoUnderstandTheSourceGetNothing() {
        assertFalse(ChatTranslator.needs("ru", "uk", understood), "uk and ru read each other")
        assertFalse(ChatTranslator.needs("en", "en", understood))
        assertFalse(ChatTranslator.needs("zh-CN", "zh-TW", understood), "both are Chinese")
        assertTrue(ChatTranslator.needs("zh-CN", "uk", understood))
        assertTrue(ChatTranslator.needs("uk", "zh-CN", understood), "Chinese readers do get Ukrainian translated")
        assertTrue(ChatTranslator.needs("", "uk", understood), "unknown source, ask anyway")
    }

    @Test
    fun aReaderWhoWritesALanguageIsNotSentItTranslated() {
        // A Ukrainian on an English client: once they write Cyrillic, Ukrainian and Russian stop coming
        // to them as English.
        val reads = ChatTranslator.reads("ru,uk")
        assertTrue(ChatTranslator.understands(reads, "uk", understood))
        assertTrue(ChatTranslator.understands(ChatTranslator.reads("uk"), "ru", understood), "uk and ru read each other")
        assertFalse(ChatTranslator.understands(reads, "zh-CN", understood))
        assertFalse(ChatTranslator.understands(ChatTranslator.reads(null), "uk", understood), "nothing written yet")
        assertTrue(ChatTranslator.understands(ChatTranslator.reads("zh"), "zh-CN", understood))
    }

    @Test
    fun aTranslationEqualToTheOriginalIsNotShown() {
        assertFalse(ChatTranslator.worthShowing("PvP", Translation("pvp", "en"), "uk", understood))
        assertFalse(ChatTranslator.worthShowing("x", Translation(" ", "zh-CN"), "uk", understood))
        assertTrue(ChatTranslator.worthShowing("你好", Translation("Привіт", "zh-CN"), "uk", understood))
    }

    @Test
    fun googleAnswerKeepsOrderAndLanguagePerText() {
        // A real clients5 answer to five texts in one request, 2026-10-02.
        val body = """[["тобі потрібна допомога?","zh-CN"],["рр","en"],["де ядро?","en"],["привіт, як справи","uk"],["добре","zh-CN"]]"""
        val items = ChatTranslator.parseGoogle(body, 5)
        assertEquals(Translation("де ядро?", "en"), items[2])
        assertEquals("uk", items[3].source)
    }

    @Test
    fun aGoogleBlockPageOrShortAnswerIsAFailure() {
        assertFailsWith<Exception> { ChatTranslator.parseGoogle("<html>Sorry...</html>", 1) }
        assertFailsWith<Exception> { ChatTranslator.parseGoogle("""[["a","en"]]""", 2) }
    }

    @Test
    fun llmAnswerInAFenceStillParsesAndBadItemsStayMissing() {
        val asks = listOf("你好" to "uk", "where?" to "uk", "hola" to "uk")
        val found = HashMap<Ask, Translation>()
        val content = "```json\n{\"items\":[{\"id\":0,\"src\":\"zh\",\"text\":\" Привіт \"},{\"id\":7,\"text\":\"?\"},{\"id\":1,\"src\":\"en\"}]}\n```"
        ChatTranslator.parseLlm(content, asks, found)
        assertEquals(mapOf(asks[0] to Translation("Привіт", "zh")), found, "id 7 does not exist and id 1 has no text")
    }
}
