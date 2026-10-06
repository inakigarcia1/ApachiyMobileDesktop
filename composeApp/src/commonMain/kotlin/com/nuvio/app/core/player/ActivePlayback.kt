package com.nuvio.app.core.player

import java.util.concurrent.CopyOnWriteArrayList

/** Stops in-process playback when auth returns to login while the activity stays up. */
object ActivePlayback {
    private val stoppers = CopyOnWriteArrayList<() -> Unit>()

    fun register(stop: () -> Unit): () -> Unit {
        stoppers.add(stop)
        return { stoppers.remove(stop) }
    }

    fun stop() {
        stoppers.toList().forEach { stopper ->
            runCatching { stopper() }
        }
    }
}
