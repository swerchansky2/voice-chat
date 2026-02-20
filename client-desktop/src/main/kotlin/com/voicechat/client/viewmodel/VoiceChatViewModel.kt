package com.voicechat.client.viewmodel

import com.voicechat.client.network.SignalingClient
import com.voicechat.client.audio.AudioEngine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.voicechat.client.`native`.WebrtcNative

private val logger = KotlinLogging.logger("VM")

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class VoiceChatViewModel(
    private val signalingClient: SignalingClient,
    private val audioEngine: AudioEngine
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

    // userId <-> native peerId mapping
    private val peerMap = mutableMapOf<String, Int>()
    private val reversePeerMap = mutableMapOf<Int, String>()

    private val webRtc = WebrtcNative()
    private var webRtcInitialized = false

    init {
        WebrtcNative.setRemoteAudioCallback { _, pcm ->
            audioEngine.playRemotePcm(pcm)
        }

        WebrtcNative.setIceCandidateCallback { peerId, candidate, sdpMid, sdpMLineIndex ->
            val targetUserId = reversePeerMap[peerId] ?: return@setIceCandidateCallback
            scope.launch {
                logger.debug { "[VM] Sending local ICE candidate to $targetUserId" }
                signalingClient.sendIceCandidate(targetUserId, candidate, sdpMid, sdpMLineIndex)
            }
        }

        observeSignalingEvents()
    }

    private fun ensureWebRtcInitialized(): Boolean {
        if (webRtcInitialized) return true
        return try {
            webRtc.initializeScreenshare()
            webRtcInitialized = true
            true
        } catch (t: Throwable) {
            logger.error(t) { "[VM] Failed to initialize native WebRTC" }
            false
        }
    }

    private fun setupAudioRouting() {
        audioEngine.sendRawFrame = { pcm ->
            val frames = pcm.size / 2
            for (p in peerMap.values.toList()) {
                try {
                    webRtc.sendAudioFrame(p, pcm, 16, 48000, 1, frames)
                } catch (t: Throwable) {
                    logger.error(t) { "[VM] Failed to send audio frame to native peer=$p" }
                }
            }
        }
    }

    private fun observeSignalingEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingClient.Event.Connected -> {}

                    is SignalingClient.Event.Disconnected -> {
                        logger.info { "[VM] Disconnected from server" }
                        cleanupAllPeers()
                        _connectionState.value = ConnectionState.Disconnected
                        _userList.value = emptyList()
                        audioEngine.stop()
                    }

                    is SignalingClient.Event.Joined -> {
                        logger.info { "[VM] Joined room — userId=${event.userId}, nickname=$currentNickname" }
                        currentUserId = event.userId
                        _connectionState.value = ConnectionState.Connected

                        if (!ensureWebRtcInitialized()) {
                            _errorMessage.value = "Failed to initialize WebRTC"
                            return@collect
                        }

                        setupAudioRouting()
                        audioEngine.start(event.userId)
                    }

                    is SignalingClient.Event.UserList -> {
                        val nicknames = event.users.map { it.nickname }
                        logger.info { "[VM] User list: $nicknames" }
                        _userList.value = nicknames

                        // Existing users will send us offers when they receive UserJoined,
                        // so we don't initiate connections here — just wait.
                    }

                    is SignalingClient.Event.UserJoined -> {
                        logger.info { "[VM] User joined: \"${event.nickname}\" (${event.userId})" }
                        _userList.value = _userList.value + event.nickname

                        // We (existing user) initiate WebRTC connection to the newcomer
                        initiateConnection(event.userId)
                    }

                    is SignalingClient.Event.UserLeft -> {
                        logger.info { "[VM] User left: \"${event.nickname}\" (${event.userId})" }
                        _userList.value = _userList.value - event.nickname
                        closePeer(event.userId)
                    }

                    is SignalingClient.Event.OfferReceived -> {
                        logger.info { "[VM] Incoming offer from=${event.from}" }
                        handleIncomingOffer(event.from, event.sdp)
                    }

                    is SignalingClient.Event.AnswerReceived -> {
                        logger.info { "[VM] Incoming answer from=${event.from}" }
                        handleIncomingAnswer(event.from, event.sdp)
                    }

                    is SignalingClient.Event.IceCandidateReceived -> {
                        logger.info { "[VM] Incoming ICE from=${event.from}" }
                        handleIncomingIce(event.from, event.candidate, event.sdpMid, event.sdpMLineIndex)
                    }

                    is SignalingClient.Event.Error -> {
                        logger.error { "[VM] Error: ${event.message}" }
                        _errorMessage.value = event.message
                        _connectionState.value = ConnectionState.Error(event.message)
                    }
                }
            }
        }
    }

    private suspend fun initiateConnection(targetUserId: String) {
        if (!ensureWebRtcInitialized()) return

        val peerId = webRtc.createPeerConnection()
        if (peerId < 0) {
            logger.error { "[VM] Failed to create peer connection for $targetUserId" }
            return
        }
        peerMap[targetUserId] = peerId
        reversePeerMap[peerId] = targetUserId

        webRtc.addLocalAudioTrack(peerId, currentUserId ?: "local")

        val sdp = webRtc.createOffer(peerId)
        if (sdp.isNullOrEmpty()) {
            logger.error { "[VM] createOffer returned empty SDP for $targetUserId" }
            closePeer(targetUserId)
            return
        }

        logger.info { "[VM] Sending offer to $targetUserId" }
        signalingClient.sendOffer(targetUserId, sdp)
    }

    private suspend fun handleIncomingOffer(fromUserId: String, sdp: String) {
        if (!ensureWebRtcInitialized()) return

        val peerId = webRtc.createPeerConnection()
        if (peerId < 0) {
            logger.error { "[VM] Failed to create peer connection for incoming offer from $fromUserId" }
            return
        }
        peerMap[fromUserId] = peerId
        reversePeerMap[peerId] = fromUserId

        webRtc.addLocalAudioTrack(peerId, currentUserId ?: "local")

        webRtc.applyRemoteDescription(peerId, sdp, "offer")

        val answer = webRtc.createAnswer(peerId)
        if (answer.isNullOrEmpty()) {
            logger.error { "[VM] createAnswer returned empty SDP for $fromUserId" }
            closePeer(fromUserId)
            return
        }

        logger.info { "[VM] Sending answer to $fromUserId" }
        signalingClient.sendAnswer(fromUserId, answer)
    }

    private fun handleIncomingAnswer(fromUserId: String, sdp: String) {
        val peerId = peerMap[fromUserId]
        if (peerId == null) {
            logger.warn { "[VM] Received answer for unknown peer from=$fromUserId" }
            return
        }
        webRtc.applyRemoteDescription(peerId, sdp, "answer")
    }

    private fun handleIncomingIce(fromUserId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val peerId = peerMap[fromUserId]
        if (peerId == null) {
            logger.warn { "[VM] Received ICE for unknown peer from=$fromUserId" }
            return
        }
        webRtc.addIceCandidate(peerId, candidate, sdpMid, sdpMLineIndex)
    }

    private fun closePeer(userId: String) {
        val peerId = peerMap.remove(userId) ?: return
        reversePeerMap.remove(peerId)
        try {
            webRtc.closePeerConnection(peerId)
        } catch (t: Throwable) {
            logger.error(t) { "[VM] Error closing peer connection for $userId" }
        }
    }

    private fun cleanupAllPeers() {
        for ((_, peerId) in peerMap) {
            try {
                webRtc.closePeerConnection(peerId)
            } catch (t: Throwable) {
                logger.error(t) { "[VM] Error closing peer $peerId" }
            }
        }
        peerMap.clear()
        reversePeerMap.clear()
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
            cleanupAllPeers()
            audioEngine.stop()
            signalingClient.disconnect()
            _connectionState.value = ConnectionState.Disconnected
            _userList.value = emptyList()
            currentUserId = null
            currentNickname = null
        }
    }

    fun toggleMute() {
        val newMutedState = !_isMuted.value
        _isMuted.value = newMutedState
        audioEngine.setMuted(newMutedState)
        logger.info { "[VM] ${if (newMutedState) "Muted" else "Unmuted"}" }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
