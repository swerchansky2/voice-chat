package com.voicechat.server.room

import io.ktor.websocket.*
import java.net.InetSocketAddress

data class UserSession(
    val userId: String,
    val nickname: String,
    val websocketSession: DefaultWebSocketSession,
    var udpAddress: InetSocketAddress? = null
)
