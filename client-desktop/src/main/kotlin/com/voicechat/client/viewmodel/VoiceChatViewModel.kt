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

private val logger = KotlinLogging.logger {}

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

    init {
        observeSignalingEvents()
        observeAudioPackets()
    }

    private fun observeSignalingEvents() {
        scope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingClient.Event.Connected -> {
                        logger.info { "WebSocket connected" }
                    }
                    is SignalingClient.Event.Disconnected -> {
                        logger.info { "WebSocket disconnected" }
                        _connectionState.value = ConnectionState.Disconnected
                        _userList.value = emptyList()
                        audioEngine.stop()
                        udpAudioClient.stop()
                    }
                    is SignalingClient.Event.Joined -> {
                        logger.info { "Joined with userId: ${event.userId}" }
                        currentUserId = event.userId
                        _connectionState.value = ConnectionState.Connected
                        
                        // Start UDP client and register
                        val udpPort = udpAudioClient.start()
                        signalingClient.registerUdp(udpPort)
                        
                        // Start audio engine
                        audioEngine.start(event.userId)
                    }
                    is SignalingClient.Event.UserList -> {
                        logger.info { "User list updated: ${event.users}" }
                        _userList.value = event.users
                    }
                    is SignalingClient.Event.UserJoined -> {
                        logger.info { "User joined: ${event.nickname}" }
                        _userList.value = _userList.value + event.nickname
                    }
                    is SignalingClient.Event.UserLeft -> {
                        logger.info { "User left: ${event.nickname}" }
                        _userList.value = _userList.value - event.nickname
                    }
                    is SignalingClient.Event.Error -> {
                        logger.error { "Signaling error: ${event.message}" }
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
                // Play received audio
                audioEngine.playAudio(packet.audioData)
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

        scope.launch {
            try {
                signalingClient.connect(host, port, nickname)
            } catch (e: Exception) {
                logger.error(e) { "Failed to connect" }
                _errorMessage.value = e.message ?: "Connection failed"
                _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            }
        }
    }

    fun disconnect() {
        scope.launch {
            signalingClient.disconnect()
            udpAudioClient.stop()
            audioEngine.stop()
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
        logger.info { "Mute toggled to: $newMutedState" }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
