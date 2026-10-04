package com.rikkaminis.app.scheduled

import android.content.Context
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.tools.AtomicFileWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [feat/scheduled-tasks-l0] Persistent store for scheduled tasks.
 *
 * Location: `filesDir/minis-config/scheduled-tasks.json` — the app's existing
 * config directory (same convention as MountedFoldersStore), intentionally
 * OUTSIDE minis-global/ so agents and the DocumentsProvider cannot see or
 * write it. Rides the user-config backup via ConfigBackup's
 * "scheduledTasks" section.
 *
 * All reads and read-modify-writes funnel through one Mutex, so the foreground
 * check pass, the UI editor and the run-settlement path can never interleave
 * a lost update. Writes are atomic (temp + rename).
 */
object ScheduledTasksStore {

    private const val DIR_NAME = "minis-config"
    private const val FILE_NAME = "scheduled-tasks.json"
    private const val TAG = "scheduled-run"

    private val lock = Mutex()

    fun fileFor(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    suspend fun load(context: Context): ScheduledTasksFile = withContext(Dispatchers.IO) {
        lock.withLock { loadLocked(context) }
    }

    suspend fun mutate(
        context: Context,
        transform: (ScheduledTasksFile) -> ScheduledTasksFile,
    ): ScheduledTasksFile = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = loadLocked(context)
            val next = transform(current)
            if (next !== current) writeLocked(context, next)
            next
        }
    }

    // ------------------------------------------------------------------
    // Named helpers — thin wrappers so call sites read as intent.
    // ------------------------------------------------------------------

    suspend fun setGlobalEnabled(context: Context, enabled: Boolean): ScheduledTasksFile =
        mutate(context) { it.copy(globalEnabled = enabled) }

    suspend fun upsertTask(context: Context, task: ScheduledTask): ScheduledTasksFile =
        mutate(context) { file ->
            val idx = file.tasks.indexOfFirst { it.id == task.id }
            val tasks = if (idx < 0) {
                file.tasks + task
            } else {
                file.tasks.toMutableList().also { it[idx] = task }
            }
            file.copy(tasks = tasks)
        }

    suspend fun deleteTask(context: Context, taskId: String): ScheduledTasksFile =
        mutate(context) { file ->
            if (file.running?.taskId == taskId) {
                file.copy(tasks = file.tasks.filterNot { it.id == taskId }, running = null)
            } else {
                file.copy(tasks = file.tasks.filterNot { it.id == taskId })
            }
        }

    suspend fun setTaskEnabled(context: Context, taskId: String, enabled: Boolean): ScheduledTasksFile =
        mutate(context) { file ->
            file.copy(
                tasks = file.tasks.map {
                    if (it.id == taskId) {
                        // Turning a fused task back on is the manual resume path.
                        if (enabled && it.suspendedByFuse) {
                            it.copy(enabled = true, suspendedByFuse = false, failStreak = 0)
                        } else {
                            it.copy(enabled = enabled)
                        }
                    } else {
                        it
                    }
                },
            )
        }

    suspend fun resumeTask(context: Context, taskId: String): ScheduledTasksFile =
        mutate(context) { ScheduledTaskTransitions.resume(it, taskId) }

    suspend fun markRunning(
        context: Context,
        taskId: String,
        sessionId: String,
        startedAt: Long,
    ): ScheduledTasksFile = mutate(context) {
        it.copy(running = ScheduledRunMarker(taskId = taskId, sessionId = sessionId, startedAt = startedAt))
    }

    /** A run settled (or was reaped). Applies transitions and clears the marker. */
    suspend fun completeRun(
        context: Context,
        taskId: String,
        outcome: String,
        today: String,
    ): ScheduledTaskTransitions.Result = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = loadLocked(context)
            val result = ScheduledTaskTransitions.completeRun(current, taskId, outcome, today)
            if (result.file !== current) writeLocked(context, result.file)
            result
        }
    }

    /** Crash recovery: marker without an in-process run counts one failure. */
    suspend fun crashFailure(
        context: Context,
        taskId: String,
    ): ScheduledTaskTransitions.Result = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = loadLocked(context)
            val result = ScheduledTaskTransitions.crashFailure(current, taskId)
            if (result.file !== current) writeLocked(context, result.file)
            result
        }
    }

    // ------------------------------------------------------------------

    private fun loadLocked(context: Context): ScheduledTasksFile {
        val file = fileFor(context)
        if (!file.exists()) return ScheduledTasksFile()
        return try {
            ScheduledTasksCodec.parse(file.readText())
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "tasks.json unreadable (${t.javaClass.simpleName}) — treating as empty")
            ScheduledTasksFile()
        }
    }

    private fun writeLocked(context: Context, file: ScheduledTasksFile) {
        try {
            AtomicFileWrite.write(fileFor(context), ScheduledTasksCodec.encode(file))
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "tasks.json write failed: ${t.javaClass.simpleName}")
        }
    }
}
