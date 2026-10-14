package essential.core.service.chat

import PluginTest.Companion.loadGame
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.newPlayer
import mindustry.Vars.netServer
import mindustry.game.EventType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The translator ships off. With the default config a chat line between two players of different languages
 * must cost exactly what it did before the feature: no HTTP client, no worker, no request, the same line.
 */
class ChatTranslatorOffTest {
    @BeforeTest
    fun setup() {
        loadGame(true)
    }

    @Test
    fun theDefaultConfigSendsNothingAndLeavesChatAlone() {
        assertEquals(TranslateConfig().toString(), ChatService.conf.translate.toString(), "the test runs on the shipped default")
        val (sender, _) = newPlayer()
        val (reader, _) = newPlayer()
        try {
            sender.locale = "en"
            reader.locale = "uk_UA"
            val message = "where is the core?"

            ChatService.conf.translate.enabled = false
            val before = netServer.chatFormatter.format(sender, message)
            // The engine's order for public chat: the event with the raw text, then the formatter.
            EventType.PlayerChatEvent(sender, message).let { arc.Events.fire(it) }
            assertNull(publicSender, "nothing marks a line while translation is off")
            val after = netServer.chatFormatter.format(sender, message)
            ChatTranslator.submit(sender, message) // the /me route

            assertEquals(before, after, "the line itself is unchanged")
            assertFalse(ChatTranslator.httpStarted, "no HTTP client, so none of its threads")
            assertFalse(ChatTranslator.workerStarted, "no worker coroutine")
            assertEquals(0, ChatTranslator.budget.usedToday, "no request")
        } finally {
            leavePlayer(sender)
            leavePlayer(reader)
        }
    }

    @Test
    fun aCommandClearsThePublicMark() {
        val (player, _) = newPlayer()
        val conf = ChatService.conf.translate
        try {
            // Active, so the mark is set, but nothing below reaches the formatter, so no request goes out.
            conf.enabled = true
            conf.google = true
            markPublicChat(EventType.PlayerChatEvent(player, "hello there"))
            assertSame(player, publicSender)
            // A filter dropped that line, so the formatter never consumed the mark. Team chat comes next
            // in the same frame: it must not inherit it, or /t would be translated for both teams.
            markPublicChat(EventType.PlayerChatEvent(player, "/t secret plan"))
            assertNull(publicSender)
        } finally {
            conf.enabled = false
            conf.google = false
            publicSender = null
            leavePlayer(player)
        }
    }

    @Test
    fun aLayoutFixedTeamCommandIsNotPublic() {
        // ".е msg" is /t typed on a Cyrillic layout; the layout filter runs it inside the filter chain, and
        // the team-chat formatter call it makes must not find the line marked public.
        val (player, _) = newPlayer()
        val conf = ChatService.conf.translate
        try {
            conf.enabled = true
            conf.google = true
            assertTrue(essential.core.Main.conf.command.layoutFix, "the shipped default")
            markPublicChat(EventType.PlayerChatEvent(player, "hello there"))
            assertSame(player, publicSender)
            markPublicChat(EventType.PlayerChatEvent(player, ".е secret plan"))
            assertNull(publicSender)
            markPublicChat(EventType.PlayerChatEvent(player, ".ф admins only"))
            assertNull(publicSender)
        } finally {
            conf.enabled = false
            conf.google = false
            publicSender = null
            leavePlayer(player)
        }
    }
}
