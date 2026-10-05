package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [CompactPin] — [feat/compact-pin-v0-1005] pin v0.
 *
 * The core contract under test: user verbatim text survives every summary
 * rewrite byte-identically and NEVER passes through an LLM call (experiment
 * D showed a once-stated constraint is 0/10 recalled after one covering
 * turn — recency wins over any prompt clause).
 *
 * Expectations are written as literals, NOT derived from the production
 * constants (evidence-discipline: derived expectations mutate into PASS
 * when the constant changes).
 */
class CompactPinTest {

    // ── helpers ────────────────────────────────────────────────

    private fun user(text: String, parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.USER, content = text, contentParts = parts, dbMessageId = "u")

    private fun assistant(text: String) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, dbMessageId = "a")

    private fun toolResultUser() = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(AgentContentPart.ToolResult("t1", "read_file", "file body here")),
        dbMessageId = "tr",
    )

    private val pinTagOpen = "<pinned-user-messages>"
    private val pinTagClose = "</pinned-user-messages>"

    // ── extract ────────────────────────────────────────────────

    @Test
    fun `extract full set in chronological order`() {
        val msgs = listOf(
            user("first prompt"),
            assistant("assistant answer"),
            toolResultUser(),
            user("second prompt"),
        )
        assertEquals(listOf("first prompt", "second prompt"), extractPinnedUserMessages(msgs))
    }

    @Test
    fun `extract includes structured text parts of user messages`() {
        val msgs = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(AgentContentPart.Text("inline user text")),
                dbMessageId = "u",
            ),
        )
        assertEquals(listOf("inline user text"), extractPinnedUserMessages(msgs))
    }

    @Test
    fun `extract empty when no user text in region`() {
        val msgs = listOf(assistant("a"), toolResultUser())
        assertEquals(emptyList<String>(), extractPinnedUserMessages(msgs))
        assertEquals(emptyList<String>(), extractPinnedUserMessages(emptyList()))
    }

    @Test
    fun `extract over limit keeps recent fit plus earliest`() {
        // Pure ASCII: CHARS_PER_TOKEN=4 → 400 chars ≈ 100 tokens (literal
        // arithmetic, not derived from the constant).
        val early = "E".repeat(400)   // ~100 tokens
        val mid1 = "M1".repeat(400)  // ~200 tokens
        val mid2 = "M2".repeat(400)  // ~200 tokens
        val late = "L".repeat(400)   // ~100 tokens
        val msgs = listOf(user(early), user(mid1), user(mid2), user(late))
        // Cap 250: newest fit walk → late(100) + mid2(200) = 300 > 250 →
        // only late fits; mid1/mid2 dropped; earliest pinned unconditionally.
        val out = extractPinnedUserMessages(msgs, tokenLimit = 250L)
        assertEquals(2, out.size)
        assertEquals(early, out[0])
        assertEquals(late, out[1])
    }

    @Test
    fun `extract under limit returns all messages untouched`() {
        val msgs = listOf(user("alpha"), user("beta"), user("gamma"))
        val out = extractPinnedUserMessages(msgs, tokenLimit = 8000L)
        assertEquals(listOf("alpha", "beta", "gamma"), out)
    }

    // ── strip ──────────────────────────────────────────────────

    @Test
    fun `strip removes block plus separator keeping base`() {
        val block = pinTagOpen + "\nheader\n\nuser said X\n" + pinTagClose
        val summary = "base summary\n\n" + block
        assertEquals("base summary", stripPinnedSection(summary))
    }

    @Test
    fun `strip without block is byte identical no-op`() {
        val summary = "plain summary with\ntrailing newline\n"
        assertEquals(summary, stripPinnedSection(summary))
        assertEquals(null, stripPinnedSection(null))
        assertEquals("", stripPinnedSection(""))
    }

    @Test
    fun `strip is idempotent`() {
        val block = pinTagOpen + "\nheader\n\nuser said X\n" + pinTagClose
        val summary = "base\n\n" + block
        val once = stripPinnedSection(summary)
        assertEquals(once, stripPinnedSection(once))
    }

    // ── append ─────────────────────────────────────────────────

    @Test
    fun `append empty pin without carried is byte identical`() {
        val summary = "untouched summary"
        assertEquals(summary, appendPinnedSection(summary, emptyList(), null))
        assertEquals(summary, appendPinnedSection(summary, emptyList(), ""))
        assertEquals(summary, appendPinnedSection(summary, listOf("  "), null))
    }

    @Test
    fun `append twice is idempotent no double block`() {
        val once = appendPinnedSection("base", listOf("user said X"))
        val twice = appendPinnedSection(once, listOf("user said X"))
        assertEquals(once, twice)
        assertEquals(1, twice.split(pinTagOpen).size - 1)
    }

    @Test
    fun `append carried plus new keeps carried verbatim first`() {
        val inner1 = "Verbatim header line\n\nold user message"
        val out = appendPinnedSection("base", listOf("new user message"), inner1)
        val inner = pinnedSectionInner(out)
        assertTrue(inner != null)
        assertTrue(inner!!.startsWith(inner1 + "\n\n"))
        assertTrue(inner.contains("new user message"))
        assertFalse(inner.contains(pinTagOpen))
    }

    // ── round-trip (core contract) ─────────────────────────────

    /**
     * 3-round rewrite chain with a mock LLM that rewrites EVERYTHING it
     * sees into an opaque one-liner (worst case: the LLM drops all detail
     * it is fed). The pinned text survives only if the strip→append wiring
     * keeps it out of the LLM and re-attaches it after.
     */
    private fun rewriteRound(
        previousSummary: String?,
        foldMessages: List<LLMMessage>,
        llmInputs: MutableList<String>,
    ): String {
        // Mirrors the production wiring in
        // ChatSessionLifecycle.generateCompactSummaryWithSplitting
        // (feat/compact-pin-v0-1005): entry strip, exit re-append.
        val pinNew = extractPinnedUserMessages(foldMessages)
        val pinCarried = pinnedSectionInner(previousSummary)
        val stripped = stripPinnedSection(previousSummary)
        val llmInput = (stripped?.let { "Previous context summary:\n$it\n\n" } ?: "") + "New conversation to merge:\n"
        llmInputs.add(llmInput)
        val llmOut = "REWRITTEN#" + llmInputs.size
        return if (pinNew.isEmpty() && pinCarried == null) {
            llmOut
        } else {
            appendPinnedSection(llmOut, pinNew, pinCarried)
        }
    }

    @Test
    fun `round trip three rounds pin survives byte identical`() {
        val llmInputs = mutableListOf<String>()
        val r1 = rewriteRound(null, listOf(user("CONSTRAINT: answer in Latin")), llmInputs)
        val inner1 = pinnedSectionInner(r1)
        assertTrue(inner1 != null)
        assertTrue(inner1!!.contains("CONSTRAINT: answer in Latin"))

        val r2 = rewriteRound(r1, listOf(user("R2 turn about weather")), llmInputs)
        val inner2 = pinnedSectionInner(r2)
        assertTrue(inner2 != null)
        // Byte-identical survival of the round-1 payload (never rewritten).
        assertTrue(inner2!!.startsWith(inner1 + "\n\n"))
        assertTrue(inner2.contains("R2 turn about weather"))

        val r3 = rewriteRound(r2, listOf(user("R3 turn about taxes")), llmInputs)
        val inner3 = pinnedSectionInner(r3)
        assertTrue(inner3 != null)
        assertTrue(inner3!!.startsWith(inner2 + "\n\n"))
        assertTrue(inner3.contains("R3 turn about taxes"))

        // Exactly one block, no accumulation of tags.
        assertEquals(1, r3.split(pinTagOpen).size - 1)
    }

    @Test
    fun `llm input never contains pin payload across rounds`() {
        val llmInputs = mutableListOf<String>()
        val r1 = rewriteRound(null, listOf(user("SECRET VALUE 42")), llmInputs)
        rewriteRound(r1, listOf(user("R2 turn")), llmInputs)
        rewriteRound(previousSummary = r1, foldMessages = listOf(user("R3 turn")), llmInputs = llmInputs)
        for (input in llmInputs) {
            assertFalse(input.contains("SECRET VALUE 42"))
            assertFalse(input.contains(pinTagOpen))
        }
    }

    @Test
    fun `no pin sessions keep summary byte identical`() {
        val llmInputs = mutableListOf<String>()
        val msgs = listOf(assistant("a"), toolResultUser())
        val out = rewriteRound(null, msgs, llmInputs)
        assertEquals("REWRITTEN#1", out)
        assertFalse(out.contains(pinTagOpen))
    }

    // ── injection (proxy) ──────────────────────────────────────

    @Test
    fun `pin block rides inside context summary wrap`() {
        // Proxy for effectiveAgentHistory (ChatSessionLifecycle): the
        // `<context-summary>` wrap embeds the summary string verbatim and
        // is intentionally untouched by this task — this asserts the block
        // is intact inside that embed (device smoke verifies the real path).
        val summary = appendPinnedSection("base summary", listOf("user said X"))
        val wrapped = "<context-summary>\n...intro...\n\n" + summary + "\n</context-summary>"
        assertTrue(wrapped.contains(pinTagOpen))
        assertTrue(wrapped.contains("user said X"))
        val inner = pinnedSectionInner(summary)
        assertTrue(inner!!.contains("user said X"))
    }
}
