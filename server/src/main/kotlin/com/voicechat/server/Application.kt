package com.voicechat.server

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
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("Server")

fun main() {
    val httpPort = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 1234
    require(httpPort in 1..65535) { "HTTP_PORT must be in range 1-65535, got $httpPort" }

    logger.info { "[Server] ========================================" }
    logger.info { "[Server] Voice Chat Server starting" }
    logger.info { "[Server]   WebSocket signaling: 0.0.0.0:$httpPort" }
    logger.info { "[Server]   Audio transport: WebRTC (P2P)" }
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
        maxFrameSize = 1024 * 1024
        masking = false
    }

    install(ContentNegotiation) {
        json()
    }

    val signalingHandler: SignalingHandler by inject()

    routing {
        with(signalingHandler) {
            signalingWebSocket()
        }
    }

    monitor.subscribe(ApplicationStopped) {
        logger.info { "[Server] Shutting down" }
    }

    logger.info { "[Server] Voice Chat Server ready" }
}
