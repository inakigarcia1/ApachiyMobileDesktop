package com.nuvio.app.features.player.agentqa

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal actual object AgentQa {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val writeLock = Any()

    actual val enabled: Boolean by lazy {
        val property = System.getProperty("apachiy.agentQa").orEmpty()
        val env = System.getenv("APACHIY_AGENT_QA").orEmpty()
        property.equals("true", ignoreCase = true) ||
            env == "1" ||
            env.equals("true", ignoreCase = true) ||
            env.equals("yes", ignoreCase = true)
    }

    private val outputDir: Path by lazy {
        val configured = System.getProperty("apachiy.agentQaDir")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("APACHIY_AGENT_QA_DIR")?.takeIf { it.isNotBlank() }
        val resolved = if (configured != null) {
            Paths.get(configured)
        } else {
            DesktopStorage.rootDir.resolve("agent-qa")
        }
        Files.createDirectories(resolved)
        resolved
    }

    actual fun publish(snapshot: AgentQaSnapshot) {
        if (!enabled) return
        writeAtomic("player-state.json", json.encodeToString(snapshot))
        writeAtomic(
            "heartbeat.json",
            """{"updatedAtEpochMs":${snapshot.updatedAtEpochMs},"pipeline":${json.encodeToString(snapshot.pipeline)}}""",
        )
    }

    actual fun event(name: String, details: String) {
        if (!enabled) return
        val line = buildString {
            append("{\"ts\":")
            append(System.currentTimeMillis())
            append(",\"name\":")
            append(json.encodeToString(name))
            append(",\"details\":")
            append(json.encodeToString(details))
            append("}\n")
        }
        synchronized(writeLock) {
            Files.createDirectories(outputDir)
            Files.writeString(
                outputDir.resolve("events.ndjson"),
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    actual fun writeBytes(fileName: String, bytes: ByteArray) {
        if (!enabled) return
        val safe = fileName.substringAfterLast('/').substringAfterLast('\\')
        if (safe.isBlank() || safe.contains("..")) return
        synchronized(writeLock) {
            Files.createDirectories(outputDir)
            val target = outputDir.resolve(safe)
            val pending = Files.createTempFile(outputDir, safe, ".part")
            try {
                Files.write(pending, bytes)
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                runCatching {
                    Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(pending)
            }
        }
    }

    actual fun takeCommand(): AgentQaCommand? =
        claimCommand(listOf("seek", "play", "pause", "open_url", "open_meta", "play_episode"))

    actual fun claimCommand(actions: List<String>): AgentQaCommand? {
        if (!enabled) return null
        val wanted = actions.map { it.lowercase() }.toSet()
        if (wanted.isEmpty()) return null
        val file = outputDir.resolve("command.json")
        if (!file.exists()) return null
        return synchronized(writeLock) {
            if (!file.exists()) return@synchronized null
            val parsed = runCatching {
                json.decodeFromString<AgentQaCommand>(file.readText().trimStart('\uFEFF'))
            }.getOrNull()
            if (parsed == null || parsed.action.lowercase() !in wanted) return@synchronized null
            Files.deleteIfExists(file)
            parsed.takeIf { it.action.isNotBlank() }
        }
    }

    actual fun ackCommand(command: AgentQaCommand, result: String) {
        if (!enabled) return
        writeAtomic(
            "command-ack.json",
            json.encodeToString(
                AgentQaCommandAck(
                    id = command.id,
                    action = command.action,
                    result = result,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            ),
        )
    }

    actual fun writeSyncScore(score: AgentQaSyncScore) {
        if (!enabled) return
        val payload = buildString {
            append('{')
            append("\"pass\":").append(score.pass).append(',')
            append("\"medianOffsetMs\":").append(score.medianOffsetMs ?: "null").append(',')
            append("\"residualP80Ms\":").append(score.residualP80Ms ?: "null").append(',')
            append("\"referenceCueCount\":").append(score.referenceCueCount).append(',')
            append("\"appliedCueCount\":").append(score.appliedCueCount).append(',')
            append("\"reason\":").append(json.encodeToString(score.reason))
            append('}')
        }
        writeAtomic("sync-score.json", payload)
    }

    actual fun writeAppState(state: AgentQaAppState) {
        if (!enabled) return
        writeAtomic("app-state.json", json.encodeToString(state))
    }

    actual fun loginAccounts(): List<AgentQaAccount> {
        val raw = System.getenv("APACHIY_AGENT_QA_ACCOUNTS_JSON")?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching { json.decodeFromString<AgentQaSecretsFile>(raw).accounts }
            .getOrElse {
                runCatching { json.decodeFromString<List<AgentQaAccount>>(raw) }.getOrDefault(emptyList())
            }
            .filter { it.email.isNotBlank() && it.password.isNotBlank() }
    }

    private fun writeAtomic(fileName: String, text: String) {
        synchronized(writeLock) {
            Files.createDirectories(outputDir)
            val target = outputDir.resolve(fileName)
            val pending = Files.createTempFile(outputDir, fileName, ".part")
            try {
                pending.writeText(text)
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                runCatching { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING) }
            } finally {
                Files.deleteIfExists(pending)
            }
        }
    }
}
