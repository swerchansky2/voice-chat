package com.voicechat.client.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import com.voicechat.client.network.SignalingClient
import com.voicechat.client.webrtc.VideoFrameConverter
import com.voicechat.client.webrtc.WebRtcManager
import dev.onvoid.webrtc.media.video.desktop.DesktopSource
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

data class ScreenShareState(
    val active: Boolean = false,
    val sharerNickname: String? = null,
    val isSelf: Boolean = false
)

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

    private val _screenShareState = MutableStateFlow(ScreenShareState())
    val screenShareState: StateFlow<ScreenShareState> = _screenShareState.asStateFlow()

    private val _currentVideoFrame = MutableStateFlow<ImageBitmap?>(null)
    val currentVideoFrame: StateFlow<ImageBitmap?> = _currentVideoFrame.asStateFlow()

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
                        _screenShareState.value = ScreenShareState()
                        _currentVideoFrame.value = null
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
                            },
                            onRemoteVideoFrame = { frame ->
                                _currentVideoFrame.value = VideoFrameConverter.toImageBitmap(frame)
                            },
                            onRemoteVideoEnded = {
                                _currentVideoFrame.value = null
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
                    is SignalingClient.Event.ScreenShareStarted -> {
                        logger.info { "[VM] Screen share started by \"${event.nickname}\"" }
                        _screenShareState.value = ScreenShareState(
                            active = true,
                            sharerNickname = event.nickname,
                            isSelf = event.nickname == currentNickname
                        )
                    }
                    is SignalingClient.Event.ScreenShareStopped -> {
                        logger.info { "[VM] Screen share stopped" }
                        _screenShareState.value = ScreenShareState()
                        _currentVideoFrame.value = null
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
            if (_screenShareState.value.isSelf) {
                webRtcManager?.stopScreenShare()
            }
            signalingClient.disconnect()
            webRtcManager?.dispose()
            webRtcManager = null
            _connectionState.value = ConnectionState.Disconnected
            _userList.value = emptyList()
            _screenShareState.value = ScreenShareState()
            _currentVideoFrame.value = null
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

    fun getAvailableScreens(): List<DesktopSource> {
        return webRtcManager?.getAvailableScreens() ?: emptyList()
    }

    fun getAvailableWindows(): List<DesktopSource> {
        return webRtcManager?.getAvailableWindows() ?: emptyList()
    }

    fun startScreenShare(sourceId: Long, isWindow: Boolean) {
        if (_screenShareState.value.active) return
        val manager = webRtcManager ?: return

        manager.startScreenShare(sourceId, isWindow)
        scope.launch {
            signalingClient.sendStartScreenShare()
        }
        logger.info { "[VM] Starting screen share (sourceId=$sourceId, isWindow=$isWindow)" }
    }

    fun stopScreenShare() {
        val manager = webRtcManager ?: return
        manager.stopScreenShare()
        scope.launch {
            signalingClient.sendStopScreenShare()
        }
        _screenShareState.value = ScreenShareState()
        logger.info { "[VM] Stopping screen share" }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
