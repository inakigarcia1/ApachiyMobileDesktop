package com.nuvio.app.features.player.agentqa

internal actual object AgentQa {
    actual val enabled: Boolean = false

    actual fun publish(snapshot: AgentQaSnapshot) = Unit

    actual fun event(name: String, details: String) = Unit

    actual fun writeBytes(fileName: String, bytes: ByteArray) = Unit

    actual fun takeCommand(): AgentQaCommand? = null

    actual fun claimCommand(actions: List<String>): AgentQaCommand? = null

    actual fun ackCommand(command: AgentQaCommand, result: String) = Unit

    actual fun writeSyncScore(score: AgentQaSyncScore) = Unit

    actual fun writeAppState(state: AgentQaAppState) = Unit

    actual fun loginAccounts(): List<AgentQaAccount> = emptyList()
}
