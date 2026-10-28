package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HealTest {
    private class FakeLlm(private val answer: String) : LlmClient {
        override suspend fun complete(system: String, user: String): String = answer
    }

    @Test
    fun parsesATapProposalEvenWithSurroundingText() = runTest {
        val healer =
            Healer(
                FakeLlm(
                    "Sure!\n```json\n{\"action\":\"tap\",\"id\":null,\"text\":\"Continue\",\"index\":0,\"reason\":\"the only button\"}\n```"
                )
            )

        val proposal =
            healer.proposeAction("Welcome page, continue", "tap id=Onboarding.Welcome.Continue", "Button text=Continue")

        assertEquals("tap", proposal?.kind)
        assertEquals("Continue", proposal?.selector?.text)
        assertEquals("the only button", proposal?.reason)
    }

    @Test
    fun noFittingElementGivesNoProposal() = runTest {
        val healer = Healer(FakeLlm("{\"action\":\"none\",\"reason\":\"nothing fits\"}"))

        assertNull(healer.proposeAction("x", "tap id=a", "tree"))
    }

    @Test
    fun garbageAnswerGivesNoProposal() = runTest {
        assertNull(Healer(FakeLlm("I cannot help")).proposeAction("x", "tap id=a", "tree"))
    }

    @Test
    fun assertionIsOnlyHealedWhenTheModelConfirmsIt() = runTest {
        val yes = Healer(FakeLlm("{\"holds\":true,\"reason\":\"details screen shown\"}")).checkAssertion("s", "a", "t")
        val no = Healer(FakeLlm("{\"holds\":false,\"reason\":\"still on the list\"}")).checkAssertion("s", "a", "t")
        val unclear = Healer(FakeLlm("maybe")).checkAssertion("s", "a", "t")

        assertTrue(yes.first)
        assertFalse(no.first)
        assertFalse(unclear.first)
    }

    @Test
    fun warningListsMissingIdsWithTheSayThatWasUsed() {
        val records =
            listOf(
                HealRecord(
                    "Onboarding",
                    "Welcome page, continue",
                    "id=Onboarding.Welcome.Continue",
                    "tap text=\"Continue\"",
                    "only button",
                )
            )

        val text = HealReport.warnings(records)

        assertTrue(text.contains("id=Onboarding.Welcome.Continue"))
        assertTrue(text.contains("Welcome page, continue"))
        assertTrue(text.contains("tap text=\"Continue\""))
        assertEquals("", HealReport.warnings(emptyList()))
    }

    @Test
    fun aNamedCliSourceNeedsThatCliOnThePath() {
        assertTrue(LlmClients.create(HealSource.Claude, commandAvailable = { true }) is ClaudeCliClient)
        assertTrue(LlmClients.create(HealSource.OpenCode, commandAvailable = { true }) is OpenCodeCliClient)
        val noClaude = assertFailsWith<IllegalStateException> { LlmClients.create(HealSource.Claude) { false } }
        assertTrue(noClaude.message.orEmpty().contains("claude"))
        val noOpenCode = assertFailsWith<IllegalStateException> { LlmClients.create(HealSource.OpenCode) { false } }
        assertTrue(noOpenCode.message.orEmpty().contains("opencode"))
    }

    @Test
    fun theSourcesAreNamedClaudeOpencodeAndApi() {
        assertEquals(listOf("claude", "opencode", "api"), HealSource.entries.map { it.key })
    }
}
