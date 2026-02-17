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

private val logger = KotlinLogging.logger {}

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
    }

    private suspend fun DefaultClientWebSocketSession.sendSignalMessage(message: SignalMessage) {
        val text = json.encodeToString(SignalMessage.serializer(), message)
        send(Frame.Text(text))
    }

    suspend fun connect(host: String, port: Int, nickname: String) {
        try {
            logger.info { "Connecting to ws://$host:$port/ws/room" }
            
            client.webSocket(
                host = host,
                port = port,
                path = "/ws/room"
            ) {
                session = this
                _events.emit(Event.Connected)
                
                // Send join message
                val joinMessage = SignalMessage.Join(nickname)
                logger.info { "Sending join message: $joinMessage" }
                sendSignalMessage(joinMessage)
                
                // Listen for messages
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                logger.debug { "Received: $text" }
                                handleMessage(text)
                            }
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error in message loop" }
                    _events.emit(Event.Error(e.message ?: "Connection error"))
                } finally {
                    _events.emit(Event.Disconnected)
                    session = null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to connect" }
            _events.emit(Event.Error(e.message ?: "Connection failed"))
            _events.emit(Event.Disconnected)
        }
    }

    private suspend fun handleMessage(text: String) {
        try {
            val message = json.decodeFromString<SignalMessage>(text)
            logger.info { "Parsed message: $message" }
            
            when (message) {
                is SignalMessage.Joined -> {
                    _events.emit(Event.Joined(message.userId))
                }
                is SignalMessage.UserList -> {
                    _events.emit(Event.UserList(message.users))
                }
                is SignalMessage.UserJoined -> {
                    _events.emit(Event.UserJoined(message.nickname))
                }
                is SignalMessage.UserLeft -> {
                    _events.emit(Event.UserLeft(message.nickname))
                }
                is SignalMessage.Error -> {
                    _events.emit(Event.Error(message.message))
                }
                else -> {
                    logger.warn { "Unhandled message type: $message" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse message: $text" }
        }
    }

    suspend fun registerUdp(port: Int) {
        val message = SignalMessage.RegisterUdp(port)
        logger.info { "Registering UDP port: $port" }
        session?.sendSignalMessage(message)
    }

    suspend fun disconnect() {
        try {
            session?.sendSignalMessage(SignalMessage.Leave)
            session?.close()
        } catch (e: Exception) {
            logger.error(e) { "Error during disconnect" }
        } finally {
            session = null
        }
    }
}
