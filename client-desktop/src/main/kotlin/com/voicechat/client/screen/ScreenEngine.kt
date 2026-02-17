package com.voicechat.client.screen

import com.voicechat.client.network.SignalingClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.awt.image.BufferedImage

private val logger = KotlinLogging.logger("ScreenEngine")

class ScreenEngine(
    private val signalingClient: SignalingClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webRtcManager: WebRtcScreenManager? = null
    private var isSharing = false
    private var screenSharerUserId: String? = null

    val receivedFrame: StateFlow<BufferedImage?> get() = getOrCreateManager().receivedFrame

    private fun getOrCreateManager(): WebRtcScreenManager {
        return webRtcManager ?: WebRtcScreenManager(
            scope = scope,
            onSdpOffer = { msg ->
                signalingClient.sendSdpOffer(msg.targetUserId, msg.sdp, msg.sdpType)
            },
            onSdpAnswer = { msg ->
                signalingClient.sendSdpAnswer(msg.targetUserId, msg.sdp, msg.sdpType)
            },
            onIceCandidate = { msg ->
                signalingClient.sendIceCandidate(msg.targetUserId, msg.sdp, msg.sdpMid, msg.sdpMLineIndex)
            }
        ).also { webRtcManager = it }
    }

    fun startSharing(userId: String, settings: ScreenShareSettings) {
        isSharing = true
        val manager = getOrCreateManager()
        manager.startSending(settings)
        logger.info { "[ScreenEngine] Started sharing via WebRTC" }
    }

    fun stopSharing() {
        isSharing = false
        webRtcManager?.stopSending()
        logger.info { "[ScreenEngine] Stopped sharing" }
    }

    fun startReceiving(sharerUserId: String) {
        screenSharerUserId = sharerUserId
        getOrCreateManager()
        logger.info { "[ScreenEngine] Ready to receive from $sharerUserId" }
    }

    fun stopReceiving() {
        screenSharerUserId = null
        webRtcManager?.closeAllPeerConnections()
        logger.info { "[ScreenEngine] Stopped receiving" }
    }

    fun createOfferForViewer(viewerUserId: String) {
        if (!isSharing) return
        getOrCreateManager().createOfferForViewer(viewerUserId)
    }

    fun handleSdpOffer(fromUserId: String, sdp: String, type: String) {
        getOrCreateManager().handleSdpOffer(fromUserId, sdp, type)
    }

    fun handleSdpAnswer(fromUserId: String, sdp: String, type: String) {
        getOrCreateManager().handleSdpAnswer(fromUserId, sdp, type)
    }

    fun handleIceCandidate(fromUserId: String, sdp: String, sdpMid: String, sdpMLineIndex: Int) {
        getOrCreateManager().handleIceCandidate(fromUserId, sdp, sdpMid, sdpMLineIndex)
    }

    fun closePeerConnection(userId: String) {
        webRtcManager?.closePeerConnection(userId)
    }

    fun clearReceivedFrame() {
        webRtcManager?.clearReceivedFrame()
    }

    fun dispose() {
        webRtcManager?.dispose()
        webRtcManager = null
    }
}
