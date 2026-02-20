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
    private var jitterBuffer: JitterBuffer? = null
    // Optional callback to send raw PCM frames (used by WebRTC transport)
    var sendRawFrame: ((ByteArray) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var userId: String? = null
    private var isMuted = false
    private var sequenceCounter = 0

    fun start(userId: String) {
        this.userId = userId
        sequenceCounter = 0

        audioPlayback.start()
        jitterBuffer = JitterBuffer(opusCodec, audioPlayback).also { it.start() }
        audioCapture.start()

        // Subscribe to captured audio
        scope.launch {
            audioCapture.audioData.collect { pcmData ->
                if (!isMuted && userId != null) {
                    try {
                        val sender = sendRawFrame
                        if (sender != null) {
                            // send raw PCM to WebRTC transport
                            sender.invoke(pcmData)
                        } else {
                            val encodedData = opusCodec.encode(pcmData)
                            if (encodedData.isNotEmpty()) {
                                val packet = AudioPacket(userId, sequenceCounter++, encodedData)
                                udpAudioClient.sendAudioPacket(packet)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "[Audio] Send error" }
                    }
                }
            }
        }

        logger.info { "[Audio] Engine started for user $userId" }
    }

    fun stop() {
        audioCapture.stop()
        jitterBuffer?.stop()
        jitterBuffer = null
        audioPlayback.stop()
        userId = null
        logger.info { "[Audio] Engine stopped" }
    }

    fun receiveAudio(sequenceNumber: Int, audioData: ByteArray) {
        jitterBuffer?.put(sequenceNumber, audioData)
    }

    // Play PCM frames delivered by WebRTC native layer
    fun playRemotePcm(pcmData: ByteArray) {
        audioPlayback.playFrame(pcmData)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }
}
