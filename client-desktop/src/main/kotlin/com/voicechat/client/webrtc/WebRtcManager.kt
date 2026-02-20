package com.voicechat.client.webrtc

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger("WebRTC")

class WebRtcManager(
    private val iceCandidateCallback: (RTCIceCandidate) -> Unit,
    private val answerCallback: (String) -> Unit
) {
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: RTCPeerConnection? = null
    private var audioSource: AudioTrackSource? = null
    private var audioTrack: AudioTrack? = null
    private var disposed = false

    fun initialize() {
        factory = PeerConnectionFactory()
        logger.info { "[WebRTC] PeerConnectionFactory initialized" }
    }

    fun handleOffer(sdp: String) {
        if (factory == null) {
            logger.error { "[WebRTC] Factory not initialized" }
            return
        }

        if (peerConnection != null) {
            logger.warn { "[WebRTC] PeerConnection already exists, closing old one" }
            dispose()
            disposed = false
            factory = PeerConnectionFactory()
        }

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
            }
        }

        val currentFactory = factory ?: return
        peerConnection = currentFactory.createPeerConnection(config, observer)

        val options = AudioOptions().apply {
            echoCancellation = true
            noiseSuppression = true
            autoGainControl = true
        }
        audioSource = currentFactory.createAudioSource(options)
        audioTrack = currentFactory.createAudioTrack("mic", audioSource)

        val pc = peerConnection ?: return

        val offerDesc = RTCSessionDescription(RTCSdpType.OFFER, sdp)
        pc.setRemoteDescription(offerDesc, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                logger.info { "[WebRTC] Remote description (offer) set" }
                pc.addTrack(audioTrack, listOf("stream0"))
                createAnswer()
            }

            override fun onFailure(error: String) {
                logger.error { "[WebRTC] Failed to set remote description: $error" }
            }
        })
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
        logger.info { "[WebRTC] Disposed" }
    }
}
