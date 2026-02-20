package com.voicechat.server.sfu

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.VideoTrack
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger("SFU")

class SfuManager {

    private val audioDeviceModule = HeadlessAudioDeviceModule()
    private val factory: PeerConnectionFactory
    private val audioMixer = AudioMixer()
    private val sessions = ConcurrentHashMap<String, UserMediaSession>()

    @Volatile
    var currentSharerId: String? = null
        private set

    private val viewerVideoSources = ConcurrentHashMap<String, CustomVideoSource>()

    init {
        audioDeviceModule.initPlayout()
        audioDeviceModule.startPlayout()
        audioDeviceModule.initRecording()
        audioDeviceModule.startRecording()
        factory = PeerConnectionFactory(audioDeviceModule)
        logger.info { "[SFU] PeerConnectionFactory initialized with HeadlessAudioDeviceModule" }
    }

    fun start() {
        audioMixer.start()
        logger.info { "[SFU] Started" }
    }

    fun stop() {
        audioMixer.stop()
        sessions.values.forEach { it.dispose() }
        sessions.clear()
        viewerVideoSources.clear()
        factory.dispose()
        try { audioDeviceModule.stopRecording() } catch (_: Throwable) {}
        try { audioDeviceModule.stopPlayout() } catch (_: Throwable) {}
        audioDeviceModule.dispose()
        logger.info { "[SFU] Stopped" }
    }

    fun createSession(
        userId: String,
        onIceCandidate: (RTCIceCandidate) -> Unit,
        onOffer: (String) -> Unit
    ): UserMediaSession {
        val session = UserMediaSession(
            userId = userId,
            factory = factory,
            audioMixer = audioMixer,
            iceCandidateCallback = onIceCandidate,
            offerCallback = onOffer
        )
        sessions[userId] = session
        session.start()
        logger.info { "[SFU] Created session for user $userId (total: ${sessions.size})" }
        return session
    }

    fun removeSession(userId: String) {
        if (currentSharerId == userId) {
            stopScreenShareInternal()
        }
        viewerVideoSources.remove(userId)
        sessions.remove(userId)?.dispose()
        logger.info { "[SFU] Removed session for user $userId (total: ${sessions.size})" }
    }

    fun getSession(userId: String): UserMediaSession? = sessions[userId]

    fun startScreenShare(sharerId: String) {
        if (currentSharerId != null) {
            logger.warn { "[SFU] Screen share already active by $currentSharerId, ignoring $sharerId" }
            return
        }

        currentSharerId = sharerId
        val sharerSession = sessions[sharerId] ?: return

        sharerSession.addVideoRecvTransceiver()
        sharerSession.onVideoTrackReceived = { videoTrack ->
            logger.info { "[SFU] Sharer $sharerId video track received, setting up forwarding" }
            setupVideoForwarding(videoTrack, sharerId)
        }
        sharerSession.renegotiate()

        for ((viewerId, viewerSession) in sessions) {
            if (viewerId == sharerId) continue
            val source = viewerSession.addVideoSendTransceiver()
            viewerVideoSources[viewerId] = source
            viewerSession.renegotiate()
        }

        logger.info { "[SFU] Screen share started by $sharerId" }
    }

    fun stopScreenShare(sharerId: String) {
        if (currentSharerId != sharerId) {
            logger.warn { "[SFU] $sharerId is not the current sharer ($currentSharerId)" }
            return
        }
        stopScreenShareInternal()
    }

    private fun stopScreenShareInternal() {
        val sharerId = currentSharerId ?: return
        currentSharerId = null

        val sharerSession = sessions[sharerId]
        sharerSession?.onVideoTrackReceived = null
        sharerSession?.removeVideoTransceivers()
        sharerSession?.renegotiate()

        for ((viewerId, viewerSession) in sessions) {
            if (viewerId == sharerId) continue
            viewerSession.removeVideoTransceivers()
            viewerSession.renegotiate()
        }
        viewerVideoSources.clear()
        logger.info { "[SFU] Screen share stopped (was by $sharerId)" }
    }

    private fun setupVideoForwarding(videoTrack: VideoTrack, sharerId: String) {
        videoTrack.addSink { frame ->
            for ((viewerId, source) in viewerVideoSources) {
                if (viewerId == sharerId) continue
                try {
                    source.pushFrame(frame)
                } catch (e: Exception) {
                    logger.warn { "[SFU] Failed to forward video frame to $viewerId: ${e.message}" }
                }
            }
        }
    }
}
