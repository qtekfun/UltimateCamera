package com.qtekfun.ultimatecamera.core.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Background work in [scope] that runs one job after the other, in the order [add] was called (D92: the depth and edit
 * files of each portrait, written after its photo is saved, so the shutter never waits for them). Thread-safe.
 */
class OrderedJobs(private val scope: CoroutineScope) {
    private val lock = Any()
    private var last: Job? = null

    /** Runs [block] once every job added before it has finished. */
    fun add(block: suspend () -> Unit) {
        synchronized(lock) {
            val previous = last
            last = scope.launch {
                previous?.join()
                block()
            }
        }
    }

    /** Waits until every job added so far has finished. */
    suspend fun join() {
        synchronized(lock) { last }?.join()
    }
}
