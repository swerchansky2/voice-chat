package com.voicechat.client.viewmodel

import com.voicechat.client.network.SignalingClient
import com.voicechat.client.network.UdpAudioClient
import com.voicechat.client.audio.AudioEngine
import com.voicechat.client.screen.ScreenEngine
import com.voicechat.client.screen.ScreenResolution
import com.voicechat.client.screen.ScreenShareSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

private val logger = KotlinLogging.logger("VM")

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class VoiceChatViewModel(
    private val signalingClient: SignalingClient,
    private val udpAudioClient: UdpAudioClient,
    private val audioEngine: AudioEngine,
    private val screenEngine: ScreenEngine
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

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing.asStateFlow()

    private val _screenSharerNickname = MutableStateFlow<String?>(null)
    val screenSharerNickname: StateFlow<String?> = _screenSharerNickname.asStateFlow()

    private val _screenShareSettings = MutableStateFlow(ScreenShareSettings())
    val screenShareSettings: StateFlow<ScreenShareSettings> = _screenShareSettings.asStateFlow()

    val receivedScreenFrame: StateFlow<BufferedImage?> = screenEngine.receivedFrame

    private var currentUserId: String? = null
    private var currentNickname: String? = null
    private var currentHost: String? = null

    private var screenSharerUserId: String? = null

    init {
        observeSignalingEvents()
        observeAudioPackets()
    }

    private fun observeSignalingEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingClient.Event.Connected -> {
                        // Connection established, waiting for Joined
                    }
                    is SignalingClient.Event.Disconnected -> {
                        logger.info { "[VM] Disconnected from server" }
                        _connectionState.value = ConnectionState.Disconnected
                        _userList.value = emptyList()
                        _isScreenSharing.value = false
                        _screenSharerNickname.value = null
                        screenSharerUserId = null
                        screenEngine.stopSharing()
                        screenEngine.stopReceiving()
                        screenEngine.clearReceivedFrame()
                        audioEngine.stop()
                        udpAudioClient.stop()
                    }
                    is SignalingClient.Event.Joined -> {
                        logger.info { "[VM] Joined room — userId=${event.userId}, nickname=$currentNickname" }
                        currentUserId = event.userId
                        _connectionState.value = ConnectionState.Connected

                        val udpPort = udpAudioClient.start(currentHost!!)
                        signalingClient.registerUdp(udpPort)

                        audioEngine.start(event.userId)
                    }
                    is SignalingClient.Event.UserList -> {
                        logger.info { "[VM] User list: ${event.users}" }
                        _userList.value = event.users
                    }
                    is SignalingClient.Event.UserJoined -> {
                        logger.info { "[VM] User joined: \"${event.nickname}\" (${event.userId})" }
                        _userList.value = _userList.value + event.nickname

                        if (_isScreenSharing.value) {
                            logger.info { "[VM] Creating WebRTC offer for new viewer ${event.userId}" }
                            screenEngine.createOfferForViewer(event.userId)
                        }
                    }
                    is SignalingClient.Event.UserLeft -> {
                        logger.info { "[VM] User left: \"${event.nickname}\" (${event.userId})" }
                        _userList.value = _userList.value - event.nickname

                        screenEngine.closePeerConnection(event.userId)
                    }
                    is SignalingClient.Event.Error -> {
                        logger.error { "[VM] Error: ${event.message}" }
                        _errorMessage.value = event.message
                        _connectionState.value = ConnectionState.Error(event.message)
                    }
                    is SignalingClient.Event.ScreenShareStarted -> {
                        logger.info { "[VM] Screen share started by ${event.nickname} (${event.width}x${event.height} @ ${event.fps}fps)" }
                        _screenSharerNickname.value = event.nickname
                        screenSharerUserId = event.userId
                        if (event.userId == currentUserId) {
                            _isScreenSharing.value = true
                        } else {
                            screenEngine.startReceiving(event.userId)
                        }
                    }
                    is SignalingClient.Event.ScreenShareStopped -> {
                        logger.info { "[VM] Screen share stopped by ${event.nickname}" }
                        _screenSharerNickname.value = null
                        screenSharerUserId = null
                        if (event.userId == currentUserId) {
                            _isScreenSharing.value = false
                        }
                        screenEngine.stopReceiving()
                        screenEngine.clearReceivedFrame()
                    }
                    is SignalingClient.Event.ScreenShareViewers -> {
                        logger.info { "[VM] Creating WebRTC offers for ${event.viewerUserIds.size} existing viewers" }
                        for (viewerId in event.viewerUserIds) {
                            screenEngine.createOfferForViewer(viewerId)
                        }
                    }
                    is SignalingClient.Event.SdpOfferReceived -> {
                        logger.info { "[VM] SDP offer from ${event.fromUserId}" }
                        screenEngine.handleSdpOffer(event.fromUserId, event.sdp, event.type)
                    }
                    is SignalingClient.Event.SdpAnswerReceived -> {
                        logger.info { "[VM] SDP answer from ${event.fromUserId}" }
                        screenEngine.handleSdpAnswer(event.fromUserId, event.sdp, event.type)
                    }
                    is SignalingClient.Event.IceCandidateReceived -> {
                        screenEngine.handleIceCandidate(event.fromUserId, event.sdp, event.sdpMid, event.sdpMLineIndex)
                    }
                }
            }
        }
    }

    private fun observeAudioPackets() {
        scope.launch {
            udpAudioClient.receivedPackets.collect { packet ->
                audioEngine.receiveAudio(packet.sequenceNumber, packet.audioData)
            }
        }
    }

    fun connect(nickname: String, host: String, port: Int) {
        if (_connectionState.value is ConnectionState.Connecting ||
            _connectionState.value is ConnectionState.Connected) {
            return
        }

        currentNickname = nickname
        currentHost = host
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
            if (_isScreenSharing.value) {
                signalingClient.stopScreenShare()
                screenEngine.stopSharing()
            }
            screenEngine.stopReceiving()
            screenEngine.clearReceivedFrame()
            signalingClient.disconnect()
            udpAudioClient.stop()
            audioEngine.stop()
            _connectionState.value = ConnectionState.Disconnected
            _userList.value = emptyList()
            _isScreenSharing.value = false
            _screenSharerNickname.value = null
            screenSharerUserId = null
            currentUserId = null
            currentNickname = null
            currentHost = null
        }
    }

    fun toggleMute() {
        val newMutedState = !_isMuted.value
        _isMuted.value = newMutedState
        audioEngine.setMuted(newMutedState)
        logger.info { "[VM] ${if (newMutedState) "Muted" else "Unmuted"}" }
    }

    fun toggleScreenShare() {
        scope.launch {
            if (_isScreenSharing.value) {
                signalingClient.stopScreenShare()
                screenEngine.stopSharing()
                _isScreenSharing.value = false
            } else {
                val userId = currentUserId ?: return@launch
                val settings = _screenShareSettings.value
                signalingClient.startScreenShare(
                    settings.resolution.width,
                    settings.resolution.height,
                    minOf(settings.fps, settings.resolution.maxFps)
                )
                try {
                    screenEngine.startSharing(userId, settings)
                    _isScreenSharing.value = true
                } catch (e: Exception) {
                    logger.error(e) { "[VM] Failed to start screen sharing" }
                    signalingClient.stopScreenShare()
                    _errorMessage.value = e.message ?: "Failed to start screen sharing"
                }
            }
        }
    }

    fun updateScreenShareSettings(resolution: ScreenResolution) {
        _screenShareSettings.value = _screenShareSettings.value.copy(resolution = resolution)
        logger.info { "Screen share resolution updated: ${resolution.label}" }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
