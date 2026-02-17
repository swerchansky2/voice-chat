package com.voicechat.client.audio

import com.voicechat.client.network.UdpAudioClient
import com.voicechat.shared.protocol.AudioPacket
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger("Audio")

class AudioEngine(
    private val udpAudioClient: UdpAudioClient
) {
    private val audioCapture = AudioCapture()
    private val audioPlayback = AudioPlayback()
    private val opusCodec = OpusCodec()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var userId: String? = null
    private var isMuted = false

    fun start(userId: String) {
        this.userId = userId

        audioPlayback.start()
        audioCapture.start()

        // Subscribe to captured audio
        scope.launch {
            audioCapture.audioData.collect { pcmData ->
                if (!isMuted && userId != null) {
                    // Encode and send
                    val encodedData = opusCodec.encode(pcmData)
                    val packet = AudioPacket(userId, encodedData)
                    udpAudioClient.sendAudioPacket(packet)
                }
            }
        }

        logger.info { "[Audio] Engine started for user $userId" }
    }

    fun stop() {
        audioCapture.stop()
        audioPlayback.stop()
        userId = null
        logger.info { "[Audio] Engine stopped" }
    }

    fun playAudio(audioData: ByteArray) {
        val pcmData = opusCodec.decode(audioData)
        audioPlayback.play(pcmData)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }
}
