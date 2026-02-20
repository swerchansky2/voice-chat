package com.voicechat.client.webrtc

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.desktop.DesktopSource
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger("WebRTC")

class WebRtcManager(
    private val iceCandidateCallback: (RTCIceCandidate) -> Unit,
    private val answerCallback: (String) -> Unit,
    private val onRemoteVideoFrame: ((VideoFrame) -> Unit)? = null,
    private val onRemoteVideoEnded: (() -> Unit)? = null
) {
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: RTCPeerConnection? = null
    private var audioSource: AudioTrackSource? = null
    private var audioTrack: AudioTrack? = null
    private var disposed = false
    private var audioAdded = false

    private var videoDesktopSource: VideoDesktopSource? = null
    private var videoTrack: VideoTrack? = null
    @Volatile
    var isSharing = false
        private set

    private var remoteVideoTrack: VideoTrack? = null

    fun initialize() {
        factory = PeerConnectionFactory()
        logger.info { "[WebRTC] PeerConnectionFactory initialized" }
    }

    fun getAvailableScreens(): List<DesktopSource> {
        val capturer = ScreenCapturer()
        val sources = capturer.desktopSources.toList()
        capturer.dispose()
        return sources
    }

    fun getAvailableWindows(): List<DesktopSource> {
        val capturer = WindowCapturer()
        val sources = capturer.desktopSources.toList()
        capturer.dispose()
        return sources
    }

    fun startScreenShare(sourceId: Long, isWindow: Boolean) {
        val currentFactory = factory ?: return
        if (isSharing) return

        val source = VideoDesktopSource()
        source.setFrameRate(30)
        source.setMaxFrameSize(1920, 1080)
        source.setSourceId(sourceId, isWindow)
        source.start()
        videoDesktopSource = source

        videoTrack = currentFactory.createVideoTrack("screen", source)
        isSharing = true
        logger.info { "[WebRTC] Screen capture started (sourceId=$sourceId, isWindow=$isWindow)" }
    }

    fun stopScreenShare() {
        isSharing = false
        try { videoDesktopSource?.stop() } catch (_: Exception) {}
        try { videoTrack?.dispose() } catch (_: Exception) {}
        try { videoDesktopSource?.dispose() } catch (_: Exception) {}
        videoTrack = null
        videoDesktopSource = null
        logger.info { "[WebRTC] Screen capture stopped" }
    }

    fun handleOffer(sdp: String) {
        if (factory == null) {
            logger.error { "[WebRTC] Factory not initialized" }
            return
        }

        val currentFactory = factory ?: return

        if (peerConnection == null) {
            setupPeerConnection(currentFactory)
        }

        val pc = peerConnection ?: return

        val offerDesc = RTCSessionDescription(RTCSdpType.OFFER, sdp)
        pc.setRemoteDescription(offerDesc, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                logger.info { "[WebRTC] Remote description (offer) set" }
                if (!audioAdded) {
                    pc.addTrack(audioTrack, listOf("stream0"))
                    audioAdded = true
                }
                if (isSharing && videoTrack != null) {
                    pc.addTrack(videoTrack, listOf("stream0"))
                    logger.info { "[WebRTC] Added video track for screen share" }
                }
                createAnswer()
            }

            override fun onFailure(error: String) {
                logger.error { "[WebRTC] Failed to set remote description: $error" }
            }
        })
    }

    private fun setupPeerConnection(currentFactory: PeerConnectionFactory) {
        val config = RTCConfiguration().apply {
            iceServers.add(RTCIceServer().apply {
                urls.add("stun:stun.l.google.com:19302")
            })
        }

        val observer = object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                logger.debug { "[WebRTC] Local ICE candidate: ${candidate.sdp}" }
                iceCandidateCallback(candidate)
            }

            override fun onIceConnectionChange(state: RTCIceConnectionState) {
                logger.info { "[WebRTC] ICE connection: $state" }
            }

            override fun onConnectionChange(state: RTCPeerConnectionState) {
                logger.info { "[WebRTC] Connection: $state" }
            }

            override fun onTrack(transceiver: RTCRtpTransceiver) {
                val track = transceiver.receiver.track
                logger.info { "[WebRTC] Remote track received: ${track?.kind}" }
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    track.addSink { frame ->
                        onRemoteVideoFrame?.invoke(frame)
                    }
                    logger.info { "[WebRTC] Remote video track sink attached" }
                }
            }

            override fun onRemoveTrack(receiver: RTCRtpReceiver) {
                val track = receiver.track
                if (track is VideoTrack) {
                    logger.info { "[WebRTC] Remote video track removed" }
                    remoteVideoTrack = null
                    onRemoteVideoEnded?.invoke()
                }
            }
        }

        peerConnection = currentFactory.createPeerConnection(config, observer)

        val options = AudioOptions().apply {
            echoCancellation = true
            noiseSuppression = true
            autoGainControl = true
        }
        audioSource = currentFactory.createAudioSource(options)
        audioTrack = currentFactory.createAudioTrack("mic", audioSource)
    }

    private fun createAnswer() {
        val pc = peerConnection ?: return

        pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        logger.info { "[WebRTC] Local description (answer) set, sending answer" }
                        answerCallback(description.sdp)
                    }

                    override fun onFailure(error: String) {
                        logger.error { "[WebRTC] Failed to set local description: $error" }
                    }
                })
            }

            override fun onFailure(error: String) {
                logger.error { "[WebRTC] Failed to create answer: $error" }
            }
        })
    }

    fun handleIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val iceCandidate = RTCIceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
        logger.info { "[WebRTC] Audio track ${if (muted) "muted" else "unmuted"}" }
    }

    fun dispose() {
        if (disposed) return
        disposed = true

        stopScreenShare()
        remoteVideoTrack = null

        try { peerConnection?.close() } catch (e: Exception) {
            logger.warn(e) { "[WebRTC] Error closing PeerConnection" }
        }
        try { audioTrack?.dispose() } catch (e: Exception) {
            logger.warn(e) { "[WebRTC] Error disposing AudioTrack" }
        }
        try { factory?.dispose() } catch (e: Exception) {
            logger.warn(e) { "[WebRTC] Error disposing factory" }
        }
        peerConnection = null
        audioTrack = null
        audioSource = null
        factory = null
        audioAdded = false
        logger.info { "[WebRTC] Disposed" }
    }
}
