package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [fix/compact-cancel-on-stop-1002]:
 *
 *  1. [queueDrainShouldDeferForCompact] — the queue drain defers ONLY while a
 *     LIVE compact exists; a stop-cancelled compact (job no longer active)
 *     must NOT stall a pending queue forever (its success kick never comes).
 *  2. Wiring source fragments: cancelStream actually kills compactJob +
 *     sendShellJob (in that order, after streamJob), the send shell and the
 *     compact launch actually store their jobs, and the compact marker
 *     insert rethrows CancellationException instead of swallowing it.
 *
 * Expected values are literal — never derived from the tested logic, so a
 * semantic flip fails these instead of following the mutation
 * (JVM 装置自指陷阱, 2026-09-26). Wiring is pinned on source fragments —
 * order of named fragments only, per the source-text-assertion lesson
 * (2026-10-01: judge sequence, never "A appears before B" across constructs).
 */
class CompactCancelOnStopTest {

    // ── deference predicate ─────────────────────────────────────────────

    @Test
    fun `defers only while a live compact is in flight`() {
        // Live compact → defer (draining would race the marker write).
        assertTrue(queueDrainShouldDeferForCompact(isCompacting = true, compactJobActive = true))
    }

    @Test
    fun `cancelled compact must not stall the drain`() {
        // The regression this fix prevents: stop cancelled the compact, the
        // stale _isCompacting flag is still true until the finally lands —
        // the drain must proceed because the success kick never comes.
        assertFalse(queueDrainShouldDeferForCompact(isCompacting = true, compactJobActive = false))
    }

    @Test
    fun `not compacting never defers`() {
        assertFalse(queueDrainShouldDeferForCompact(isCompacting = false, compactJobActive = true))
        assertFalse(queueDrainShouldDeferForCompact(isCompacting = false, compactJobActive = null))
    }

    @Test
    fun `unknown compact path keeps the conservative defer`() {
        // compactJob null = compact from an unknown path → keep the old
        // behavior (defer on the flag alone).
        assertTrue(queueDrainShouldDeferForCompact(isCompacting = true, compactJobActive = null))
    }

    // ── wiring: the cancel entries actually exist and are ordered ──────

    @Test
    fun `cancelStream kills stream compact and send shell in order`() {
        val vm = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
        val cancelBody = vm.substringAfter("fun cancelStream() {")
        val atStream = cancelBody.indexOf("streamJob?.cancel()")
        val atCompact = cancelBody.indexOf("compactJob?.cancel()")
        val atShell = cancelBody.indexOf("sendShellJob?.cancel()")
        assertTrue("cancelStream missing streamJob cancel", atStream >= 0)
        assertTrue("cancelStream missing compactJob cancel", atCompact >= 0)
        assertTrue("cancelStream missing sendShellJob cancel", atShell >= 0)
        // Sequence: stream first (today's kill), then compact, then the shell.
        assertTrue(atStream < atCompact)
        assertTrue(atCompact < atShell)
    }

    @Test
    fun `sendMessage shell and compactAll store their jobs`() {
        val vm = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
        // The send shell: only the sendMessage launch (the one that awaits
        // the auto-compact before streamJob is assigned) stores the shell job.
        val shellAssign = vm.indexOf("sendShellJob = viewModelScope.launch(Dispatchers.IO)")
        assertTrue("sendMessage shell job not stored", shellAssign >= 0)
        // The shell assignment must precede the compact wait it guards.
        val awaitWait = vm.indexOf("awaitAutoCompactIfNeeded()")
        assertTrue(shellAssign < awaitWait)

        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/ui/chat/ChatSessionLifecycle.kt",
        )
        assertTrue(
            "compactAll job not stored",
            lifecycle.indexOf("compactJob = viewModelScope.launch(Dispatchers.IO)") >= 0,
        )
    }

    @Test
    fun `compact marker insert rethrows cancellation instead of swallowing it`() {
        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/ui/chat/ChatSessionLifecycle.kt",
        )
        // The old runCatching swallow must be gone; a try/catch that rethrows
        // CancellationException takes its place.
        assertFalse(
            "marker insert still swallows cancellation via runCatching",
            lifecycle.contains("runCatching { chatRepository.dao.insertCompactMarker"),
        )
        val rethrow = lifecycle.indexOf("chatRepository.dao.insertCompactMarker(marker)")
        assertTrue("marker insert call missing", rethrow >= 0)
        val rethrowCatch = lifecycle.indexOf("catch (e: CancellationException)")
        assertTrue("cancellation rethrow missing", rethrowCatch >= 0)
        // Sequence: the guarded call first, its rethrowing catch after —
        // i.e. the same try/catch region, not two unrelated constructs.
        assertTrue(rethrow < rethrowCatch)
    }

    @Test
    fun `queue drain deference consumes the pure predicate`() {
        val qi = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt",
        )
        val deferCall = qi.indexOf("queueDrainShouldDeferForCompact(_isCompacting.value, compactJob?.isActive)")
        assertTrue("resumeQueueAfterCancel not wired to the predicate", deferCall >= 0)
        // The raw flag check must be gone from the defer branch.
        assertFalse(
            "defer branch still checks the raw flag",
            qi.contains("if (_isCompacting.value) {"),
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
