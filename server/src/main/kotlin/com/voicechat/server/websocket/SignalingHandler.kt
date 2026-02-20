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

private val logger = KotlinLogging.logger("WS")

class SignalingHandler(private val roomManager: RoomManager) {

    fun Route.signalingWebSocket() {
        webSocket("/ws/room") {
            var currentUserId: String? = null
            var currentNickname: String? = null
            var currentRoomId = RoomManager.DEFAULT_ROOM_ID

            val remoteAddress = call.request.local.remoteAddress
            logger.info { "[WS] New connection from $remoteAddress" }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue

                    val text = frame.readText()
                    logger.debug { "[WS] Received: $text" }

                    try {
                        val message = Json.decodeFromString<SignalMessage>(text)
                        handleMessage(message, currentUserId, currentNickname, currentRoomId) { userId, nickname, roomId ->
                            currentUserId = userId
                            currentNickname = nickname
                            currentRoomId = roomId
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "[WS] Failed to parse message from ${currentNickname ?: remoteAddress}" }
                        sendMessage(SignalMessage.Error("Invalid message format"))
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "[WS] Connection error for ${currentNickname ?: remoteAddress}" }
            } finally {
                currentUserId?.let { userId ->
                    handleDisconnect(userId, currentNickname, currentRoomId)
                }
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleMessage(
        message: SignalMessage,
        currentUserId: String?,
        currentNickname: String?,
        roomId: String,
        updateSession: (String?, String?, String) -> Unit
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

                updateSession(userId, message.nickname, roomId)

                sendMessage(SignalMessage.Joined(userId))

                val userList = room.getAllUsers().map { it.nickname }
                sendMessage(SignalMessage.UserList(userList))

                room.broadcast(SignalMessage.UserJoined(message.nickname), excludeUserId = userId)
            }

            is SignalMessage.Leave -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }

                handleDisconnect(currentUserId, currentNickname, roomId)
                updateSession(null, null, roomId)
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

                logger.info { "[WS] User \"${currentNickname}\" registered UDP address $udpAddress" }
            }

            is SignalMessage.Offer -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }
                val room = roomManager.getRoom(roomId)
                if (room == null) {
                    sendMessage(SignalMessage.Error("Room not found"))
                    return
                }
                // forward to target user
                room.sendToUser(message.to, SignalMessage.OfferReceived(from = currentUserId, sdp = message.sdp))
            }

            is SignalMessage.Answer -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }
                val room = roomManager.getRoom(roomId)
                if (room == null) {
                    sendMessage(SignalMessage.Error("Room not found"))
                    return
                }
                room.sendToUser(message.to, SignalMessage.AnswerReceived(from = currentUserId, sdp = message.sdp))
            }

            is SignalMessage.IceCandidate -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }
                val room = roomManager.getRoom(roomId)
                if (room == null) {
                    sendMessage(SignalMessage.Error("Room not found"))
                    return
                }
                room.sendToUser(message.to, SignalMessage.IceCandidateReceived(from = currentUserId, candidate = message.candidate, sdpMid = message.sdpMid, sdpMLineIndex = message.sdpMLineIndex))
            }

            else -> {
                logger.warn { "[WS] Unexpected message type: ${message::class.simpleName} from ${currentNickname ?: "unknown"}" }
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleDisconnect(userId: String, nickname: String?, roomId: String) {
        val room = roomManager.getRoom(roomId) ?: return
        val userSession = room.removeUser(userId)

        userSession?.let {
            room.broadcast(SignalMessage.UserLeft(it.nickname))
            roomManager.removeRoomIfEmpty(roomId)
            logger.info { "[WS] User \"${it.nickname}\" ($userId) disconnected" }
        }
    }

    private suspend fun DefaultWebSocketServerSession.sendMessage(message: SignalMessage) {
        val json = Json.encodeToString(message)
        send(Frame.Text(json))
    }
}
