package com.voicechat.client.viewmodel

import com.voicechat.client.network.SignalingClient
import com.voicechat.client.webrtc.WebRtcManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger("VM")

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class VoiceChatViewModel(
    private val signalingClient: SignalingClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _userList = MutableStateFlow<List<String>>(emptyList())
    val userList: StateFlow<List<String>> = _userList.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var currentUserId: String? = null
    private var currentNickname: String? = null
    private var webRtcManager: WebRtcManager? = null

    init {
        observeSignalingEvents()
    }

    private fun observeSignalingEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingClient.Event.Connected -> {}
                    is SignalingClient.Event.Disconnected -> {
                        logger.info { "[VM] Disconnected from server" }
                        _connectionState.value = ConnectionState.Disconnected
                        _userList.value = emptyList()
                        webRtcManager?.dispose()
                        webRtcManager = null
                    }
                    is SignalingClient.Event.Joined -> {
                        logger.info { "[VM] Joined room — userId=${event.userId}, nickname=$currentNickname" }
                        currentUserId = event.userId
                        _connectionState.value = ConnectionState.Connected

                        val manager = WebRtcManager(
                            iceCandidateCallback = { candidate ->
                                scope.launch {
                                    signalingClient.sendIceCandidate(
                                        candidate.sdp,
                                        candidate.sdpMid,
                                        candidate.sdpMLineIndex
                                    )
                                }
                            },
                            answerCallback = { sdp ->
                                scope.launch {
                                    signalingClient.sendAnswer(sdp)
                                }
                            }
                        )
                        manager.initialize()
                        webRtcManager = manager
                    }
                    is SignalingClient.Event.UserList -> {
                        logger.info { "[VM] User list: ${event.users}" }
                        _userList.value = event.users
                    }
                    is SignalingClient.Event.UserJoined -> {
                        logger.info { "[VM] User joined: \"${event.nickname}\"" }
                        _userList.value = _userList.value + event.nickname
                    }
                    is SignalingClient.Event.UserLeft -> {
                        logger.info { "[VM] User left: \"${event.nickname}\"" }
                        _userList.value = _userList.value - event.nickname
                    }
                    is SignalingClient.Event.Error -> {
                        logger.error { "[VM] Error: ${event.message}" }
                        _errorMessage.value = event.message
                        _connectionState.value = ConnectionState.Error(event.message)
                    }
                    is SignalingClient.Event.WebRtcOffer -> {
                        logger.info { "[VM] Received WebRTC offer, processing..." }
                        webRtcManager?.handleOffer(event.sdp)
                    }
                    is SignalingClient.Event.WebRtcIceCandidate -> {
                        webRtcManager?.handleIceCandidate(
                            event.candidate, event.sdpMid, event.sdpMLineIndex
                        )
                    }
                }
            }
        }
    }

    fun connect(nickname: String, host: String, port: Int) {
        if (_connectionState.value is ConnectionState.Connecting ||
            _connectionState.value is ConnectionState.Connected) {
            return
        }

        currentNickname = nickname
        _connectionState.value = ConnectionState.Connecting
        _errorMessage.value = null

        logger.info { "[VM] Connecting as \"$nickname\" to $host:$port" }

        scope.launch {
            try {
                signalingClient.connect(host, port, nickname)
            } catch (e: Exception) {
                logger.error(e) { "[VM] Failed to connect to $host:$port" }
                _errorMessage.value = e.message ?: "Connection failed"
                _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            }
        }
    }

    fun disconnect() {
        scope.launch {
            logger.info { "[VM] Disconnecting" }
            signalingClient.disconnect()
            webRtcManager?.dispose()
            webRtcManager = null
            _connectionState.value = ConnectionState.Disconnected
            _userList.value = emptyList()
            currentUserId = null
            currentNickname = null
        }
    }

    fun toggleMute() {
        val newMutedState = !_isMuted.value
        _isMuted.value = newMutedState
        webRtcManager?.setMuted(newMutedState)
        logger.info { "[VM] ${if (newMutedState) "Muted" else "Unmuted"}" }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
