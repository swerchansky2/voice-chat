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

private val logger = KotlinLogging.logger {}

class UdpAudioClient {
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 9001 // Default UDP port for server

    private val _receivedPackets = MutableSharedFlow<AudioPacket>()
    val receivedPackets: SharedFlow<AudioPacket> = _receivedPackets.asSharedFlow()

    fun start(): Int {
        stop()
        
        socket = DatagramSocket()
        val localPort = socket!!.localPort
        
        logger.info { "UDP client started on port $localPort" }
        
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
        logger.info { "UDP client stopped" }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(2048) // Large enough for audio packets
        
        while (coroutineContext.isActive && socket != null) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)
                
                // First time we receive, save server address
                if (serverAddress == null) {
                    serverAddress = packet.address
                    serverPort = packet.port
                    logger.info { "Server address set to ${packet.address}:${packet.port}" }
                }
                
                val data = packet.data.copyOfRange(0, packet.length)
                val audioPacket = AudioPacket.fromBytes(data)
                
                if (audioPacket != null) {
                    _receivedPackets.emit(audioPacket)
                } else {
                    logger.warn { "Failed to parse audio packet" }
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    logger.error(e) { "Error receiving UDP packet" }
                }
            }
        }
    }

    fun sendAudioPacket(packet: AudioPacket, serverHost: String = "localhost") {
        val socket = this.socket ?: return
        
        try {
            val data = packet.toBytes()
            val address = serverAddress ?: InetAddress.getByName(serverHost)
            val datagramPacket = DatagramPacket(data, data.size, address, serverPort)
            socket.send(datagramPacket)
        } catch (e: Exception) {
            logger.error(e) { "Failed to send audio packet" }
        }
    }
}
