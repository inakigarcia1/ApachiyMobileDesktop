package com.nuvio.app.features.player.agentqa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.deeplink.buildMetaDeepLinkUrl
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.features.watchprogress.WatchProgressClock
import kotlinx.coroutines.delay

@Composable
internal fun BindAgentQaAppEffects(gateScreen: String) {
    if (!AgentQa.enabled) return

    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    var autoLoginStatus by remember { mutableStateOf("idle") }
    var autoLoginTried by remember { mutableStateOf(false) }

    LaunchedEffect(authState) {
        when (val current = authState) {
            is AuthState.Authenticated -> {
                if (!current.isAnonymous) autoLoginStatus = "ok"
            }
            AuthState.Unauthenticated -> {
                if (autoLoginTried) return@LaunchedEffect
                autoLoginTried = true
                val accounts = AgentQa.loginAccounts()
                if (accounts.isEmpty()) {
                    autoLoginStatus = "missing_accounts"
                    return@LaunchedEffect
                }
                autoLoginStatus = "trying"
                var signedIn = false
                for (account in accounts) {
                    AgentQa.event("auto_login", account.email)
                    val result = AuthRepository.signInWithEmail(account.email, account.password)
                    if (result.isSuccess) {
                        signedIn = true
                        break
                    }
                }
                autoLoginStatus = if (signedIn) "ok" else "failed"
            }
            AuthState.Loading -> Unit
        }
    }

    LaunchedEffect(gateScreen, autoLoginStatus) {
        while (true) {
            val command = AgentQa.claimCommand(listOf("open_url", "open_meta", "play_episode"))
            if (command != null) {
                val result = when (command.action.lowercase()) {
                    "open_url" -> {
                        val url = command.url.orEmpty()
                        if (url.isBlank()) {
                            "missing_url"
                        } else {
                            handleAppUrl(url)
                            "ok"
                        }
                    }
                    "open_meta" -> {
                        val type = command.metaType.orEmpty()
                        val id = command.metaId.orEmpty()
                        if (type.isBlank() || id.isBlank()) {
                            "missing_meta"
                        } else {
                            handleAppUrl(buildMetaDeepLinkUrl(type, id))
                            "ok"
                        }
                    }
                    "play_episode" -> {
                        val type = command.metaType.orEmpty()
                        val id = command.metaId.orEmpty()
                        val season = command.season
                        val episode = command.episode
                        if (type.isBlank() || id.isBlank() || season == null || episode == null) {
                            "missing_episode"
                        } else {
                            AgentQaPlaybackRequests.emit(
                                AgentQaPlayEpisodeRequest(
                                    type = type,
                                    metaId = id,
                                    season = season,
                                    episode = episode,
                                    command = command,
                                ),
                            )
                            "queued"
                        }
                    }
                    else -> "unknown_action"
                }
                if (result != "queued") {
                    AgentQa.ackCommand(command, result)
                }
                AgentQa.event("app_command", "${command.action}:$result")
            }

            val currentAuth = AuthRepository.state.value
            val authName = when (currentAuth) {
                is AuthState.Authenticated -> if (currentAuth.isAnonymous) "anonymous" else "authenticated"
                AuthState.Unauthenticated -> "unauthenticated"
                AuthState.Loading -> "loading"
            }
            AgentQa.writeAppState(
                AgentQaAppState(
                    updatedAtEpochMs = WatchProgressClock.nowEpochMs(),
                    gate = gateScreen,
                    auth = authName,
                    email = (currentAuth as? AuthState.Authenticated)?.email,
                    autoLogin = autoLoginStatus,
                ),
            )
            delay(400)
        }
    }
}
