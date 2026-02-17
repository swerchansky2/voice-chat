package com.voicechat.server.websocket

import com.voicechat.server.room.RoomManager
import com.voicechat.server.room.UserSession
import com.voicechat.shared.protocol.SignalMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.net.InetSocketAddress
import java.util.*

private val logger = KotlinLogging.logger {}

class SignalingHandler(private val roomManager: RoomManager) {

    fun Route.signalingWebSocket() {
        webSocket("/ws/room") {
            var currentUserId: String? = null
            var currentRoomId = RoomManager.DEFAULT_ROOM_ID

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue

                    val text = frame.readText()
                    logger.debug { "Received message: $text" }

                    try {
                        val message = Json.decodeFromString<SignalMessage>(text)
                        handleMessage(message, currentUserId, currentRoomId) { userId, roomId ->
                            currentUserId = userId
                            currentRoomId = roomId
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to parse message: $text" }
                        sendMessage(SignalMessage.Error("Invalid message format"))
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "WebSocket error" }
            } finally {
                currentUserId?.let { userId ->
                    handleDisconnect(userId, currentRoomId)
                }
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleMessage(
        message: SignalMessage,
        currentUserId: String?,
        roomId: String,
        updateSession: (String?, String) -> Unit
    ) {
        when (message) {
            is SignalMessage.Join -> {
                if (currentUserId != null) {
                    sendMessage(SignalMessage.Error("Already joined"))
                    return
                }

                val room = roomManager.getOrCreateRoom(roomId)
                val userId = UUID.randomUUID().toString()
                val userSession = UserSession(
                    userId = userId,
                    nickname = message.nickname,
                    websocketSession = this
                )

                if (!room.addUser(userSession)) {
                    sendMessage(SignalMessage.Error("Nickname already taken"))
                    return
                }

                updateSession(userId, roomId)

                sendMessage(SignalMessage.Joined(userId))
                
                val userList = room.getAllUsers().map { it.nickname }
                sendMessage(SignalMessage.UserList(userList))
                
                room.broadcast(SignalMessage.UserJoined(message.nickname), excludeUserId = userId)
                
                logger.info { "User ${message.nickname} joined as $userId" }
            }

            is SignalMessage.Leave -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }

                handleDisconnect(currentUserId, roomId)
                updateSession(null, roomId)
            }

            is SignalMessage.RegisterUdp -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }

                val room = roomManager.getRoom(roomId)
                if (room == null) {
                    sendMessage(SignalMessage.Error("Room not found"))
                    return
                }

                val clientAddress = call.request.local.remoteAddress
                val udpAddress = InetSocketAddress(clientAddress, message.port)
                room.updateUdpAddress(currentUserId, udpAddress)
                
                logger.info { "User $currentUserId registered UDP address: $udpAddress" }
            }

            else -> {
                logger.warn { "Received unexpected message type: ${message::class.simpleName}" }
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleDisconnect(userId: String, roomId: String) {
        val room = roomManager.getRoom(roomId) ?: return
        val userSession = room.removeUser(userId)
        
        userSession?.let {
            room.broadcast(SignalMessage.UserLeft(it.nickname))
            roomManager.removeRoomIfEmpty(roomId)
            logger.info { "User ${it.nickname} ($userId) disconnected" }
        }
    }

    private suspend fun DefaultWebSocketServerSession.sendMessage(message: SignalMessage) {
        val json = Json.encodeToString(message)
        send(Frame.Text(json))
    }
}
