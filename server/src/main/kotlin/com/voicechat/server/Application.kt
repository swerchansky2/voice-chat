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

private val logger = KotlinLogging.logger {}

fun main() {
    val httpPort = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 8080
    val udpPort = System.getenv("UDP_PORT")?.toIntOrNull() ?: 9001

    logger.info { "Starting Voice Chat Server..." }
    logger.info { "HTTP/WebSocket port: $httpPort" }
    logger.info { "UDP port: $udpPort" }

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
        maxFrameSize = Long.MAX_VALUE
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
        logger.info { "Stopping Voice Chat Server..." }
        udpAudioRelay.stop()
    }

    logger.info { "Voice Chat Server started successfully" }
}
