package com.voicechat.server.room

import io.ktor.websocket.*

data class UserSession(
    val userId: String,
    val nickname: String,
    val websocketSession: DefaultWebSocketSession
)
