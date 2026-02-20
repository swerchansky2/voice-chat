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

    // --- WebRTC signaling messages ---
    @Serializable
    @SerialName("offer")
    data class Offer(val to: String, val sdp: String) : SignalMessage()

    @Serializable
    @SerialName("offer_received")
    data class OfferReceived(val from: String, val sdp: String) : SignalMessage()

    @Serializable
    @SerialName("answer")
    data class Answer(val to: String, val sdp: String) : SignalMessage()

    @Serializable
    @SerialName("answer_received")
    data class AnswerReceived(val from: String, val sdp: String) : SignalMessage()

    @Serializable
    @SerialName("ice")
    data class IceCandidate(val to: String, val candidate: String, val sdpMid: String, val sdpMLineIndex: Int) : SignalMessage()

    @Serializable
    @SerialName("ice_received")
    data class IceCandidateReceived(val from: String, val candidate: String, val sdpMid: String, val sdpMLineIndex: Int) : SignalMessage()
}
