package com.voicechat.client.viewmodel

import com.voicechat.client.network.SignalingClient
import com.voicechat.client.network.UdpAudioClient
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
    private val udpAudioClient: UdpAudioClient,
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
    private var currentHost: String? = null

    // Map remote user id -> native peerId
    private val peerMap = mutableMapOf<String, Int>()
    private val webRtc = WebrtcNative()
    private var webRtcInitialized = false
    init {
        // register native -> Kotlin callback for remote audio frames
        WebrtcNative.setRemoteAudioCallback { peerId, pcm ->
            // forward to audio engine for playback
            audioEngine.playRemotePcm(pcm)
        }
    }

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
                        audioEngine.stop()
                        udpAudioClient.stop()
                    }
                    is SignalingClient.Event.Joined -> {
                        logger.info { "[VM] Joined room — userId=${event.userId}, nickname=$currentNickname" }
                        currentUserId = event.userId
                        _connectionState.value = ConnectionState.Connected

                        // Start UDP client and register
                        val udpPort = udpAudioClient.start(currentHost!!)
                        signalingClient.registerUdp(udpPort)

                        // Start audio engine
                        audioEngine.start(event.userId)
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
                        // TODO: if we had mapping from nickname->userId, remove peerMap entries here
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
            signalingClient.disconnect()
            udpAudioClient.stop()
            audioEngine.stop()
            _connectionState.value = ConnectionState.Disconnected
            _userList.value = emptyList()
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

    fun clearError() {
        _errorMessage.value = null
    }

    // High-level call control (basic P2P WebRTC audio)
    fun callUser(targetUserId: String) {
        scope.launch {
            if (!webRtcInitialized) {
                try {
                    webRtc.initializeScreenshare()
                } catch (t: Throwable) {
                    logger.error(t) { "Failed to initialize native WebRTC" }
                    return@launch
                }
                webRtcInitialized = true
            }

            val peerId = webRtc.createPeerConnection()
            if (peerId < 0) {
                logger.error { "Failed to create native peer connection" }
                return@launch
            }
            peerMap[targetUserId] = peerId

            // Route captured PCM to native for all active peers
            audioEngine.sendRawFrame = { pcm ->
                // pcm is a ByteArray of 16-bit little-endian samples
                val frames = pcm.size / 2 // 2 bytes per sample (mono)
                for (p in peerMap.values) {
                    try {
                        webRtc.sendAudioFrame(p, pcm, 16, 48000, 1, frames)
                    } catch (t: Throwable) {
                        logger.error(t) { "Failed to send audio frame to native peer=$p" }
                    }
                }
            }

            // Add a local audio track (uses native audio capture or generator)
            webRtc.addLocalAudioTrack(peerId, currentUserId ?: "local")
            webRtc.startAudioGenerator(peerId)

            // Create offer and send to remote
            val sdp = webRtc.createOffer(peerId)
            signalingClient.sendOffer(targetUserId, sdp)
        }
    }

    private fun handleIncomingOffer(fromUserId: String, sdp: String) {
        scope.launch {
            if (!webRtcInitialized) {
                try {
                    webRtc.initializeScreenshare()
                } catch (t: Throwable) {
                    logger.error(t) { "Failed to initialize native WebRTC" }
                    return@launch
                }
                webRtcInitialized = true
            }

            val peerId = webRtc.createPeerConnection()
            if (peerId < 0) {
                logger.error { "Failed to create native peer connection for incoming offer" }
                return@launch
            }
            peerMap[fromUserId] = peerId

            webRtc.addLocalAudioTrack(peerId, currentUserId ?: "local")
            webRtc.startAudioGenerator(peerId)

            // Apply remote offer, create answer and send back
            webRtc.applyRemoteDescription(peerId, sdp, "offer")
            val answer = webRtc.createAnswer(peerId)
            signalingClient.sendAnswer(fromUserId, answer)
        }
    }

    private fun handleIncomingAnswer(fromUserId: String, sdp: String) {
        scope.launch {
            val peerId = peerMap[fromUserId]
            if (peerId == null) {
                logger.warn { "Received answer for unknown peer from=$fromUserId" }
                return@launch
            }
            webRtc.applyRemoteDescription(peerId, sdp, "answer")
        }
    }

    private fun handleIncomingIce(fromUserId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        scope.launch {
            val peerId = peerMap[fromUserId]
            if (peerId == null) {
                logger.warn { "Received ICE for unknown peer from=$fromUserId" }
                return@launch
            }
            webRtc.addIceCandidate(peerId, candidate, sdpMid, sdpMLineIndex)
        }
    }
}
