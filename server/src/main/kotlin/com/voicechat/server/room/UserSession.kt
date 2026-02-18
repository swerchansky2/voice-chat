package com.voicechat.server.room

import io.ktor.websocket.*
import java.net.InetSocketAddress

data class UserSession(
    val userId: String,
    val nickname: String,
    val websocketSession: DefaultWebSocketSession,
    var udpAddress: InetSocketAddress? = null,
    var videoUdpAddress: InetSocketAddress? = null,
    var isScreenSharing: Boolean = false,
    var screenShareWidth: Int = 0,
    var screenShareHeight: Int = 0,
    var screenShareFps: Int = 0
)
