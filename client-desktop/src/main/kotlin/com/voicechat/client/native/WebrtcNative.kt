package com.voicechat.client.`native`

/**
 * Simple JNI wrapper for native webrtc bindings.
 * The corresponding native functions are defined in webrtc_native_jni.cpp
 */
class WebrtcNative {
    init {
        try {
            System.loadLibrary("webrtc_native")
        } catch (t: Throwable) {
            // library load errors will surface when methods are called; log if available
            // keep silent here to avoid crashing during unit tests
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

    // send captured PCM raw samples to native audio source for a given peer
    external fun sendAudioFrame(peerId: Int, pcm: ByteArray, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int): Int

    companion object {
        // callback invoked from native when a remote audio frame is available
        private var remoteAudioCallback: ((peerId: Int, pcm: ByteArray) -> Unit)? = null

        @JvmStatic
        fun onRemoteAudioFrame(peerId: Int, pcm: ByteArray) {
            remoteAudioCallback?.invoke(peerId, pcm)
        }

        fun setRemoteAudioCallback(cb: (Int, ByteArray) -> Unit) {
            remoteAudioCallback = cb
        }
    }
}
