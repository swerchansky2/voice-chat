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

class SignalingClient {
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
        data class UserJoined(val nickname: String) : Event()
        data class UserLeft(val nickname: String) : Event()
        data class Error(val message: String) : Event()
        data class WebRtcOffer(val sdp: String) : Event()
        data class WebRtcIceCandidate(
            val candidate: String,
            val sdpMid: String?,
            val sdpMLineIndex: Int
        ) : Event()
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
                    logger.info { "[WS] Received UserJoined — \"${message.nickname}\"" }
                    _events.emit(Event.UserJoined(message.nickname))
                }
                is SignalMessage.UserLeft -> {
                    logger.info { "[WS] Received UserLeft — \"${message.nickname}\"" }
                    _events.emit(Event.UserLeft(message.nickname))
                }
                is SignalMessage.Error -> {
                    logger.warn { "[WS] Received Error — ${message.message}" }
                    _events.emit(Event.Error(message.message))
                }
                is SignalMessage.WebRtcOffer -> {
                    logger.info { "[WS] Received WebRTC Offer" }
                    _events.emit(Event.WebRtcOffer(message.sdp))
                }
                is SignalMessage.WebRtcIceCandidate -> {
                    logger.debug { "[WS] Received WebRTC ICE candidate" }
                    _events.emit(Event.WebRtcIceCandidate(
                        message.candidate, message.sdpMid, message.sdpMLineIndex
                    ))
                }
                else -> {
                    logger.warn { "[WS] Unhandled message type: ${message::class.simpleName}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "[WS] Failed to parse message: $text" }
        }
    }

    suspend fun sendAnswer(sdp: String) {
        session?.sendSignalMessage(SignalMessage.WebRtcAnswer(sdp))
    }

    suspend fun sendIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        session?.sendSignalMessage(
            SignalMessage.WebRtcIceCandidate(candidate, sdpMid, sdpMLineIndex)
        )
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
