package com.ntq.training.momentbox.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


data class PendingJournalDelete(val id: Long, val deletedAt: Long)

class JournalDeletionScheduler(
    private val repository: JournalRepository,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val jobs = mutableMapOf<Long, Pair<Long, Job>>()
    init {
        scope.launch {
            repository.observePendingDeletes().collect { rows ->
                val active = rows.associateBy { it.id }
                jobs.keys.toList().filter { id -> active[id]?.deletedAt != jobs[id]?.first }.forEach { id ->
                    jobs.remove(id)?.second?.cancel()
                }
                rows.forEach { row ->
                    if (jobs[row.id] == null) {
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            delay((row.deletedAt + UNDO_MS - now()).coerceAtLeast(0))
                            // Lỗi I/O có thể tạm thời. Room còn bản ghi để lượt này/lần mở app sau retry.
                            while (isActive && repository.hardDelete(row.id).isFailure) delay(1000)
                        }
                        jobs[row.id] = row.deletedAt to job
                        job.start()
                    }
                }
            }
        }
    }
    companion object { const val UNDO_MS = 5000L }
}
