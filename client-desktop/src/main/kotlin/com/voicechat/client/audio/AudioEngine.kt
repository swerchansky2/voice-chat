package com.voicechat.client.audio

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger("Audio")

class AudioEngine {
    private val audioCapture = AudioCapture()
    private val audioPlayback = AudioPlayback()

    var sendRawFrame: ((ByteArray) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var userId: String? = null
    private var isMuted = false

    fun start(userId: String) {
        this.userId = userId

        audioPlayback.start()
        audioCapture.start()

        scope.launch {
            audioCapture.audioData.collect { pcmData ->
                if (!isMuted && this@AudioEngine.userId != null) {
                    try {
                        sendRawFrame?.invoke(pcmData)
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
        audioPlayback.stop()
        sendRawFrame = null
        userId = null
        logger.info { "[Audio] Engine stopped" }
    }

    fun playRemotePcm(pcmData: ByteArray) {
        audioPlayback.playFrame(pcmData)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }
}
