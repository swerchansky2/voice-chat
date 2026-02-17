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
    @SerialName("register_udp")
    data class RegisterUdp(val port: Int) : SignalMessage()
    
    @Serializable
    @SerialName("user_list")
    data class UserList(val users: List<String>) : SignalMessage()
    
    @Serializable
    @SerialName("user_joined")
    data class UserJoined(val userId: String, val nickname: String) : SignalMessage()
    
    @Serializable
    @SerialName("user_left")
    data class UserLeft(val userId: String, val nickname: String) : SignalMessage()
    
    @Serializable
    @SerialName("error")
    data class Error(val message: String) : SignalMessage()
    
    @Serializable
    @SerialName("joined")
    data class Joined(val userId: String) : SignalMessage()

    @Serializable
    @SerialName("start_screen_share")
    data class StartScreenShare(val width: Int, val height: Int, val fps: Int) : SignalMessage()

    @Serializable
    @SerialName("stop_screen_share")
    data object StopScreenShare : SignalMessage()

    @Serializable
    @SerialName("screen_share_started")
    data class ScreenShareStarted(
        val userId: String,
        val nickname: String,
        val width: Int,
        val height: Int,
        val fps: Int
    ) : SignalMessage()

    @Serializable
    @SerialName("screen_share_stopped")
    data class ScreenShareStopped(val userId: String, val nickname: String) : SignalMessage()

    @Serializable
    @SerialName("screen_share_viewers")
    data class ScreenShareViewers(val viewerUserIds: List<String>) : SignalMessage()

    @Serializable
    @SerialName("sdp_offer")
    data class SdpOffer(
        val targetUserId: String,
        val sdp: String,
        val sdpType: String
    ) : SignalMessage()

    @Serializable
    @SerialName("sdp_answer")
    data class SdpAnswer(
        val targetUserId: String,
        val sdp: String,
        val sdpType: String
    ) : SignalMessage()

    @Serializable
    @SerialName("ice_candidate")
    data class IceCandidateMsg(
        val targetUserId: String,
        val sdp: String,
        val sdpMid: String,
        val sdpMLineIndex: Int
    ) : SignalMessage()
}
