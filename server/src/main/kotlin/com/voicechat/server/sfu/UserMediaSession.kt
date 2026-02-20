package com.voicechat.server.sfu

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger("UserMedia")

class UserMediaSession(
    val userId: String,
    private val factory: PeerConnectionFactory,
    private val audioMixer: AudioMixer,
    private val iceCandidateCallback: (RTCIceCandidate) -> Unit,
    private val offerCallback: (String) -> Unit
) {
    private val sendSource = CustomAudioSource()
    private var sendTrack: AudioTrack? = null
    private var receiveTrackSink: AudioTrackSink? = null
    private var peerConnection: RTCPeerConnection? = null

    fun start() {
        val config = RTCConfiguration().apply {
            iceServers.add(RTCIceServer().apply {
                urls.add("stun:stun.l.google.com:19302")
            })
        }

        val observer = object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                logger.debug { "[UserMedia:$userId] ICE candidate: ${candidate.sdp}" }
                iceCandidateCallback(candidate)
            }

            override fun onIceConnectionChange(state: RTCIceConnectionState) {
                logger.info { "[UserMedia:$userId] ICE connection: $state" }
            }

            override fun onConnectionChange(state: RTCPeerConnectionState) {
                logger.info { "[UserMedia:$userId] Connection: $state" }
            }

            override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track
                if (track is AudioTrack) {
                    logger.info { "[UserMedia:$userId] Received remote audio track" }
                    val sink = AudioTrackSink { data, bitsPerSample, sampleRate, channels, frames ->
                        audioMixer.pushAudioFrame(userId, data)
                    }
                    receiveTrackSink = sink
                    track.addSink(sink)
                }
            }
        }

        peerConnection = factory.createPeerConnection(config, observer)

        sendTrack = factory.createAudioTrack("send-$userId", sendSource)

        val init = RTCRtpTransceiverInit().apply {
            direction = RTCRtpTransceiverDirection.SEND_RECV
        }
        peerConnection!!.addTransceiver(sendTrack, init)

        audioMixer.addUser(userId) { mixedAudio ->
            sendSource.pushAudio(
                mixedAudio,
                AudioMixer.BITS_PER_SAMPLE,
                AudioMixer.SAMPLE_RATE,
                AudioMixer.CHANNELS,
                AudioMixer.FRAME_COUNT
            )
        }

        createAndSendOffer()
    }

    private fun createAndSendOffer() {
        val pc = peerConnection ?: return

        pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        logger.info { "[UserMedia:$userId] Local description set, sending offer" }
                        offerCallback(description.sdp)
                    }

                    override fun onFailure(error: String) {
                        logger.error { "[UserMedia:$userId] Failed to set local description: $error" }
                    }
                })
            }

            override fun onFailure(error: String) {
                logger.error { "[UserMedia:$userId] Failed to create offer: $error" }
            }
        })
    }

    fun handleAnswer(sdp: String) {
        val description = RTCSessionDescription(RTCSdpType.ANSWER, sdp)
        peerConnection?.setRemoteDescription(description, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                logger.info { "[UserMedia:$userId] Remote description (answer) set" }
            }

            override fun onFailure(error: String) {
                logger.error { "[UserMedia:$userId] Failed to set remote description: $error" }
            }
        })
    }

    fun handleIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val iceCandidate = RTCIceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun dispose() {
        audioMixer.removeUser(userId)
        peerConnection?.close()
        sendTrack?.dispose()
        sendSource.dispose()
        peerConnection = null
        sendTrack = null
        receiveTrackSink = null
        logger.info { "[UserMedia:$userId] Disposed" }
    }
}
