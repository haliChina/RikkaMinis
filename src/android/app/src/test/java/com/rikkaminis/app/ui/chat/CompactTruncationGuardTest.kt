package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [fix/compact-truncation-guard-1005]:
 *
 *  A. the compaction summary truncation guard — a SendResult.Success whose
 *     stopReason says the output was cut at the ceiling ("length") must NOT be
 *     adopted; it feeds `lastFailure` and the chain falls through to the next
 *     candidate (A-1), while normal completion spellings (null / "end_turn" /
 *     "stop") pass untouched (A-2). An all-truncated chain therefore throws a
 *     failure whose message names the truncation (A-3).
 *  B. the noisy-member budget adaptation — `declaresNoEffortTiers == true`
 *     candidates get 60s instead of the 30s quiet floor (B-1), still clamped
 *     by the 120s chain deadline.
 *
 * Expected values are literal — never derived from the tested constants, so a
 * silent change of a budget or a verdict fails these instead of following the
 * mutation (JVM 装置自指陷阱, 2026-09-26). Wiring is pinned on source
 * fragments — order of named fragments only, per the source-text-assertion
 * lesson (2026-10-01). The end-to-end "all candidates truncated → throw"
 * shape (A-3) is pinned as the composition: the guard assigns `lastFailure`
 * and `continue`s (wiring pin below), and the loop's terminal `throw
 * lastFailure` is the pre-existing line pinned by CompactQuietFirstTest.
 */
class CompactTruncationGuardTest {

    // ── A: truncation verdict ───────────────────────────────────────────

    @Test
    fun `stopReason length is the truncated spelling that fires`() {
        assertTrue(compactSummaryIsTruncated("length"))
    }

    @Test
    fun `normal completion spellings pass the guard untouched`() {
        // A-2:防误伤 — these are the completion markers the observed channels
        // emit for a genuinely finished summary; none may be discarded.
        assertFalse(compactSummaryIsTruncated(null))
        assertFalse(compactSummaryIsTruncated("end_turn"))
        assertFalse(compactSummaryIsTruncated("stop"))
        assertFalse(compactSummaryIsTruncated(""))
    }

    @Test
    fun `cosmetic case and padding of length still fires`() {
        // Relays occasionally echo the spelling with padding or odd casing;
        // the guard normalises those without widening the trigger set.
        assertTrue(compactSummaryIsTruncated(" Length "))
    }

    @Test
    fun `truncation failure message names the model and the stop reason`() {
        val msg = compactSummaryTruncatedFailure("GLM-5.3-Flash").message.orEmpty()
        assertTrue(msg.contains("truncated"))
        assertTrue(msg.contains("stopReason=length"))
        assertTrue(msg.contains("GLM-5.3-Flash"))
    }

    // ── B: budget adaptation (literal values, not derived) ──────────────

    @Test
    fun `noisy candidate gets the 60s budget`() {
        assertEquals(
            60_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = true, nowMs = 0L, deadlineAt = 120_000L,
            ),
        )
    }

    @Test
    fun `quiet and unknown candidates keep the 30s budget`() {
        assertEquals(
            30_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = false, nowMs = 0L, deadlineAt = 120_000L,
            ),
        )
        assertEquals(
            30_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = null, nowMs = 0L, deadlineAt = 120_000L,
            ),
        )
    }

    @Test
    fun `chain deadline still clamps either budget`() {
        assertEquals(
            20_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = true, nowMs = 100_000L, deadlineAt = 120_000L,
            ),
        )
        assertEquals(
            15_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = false, nowMs = 105_000L, deadlineAt = 120_000L,
            ),
        )
    }

    @Test
    fun `spent deadline yields a non-positive budget so the chain stops`() {
        assertTrue(
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = true, nowMs = 120_000L, deadlineAt = 120_000L,
            ) <= 0L,
        )
        assertTrue(
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = false, nowMs = 130_000L, deadlineAt = 120_000L,
            ) <= 0L,
        )
    }

    @Test
    fun `noisy candidate budget constant is the measured 60s`() {
        assertEquals(60_000L, ChatViewModel.COMPACT_SUMMARY_NOISY_CANDIDATE_BUDGET_MS)
    }

    // ── wiring: the call site consumes the guards ───────────────────────
    //
    // generateCompactSummary lives on ChatViewModel (uninstantiable in JVM),
    // so the integration is pinned on source fragments — order of named
    // fragments only, per the source-text-assertion lesson (2026-10-01).

    @Test
    fun `success branch guards truncated summaries before adopting the text`() {
        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/ui/chat/ChatSessionLifecycle.kt",
        )
        val successRegion = lifecycle
            .substringAfter("is ProviderExecutionGateway.SendResult.Success -> {")
            .substringBefore("is ProviderExecutionGateway.SendResult.RemoteFailure ->")
        val atGuard = successRegion.indexOf("compactSummaryIsTruncated(r.response.stopReason)")
        val atTruncatedLog = successRegion.indexOf("summary TRUNCATED")
        val atFailure = successRegion.indexOf("lastFailure = compactSummaryTruncatedFailure(")
        val atContinue = successRegion.indexOf("continue")
        val atSuccessLog = successRegion.indexOf("SUCCESS")
        val atReturn = successRegion.indexOf("return r.response.text")
        for ((name, idx) in listOf(
            "truncation guard" to atGuard, "TRUNCATED log" to atTruncatedLog,
            "failure assignment" to atFailure, "continue" to atContinue,
            "SUCCESS log" to atSuccessLog, "text adoption" to atReturn,
        )) {
            assertTrue("success branch missing $name", idx >= 0)
        }
        // The guard must run BEFORE everything it gates: the failure is
        // recorded and the loop continues (next candidate) ahead of the
        // success log and the text adoption (A-1).
        assertTrue(atGuard < atTruncatedLog)
        assertTrue(atTruncatedLog < atFailure)
        assertTrue(atFailure < atContinue)
        assertTrue(atContinue < atSuccessLog)
        assertTrue(atSuccessLog < atReturn)
    }

    @Test
    fun `budget call site resolves the candidate budget through the helper`() {
        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/ui/chat/ChatSessionLifecycle.kt",
        )
        val atCall = lifecycle.indexOf("val budgetMs = compactionCandidateBudgetMs(")
        assertTrue("budget calc not wired through compactionCandidateBudgetMs", atCall >= 0)
        // The noisy flag must be one of the arguments (B-1), not merely a
        // later unrelated reference: keep the match inside the call window.
        val atFlagArg = lifecycle.indexOf("candidate.model.declaresNoEffortTiers", atCall)
        assertTrue(
            "noisy flag not passed into the budget helper call",
            atFlagArg > atCall && atFlagArg - atCall < 200,
        )
        // And the old quiet-only inline shape must be gone — re-inlining it
        // would silently drop the noisy adaptation.
        assertFalse(
            "budget must not be inlined again",
            lifecycle.contains("val budgetMs = minOf("),
        )
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir (`.../src/android/app`),
     * so a single-level parent lookup is not enough — CI caught this shape.
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
