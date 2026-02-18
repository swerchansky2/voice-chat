package com.voicechat.server

import com.voicechat.server.audio.UdpAudioRelay
import com.voicechat.server.di.serverModule
import com.voicechat.server.video.UdpVideoRelay
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
    val videoUdpPort = System.getenv("VIDEO_UDP_PORT")?.toIntOrNull() ?: 9002

    require(httpPort in 1..65535) { "HTTP_PORT must be in range 1-65535, got $httpPort" }
    require(udpPort in 1..65535) { "UDP_PORT must be in range 1-65535, got $udpPort" }
    require(videoUdpPort in 1..65535) { "VIDEO_UDP_PORT must be in range 1-65535, got $videoUdpPort" }

    logger.info { "[Server] ========================================" }
    logger.info { "[Server] Voice Chat Server starting" }
    logger.info { "[Server]   WebSocket: 0.0.0.0:$httpPort" }
    logger.info { "[Server]   UDP Audio: 0.0.0.0:$udpPort" }
    logger.info { "[Server]   UDP Video: 0.0.0.0:$videoUdpPort" }
    logger.info { "[Server] ========================================" }

    embeddedServer(Netty, port = httpPort, host = "0.0.0.0") {
        configureServer(udpPort, videoUdpPort)
    }.start(wait = true)
}

fun Application.configureServer(audioUdpPort: Int = 9001, videoUdpPort: Int = 9002) {
    install(Koin) {
        modules(serverModule)
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = 64 * 1024  // 64 KB — signaling only, video goes via UDP
        masking = false
    }

    install(ContentNegotiation) {
        json()
    }

    val signalingHandler: SignalingHandler by inject()
    val udpAudioRelay: UdpAudioRelay by inject()
    val udpVideoRelay: UdpVideoRelay by inject()

    val udpScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    udpAudioRelay.start(udpScope)
    udpVideoRelay.start(udpScope)

    routing {
        with(signalingHandler) {
            signalingWebSocket()
        }
    }

    monitor.subscribe(ApplicationStopped) {
        logger.info { "[Server] Shutting down" }
        udpAudioRelay.stop()
        udpVideoRelay.stop()
    }

    logger.info { "[Server] Voice Chat Server ready" }
}
