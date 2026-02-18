package com.voicechat.client.screen

import dev.onvoid.webrtc.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

private val logger = KotlinLogging.logger("WebRTC")

data class SdpMessage(val targetUserId: String, val sdp: String, val sdpType: String)
data class IceMessage(val targetUserId: String, val sdp: String, val sdpMid: String, val sdpMLineIndex: Int)

/**
 * Maximum payload bytes per DataChannel send(). SCTP in libwebrtc has a hard limit of ~256 KB
 * per message; by chunking to 60 KB we stay well below it at any resolution.
 */
private const val CHUNK_SIZE = 60_000

class WebRtcScreenManager(
    private val scope: CoroutineScope,
    private val onSdpOffer: suspend (SdpMessage) -> Unit,
    private val onSdpAnswer: suspend (SdpMessage) -> Unit,
    private val onIceCandidate: suspend (IceMessage) -> Unit
) {
    private val factory by lazy { PeerConnectionFactory() }

    private var isSender = false
    private var captureThread: ScreenCaptureThread? = null

    private val peerConnections = ConcurrentHashMap<String, RTCPeerConnection>()
    private val pendingIceCandidates = ConcurrentHashMap<String, MutableList<RTCIceCandidate>>()
    private val senderDataChannels = ConcurrentHashMap<String, RTCDataChannel>()

    // Updated only from the DC observer's onStateChange — avoids querying dc.state via JNI
    // from an arbitrary thread.
    private val openSenderChannels: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val _receivedFrame = MutableStateFlow<BufferedImage?>(null)
    val receivedFrame: StateFlow<BufferedImage?> = _receivedFrame.asStateFlow()

    private var frameCounter = 0
    private var sentFrameCount = 0L

    private fun createRtcConfig(): RTCConfiguration {
        val config = RTCConfiguration()
        val iceServer = RTCIceServer()
        iceServer.urls.add("stun:stun.l.google.com:19302")
        config.iceServers.add(iceServer)
        return config
    }

    fun startSending(settings: ScreenShareSettings) {
        isSender = true
        captureThread = ScreenCaptureThread(
            targetFps = settings.fps,
            targetWidth = settings.resolution.width,
            targetHeight = settings.resolution.height,
        ) { jpegBytes ->
            broadcastFrame(jpegBytes)
        }.also { it.start() }
        logger.info { "[WebRTC] Sender started @ ${settings.fps}fps, ${settings.resolution.width}x${settings.resolution.height}" }
    }

    /**
     * Splits [jpegBytes] into [CHUNK_SIZE]-byte chunks and sends each as a separate DataChannel
     * message. Chunk header (6 bytes): [frameId:2][chunkIndex:2][totalChunks:2].
     * Returns true if all chunks were delivered to at least one viewer.
     */
    private fun broadcastFrame(jpegBytes: ByteArray): Boolean {
        if (openSenderChannels.isEmpty()) return false

        val frameId = (frameCounter++ and 0xFFFF).toShort()
        val totalChunks = (jpegBytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE

        var sent = false
        senderDataChannels.forEach { (userId, dc) ->
            if (!openSenderChannels.contains(userId)) return@forEach
            try {
                for (i in 0 until totalChunks) {
                    val offset = i * CHUNK_SIZE
                    val length = minOf(CHUNK_SIZE, jpegBytes.size - offset)
                    val buf = ByteBuffer.allocate(6 + length)
                    buf.putShort(frameId)
                    buf.putShort(i.toShort())
                    buf.putShort(totalChunks.toShort())
                    buf.put(jpegBytes, offset, length)
                    buf.flip()
                    dc.send(RTCDataChannelBuffer(buf, true))
                }
                sent = true
                val n = ++sentFrameCount
                if (n % 60 == 0L) {
                    logger.debug { "[WebRTC] Sent frame #$n to $userId (${jpegBytes.size / 1024}KB, $totalChunks chunks)" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "[WebRTC] Failed to send frame to $userId (${jpegBytes.size / 1024}KB)" }
                openSenderChannels.remove(userId)
            }
        }
        return sent
    }

    fun stopSending() {
        isSender = false
        captureThread?.stopCapture()
        captureThread = null
        senderDataChannels.clear()
        openSenderChannels.clear()
        closeAllPeerConnections()
        logger.info { "[WebRTC] Sender stopped" }
    }

    fun createOfferForViewer(viewerUserId: String) {
        if (!isSender) return

        val config = createRtcConfig()
        val observer = createPeerConnectionObserver(viewerUserId)
        val pc = factory.createPeerConnection(config, observer)
        peerConnections[viewerUserId] = pc
        pendingIceCandidates[viewerUserId] = mutableListOf()

        val dcInit = RTCDataChannelInit()
        // Reliable + ordered: all chunks arrive in sequence. On a LAN this adds negligible
        // overhead while guaranteeing every frame is fully reassembled without artifacts.
        dcInit.ordered = true
        val dc = pc.createDataChannel("screen", dcInit)
        senderDataChannels[viewerUserId] = dc
        openSenderChannels.remove(viewerUserId)

        dc.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                val state = dc.state
                logger.info { "[WebRTC] Sender DC [$viewerUserId] state: $state" }
                if (state == RTCDataChannelState.OPEN) {
                    openSenderChannels.add(viewerUserId)
                    captureThread?.forceNextSend()
                } else {
                    openSenderChannels.remove(viewerUserId)
                }
            }
            override fun onMessage(buffer: RTCDataChannelBuffer) {}
        })

        val offerOptions = RTCOfferOptions()
        pc.createOffer(offerOptions, object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        scope.launch {
                            onSdpOffer(SdpMessage(viewerUserId, description.sdp, description.sdpType.name.lowercase()))
                        }
                        logger.info { "[WebRTC] Offer created and sent to $viewerUserId" }
                    }
                    override fun onFailure(error: String) {
                        logger.error { "[WebRTC] Failed to set local description: $error" }
                    }
                })
            }
            override fun onFailure(error: String) {
                logger.error { "[WebRTC] Failed to create offer for $viewerUserId: $error" }
            }
        })
    }

    fun handleSdpOffer(fromUserId: String, sdp: String, type: String) {
        val config = createRtcConfig()
        val observer = createPeerConnectionObserver(fromUserId)
        val pc = factory.createPeerConnection(config, observer)
        peerConnections[fromUserId] = pc
        pendingIceCandidates[fromUserId] = mutableListOf()

        val remoteDesc = RTCSessionDescription(RTCSdpType.OFFER, sdp)
        pc.setRemoteDescription(remoteDesc, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                drainPendingIceCandidates(fromUserId)
                pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) {
                        pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                scope.launch {
                                    onSdpAnswer(SdpMessage(fromUserId, description.sdp, description.sdpType.name.lowercase()))
                                }
                                logger.info { "[WebRTC] Answer created and sent to $fromUserId" }
                            }
                            override fun onFailure(error: String) {
                                logger.error { "[WebRTC] Failed to set local description (answer): $error" }
                            }
                        })
                    }
                    override fun onFailure(error: String) {
                        logger.error { "[WebRTC] Failed to create answer for $fromUserId: $error" }
                    }
                })
            }
            override fun onFailure(error: String) {
                logger.error { "[WebRTC] Failed to set remote description (offer from $fromUserId): $error" }
            }
        })
    }

    fun handleSdpAnswer(fromUserId: String, sdp: String, type: String) {
        val pc = peerConnections[fromUserId]
        if (pc == null) {
            logger.warn { "[WebRTC] No peer connection found for SDP answer from $fromUserId" }
            return
        }

        val remoteDesc = RTCSessionDescription(RTCSdpType.ANSWER, sdp)
        pc.setRemoteDescription(remoteDesc, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                drainPendingIceCandidates(fromUserId)
                logger.info { "[WebRTC] Remote description (answer) set for $fromUserId" }
            }
            override fun onFailure(error: String) {
                logger.error { "[WebRTC] Failed to set remote description (answer from $fromUserId): $error" }
            }
        })
    }

    fun handleIceCandidate(fromUserId: String, sdp: String, sdpMid: String, sdpMLineIndex: Int) {
        val candidate = RTCIceCandidate(sdpMid, sdpMLineIndex, sdp)
        val pc = peerConnections[fromUserId]

        if (pc?.remoteDescription != null) {
            try {
                pc.addIceCandidate(candidate)
            } catch (e: Exception) {
                logger.warn(e) { "[WebRTC] Failed to add ICE candidate from $fromUserId" }
            }
        } else {
            pendingIceCandidates.getOrPut(fromUserId) { mutableListOf() }.add(candidate)
            logger.debug { "[WebRTC] Queued ICE candidate from $fromUserId (no remote description yet)" }
        }
    }

    private fun drainPendingIceCandidates(userId: String) {
        val pc = peerConnections[userId] ?: return
        val candidates = pendingIceCandidates.remove(userId) ?: return
        for (candidate in candidates) {
            try {
                pc.addIceCandidate(candidate)
            } catch (e: Exception) {
                logger.warn(e) { "[WebRTC] Failed to add queued ICE candidate for $userId" }
            }
        }
        if (candidates.isNotEmpty()) {
            logger.info { "[WebRTC] Drained ${candidates.size} pending ICE candidates for $userId" }
        }
    }

    fun closePeerConnection(userId: String) {
        pendingIceCandidates.remove(userId)
        senderDataChannels.remove(userId)
        openSenderChannels.remove(userId)
        peerConnections.remove(userId)?.let { pc ->
            try {
                pc.close()
            } catch (e: Exception) {
                logger.warn(e) { "[WebRTC] Error closing peer connection for $userId" }
            }
            logger.info { "[WebRTC] Closed peer connection for $userId" }
        }
    }

    fun closeAllPeerConnections() {
        peerConnections.keys.toList().forEach { closePeerConnection(it) }
    }

    fun clearReceivedFrame() {
        _receivedFrame.value = null
    }

    fun dispose() {
        stopSending()
        closeAllPeerConnections()
        try {
            factory.dispose()
        } catch (e: Exception) {
            logger.warn(e) { "[WebRTC] Error disposing factory" }
        }
        logger.info { "[WebRTC] Manager disposed" }
    }

    private fun createPeerConnectionObserver(remoteUserId: String): PeerConnectionObserver {
        return object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                scope.launch {
                    onIceCandidate(IceMessage(remoteUserId, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
                }
            }

            override fun onConnectionChange(state: RTCPeerConnectionState) {
                logger.info { "[WebRTC] Connection state [$remoteUserId]: $state" }
                if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.DISCONNECTED) {
                    logger.warn { "[WebRTC] Peer connection to $remoteUserId is $state" }
                }
            }

            override fun onIceConnectionChange(state: RTCIceConnectionState) {
                logger.debug { "[WebRTC] ICE connection [$remoteUserId]: $state" }
            }

            override fun onIceGatheringChange(state: RTCIceGatheringState) {
                logger.debug { "[WebRTC] ICE gathering [$remoteUserId]: $state" }
            }

            override fun onSignalingChange(state: RTCSignalingState) {
                logger.debug { "[WebRTC] Signaling [$remoteUserId]: $state" }
            }

            // Viewer side: receives the DataChannel created by the sender
            override fun onDataChannel(dataChannel: RTCDataChannel) {
                logger.info { "[WebRTC] DataChannel received from $remoteUserId" }

                // Frame reassembly state — lives per DataChannel, mutated only from onMessage
                // callbacks which are always delivered sequentially by the WebRTC thread.
                var currentFrameId = -1
                var currentChunks: Array<ByteArray?> = emptyArray()
                var receivedChunkCount = 0

                dataChannel.registerObserver(object : RTCDataChannelObserver {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        logger.info { "[WebRTC] Viewer DC [$remoteUserId] state: ${dataChannel.state}" }
                    }

                    override fun onMessage(buffer: RTCDataChannelBuffer) {
                        val data = buffer.data
                        if (data.remaining() < 6) return

                        val frameId    = data.getShort().toInt() and 0xFFFF
                        val chunkIndex = data.getShort().toInt() and 0xFFFF
                        val totalChunks = data.getShort().toInt() and 0xFFFF

                        if (totalChunks <= 0 || chunkIndex >= totalChunks) return

                        val chunk = ByteArray(data.remaining())
                        data.get(chunk)

                        if (frameId != currentFrameId) {
                            // A new frame started — discard any incomplete previous frame
                            currentFrameId = frameId
                            currentChunks = arrayOfNulls(totalChunks)
                            receivedChunkCount = 0
                        }

                        if (chunkIndex < currentChunks.size && currentChunks[chunkIndex] == null) {
                            currentChunks[chunkIndex] = chunk
                            receivedChunkCount++

                            if (receivedChunkCount == currentChunks.size) {
                                // All chunks arrived — reassemble and decode
                                try {
                                    val baos = ByteArrayOutputStream()
                                    currentChunks.forEach { c -> if (c != null) baos.write(c) }
                                    val image = ImageIO.read(ByteArrayInputStream(baos.toByteArray()))
                                    if (image != null) {
                                        _receivedFrame.value = image
                                    } else {
                                        logger.warn { "[WebRTC] ImageIO.read returned null for frame $frameId" }
                                    }
                                } catch (e: Exception) {
                                    logger.error(e) { "[WebRTC] Failed to decode frame $frameId" }
                                } finally {
                                    currentFrameId = -1
                                    currentChunks = emptyArray()
                                    receivedChunkCount = 0
                                }
                            }
                        }
                    }
                })
            }

            override fun onRenegotiationNeeded() {
                logger.debug { "[WebRTC] Renegotiation needed [$remoteUserId]" }
            }

            override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<dev.onvoid.webrtc.media.MediaStream>) {}
            override fun onRemoveTrack(receiver: RTCRtpReceiver) {}
            override fun onTrack(transceiver: RTCRtpTransceiver) {}
        }
    }
}

/**
 * Captures the primary screen using AWT Robot, scales to [targetWidth]x[targetHeight],
 * and JPEG-encodes each frame at quality 0.65 (good balance for screen/UI content).
 *
 * Hash-based skip: frames are only encoded when pixels actually change, and only the
 * hash of a successfully delivered frame is remembered — so retries happen automatically
 * until a viewer receives the frame.
 */
private class ScreenCaptureThread(
    private val targetFps: Int,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val onFrame: (ByteArray) -> Boolean
) : Thread("ScreenCapture") {

    @Volatile private var running = true
    @Volatile private var forceFlag = true // always send the very first frame

    private val robot = Robot()
    private val screenRect: Rectangle = Rectangle(Toolkit.getDefaultToolkit().screenSize)
    private val baos = ByteArrayOutputStream(512 * 1024)
    private val jpegWriter = ImageIO.getImageWritersByFormatName("jpeg").next()
    private val writeParam: ImageWriteParam = jpegWriter.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = 0.65f  // smaller files, negligible visual difference for screen content
    }

    // Reused scaled-frame buffer — avoids a BufferedImage allocation on every frame
    private val scaledBuf = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)

    private var lastSentHash = 0L

    init { isDaemon = true }

    /** Force-send the next captured frame regardless of hash (called when a viewer DC opens). */
    fun forceNextSend() { forceFlag = true }

    override fun run() {
        val frameIntervalMs = 1000L / targetFps.coerceAtLeast(1)

        while (running) {
            val frameStart = System.currentTimeMillis()

            try {
                val screenshot = robot.createScreenCapture(screenRect)

                val frame = if (screenshot.width == targetWidth && screenshot.height == targetHeight) {
                    screenshot
                } else {
                    val g2d = scaledBuf.createGraphics()
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g2d.drawImage(screenshot, 0, 0, targetWidth, targetHeight, null)
                    g2d.dispose()
                    scaledBuf
                }

                val hash = quickHash(frame)
                if (forceFlag || hash != lastSentHash) {
                    forceFlag = false
                    baos.reset()
                    ImageIO.createImageOutputStream(baos).use { ios ->
                        jpegWriter.output = ios
                        jpegWriter.write(null, IIOImage(frame, null, null), writeParam)
                    }
                    if (onFrame(baos.toByteArray())) {
                        lastSentHash = hash
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                logger.error(e) { "[WebRTC] Screen capture error" }
            }

            val elapsed = System.currentTimeMillis() - frameStart
            val sleepMs = frameIntervalMs - elapsed
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs) } catch (e: InterruptedException) { break }
            }
        }

        jpegWriter.dispose()
    }

    // Sample 64 evenly-spaced pixels to cheaply detect frame changes
    private fun quickHash(img: BufferedImage): Long {
        val stepX = img.width / 8
        val stepY = img.height / 8
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                hash = hash * 31 + img.getRGB(x * stepX, y * stepY)
            }
        }
        return hash
    }

    fun stopCapture() {
        running = false
        interrupt()
    }
}
