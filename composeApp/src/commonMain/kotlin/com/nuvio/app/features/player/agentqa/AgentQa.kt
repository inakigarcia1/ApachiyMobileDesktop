package com.nuvio.app.features.player.agentqa

internal expect object AgentQa {
    val enabled: Boolean

    fun publish(snapshot: AgentQaSnapshot)

    fun event(name: String, details: String = "")

    fun writeBytes(fileName: String, bytes: ByteArray)

    fun takeCommand(): AgentQaCommand?

    fun claimCommand(actions: List<String>): AgentQaCommand?

    fun ackCommand(command: AgentQaCommand, result: String)

    fun writeSyncScore(score: AgentQaSyncScore)

    fun writeAppState(state: AgentQaAppState)

    fun loginAccounts(): List<AgentQaAccount>
}
