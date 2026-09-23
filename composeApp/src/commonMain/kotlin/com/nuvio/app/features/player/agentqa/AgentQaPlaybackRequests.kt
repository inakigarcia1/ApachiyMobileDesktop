package com.nuvio.app.features.player.agentqa

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal data class AgentQaPlayEpisodeRequest(
    val type: String,
    val metaId: String,
    val season: Int,
    val episode: Int,
    val command: AgentQaCommand,
)

internal object AgentQaPlaybackRequests {
    private val _events = MutableSharedFlow<AgentQaPlayEpisodeRequest>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AgentQaPlayEpisodeRequest> = _events.asSharedFlow()

    fun emit(request: AgentQaPlayEpisodeRequest) {
        _events.tryEmit(request)
    }
}
