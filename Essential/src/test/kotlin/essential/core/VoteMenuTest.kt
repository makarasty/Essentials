package essential.core

import PluginTest.Companion.leavePlayer
import essential.common.bundle.Bundle
import essential.common.isVoting
import essential.core.service.vote.VoteSystem
import essential.core.ui.Sent
import essential.core.ui.UiHarness
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.FollowUpMenuCallPacket
import mindustry.gen.HideFollowUpMenuCallPacket
import mindustry.ui.Menus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A vote shows every voter a Yes / No follow-up menu; a button is the same input as the chat word. */
class VoteMenuTest {
    @BeforeTest
    fun setup() {
        UiHarness.load()
    }

    @AfterTest
    fun restore() {
        isVoting = false
        UiHarness.netServer(false)
    }

    private fun menus(s: Sent) = s.packets.filterIsInstance<FollowUpMenuCallPacket>()

    private fun hides(s: Sent) = s.packets.filterIsInstance<HideFollowUpMenuCallPacket>()

    private fun press(s: Sent, id: Int, option: Int) = Menus.menuChoose(s.player, id, option)

    /** [voters] on a normal map, the first of them the starter. */
    private fun withVote(voters: Int, admins: Int = 0, body: (List<Sent>, VoteSystem, Int) -> Unit) {
        val all = (1..voters).map { UiHarness.joined(if (it > voters - admins) "owner" else "user") }
        isVoting = true
        val vote = VoteSystem(VoteData(type = VoteType.Skip, wave = 1, starter = all[0].data))
        try {
            body(all, vote, menus(all[0]).single().menuId)
        } finally {
            vote.cancel()
            all.forEach { leavePlayer(it.player) }
        }
    }

    @Test
    fun everyVoterGetsAYesNoMenuWithTheVoteText() = withVote(3) { all, _, _ ->
        val bundle = Bundle()
        all.forEach {
            val menu = menus(it).single()
            assertEquals(listOf(bundle["command.menu.yes"], bundle["command.menu.no"]), menu.options.single().toList())
            assertEquals(bundle["command.vote.skip.start", 1], menu.message)
        }
    }

    @Test
    fun onAPvpMapOnlyTheStartersTeamGetsTheMenuAndOtherTeamPressesChangeNothing() {
        val wasPvp = Vars.state.rules.pvp
        val all = (1..3).map { UiHarness.joined("user") }
        val (starter, mate, rival) = all
        val teams = all.map { it.player.team() }
        try {
            Vars.state.rules.pvp = true
            starter.player.team(Team.crux)
            mate.player.team(Team.crux)
            rival.player.team(Team.sharded)
            isVoting = true
            val vote = VoteSystem(VoteData(type = VoteType.Skip, wave = 1, starter = starter.data))
            try {
                assertEquals(1, menus(starter).size)
                assertEquals(1, menus(mate).size)
                assertTrue(menus(rival).isEmpty(), "the other team is not told")
                val id = menus(starter).single().menuId
                rival.data.lastReceivedMessage = ""
                press(rival, id, 0) // a forged packet
                assertEquals(1, vote.votes())
                assertEquals("", rival.data.lastReceivedMessage)
                press(mate, id, 0)
                assertEquals(2, vote.votes())
            } finally {
                vote.cancel()
            }
        } finally {
            isVoting = false
            Vars.state.rules.pvp = wasPvp
            all.forEachIndexed { i, s -> s.player.team(teams[i]) }
            all.forEach { leavePlayer(it.player) }
        }
    }

    @Test
    fun yesButtonCountsLikeTheChatWordAndHidesTheMenuForThatVoter() = withVote(4) { all, vote, id ->
        val (_, button, chat, other) = all
        press(button, id, 0)
        assertEquals(2, vote.votes())
        assertEquals(1, hides(button).size)
        assertTrue(hides(other).isEmpty(), "only the voter's own menu goes")
        assertEquals(button.data.lastReceivedMessage, run { Vars.netServer.admins.filterMessage(chat.player, "y"); chat.data.lastReceivedMessage })
        assertEquals(3, vote.votes())
    }

    @Test
    fun noButtonIsNotAVoteFromAnOrdinaryPlayerAndCancelsFromAVotePassHolder() = withVote(3, admins = 1) { all, vote, id ->
        press(all[1], id, 1)
        assertEquals(1, vote.votes())
        vote.run()
        assertTrue(isVoting, "an ordinary No cancels nothing, as the word n does not")
        press(all.last(), id, 1)
        vote.run()
        assertFalse(isVoting, "a vote.pass holder's No cancels, as the word n does")
    }

    /** On a PvP map: team crux starts, a vote.pass holder from [team] types "n"; whether the vote survived. */
    private fun pvpNoSurvives(team: Team): Boolean {
        val wasPvp = Vars.state.rules.pvp
        val all = listOf(UiHarness.joined("user"), UiHarness.joined("user"), UiHarness.joined("owner"))
        val (starter, mate, admin) = all
        val teams = all.map { it.player.team() }
        try {
            Vars.state.rules.pvp = true
            starter.player.team(Team.crux)
            mate.player.team(Team.crux)
            admin.player.team(team)
            isVoting = true
            val vote = VoteSystem(VoteData(type = VoteType.Skip, wave = 1, starter = starter.data))
            try {
                Vars.netServer.admins.filterMessage(admin.player, "n")
                vote.run()
                return isVoting
            } finally {
                vote.cancel()
            }
        } finally {
            isVoting = false
            Vars.state.rules.pvp = wasPvp
            all.forEachIndexed { i, s -> s.player.team(teams[i]) }
            all.forEach { leavePlayer(it.player) }
        }
    }

    @Test
    fun aVotePassHolderOnTheOtherPvpTeamCannotCancelWithTheWordN() = assertTrue(pvpNoSurvives(Team.sharded))

    @Test
    fun aVotePassHolderOnTheStartersPvpTeamCancelsWithTheWordN() = assertFalse(pvpNoSurvives(Team.crux))

    @Test
    fun aDoublePressCountsOnce() = withVote(4) { all, vote, id ->
        press(all[1], id, 0)
        press(all[1], id, 0)
        assertEquals(2, vote.votes())
    }

    @Test
    fun aPressAfterTheVoteEndedIsIgnoredAndTheMenusAreHidden() = withVote(3) { all, vote, id ->
        vote.cancel()
        all.forEach { assertEquals(1, hides(it).size, "every menu hidden when the vote ends") }
        press(all[1], id, 0)
        assertEquals(1, vote.votes())
        assertEquals(1, hides(all[1]).size)
    }

    @Test
    fun anAfkFlaggedVoterGetsTheMenuAndTheirPressCountsLikeTheWordY() {
        val all = (1..4).map { UiHarness.joined("user") }
        val (starter, buttonAfk, chatAfk) = all
        buttonAfk.data.afk = true
        chatAfk.data.afk = true
        isVoting = true
        val vote = VoteSystem(VoteData(type = VoteType.Skip, wave = 1, starter = starter.data))
        try {
            assertEquals(1, menus(buttonAfk).size, "the announcement set gets the menu, afk flag or not")
            press(buttonAfk, menus(starter).single().menuId, 0)
            assertEquals(2, vote.votes())
            Vars.netServer.admins.filterMessage(chatAfk.player, "y")
            assertEquals(3, vote.votes(), "the word counts the same as the button")
        } finally {
            vote.cancel()
            all.forEach { leavePlayer(it.player) }
        }
    }

    @Test
    fun aChatVoteHidesThatVotersMenu() = withVote(3) { all, _, _ ->
        Vars.netServer.admins.filterMessage(all[1].player, "y")
        assertEquals(1, hides(all[1]).size)
        assertTrue(hides(all[2]).isEmpty())
    }

    @Test
    fun aKickVotesTargetGetsNoMenuAndAForgedPressFromThemChangesNothing() {
        val all = (1..4).map { UiHarness.joined("user") }
        val (starter, target, other) = all
        isVoting = true
        val vote = VoteSystem(VoteData(type = VoteType.Kick, target = target.player, targetUUID = target.player.uuid(), reason = "test", starter = starter.data))
        try {
            assertTrue(menus(target).isEmpty(), "the target is not asked to vote on their own kick")
            assertEquals(1, menus(starter).size)
            assertEquals(1, menus(other).size)
            val id = menus(starter).single().menuId
            val before = vote.votes()
            press(target, id, 0) // a forged packet
            press(target, id, 1)
            assertEquals(before, vote.votes())
            assertTrue(hides(target).isEmpty())
            press(other, id, 0)
            assertEquals(before + 1, vote.votes())
        } finally {
            vote.cancel()
            all.forEach { leavePlayer(it.player) }
        }
    }
}
