package com.voicechat.client.webrtc

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

private val logger = KotlinLogging.logger("WebRTC")

data class CaptureSource(
    val id: Long,
    val title: String,
    val isWindow: Boolean
)

private val isLinux = System.getProperty("os.name").lowercase().contains("linux")

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
    private var robotCapture: RobotScreenCapture? = null
    private var ffmpegCapture: FfmpegScreenCapture? = null
    private var customVideoSource: CustomVideoSource? = null
    private var videoTrack: VideoTrack? = null
    @Volatile
    var isSharing = false
        private set

    private var remoteVideoTrack: VideoTrack? = null

    fun initialize() {
        factory = PeerConnectionFactory()
        logger.info { "[WebRTC] PeerConnectionFactory initialized" }
    }

    fun getAvailableScreens(): List<CaptureSource> {
        if (isLinux) return getLinuxScreens()
        return try {
            val capturer = ScreenCapturer()
            val sources = capturer.desktopSources.map { CaptureSource(it.id, it.title.ifEmpty { "Screen ${it.id}" }, false) }
            capturer.dispose()
            sources
        } catch (e: Exception) {
            logger.warn(e) { "[WebRTC] Native ScreenCapturer failed, using fallback" }
            getLinuxScreens()
        }
    }

    fun getAvailableWindows(): List<CaptureSource> {
        if (isLinux) return getLinuxWindows()
        return try {
            val capturer = WindowCapturer()
            val sources = capturer.desktopSources.map { CaptureSource(it.id, it.title.ifEmpty { "Window ${it.id}" }, true) }
            capturer.dispose()
            sources
        } catch (e: Exception) {
            logger.warn(e) { "[WebRTC] Native WindowCapturer failed, using fallback" }
            getLinuxWindows()
        }
    }

    private fun getLinuxScreens(): List<CaptureSource> {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        return ge.screenDevices.mapIndexed { index, device ->
            val bounds = device.defaultConfiguration.bounds
            CaptureSource(index.toLong(), "Screen $index (${bounds.width}x${bounds.height})", false)
        }
    }

    private fun getLinuxWindows(): List<CaptureSource> {
        return try {
            val process = ProcessBuilder("wmctrl", "-l")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() != 0) return emptyList()

            output.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"), limit = 4)
                    if (parts.size < 4) return@mapNotNull null
                    val hexId = parts[0]
                    val title = parts[3]
                    val id = hexId.removePrefix("0x").toLongOrNull(16) ?: return@mapNotNull null
                    CaptureSource(id, title, true)
                }
        } catch (e: Exception) {
            logger.debug { "[WebRTC] wmctrl not available for window list: ${e.message}" }
            emptyList()
        }
    }

    fun startScreenShare(sourceId: Long, isWindow: Boolean) {
        val currentFactory = factory ?: return
        if (isSharing) return

        if (isLinux) {
            startLinuxCapture(currentFactory, sourceId, isWindow)
        } else {
            startNativeCapture(currentFactory, sourceId, isWindow)
        }
        isSharing = true
    }

    private fun startNativeCapture(currentFactory: PeerConnectionFactory, sourceId: Long, isWindow: Boolean) {
        val source = VideoDesktopSource()
        source.setFrameRate(30)
        source.setMaxFrameSize(1920, 1080)
        source.setSourceId(sourceId, isWindow)
        source.start()
        videoDesktopSource = source
        videoTrack = currentFactory.createVideoTrack("screen", source)
        logger.info { "[WebRTC] Native capture started (sourceId=$sourceId, isWindow=$isWindow)" }
    }

    private fun startLinuxCapture(currentFactory: PeerConnectionFactory, sourceId: Long, isWindow: Boolean) {
        val bounds = if (isWindow) {
            getWindowBounds(sourceId)
        } else {
            getScreenBounds(sourceId.toInt())
        }
        if (bounds == null) {
            logger.error { "[WebRTC] Could not determine capture bounds for sourceId=$sourceId, isWindow=$isWindow" }
            return
        }

        val source = CustomVideoSource()
        customVideoSource = source
        videoTrack = currentFactory.createVideoTrack("screen", source)

        if (!isWindow && FfmpegScreenCapture.isAvailable()) {
            val capture = FfmpegScreenCapture(source, bounds, frameRate = 30)
            ffmpegCapture = capture
            capture.start()
            logger.info { "[WebRTC] FFmpeg capture started (${bounds.width}x${bounds.height} @ 30fps)" }
        } else {
            val capture = RobotScreenCapture(source, bounds, frameRate = 15)
            robotCapture = capture
            capture.start()
            logger.info { "[WebRTC] Robot capture fallback (${bounds.width}x${bounds.height} @ 15fps)" }
        }
    }

    private fun getScreenBounds(screenIndex: Int): Rectangle? {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val devices = ge.screenDevices
        if (screenIndex < 0 || screenIndex >= devices.size) return devices.firstOrNull()?.defaultConfiguration?.bounds
        return devices[screenIndex].defaultConfiguration.bounds
    }

    private fun getWindowBounds(windowId: Long): Rectangle? {
        return try {
            val process = ProcessBuilder("xdotool", "getwindowgeometry", "--shell", windowId.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() != 0) return null

            var x = 0; var y = 0; var w = 0; var h = 0
            for (line in output.lines()) {
                when {
                    line.startsWith("X=") -> x = line.substringAfter("=").toIntOrNull() ?: 0
                    line.startsWith("Y=") -> y = line.substringAfter("=").toIntOrNull() ?: 0
                    line.startsWith("WIDTH=") -> w = line.substringAfter("=").toIntOrNull() ?: 0
                    line.startsWith("HEIGHT=") -> h = line.substringAfter("=").toIntOrNull() ?: 0
                }
            }
            if (w > 0 && h > 0) Rectangle(x, y, w, h) else null
        } catch (e: Exception) {
            logger.warn(e) { "[WebRTC] xdotool failed for window $windowId" }
            null
        }
    }

    fun stopScreenShare() {
        isSharing = false
        ffmpegCapture?.stop()
        ffmpegCapture = null
        robotCapture?.stop()
        robotCapture = null
        try { videoDesktopSource?.stop() } catch (_: Exception) {}
        try { videoTrack?.dispose() } catch (_: Exception) {}
        try { videoDesktopSource?.dispose() } catch (_: Exception) {}
        try { customVideoSource?.dispose() } catch (_: Exception) {}
        videoTrack = null
        videoDesktopSource = null
        customVideoSource = null
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
