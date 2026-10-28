package com.projectkite.stemsplitter

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

sealed interface JobState {
    data object Idle : JobState
    data class Running(val title: String, val stage: String, val progress: Float, val etaSeconds: Long?) : JobState
    data class Done(val split: Split) : JobState
    data class Failed(val title: String, val message: String) : JobState
}

/** Process-wide state of the (single) running separation, observed by the UI. */
object SeparationJob {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(JobState) -> Unit>()

    @Volatile
    var state: JobState = JobState.Idle
        private set

    @Volatile
    var cancelRequested = false

    val isRunning get() = state is JobState.Running

    fun update(newState: JobState) {
        state = newState
        main.post { listeners.forEach { it(newState) } }
    }

    fun observe(listener: (JobState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeObserver(listener: (JobState) -> Unit) {
        listeners -= listener
    }
}
