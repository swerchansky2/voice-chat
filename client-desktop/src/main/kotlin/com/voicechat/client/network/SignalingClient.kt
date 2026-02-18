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

class SignalingClient(private val maxFrameSize: Long = 64L * 1024) {
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
    }

    private suspend fun DefaultClientWebSocketSession.sendSignalMessage(message: SignalMessage) {
        val text = json.encodeToString(SignalMessage.serializer(), message)
        send(Frame.Text(text))
    }

    suspend fun connect(host: String, port: Int, nickname: String) {
        try {
            logger.info { "[WS] Connecting to ws://$host:$port/ws/room as \"$nickname\"" }

            client.webSocket(host = host, port = port, path = "/ws/room") {
                session = this
                logger.info { "[WS] Connected to server" }
                _events.emit(Event.Connected)

                sendSignalMessage(SignalMessage.Join(nickname))

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
                    logger.info { "[WS] Joined — userId=${message.userId}" }
                    _events.emit(Event.Joined(message.userId))
                }
                is SignalMessage.UserList -> {
                    logger.info { "[WS] UserList — ${message.users.size} users" }
                    _events.emit(Event.UserList(message.users))
                }
                is SignalMessage.UserJoined -> {
                    logger.info { "[WS] UserJoined — \"${message.nickname}\" (${message.userId})" }
                    _events.emit(Event.UserJoined(message.userId, message.nickname))
                }
                is SignalMessage.UserLeft -> {
                    logger.info { "[WS] UserLeft — \"${message.nickname}\" (${message.userId})" }
                    _events.emit(Event.UserLeft(message.userId, message.nickname))
                }
                is SignalMessage.Error -> {
                    logger.warn { "[WS] Error — ${message.message}" }
                    _events.emit(Event.Error(message.message))
                }
                is SignalMessage.ScreenShareStarted -> {
                    logger.info { "[WS] ScreenShareStarted by ${message.nickname} (${message.width}x${message.height} @ ${message.fps}fps)" }
                    _events.emit(Event.ScreenShareStarted(message.userId, message.nickname, message.width, message.height, message.fps))
                }
                is SignalMessage.ScreenShareStopped -> {
                    logger.info { "[WS] ScreenShareStopped by ${message.nickname}" }
                    _events.emit(Event.ScreenShareStopped(message.userId, message.nickname))
                }
                is SignalMessage.ScreenShareViewers -> {
                    // Kept for protocol compatibility; not used with UDP relay
                }
                else -> {
                    logger.warn { "[WS] Unhandled message: ${message::class.simpleName}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "[WS] Failed to parse: $text" }
        }
    }

    suspend fun registerUdp(port: Int) {
        logger.info { "[WS] Registering audio UDP port $port" }
        session?.sendSignalMessage(SignalMessage.RegisterUdp(port))
    }

    suspend fun registerVideoUdp(port: Int) {
        logger.info { "[WS] Registering video UDP port $port" }
        session?.sendSignalMessage(SignalMessage.RegisterVideoUdp(port))
    }

    suspend fun startScreenShare(width: Int, height: Int, fps: Int) {
        logger.info { "[WS] StartScreenShare ${width}x${height} @ ${fps}fps" }
        session?.sendSignalMessage(SignalMessage.StartScreenShare(width, height, fps))
    }

    suspend fun stopScreenShare() {
        logger.info { "[WS] StopScreenShare" }
        session?.sendSignalMessage(SignalMessage.StopScreenShare)
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
