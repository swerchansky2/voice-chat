package com.voicechat.server.room

import com.voicechat.shared.protocol.SignalMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger("Room")

class Room(val roomId: String) {
    private val users = ConcurrentHashMap<String, UserSession>()
    private val nicknames = ConcurrentHashMap<String, String>()

    fun addUser(session: UserSession): Boolean {
        val previousUserId = nicknames.putIfAbsent(session.nickname, session.userId)
        if (previousUserId != null) {
            return false
        }

        users[session.userId] = session
        logger.info { "[Room] User \"${session.nickname}\" (${session.userId}) joined room \"$roomId\" [${users.size} user${if (users.size != 1) "s" else ""}]" }
        return true
    }

    fun removeUser(userId: String): UserSession? {
        val session = users.remove(userId)
        session?.let {
            nicknames.remove(it.nickname)
            logger.info { "[Room] User \"${it.nickname}\" ($userId) left room \"$roomId\" [${users.size} user${if (users.size != 1) "s" else ""}]" }
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
                    logger.error(e) { "[Room] Failed to broadcast ${message::class.simpleName} to \"${user.nickname}\"" }
                }
            }
    }

    suspend fun sendToUser(userId: String, message: SignalMessage) {
        val user = users[userId] ?: return
        try {
            val json = Json.encodeToString(message)
            user.websocketSession.send(Frame.Text(json))
        } catch (e: Exception) {
            logger.error(e) { "[Room] Failed to send ${message::class.simpleName} to \"${user.nickname}\"" }
        }
    }

    fun size(): Int = users.size
}
