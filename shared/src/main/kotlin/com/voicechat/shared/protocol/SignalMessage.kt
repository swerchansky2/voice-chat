package com.voicechat.shared.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed class SignalMessage {
    
    @Serializable
    @SerialName("join")
    data class Join(val nickname: String) : SignalMessage()
    
    @Serializable
    @SerialName("leave")
    data object Leave : SignalMessage()
    
    @Serializable
    @SerialName("user_list")
    data class UserList(val users: List<String>) : SignalMessage()
    
    @Serializable
    @SerialName("user_joined")
    data class UserJoined(val nickname: String) : SignalMessage()
    
    @Serializable
    @SerialName("user_left")
    data class UserLeft(val nickname: String) : SignalMessage()
    
    @Serializable
    @SerialName("error")
    data class Error(val message: String) : SignalMessage()
    
    @Serializable
    @SerialName("joined")
    data class Joined(val userId: String) : SignalMessage()

    @Serializable
    @SerialName("webrtc_offer")
    data class WebRtcOffer(val sdp: String) : SignalMessage()

    @Serializable
    @SerialName("webrtc_answer")
    data class WebRtcAnswer(val sdp: String) : SignalMessage()

    @Serializable
    @SerialName("webrtc_ice_candidate")
    data class WebRtcIceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int
    ) : SignalMessage()

    @Serializable
    @SerialName("start_screen_share")
    data object StartScreenShare : SignalMessage()

    @Serializable
    @SerialName("stop_screen_share")
    data object StopScreenShare : SignalMessage()

    @Serializable
    @SerialName("screen_share_started")
    data class ScreenShareStarted(val nickname: String) : SignalMessage()

    @Serializable
    @SerialName("screen_share_stopped")
    data object ScreenShareStopped : SignalMessage()
}
