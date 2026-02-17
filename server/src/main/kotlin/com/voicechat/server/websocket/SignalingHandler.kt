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
                    when (frame) {
                        is Frame.Text -> {

                            val text = frame.readText()
                            logger.debug { "[WS] Received: $text" }

                            try {
                                val message = Json.decodeFromString<SignalMessage>(text)
                                handleMessage(
                                    message,
                                    currentUserId,
                                    currentNickname,
                                    currentRoomId
                                ) { userId, nickname, roomId ->
                                    currentUserId = userId
                                    currentNickname = nickname
                                    currentRoomId = roomId
                                }
                            } catch (e: Exception) {
                                logger.error(e) { "[WS] Failed to parse message from ${currentNickname ?: remoteAddress}" }
                                sendMessage(SignalMessage.Error("Invalid message format"))
                            }
                        }

                        else -> {}
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

                room.broadcast(SignalMessage.UserJoined(userId, message.nickname), excludeUserId = userId)


                val screenSharer = room.getScreenSharer()
                if (screenSharer != null) {
                    sendMessage(
                        SignalMessage.ScreenShareStarted(
                            screenSharer.userId,
                            screenSharer.nickname,
                            screenSharer.screenShareWidth,
                            screenSharer.screenShareHeight,
                            screenSharer.screenShareFps
                        )
                    )
                }

                logger.info { "User ${message.nickname} joined as $userId" }
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

            is SignalMessage.StartScreenShare -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }

                val room = roomManager.getRoom(roomId)
                if (room == null) {
                    sendMessage(SignalMessage.Error("Room not found"))
                    return
                }

                val existingSharer = room.getScreenSharer()
                if (existingSharer != null) {
                    sendMessage(SignalMessage.Error("Someone is already sharing their screen"))
                    return
                }

                val user = room.getUser(currentUserId)
                if (user == null) {
                    sendMessage(SignalMessage.Error("User not found"))
                    return
                }

                room.setScreenSharing(currentUserId, true, message.width, message.height, message.fps)
                room.broadcast(
                    SignalMessage.ScreenShareStarted(
                        currentUserId, user.nickname,
                        message.width, message.height, message.fps
                    )
                )

                val viewerIds = room.getAllUsers()
                    .filter { it.userId != currentUserId }
                    .map { it.userId }
                if (viewerIds.isNotEmpty()) {
                    sendMessage(SignalMessage.ScreenShareViewers(viewerIds))
                }

                logger.info { "User ${user.nickname} started screen sharing: ${message.width}x${message.height} @ ${message.fps}fps, viewers: ${viewerIds.size}" }
            }

            is SignalMessage.StopScreenShare -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }

                val room = roomManager.getRoom(roomId)
                if (room == null) {
                    sendMessage(SignalMessage.Error("Room not found"))
                    return
                }

                val user = room.getUser(currentUserId)
                if (user == null) {
                    sendMessage(SignalMessage.Error("User not found"))
                    return
                }

                room.setScreenSharing(currentUserId, false)
                room.broadcast(
                    SignalMessage.ScreenShareStopped(currentUserId, user.nickname)
                )

                logger.info { "User ${user.nickname} stopped screen sharing" }
            }

            is SignalMessage.SdpOffer -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }
                val room = roomManager.getRoom(roomId) ?: return
                val targetUser = room.getUser(message.targetUserId)
                if (targetUser == null) {
                    logger.warn { "[WS] SDP offer target ${message.targetUserId} not found" }
                    return
                }
                val relayMessage = SignalMessage.SdpOffer(
                    targetUserId = currentUserId!!,
                    sdp = message.sdp,
                    type = message.type
                )
                room.sendToUser(message.targetUserId, relayMessage)
                logger.debug { "[WS] Relayed SDP offer from $currentUserId to ${message.targetUserId}" }
            }

            is SignalMessage.SdpAnswer -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }
                val room = roomManager.getRoom(roomId) ?: return
                val targetUser = room.getUser(message.targetUserId)
                if (targetUser == null) {
                    logger.warn { "[WS] SDP answer target ${message.targetUserId} not found" }
                    return
                }
                val relayMessage = SignalMessage.SdpAnswer(
                    targetUserId = currentUserId!!,
                    sdp = message.sdp,
                    type = message.type
                )
                room.sendToUser(message.targetUserId, relayMessage)
                logger.debug { "[WS] Relayed SDP answer from $currentUserId to ${message.targetUserId}" }
            }

            is SignalMessage.IceCandidateMsg -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }
                val room = roomManager.getRoom(roomId) ?: return
                val targetUser = room.getUser(message.targetUserId)
                if (targetUser == null) {
                    logger.warn { "[WS] ICE candidate target ${message.targetUserId} not found" }
                    return
                }
                val relayMessage = SignalMessage.IceCandidateMsg(
                    targetUserId = currentUserId!!,
                    sdp = message.sdp,
                    sdpMid = message.sdpMid,
                    sdpMLineIndex = message.sdpMLineIndex
                )
                room.sendToUser(message.targetUserId, relayMessage)
                logger.debug { "[WS] Relayed ICE candidate from $currentUserId to ${message.targetUserId}" }
            }

            else -> {
                logger.warn { "[WS] Unexpected message type: ${message::class.simpleName} from ${currentNickname ?: "unknown"}" }
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleDisconnect(userId: String, nickname: String?, roomId: String) {
        val room = roomManager.getRoom(roomId) ?: return

        val wasSharing = room.getUser(userId)?.isScreenSharing == true

        val userSession = room.removeUser(userId)

        userSession?.let {

            if (wasSharing) {
                room.broadcast(SignalMessage.ScreenShareStopped(userId, it.nickname))
            }
            room.broadcast(SignalMessage.UserLeft(userId, it.nickname))
            roomManager.removeRoomIfEmpty(roomId)
            logger.info { "[WS] User \"${it.nickname}\" ($userId) disconnected" }
        }
    }

    private suspend fun DefaultWebSocketServerSession.sendMessage(message: SignalMessage) {
        val json = Json.encodeToString(message)
        send(Frame.Text(json))
    }
}
