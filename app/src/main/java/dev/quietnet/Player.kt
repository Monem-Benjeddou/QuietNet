package dev.quietnet

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/** Playback state shared by the YouTube screen and the background playback service. */
object Player {
    data class State(
        val playing: Boolean = false,
        val title: String = "",
        val videoId: String = "",
        val position: Double = 0.0,
        val duration: Double = 0.0,
    )

    val state = MutableStateFlow(State())

    /** Runs a command ("play", "pause", "forward", "back", "seek:<seconds>") in the page. Set by the YouTube screen. */
    var controller: ((String) -> Unit)? = null

    var serviceRunning = false

    fun command(cmd: String) {
        controller?.invoke(cmd)
    }

    fun update(context: Context, s: State) {
        state.value = s
        if (s.playing && !serviceRunning) {
            try {
                context.startForegroundService(Intent(context, PlaybackService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background; it starts the next time the screen is open.
            }
        }
    }

    fun reset() {
        controller = null
        state.value = State()
    }
}
