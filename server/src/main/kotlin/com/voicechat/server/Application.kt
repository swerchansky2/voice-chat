package com.voicechat.server

import com.voicechat.server.audio.UdpAudioRelay
import com.voicechat.server.di.serverModule
import com.voicechat.server.websocket.SignalingHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("Server")

fun main() {
    val httpPort = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 8080
    val udpPort = System.getenv("UDP_PORT")?.toIntOrNull() ?: 9001

    // Validate port ranges
    require(httpPort in 1..65535) { "HTTP_PORT must be in range 1-65535, got $httpPort" }
    require(udpPort in 1..65535) { "UDP_PORT must be in range 1-65535, got $udpPort" }

    logger.info { "[Server] ========================================" }
    logger.info { "[Server] Voice Chat Server starting" }
    logger.info { "[Server]   WebSocket: 0.0.0.0:$httpPort" }
    logger.info { "[Server]   UDP Relay: 0.0.0.0:$udpPort" }
    logger.info { "[Server] ========================================" }

    embeddedServer(Netty, port = httpPort, host = "0.0.0.0") {
        configureServer()
    }.start(wait = true)
}

fun Application.configureServer() {
    install(Koin) {
        modules(serverModule)
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = 1024 * 1024 // 1MB — JSON signaling only, video goes via WebRTC P2P
        masking = false
    }

    install(ContentNegotiation) {
        json()
    }

    val signalingHandler: SignalingHandler by inject()
    val udpAudioRelay: UdpAudioRelay by inject()

    val udpScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    udpAudioRelay.start(udpScope)

    routing {
        with(signalingHandler) {
            signalingWebSocket()
        }
    }

    monitor.subscribe(ApplicationStopped) {
        logger.info { "[Server] Shutting down" }
        udpAudioRelay.stop()
    }

    logger.info { "[Server] Voice Chat Server ready" }
}
