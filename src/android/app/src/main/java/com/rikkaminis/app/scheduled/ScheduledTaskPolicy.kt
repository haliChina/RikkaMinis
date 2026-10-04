package com.rikkaminis.app.scheduled

/**
 * [feat/scheduled-tasks-l0] Pure decision logic for one foreground check pass.
 *
 * android-free by design so JVM unit tests can drive every branch with fixed
 * clock inputs (see ScheduledTaskPolicyTest).
 */
object ScheduledTaskPolicy {

    /** Consecutive completed failures that suspend a task (brief §5.3). */
    const val FUSE_THRESHOLD = 3

    /** Why a task was not fired this pass. `wire` values land in [scheduled-run] logs. */
    enum class SkipReason(val wire: String) {
        DISABLED("disabled"),
        FUSE("fuse"),
        ALREADY_RAN("already-ran"),
        OUTSIDE_WINDOW("outside-window"),
        INVALID_WINDOW("invalid-window"),
        OVERLAP("overlap"),
    }

    sealed interface PassVerdict {
        val taskId: String

        data class Fire(override val taskId: String) : PassVerdict
        data class Skip(override val taskId: String, val reason: SkipReason) : PassVerdict
        data class Miss(override val taskId: String) : PassVerdict
    }

    /**
     * "HH:MM" -> minutes since midnight. Strict: exactly two digits, two digits,
     * hour 0..23, minute 0..59. Null when the value is not a valid wall-clock time.
     */
    fun parseWindowMinuteOfDay(value: String): Int? {
        if (value.length != 5 || value[2] != ':') return null
        val hh = value.substring(0, 2).toIntOrNull() ?: return null
        val mm = value.substring(3, 5).toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        return hh * 60 + mm
    }

    /** Half-open window: `[start, end)` in minutes-of-day. */
    fun isWithinWindow(nowMinuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean =
        nowMinuteOfDay >= startMinute && nowMinuteOfDay < endMinute

    /**
     * Decide, for every task in list order, what this pass should do.
     *
     * Single-flight (brief §5.2): at most ONE task may carry [PassVerdict.Fire]
     * per pass — the first due task in list order. When [runningTaskId] is
     * non-null a scheduled run is already in flight (or being finalised) and
     * every otherwise-due task degrades to [SkipReason.OVERLAP].
     *
     * @param nowDate local calendar day, "YYYY-MM-DD"
     * @param nowMinuteOfDay local wall-clock minutes since midnight
     */
    fun evaluate(
        nowDate: String,
        nowMinuteOfDay: Int,
        tasks: List<ScheduledTask>,
        runningTaskId: String?,
    ): List<PassVerdict> {
        var firing = runningTaskId != null
        val verdicts = ArrayList<PassVerdict>(tasks.size)
        for (task in tasks) {
            verdicts += verdictFor(task, nowDate, nowMinuteOfDay) {
                if (firing) {
                    PassVerdict.Skip(task.id, SkipReason.OVERLAP)
                } else {
                    firing = true
                    PassVerdict.Fire(task.id)
                }
            }
        }
        return verdicts
    }

    private inline fun verdictFor(
        task: ScheduledTask,
        nowDate: String,
        nowMinuteOfDay: Int,
        eligible: () -> PassVerdict,
    ): PassVerdict {
        if (!task.enabled) return PassVerdict.Skip(task.id, SkipReason.DISABLED)
        if (task.suspendedByFuse) return PassVerdict.Skip(task.id, SkipReason.FUSE)
        val start = parseWindowMinuteOfDay(task.windowStart)
        val end = parseWindowMinuteOfDay(task.windowEnd)
        if (start == null || end == null || start >= end) {
            return PassVerdict.Skip(task.id, SkipReason.INVALID_WINDOW)
        }
        if (task.lastRunDate == nowDate) {
            return PassVerdict.Skip(task.id, SkipReason.ALREADY_RAN)
        }
        if (nowMinuteOfDay < start) return PassVerdict.Skip(task.id, SkipReason.OUTSIDE_WINDOW)
        if (nowMinuteOfDay >= end) return PassVerdict.Miss(task.id)
        return eligible()
    }
}

/**
 * [feat/scheduled-tasks-l0] Pure state transitions applied to the whole file
 * document. Every function is total: unknown task ids pass the document
 * through unchanged so callers never have to guard for races with deletion.
 */
object ScheduledTaskTransitions {

    data class Result(
        val file: ScheduledTasksFile,
        val task: ScheduledTask?,
        val fuseTripped: Boolean,
    )

    /**
     * A scheduled run settled. [outcome] is one of "ok" / "error" / "timeout".
     *
     * Success advances lastRunDate (daily gate) and resets the streak; a failed
     * run leaves lastRunDate untouched so the task retries on the next eligible
     * foreground beat inside the same window (brief §3 crash semantics share
     * this shape). The persisted run marker is cleared either way.
     */
    fun completeRun(
        file: ScheduledTasksFile,
        taskId: String,
        outcome: String,
        today: String,
    ): Result {
        val idx = file.tasks.indexOfFirst { it.id == taskId }
        if (idx < 0) {
            // Task deleted while its run was in flight: just clear the marker.
            return Result(clearMarker(file, taskId), null, fuseTripped = false)
        }
        val ok = outcome == ScheduledTask.RESULT_OK
        val updated = file.tasks[idx].let { t ->
            if (ok) {
                t.copy(
                    lastRunDate = today,
                    lastResult = ScheduledTask.RESULT_OK,
                    failStreak = 0,
                )
            } else {
                val streak = t.failStreak + 1
                val fuse = streak >= ScheduledTaskPolicy.FUSE_THRESHOLD
                t.copy(
                    lastResult = outcome,
                    failStreak = streak,
                    enabled = if (fuse) false else t.enabled,
                    suspendedByFuse = if (fuse) true else t.suspendedByFuse,
                )
            }
        }
        val next = clearMarker(file, taskId).let { f ->
            f.copy(tasks = f.tasks.toMutableList().also { it[idx] = updated })
        }
        return Result(
            file = next,
            task = updated,
            fuseTripped = !ok && updated.failStreak >= ScheduledTaskPolicy.FUSE_THRESHOLD,
        )
    }

    /**
     * The persisted marker has no matching in-process run: the previous process
     * died mid-run. Counts one failure (brief §3 "并计 failStreak") and clears
     * the marker. No-op when the marker does not match [taskId].
     */
    fun crashFailure(file: ScheduledTasksFile, taskId: String): Result {
        if (file.running?.taskId != taskId) return Result(file, null, fuseTripped = false)
        return completeRun(file, taskId, ScheduledTask.RESULT_ERROR, today = "")
    }

    /** Manual resume from the settings UI: clears the fuse and re-enables. */
    fun resume(file: ScheduledTasksFile, taskId: String): ScheduledTasksFile {
        val idx = file.tasks.indexOfFirst { it.id == taskId }
        if (idx < 0) return file
        val updated = file.tasks[idx].copy(
            suspendedByFuse = false,
            failStreak = 0,
            enabled = true,
        )
        return file.copy(tasks = file.tasks.toMutableList().also { it[idx] = updated })
    }

    private fun clearMarker(file: ScheduledTasksFile, taskId: String): ScheduledTasksFile =
        if (file.running?.taskId == taskId) file.copy(running = null) else file
}
