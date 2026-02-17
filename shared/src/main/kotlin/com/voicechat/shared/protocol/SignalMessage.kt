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
}
