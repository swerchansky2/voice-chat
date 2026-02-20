package com.voicechat.server.websocket

import com.voicechat.server.room.RoomManager
import com.voicechat.server.room.UserSession
import com.voicechat.server.sfu.SfuManager
import com.voicechat.shared.protocol.SignalMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.*

private val logger = KotlinLogging.logger("WS")

class SignalingHandler(
    private val roomManager: RoomManager,
    private val sfuManager: SfuManager
) {

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

                val wsSession = this
                sfuManager.createSession(
                    userId = userId,
                    onIceCandidate = { candidate ->
                        runBlocking {
                            wsSession.sendMessage(
                                SignalMessage.WebRtcIceCandidate(
                                    candidate = candidate.sdp,
                                    sdpMid = candidate.sdpMid,
                                    sdpMLineIndex = candidate.sdpMLineIndex
                                )
                            )
                        }
                    },
                    onOffer = { sdp ->
                        runBlocking {
                            wsSession.sendMessage(SignalMessage.WebRtcOffer(sdp))
                        }
                    }
                )
            }

            is SignalMessage.Leave -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }

                handleDisconnect(currentUserId, currentNickname, roomId)
                updateSession(null, null, roomId)
            }

            is SignalMessage.WebRtcAnswer -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }
                sfuManager.getSession(currentUserId)?.handleAnswer(message.sdp)
            }

            is SignalMessage.WebRtcIceCandidate -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }
                sfuManager.getSession(currentUserId)?.handleIceCandidate(
                    message.candidate, message.sdpMid, message.sdpMLineIndex
                )
            }

            is SignalMessage.StartScreenShare -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }
                if (sfuManager.currentSharerId != null) {
                    sendMessage(SignalMessage.Error("Screen share already active"))
                    return
                }
                sfuManager.startScreenShare(currentUserId)
                val room = roomManager.getRoom(roomId) ?: return
                room.broadcast(SignalMessage.ScreenShareStarted(currentNickname ?: ""))
                logger.info { "[WS] User \"$currentNickname\" started screen share" }
            }

            is SignalMessage.StopScreenShare -> {
                if (currentUserId == null) {
                    sendMessage(SignalMessage.Error("Not joined"))
                    return
                }
                sfuManager.stopScreenShare(currentUserId)
                val room = roomManager.getRoom(roomId) ?: return
                room.broadcast(SignalMessage.ScreenShareStopped)
                logger.info { "[WS] User \"$currentNickname\" stopped screen share" }
            }

            else -> {
                logger.warn { "[WS] Unexpected message type: ${message::class.simpleName} from ${currentNickname ?: "unknown"}" }
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleDisconnect(userId: String, nickname: String?, roomId: String) {
        if (sfuManager.currentSharerId == userId) {
            sfuManager.stopScreenShare(userId)
            val room = roomManager.getRoom(roomId)
            room?.broadcast(SignalMessage.ScreenShareStopped)
        }

        sfuManager.removeSession(userId)

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
