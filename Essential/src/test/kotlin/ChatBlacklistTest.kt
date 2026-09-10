import PluginTest.Companion.loadGame
import essential.common.rootPath
import essential.core.service.chat.ChatService
import essential.core.service.chat.isChatBlacklisted
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task-106: the blacklist file used to be re-read from disk and every entry re-compiled into a fresh
 * Pattern inside the filter itself, so all of it happened again for every chat message. It is now cached,
 * keyed on the file's mtime and size, so both tests below matter: caching must not mean the file is
 * frozen at boot, and a malformed entry must not take the whole filter down.
 */
class ChatBlacklistTest {
    companion object {
        private var done = false
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    private fun withBlacklist(regex: Boolean, contents: String, block: () -> Unit) {
        val file = rootPath.child("chat_blacklist.txt")
        val original = if (file.exists()) file.readString("UTF-8") else null
        val wasEnabled = ChatService.conf.blacklist.enabled
        val wasRegex = ChatService.conf.blacklist.regex
        try {
            file.writeString(contents)
            ChatService.conf.blacklist.enabled = true
            ChatService.conf.blacklist.regex = regex
            block()
        } finally {
            if (original != null) file.writeString(original) else file.delete()
            ChatService.conf.blacklist.enabled = wasEnabled
            ChatService.conf.blacklist.regex = wasRegex
        }
    }

    @Test
    fun anEditedFileIsPickedUpWithoutARestart() = withBlacklist(regex = false, contents = "banana") {
        assertTrue(isChatBlacklisted("i like banana bread"), "the entry just written must block on the first call")

        // A different length as well as a different mtime, so a cache keyed on either alone still sees
        // the edit - a live operator edits this file on a running server and expects it to take.
        rootPath.child("chat_blacklist.txt").writeString("cherry pie")
        assertFalse(
            isChatBlacklisted("i like banana bread"),
            "an edit to the file must be visible on the next call, not held from a stale cache"
        )
        assertTrue(isChatBlacklisted("a cherry pie"), "and the new entry must be the one in effect")
    }

    @Test
    fun aMalformedRegexEntryDoesNotTakeDownTheOthers() = withBlacklist(regex = true, contents = "(\nbanana") {
        // Used to throw PatternSyntaxException out of the filter on every single message once one bad
        // line was in the file, not just refuse the one entry. The good entry must still match, and a
        // message that touches neither entry must not throw evaluating the bad one.
        assertTrue(isChatBlacklisted("a banana split"), "a good entry after a malformed one must still match")
        assertFalse(isChatBlacklisted("nothing here"), "and a message matching nothing must not throw")
    }
}
