package com.voicechat.client.screen

import com.voicechat.client.network.SignalingClient
import com.voicechat.shared.protocol.ScreenFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

private val logger = KotlinLogging.logger {}

class ScreenEngine(
    private val signalingClient: SignalingClient
) {
    private val screenCapture = ScreenCapture()
    private var encoder: ScreenEncoder? = null
    private val decoder = ScreenDecoder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var userId: String? = null
    private var isSharing = false

    val receivedFrame: StateFlow<BufferedImage?> = decoder.decodedFrame

    fun startSharing(userId: String, settings: ScreenShareSettings) {
        this.userId = userId
        isSharing = true

        val res = settings.resolution
        val fps = settings.fps

        encoder = ScreenEncoder(res.width, res.height, fps, res.bitrate).also { it.start() }

        screenCapture.start(scope, res.width, res.height, fps)

        scope.launch {
            screenCapture.frames.collect { image ->
                if (!isSharing) return@collect
                val encoded = encoder?.encode(image) ?: return@collect
                val frame = ScreenFrame(userId, encoded)
                signalingClient.sendScreenFrame(frame)
            }
        }

        logger.info { "Screen sharing started: ${res.width}x${res.height} @ ${fps}fps, H.264" }
    }

    fun stopSharing() {
        isSharing = false
        screenCapture.stop()
        encoder?.stop()
        encoder = null
        logger.info { "Screen sharing stopped" }
    }

    fun startReceiving() {
        decoder.start(scope)
        logger.info { "Screen receiving started" }
    }

    fun stopReceiving() {
        decoder.stop()
        logger.info { "Screen receiving stopped" }
    }

    fun handleReceivedFrame(data: ByteArray) {
        val screenFrame = ScreenFrame.fromBytes(data) ?: return
        decoder.feedData(screenFrame.encodedData)
    }

    fun clearReceivedFrame() {
        decoder.clearFrame()
    }
}
