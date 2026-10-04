package com.rikkaminis.app.agent.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * [feat/scheduled-tasks-l0] Process-wide per-session budget ceilings for
 * one-shot automated runs.
 *
 * A scheduled task registers a ceiling keyed by its freshly-created session id
 * right before dispatching; [com.rikkaminis.app.ui.chat.AgentLoopEngine] then
 * builds the run's AgentExecutionBudget as the STRICTER of the user's global
 * runtime limits and this ceiling (min semantics). The ceiling is consumed by
 * exactly one run — the runner clears the entry when the run settles.
 *
 * Kept in the runtime package (next to AgentExecutionBudget) because the
 * mechanism is generic; the only writer today is ScheduledTaskRunner.
 */
object AgentRunBudgetCeilings {

    data class Ceiling(
        /** Max wall-clock run time, measured from run start. */
        val deadlineMs: Long,
        val maxTurns: Int,
        val maxEstimatedTokens: Long,
    )

    private val ceilings = ConcurrentHashMap<String, Ceiling>()

    fun register(sessionId: String, ceiling: Ceiling) {
        if (sessionId.isBlank()) return
        ceilings[sessionId] = ceiling
    }

    fun lookup(sessionId: String): Ceiling? = ceilings[sessionId]

    fun clear(sessionId: String) {
        ceilings.remove(sessionId)
    }
}
