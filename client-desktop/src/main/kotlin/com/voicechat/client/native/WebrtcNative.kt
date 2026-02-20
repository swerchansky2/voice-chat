package com.voicechat.client.`native`

/**
 * JNI wrapper for native webrtc bindings.
 * The corresponding native functions are defined in webrtc_native_jni.cpp
 */
class WebrtcNative {
    init {
        try {
            System.loadLibrary("webrtc_native")
        } catch (_: Throwable) {
        }
    }

    external fun initializeScreenshare(): Int
    external fun shutdownScreenshare()

    external fun createPeerConnection(): Int
    external fun addLocalAudioTrack(peerId: Int, trackId: String): Int
    external fun startAudioGenerator(peerId: Int): Int

    external fun createOffer(peerId: Int): String
    external fun createAnswer(peerId: Int): String
    external fun applyRemoteDescription(peerId: Int, sdp: String, type: String): Int
    external fun addIceCandidate(peerId: Int, candidate: String, sdpMid: String, sdpMLineIndex: Int): Int
    external fun closePeerConnection(peerId: Int): Int

    external fun sendAudioFrame(peerId: Int, pcm: ByteArray, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int): Int

    companion object {
        private var remoteAudioCallback: ((peerId: Int, pcm: ByteArray) -> Unit)? = null
        private var iceCandidateCallback: ((peerId: Int, candidate: String, sdpMid: String, sdpMLineIndex: Int) -> Unit)? = null

        @JvmStatic
        fun onRemoteAudioFrame(peerId: Int, pcm: ByteArray) {
            remoteAudioCallback?.invoke(peerId, pcm)
        }

        @JvmStatic
        fun onIceCandidate(peerId: Int, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
            iceCandidateCallback?.invoke(peerId, candidate, sdpMid, sdpMLineIndex)
        }

        fun setRemoteAudioCallback(cb: (Int, ByteArray) -> Unit) {
            remoteAudioCallback = cb
        }

        fun setIceCandidateCallback(cb: (peerId: Int, candidate: String, sdpMid: String, sdpMLineIndex: Int) -> Unit) {
            iceCandidateCallback = cb
        }
    }
}
