package com.qtekfun.ultimatecamera.core.recording

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** D92: the depth files of each portrait follow its photo in the background, one shot after the other. */
class OrderedJobsTest {
    @Test
    fun jobsRunInTheOrderTheyWereAddedAndJoinWaitsForAll() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val jobs = OrderedJobs(scope)
            val order = Collections.synchronizedList(ArrayList<Int>())
            val gate = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            jobs.add {
                gate.await() // The first shot's files are slow; the second must still wait for them.
                order += 1
            }
            jobs.add {
                secondStarted.complete(Unit)
                order += 2
            }
            assertEquals(false, secondStarted.isCompleted)
            assertEquals(emptyList<Int>(), order.toList())
            gate.complete(Unit)
            withTimeout(TIMEOUT_MS) { jobs.join() }
            assertEquals(listOf(1, 2), order.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFailedJobDoesNotStopTheNextOne() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        try {
            val jobs = OrderedJobs(scope)
            val done = CompletableDeferred<Unit>()
            jobs.add { error("storage gone") }
            jobs.add { done.complete(Unit) }
            withTimeout(TIMEOUT_MS) { jobs.join() }
            assertEquals(true, done.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun joinWithNothingAddedReturns() = runBlocking {
        OrderedJobs(CoroutineScope(Dispatchers.Default)).join()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
