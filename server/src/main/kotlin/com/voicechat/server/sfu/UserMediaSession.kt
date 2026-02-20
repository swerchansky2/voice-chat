package com.voicechat.server.sfu

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import dev.onvoid.webrtc.media.video.CustomVideoSource
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger("UserMedia")

class UserMediaSession(
    val userId: String,
    private val factory: PeerConnectionFactory,
    private val audioMixer: AudioMixer,
    private val iceCandidateCallback: (RTCIceCandidate) -> Unit,
    private val offerCallback: (String) -> Unit
) {
    private val audioSendSource = CustomAudioSource()
    private var audioSendTrack: AudioTrack? = null
    private var receiveTrackSink: AudioTrackSink? = null
    private var peerConnection: RTCPeerConnection? = null

    private var videoSendSource: CustomVideoSource? = null
    private var videoSendTrack: VideoTrack? = null
    private var videoReceiveSink: VideoTrackSink? = null

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
                when (track) {
                    is AudioTrack -> {
                        logger.info { "[UserMedia:$userId] Received remote audio track" }
                        val sink = AudioTrackSink { data, bitsPerSample, sampleRate, channels, frames ->
                            audioMixer.pushAudioFrame(userId, data)
                        }
                        receiveTrackSink = sink
                        track.addSink(sink)
                    }
                    is VideoTrack -> {
                        logger.info { "[UserMedia:$userId] Received remote video track" }
                        onVideoTrackReceived?.invoke(track)
                    }
                }
            }
        }

        peerConnection = factory.createPeerConnection(config, observer)

        audioSendTrack = factory.createAudioTrack("send-$userId", audioSendSource)

        val init = RTCRtpTransceiverInit().apply {
            direction = RTCRtpTransceiverDirection.SEND_RECV
        }
        peerConnection!!.addTransceiver(audioSendTrack, init)

        audioMixer.addUser(userId) { mixedAudio ->
            audioSendSource.pushAudio(
                mixedAudio,
                AudioMixer.BITS_PER_SAMPLE,
                AudioMixer.SAMPLE_RATE,
                AudioMixer.CHANNELS,
                AudioMixer.FRAME_COUNT
            )
        }

        createAndSendOffer()
    }

    var onVideoTrackReceived: ((VideoTrack) -> Unit)? = null

    fun addVideoSendTransceiver(): CustomVideoSource {
        val source = CustomVideoSource()
        videoSendSource = source
        videoSendTrack = factory.createVideoTrack("video-send-$userId", source)

        val init = RTCRtpTransceiverInit().apply {
            direction = RTCRtpTransceiverDirection.SEND_ONLY
        }
        peerConnection!!.addTransceiver(videoSendTrack, init)
        logger.info { "[UserMedia:$userId] Added video SEND_ONLY transceiver" }
        return source
    }

    fun addVideoRecvTransceiver() {
        val dummySource = CustomVideoSource()
        val dummyTrack = factory.createVideoTrack("video-recv-$userId", dummySource)
        val init = RTCRtpTransceiverInit().apply {
            direction = RTCRtpTransceiverDirection.RECV_ONLY
        }
        peerConnection!!.addTransceiver(dummyTrack, init)
        logger.info { "[UserMedia:$userId] Added video RECV_ONLY transceiver" }
    }

    fun removeVideoTransceivers() {
        val pc = peerConnection ?: return
        for (transceiver in pc.transceivers) {
            if (transceiver.sender?.track is VideoTrack || transceiver.receiver?.track is VideoTrack) {
                transceiver.direction = RTCRtpTransceiverDirection.INACTIVE
            }
        }
        try { videoSendTrack?.dispose() } catch (_: Exception) {}
        try { videoSendSource?.dispose() } catch (_: Exception) {}
        videoSendTrack = null
        videoSendSource = null
        videoReceiveSink = null
        logger.info { "[UserMedia:$userId] Video transceivers set to INACTIVE" }
    }

    fun renegotiate() {
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
        try { peerConnection?.close() } catch (_: Exception) {}
        try { audioSendTrack?.dispose() } catch (_: Exception) {}
        try { audioSendSource.dispose() } catch (_: Exception) {}
        try { videoSendTrack?.dispose() } catch (_: Exception) {}
        try { videoSendSource?.dispose() } catch (_: Exception) {}
        peerConnection = null
        audioSendTrack = null
        receiveTrackSink = null
        videoSendTrack = null
        videoSendSource = null
        videoReceiveSink = null
        onVideoTrackReceived = null
        logger.info { "[UserMedia:$userId] Disposed" }
    }
}
