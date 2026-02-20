package com.voicechat.server.sfu

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger("SFU")

class SfuManager {

    private val audioDeviceModule = HeadlessAudioDeviceModule()
    private val factory: PeerConnectionFactory
    private val audioMixer = AudioMixer()
    private val sessions = ConcurrentHashMap<String, UserMediaSession>()

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
        sessions.remove(userId)?.dispose()
        logger.info { "[SFU] Removed session for user $userId (total: ${sessions.size})" }
    }

    fun getSession(userId: String): UserMediaSession? = sessions[userId]
}
