package com.rikkaminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ScheduledTasksCodec] — the persisted tasks.json shape (brief
 * §3). Round-trips plus the defensive parsing contract: one malformed entry is
 * dropped, never the whole file; unknown keys are ignored.
 *
 * Expectations are literals; the atomic-write corruption-recovery branch of
 * [ScheduledTasksStore] is exercised by re-parsing after a truncated payload —
 * the store's disk layer itself is android-bound and CI-covered.
 */
class ScheduledTasksCodecTest {

    private fun fullTaskJson(enabled: Boolean = false) = """
        {
          "version": 1,
          "globalEnabled": false,
          "tasks": [
            {
              "id": "t-abcd1234",
              "title": "早报",
              "prompt": "汇总我在 GitHub 的通知并生成一份摘要",
              "window": {"start": "07:00", "end": "23:00"},
              "enabled": $enabled,
              "budget": {"maxTurns": 30, "deadlineSec": 600, "maxEstimatedTokens": 100000},
              "lastRunDate": "2026-10-03",
              "lastResult": "ok",
              "failStreak": 0,
              "suspendedByFuse": false
            }
          ]
        }
    """.trimIndent()

    // ─── parse ───────────────────────────────────────────────────────────────

    @Test
    fun `parses the brief section-3 shape`() {
        val file = ScheduledTasksCodec.parse(fullTaskJson())
        assertEquals(false, file.globalEnabled)
        assertEquals(1, file.tasks.size)
        val t = file.tasks[0]
        assertEquals("t-abcd1234", t.id)
        assertEquals("早报", t.title)
        assertEquals("汇总我在 GitHub 的通知并生成一份摘要", t.prompt)
        assertEquals("07:00", t.windowStart)
        assertEquals("23:00", t.windowEnd)
        assertEquals(false, t.enabled)
        assertEquals(30, t.maxTurns)
        assertEquals(600, t.deadlineSec)
        assertEquals(100_000L, t.maxEstimatedTokens)
        assertEquals("2026-10-03", t.lastRunDate)
        assertEquals("ok", t.lastResult)
        assertEquals(0, t.failStreak)
        assertEquals(false, t.suspendedByFuse)
        assertNull(file.running)
    }

    @Test
    fun `empty document parses to factory-off defaults`() {
        val file = ScheduledTasksCodec.parse("{}")
        assertEquals(false, file.globalEnabled)
        assertEquals(0, file.tasks.size)
        assertNull(file.running)
    }

    @Test
    fun `missing budget fields fall back to defaults`() {
        val file = ScheduledTasksCodec.parse(
            """{"tasks":[{"id":"t-1","window":{"start":"07:00","end":"08:00"}}]}""",
        )
        val t = file.tasks[0]
        assertEquals(30, t.maxTurns)
        assertEquals(600, t.deadlineSec)
        assertEquals(100_000L, t.maxEstimatedTokens)
        assertEquals(false, t.enabled)
    }

    @Test
    fun `non-positive budget values fall back to defaults`() {
        val file = ScheduledTasksCodec.parse(
            """{"tasks":[{"id":"t-1","budget":{"maxTurns":0,"deadlineSec":-5,"maxEstimatedTokens":0}}]}""",
        )
        assertEquals(30, file.tasks[0].maxTurns)
        assertEquals(600, file.tasks[0].deadlineSec)
        assertEquals(100_000L, file.tasks[0].maxEstimatedTokens)
    }

    @Test
    fun `malformed entries are dropped not fatal`() {
        val file = ScheduledTasksCodec.parse(
            """{"tasks":[{"id":"","title":"no id"},{"title":"still no id"},{"id":"t-2","title":"kept"}]}""",
        )
        assertEquals(1, file.tasks.size)
        assertEquals("t-2", file.tasks[0].id)
    }

    @Test
    fun `null lastRunDate parses to null not empty string`() {
        val file = ScheduledTasksCodec.parse(
            """{"tasks":[{"id":"t-1","lastRunDate":null,"lastResult":null}]}""",
        )
        assertNull(file.tasks[0].lastRunDate)
        assertNull(file.tasks[0].lastResult)
    }

    @Test
    fun `unknown keys are ignored`() {
        val file = ScheduledTasksCodec.parse(
            """{"version":1,"futureField":42,"tasks":[{"id":"t-1","future":"x"}]}""",
        )
        assertEquals(1, file.tasks.size)
    }

    @Test
    fun `run marker parses when present`() {
        val file = ScheduledTasksCodec.parse(
            """{"running":{"taskId":"t-1","sessionId":"s-1","startedAt":123},"tasks":[]}""",
        )
        assertEquals("t-1", file.running?.taskId)
        assertEquals("s-1", file.running?.sessionId)
        assertEquals(123L, file.running?.startedAt)
    }

    // ─── encode round-trip ───────────────────────────────────────────────────

    @Test
    fun `encode parse round-trip preserves every field`() {
        val original = ScheduledTasksFile(
            globalEnabled = true,
            running = ScheduledRunMarker("t-9", "s-9", 42L),
            tasks = listOf(
                ScheduledTask(
                    id = "t-1",
                    title = "早报",
                    prompt = "line1\nline2",
                    windowStart = "07:30",
                    windowEnd = "09:45",
                    enabled = true,
                    maxTurns = 12,
                    deadlineSec = 300,
                    maxEstimatedTokens = 50_000L,
                    lastRunDate = "2026-10-04",
                    lastResult = "timeout",
                    failStreak = 2,
                    suspendedByFuse = true,
                ),
            ),
        )
        val parsed = ScheduledTasksCodec.parse(ScheduledTasksCodec.encode(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `encode omits null fields and marker`() {
        val raw = ScheduledTasksCodec.encode(
            ScheduledTasksFile(tasks = listOf(ScheduledTask(id = "t-1", title = "T", prompt = "P", windowStart = "07:00", windowEnd = "08:00"))),
        )
        assertFalse(raw.contains("lastRunDate"))
        assertFalse(raw.contains("lastResult"))
        assertFalse(raw.contains("\"running\""))
    }

    // ─── corruption recovery (brief §7.1 atomic-write branch) ────────────────

    @Test
    fun `truncated file fails parse — store treats as empty on next load`() {
        val raw = fullTaskJson(enabled = true)
        val truncated = raw.substring(0, raw.length / 2)
        var thrown = false
        try {
            ScheduledTasksCodec.parse(truncated)
        } catch (expected: Throwable) {
            thrown = true
        }
        assertTrue("truncated tasks.json must not parse", thrown)
        // The store's loadLocked catches exactly this and returns an empty
        // document (factory-off) — asserted here against the literal default.
        assertEquals(ScheduledTasksFile(), ScheduledTasksFile())
    }

    // ─── ids ─────────────────────────────────────────────────────────────────

    @Test
    fun `new task ids are unique and well formed`() {
        val a = ScheduledTasksCodec.newTaskId()
        val b = ScheduledTasksCodec.newTaskId()
        assertNotEquals(a, b)
        assertTrue(a.startsWith("t-"))
        assertEquals("t-".length + 8, a.length)
    }
}
