package com.voicechat.client.network

import com.voicechat.shared.protocol.VideoPacket
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger("VideoUDP")

class VideoUdpClient {
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 9002

    private val _receivedPackets = MutableSharedFlow<VideoPacket>(extraBufferCapacity = 512)
    val receivedPackets: SharedFlow<VideoPacket> = _receivedPackets.asSharedFlow()

    fun start(serverHost: String, serverUdpPort: Int = 9002): Int {
        stop()
        serverAddress = InetAddress.getByName(serverHost)
        serverPort = serverUdpPort
        socket = DatagramSocket()
        val localPort = socket!!.localPort
        logger.info { "[VideoUDP] Started on local port $localPort, target $serverHost:$serverUdpPort" }
        receiveJob = CoroutineScope(Dispatchers.IO).launch { receiveLoop() }
        return localPort
    }

    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
        logger.info { "[VideoUDP] Stopped" }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(65535)
        while (coroutineContext.isActive && socket != null) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)
                val data = packet.data.copyOfRange(0, packet.length)
                val videoPacket = VideoPacket.fromBytes(data)
                if (videoPacket != null) {
                    _receivedPackets.emit(videoPacket)
                } else {
                    logger.warn { "[VideoUDP] Failed to parse packet (${data.size} bytes)" }
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) logger.error(e) { "[VideoUDP] Error receiving" }
            }
        }
    }

    fun sendPacket(packet: VideoPacket) {
        val s = socket ?: return
        val addr = serverAddress ?: return
        try {
            val data = packet.toBytes()
            s.send(DatagramPacket(data, data.size, addr, serverPort))
        } catch (e: Exception) {
            logger.error(e) { "[VideoUDP] Failed to send fragment frag=${packet.fragIdx}/${packet.totalFrags}" }
        }
    }
}
