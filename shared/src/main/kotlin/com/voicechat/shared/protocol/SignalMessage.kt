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
}
