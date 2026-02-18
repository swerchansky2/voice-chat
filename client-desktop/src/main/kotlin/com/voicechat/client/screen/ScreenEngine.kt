package com.voicechat.client.screen

import androidx.compose.ui.graphics.ImageBitmap
import com.voicechat.client.network.VideoUdpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

private val logger = KotlinLogging.logger("ScreenEngine")

class ScreenEngine(
    private val videoUdpClient: VideoUdpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var manager: ScreenManager? = null

    val receivedFrame: StateFlow<ImageBitmap?> get() = getOrCreateManager().receivedFrame

    private fun getOrCreateManager(): ScreenManager {
        return manager ?: ScreenManager(scope, videoUdpClient).also { manager = it }
    }

    fun startSharing(userId: String, settings: ScreenShareSettings) {
        getOrCreateManager().startSending(userId, settings)
        logger.info { "[ScreenEngine] Started sharing as $userId" }
    }

    fun stopSharing() {
        manager?.stopSending()
        logger.info { "[ScreenEngine] Stopped sharing" }
    }

    fun startReceiving(sharerUserId: String) {
        getOrCreateManager().startReceiving(sharerUserId)
        logger.info { "[ScreenEngine] Receiving from $sharerUserId" }
    }

    fun stopReceiving() {
        manager?.stopReceiving()
        logger.info { "[ScreenEngine] Stopped receiving" }
    }

    fun clearReceivedFrame() {
        manager?.clearReceivedFrame()
    }

    fun dispose() {
        manager?.dispose()
        manager = null
    }
}
