package com.voicechat.client.network

import com.voicechat.shared.protocol.AudioPacket
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger("UDP")

class UdpAudioClient {
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private lateinit var serverAddress: InetAddress
    private var serverPort: Int = 9001 // Default UDP port for server

    private val _receivedPackets = MutableSharedFlow<AudioPacket>()
    val receivedPackets: SharedFlow<AudioPacket> = _receivedPackets.asSharedFlow()

    fun start(serverHost: String, serverUdpPort: Int = 9001): Int {
        stop()

        serverAddress = InetAddress.getByName(serverHost)
        serverPort = serverUdpPort

        socket = DatagramSocket()
        val localPort = socket!!.localPort

        logger.info { "[UDP] Started on local port $localPort, target server $serverHost:$serverUdpPort" }

        // Start receiving
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            receiveLoop()
        }

        return localPort
    }

    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
        logger.info { "[UDP] Stopped" }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(4096)

        while (coroutineContext.isActive && socket != null) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)

                val data = packet.data.copyOfRange(0, packet.length)
                val audioPacket = AudioPacket.fromBytes(data)

                if (audioPacket != null) {
                    logger.debug { "[UDP] Received packet from user ${audioPacket.userId}, size=${data.size}" }
                    _receivedPackets.emit(audioPacket)
                } else {
                    logger.warn { "[UDP] Failed to parse audio packet (${data.size} bytes)" }
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    logger.error(e) { "[UDP] Error receiving packet" }
                }
            }
        }
    }

    fun sendAudioPacket(packet: AudioPacket) {
        val socket = this.socket ?: return

        try {
            val data = packet.toBytes()
            val datagramPacket = DatagramPacket(data, data.size, serverAddress, serverPort)
            socket.send(datagramPacket)
        } catch (e: Exception) {
            logger.error(e) { "[UDP] Failed to send audio packet (${packet.audioData.size} bytes)" }
        }
    }
}
