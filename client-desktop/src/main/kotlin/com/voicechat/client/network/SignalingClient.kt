package com.voicechat.client.network

import com.voicechat.shared.protocol.SignalMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger("WS")

class SignalingClient(private val maxFrameSize: Long = 1024L * 1024) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
            maxFrameSize = this@SignalingClient.maxFrameSize
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private var session: DefaultClientWebSocketSession? = null

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    sealed class Event {
        data object Connected : Event()
        data object Disconnected : Event()
        data class Joined(val userId: String) : Event()
        data class UserList(val users: List<String>) : Event()
        data class UserJoined(val userId: String, val nickname: String) : Event()
        data class UserLeft(val userId: String, val nickname: String) : Event()
        data class Error(val message: String) : Event()
        data class ScreenShareStarted(val userId: String, val nickname: String, val width: Int, val height: Int, val fps: Int) : Event()
        data class ScreenShareStopped(val userId: String, val nickname: String) : Event()
        data class ScreenShareViewers(val viewerUserIds: List<String>) : Event()
        data class SdpOfferReceived(val fromUserId: String, val sdp: String, val type: String) : Event()
        data class SdpAnswerReceived(val fromUserId: String, val sdp: String, val type: String) : Event()
        data class IceCandidateReceived(val fromUserId: String, val sdp: String, val sdpMid: String, val sdpMLineIndex: Int) : Event()
    }

    private suspend fun DefaultClientWebSocketSession.sendSignalMessage(message: SignalMessage) {
        val text = json.encodeToString(SignalMessage.serializer(), message)
        send(Frame.Text(text))
    }

    suspend fun connect(host: String, port: Int, nickname: String) {
        try {
            logger.info { "[WS] Connecting to ws://$host:$port/ws/room as \"$nickname\"" }

            client.webSocket(
                host = host,
                port = port,
                path = "/ws/room"
            ) {
                session = this
                logger.info { "[WS] Connected to server" }
                _events.emit(Event.Connected)

                val joinMessage = SignalMessage.Join(nickname)
                sendSignalMessage(joinMessage)

                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                logger.debug { "[WS] Received: $text" }
                                handleMessage(text)
                            }
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[WS] Error in message loop" }
                    _events.emit(Event.Error(e.message ?: "Connection error"))
                } finally {
                    logger.info { "[WS] Disconnected from server" }
                    _events.emit(Event.Disconnected)
                    session = null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "[WS] Failed to connect to $host:$port" }
            _events.emit(Event.Error(e.message ?: "Connection failed"))
            _events.emit(Event.Disconnected)
        }
    }

    private suspend fun handleMessage(text: String) {
        try {
            val message = json.decodeFromString<SignalMessage>(text)

            when (message) {
                is SignalMessage.Joined -> {
                    logger.info { "[WS] Received Joined — userId=${message.userId}" }
                    _events.emit(Event.Joined(message.userId))
                }
                is SignalMessage.UserList -> {
                    logger.info { "[WS] Received UserList — ${message.users.size} users" }
                    _events.emit(Event.UserList(message.users))
                }
                is SignalMessage.UserJoined -> {
                    logger.info { "[WS] Received UserJoined — \"${message.nickname}\" (${message.userId})" }
                    _events.emit(Event.UserJoined(message.userId, message.nickname))
                }
                is SignalMessage.UserLeft -> {
                    logger.info { "[WS] Received UserLeft — \"${message.nickname}\" (${message.userId})" }
                    _events.emit(Event.UserLeft(message.userId, message.nickname))
                }
                is SignalMessage.Error -> {
                    logger.warn { "[WS] Received Error — ${message.message}" }
                    _events.emit(Event.Error(message.message))
                }
                is SignalMessage.ScreenShareStarted -> {
                    logger.info { "[WS] Screen share started by ${message.nickname} (${message.width}x${message.height} @ ${message.fps}fps)" }
                    _events.emit(Event.ScreenShareStarted(message.userId, message.nickname, message.width, message.height, message.fps))
                }
                is SignalMessage.ScreenShareStopped -> {
                    logger.info { "[WS] Screen share stopped by ${message.nickname}" }
                    _events.emit(Event.ScreenShareStopped(message.userId, message.nickname))
                }
                is SignalMessage.ScreenShareViewers -> {
                    logger.info { "[WS] Received viewer list: ${message.viewerUserIds.size} viewers" }
                    _events.emit(Event.ScreenShareViewers(message.viewerUserIds))
                }
                is SignalMessage.SdpOffer -> {
                    logger.info { "[WS] Received SDP offer from ${message.targetUserId}" }
                    _events.emit(Event.SdpOfferReceived(message.targetUserId, message.sdp, message.type))
                }
                is SignalMessage.SdpAnswer -> {
                    logger.info { "[WS] Received SDP answer from ${message.targetUserId}" }
                    _events.emit(Event.SdpAnswerReceived(message.targetUserId, message.sdp, message.type))
                }
                is SignalMessage.IceCandidateMsg -> {
                    logger.debug { "[WS] Received ICE candidate from ${message.targetUserId}" }
                    _events.emit(Event.IceCandidateReceived(message.targetUserId, message.sdp, message.sdpMid, message.sdpMLineIndex))
                }
                else -> {
                    logger.warn { "[WS] Unhandled message type: ${message::class.simpleName}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "[WS] Failed to parse message: $text" }
        }
    }

    suspend fun registerUdp(port: Int) {
        val message = SignalMessage.RegisterUdp(port)
        logger.info { "[WS] Registered UDP port $port with server" }
        session?.sendSignalMessage(message)
    }

    suspend fun startScreenShare(width: Int, height: Int, fps: Int) {
        logger.info { "[WS] Sending start screen share: ${width}x${height} @ ${fps}fps" }
        session?.sendSignalMessage(SignalMessage.StartScreenShare(width, height, fps))
    }

    suspend fun stopScreenShare() {
        logger.info { "[WS] Sending stop screen share" }
        session?.sendSignalMessage(SignalMessage.StopScreenShare)
    }

    suspend fun sendSdpOffer(targetUserId: String, sdp: String, type: String) {
        logger.debug { "[WS] Sending SDP offer to $targetUserId" }
        session?.sendSignalMessage(SignalMessage.SdpOffer(targetUserId, sdp, type))
    }

    suspend fun sendSdpAnswer(targetUserId: String, sdp: String, type: String) {
        logger.debug { "[WS] Sending SDP answer to $targetUserId" }
        session?.sendSignalMessage(SignalMessage.SdpAnswer(targetUserId, sdp, type))
    }

    suspend fun sendIceCandidate(targetUserId: String, sdp: String, sdpMid: String, sdpMLineIndex: Int) {
        logger.debug { "[WS] Sending ICE candidate to $targetUserId" }
        session?.sendSignalMessage(SignalMessage.IceCandidateMsg(targetUserId, sdp, sdpMid, sdpMLineIndex))
    }

    suspend fun disconnect() {
        try {
            logger.info { "[WS] Disconnecting" }
            session?.sendSignalMessage(SignalMessage.Leave)
            session?.close()
        } catch (e: Exception) {
            logger.error(e) { "[WS] Error during disconnect" }
        } finally {
            session = null
        }
    }
}
