package org.yaqmc.android.core

/** A process-owned lease; Activity recreation must not end an external authorization. */
internal class MobileLoginLeaseRegistry(
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val heartbeat: (Attempt, (String?, String?, String?) -> Unit) -> Unit,
    private val now: () -> Long,
    private val event: (Attempt, String, String?) -> Unit = { _, _, _ -> },
) {
    data class Attempt(
        val attemptId: String,
        val providerId: String,
        val ownerLeaseId: String,
        val deadlineMs: Long,
    )

    private val attempts = mutableMapOf<Pair<String, String>, Attempt>()
    private val tasks = mutableMapOf<Pair<String, String>, Runnable>()
    private fun key(attempt: Attempt) = attempt.providerId to attempt.attemptId

    fun hasProvider(providerId: String) = attempts.values.any { it.providerId == providerId }

    fun adopt(attempt: Attempt) {
        val current = attempts[key(attempt)]
        if (current != null && current.ownerLeaseId == attempt.ownerLeaseId) return
        attempts.values.filter { it.providerId == attempt.providerId }.toList().forEach(::stop)
        attempts[key(attempt)] = attempt
        event(attempt, "owner-attached", null)
        enqueue(attempt, 0)
    }

    fun lifecycle(state: String) {
        attempts.values.forEach { event(it, "lifecycle", state) }
    }

    private fun enqueue(attempt: Attempt, delay: Long) {
        if (attempts[key(attempt)] !== attempt) return
        tasks.remove(key(attempt))?.let(unschedule)
        val task = Runnable { renew(attempt) }
        tasks[key(attempt)] = task
        schedule(task, delay)
    }

    private fun renew(attempt: Attempt) {
        if (attempts[key(attempt)] !== attempt) return
        if (now() >= attempt.deadlineMs) {
            event(attempt, "owner-deadline", null)
            stop(attempt)
            return
        }
        heartbeat(attempt) { state, id, lease ->
            // A response queued before a retry must never revive the previous owner.
            if (attempts[key(attempt)] !== attempt) return@heartbeat
            if (state == null) {
                enqueue(attempt, 1_000)
            } else if (state in ACTIVE_STATES && id == attempt.attemptId && lease == attempt.ownerLeaseId) {
                enqueue(attempt, 2_000)
            } else {
                event(attempt, "owner-finished", state)
                stop(attempt)
            }
        }
    }

    private fun stop(attempt: Attempt) {
        if (attempts[key(attempt)] !== attempt) return
        attempts.remove(key(attempt))
        tasks.remove(key(attempt))?.let(unschedule)
    }

    companion object {
        val ACTIVE_STATES = setOf("starting-login", "waiting-for-scan", "waiting-for-confirmation")
    }
}
