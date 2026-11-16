package com.foodtracker.android.camera

import com.google.android.gms.tasks.Task

/**
 * Closes an ML Kit client ([closeClient]) once the analyzer using it is done with: at [close]
 * when nothing is running on it, else when the last task still running finishes, so no task
 * loses the client under it. Nothing new starts once [close] has been called.
 *
 * Tasks start on the analyzer thread and finish on the main thread, and [close] is called from
 * the main thread, hence the lock.
 */
internal class InFlightClient(private val closeClient: () -> Unit) {
    private val lock = Any()
    private var running = 0
    private var closing = false

    /** Starts [task] and returns it, or null once closing. */
    fun <T> start(task: () -> Task<T>): Task<T>? {
        synchronized(lock) {
            if (closing) return null
            running++
        }
        val started = try {
            task()
        } catch (e: RuntimeException) {
            finished()
            throw e
        }
        started.addOnCompleteListener { finished() }
        return started
    }

    fun close() {
        val now = synchronized(lock) {
            if (closing) return
            closing = true
            running == 0
        }
        if (now) closeClient()
    }

    private fun finished() {
        val now = synchronized(lock) {
            running--
            closing && running == 0
        }
        if (now) closeClient()
    }
}
