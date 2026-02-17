package com.voicechat.server.room

import com.voicechat.shared.protocol.SignalMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class Room(val roomId: String) {
    private val users = ConcurrentHashMap<String, UserSession>()

    fun addUser(session: UserSession): Boolean {
        if (users.values.any { it.nickname == session.nickname }) {
            return false
        }
        users[session.userId] = session
        logger.info { "User ${session.nickname} (${session.userId}) joined room $roomId" }
        return true
    }

    fun removeUser(userId: String): UserSession? {
        val session = users.remove(userId)
        session?.let {
            logger.info { "User ${it.nickname} ($userId) left room $roomId" }
        }
        return session
    }

    fun getUser(userId: String): UserSession? = users[userId]

    fun getAllUsers(): List<UserSession> = users.values.toList()

    fun getUserByNickname(nickname: String): UserSession? =
        users.values.find { it.nickname == nickname }

    suspend fun broadcast(message: SignalMessage, excludeUserId: String? = null) {
        val json = Json.encodeToString(message)
        users.values
            .filter { it.userId != excludeUserId }
            .forEach { user ->
                try {
                    user.websocketSession.send(Frame.Text(json))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to send message to user ${user.nickname}" }
                }
            }
    }

    suspend fun sendToUser(userId: String, message: SignalMessage) {
        val user = users[userId] ?: return
        try {
            val json = Json.encodeToString(message)
            user.websocketSession.send(Frame.Text(json))
        } catch (e: Exception) {
            logger.error(e) { "Failed to send message to user ${user.nickname}" }
        }
    }

    fun updateUdpAddress(userId: String, address: java.net.InetSocketAddress) {
        users[userId]?.udpAddress = address
        logger.info { "Updated UDP address for user $userId: $address" }
    }

    fun size(): Int = users.size
}
