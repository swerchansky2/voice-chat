package com.voicechat.client.screen

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger("WebRTC")

data class SdpMessage(val targetUserId: String, val sdp: String, val sdpType: String)
data class IceMessage(val targetUserId: String, val sdp: String, val sdpMid: String, val sdpMLineIndex: Int)

class WebRtcScreenManager(
    private val scope: CoroutineScope,
    private val onSdpOffer: suspend (SdpMessage) -> Unit,
    private val onSdpAnswer: suspend (SdpMessage) -> Unit,
    private val onIceCandidate: suspend (IceMessage) -> Unit
) {
    private val factory by lazy { PeerConnectionFactory() }

    private var videoSource: VideoDesktopSource? = null
    private var videoTrack: VideoTrack? = null
    private var isSender = false

    private val peerConnections = ConcurrentHashMap<String, RTCPeerConnection>()
    private val pendingIceCandidates = ConcurrentHashMap<String, MutableList<RTCIceCandidate>>()

    private val _receivedFrame = MutableStateFlow<BufferedImage?>(null)
    val receivedFrame: StateFlow<BufferedImage?> = _receivedFrame.asStateFlow()

    private val videoSink = object : VideoTrackSink {
        override fun onVideoFrame(frame: VideoFrame) {
            try {
                val buffer = frame.buffer
                val width = buffer.width
                val height = buffer.height

                // Use native-optimized conversion: I420 -> BGRA (matches TYPE_4BYTE_ABGR byte order)
                val dstBytes = ByteArray(width * height * 4)
                VideoBufferConverter.convertFromI420(buffer, dstBytes, FourCC.ABGR)

                val image = BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
                val imgData = (image.raster.dataBuffer as DataBufferByte).data
                System.arraycopy(dstBytes, 0, imgData, 0, dstBytes.size)

                _receivedFrame.value = image
            } catch (e: Exception) {
                logger.error(e) { "[WebRTC] Error converting video frame" }
            } finally {
                frame.release()
            }
        }
    }

    private fun createRtcConfig(): RTCConfiguration {
        val config = RTCConfiguration()
        val iceServer = RTCIceServer()
        iceServer.urls.add("stun:stun.l.google.com:19302")
        config.iceServers.add(iceServer)
        return config
    }

    fun startSending(settings: ScreenShareSettings) {
        isSender = true
        factory // initialize WebRTC native environment before VideoDesktopSource

        videoSource = VideoDesktopSource().apply {
            setFrameRate(settings.fps)
            setMaxFrameSize(settings.resolution.width, settings.resolution.height)
            setSourceId(0, false)
            start()
        }

        videoTrack = factory.createVideoTrack("screen0", videoSource)
        logger.info { "[WebRTC] Sender started: ${settings.resolution.width}x${settings.resolution.height} @ ${settings.fps}fps" }
    }

    fun stopSending() {
        isSender = false
        closeAllPeerConnections()
        videoTrack = null
        videoSource?.let {
            try {
                it.stop()
                it.dispose()
            } catch (e: Exception) {
                logger.warn(e) { "[WebRTC] Error stopping video source" }
            }
        }
        videoSource = null
        logger.info { "[WebRTC] Sender stopped" }
    }

    fun createOfferForViewer(viewerUserId: String) {
        if (!isSender) return

        val config = createRtcConfig()
        val observer = createPeerConnectionObserver(viewerUserId)
        val pc = factory.createPeerConnection(config, observer)
        peerConnections[viewerUserId] = pc
        pendingIceCandidates[viewerUserId] = mutableListOf()

        videoTrack?.let { track ->
            pc.addTrack(track, listOf("screen-stream"))
        }

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
        val userIds = peerConnections.keys.toList()
        for (userId in userIds) {
            closePeerConnection(userId)
        }
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

            override fun onDataChannel(dataChannel: RTCDataChannel) {}

            override fun onRenegotiationNeeded() {
                logger.debug { "[WebRTC] Renegotiation needed [$remoteUserId]" }
            }

            override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<MediaStream>) {
                val track = receiver.track
                if (track.kind == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    logger.info { "[WebRTC] Received video track from $remoteUserId" }
                    (track as VideoTrack).addSink(videoSink)
                }
            }

            override fun onRemoveTrack(receiver: RTCRtpReceiver) {
                logger.info { "[WebRTC] Track removed from $remoteUserId" }
            }

            override fun onTrack(transceiver: RTCRtpTransceiver) {
                val track = transceiver.receiver.track
                if (track.kind == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    logger.info { "[WebRTC] Transceiver video track from $remoteUserId" }
                    (track as VideoTrack).addSink(videoSink)
                }
            }
        }
    }
}
