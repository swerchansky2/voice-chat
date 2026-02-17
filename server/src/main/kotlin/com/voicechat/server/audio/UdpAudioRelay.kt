package com.voicechat.server.audio

import com.voicechat.server.room.RoomManager
import com.voicechat.shared.protocol.AudioPacket
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

private val logger = KotlinLogging.logger("Relay")

class UdpAudioRelay(
    private val roomManager: RoomManager,
    private val port: Int = 9001
) {
    private var socket: DatagramSocket? = null
    private var relayJob: Job? = null

    fun start(scope: CoroutineScope) {
        socket = DatagramSocket(port)
        logger.info { "[Relay] UDP Audio Relay listening on port $port" }

        relayJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(65535)

            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val receivedData = packet.data.copyOfRange(0, packet.length)
                    val audioPacket = AudioPacket.fromBytes(receivedData)

                    if (audioPacket == null) {
                        logger.warn { "[Relay] Failed to parse audio packet from ${packet.address}:${packet.port} (${receivedData.size} bytes)" }
                        continue
                    }

                    logger.debug { "[Relay] Received packet from user ${audioPacket.userId}, size=${audioPacket.audioData.size}" }

                    relayAudioPacket(audioPacket)
                } catch (e: Exception) {
                    if (isActive) {
                        logger.error(e) { "[Relay] Error receiving UDP packet" }
                    }
                }
            }
        }
    }

    private fun relayAudioPacket(audioPacket: AudioPacket) {
        val room = roomManager.getRoom(RoomManager.DEFAULT_ROOM_ID) ?: return
        val sender = room.getUser(audioPacket.userId)

        if (sender == null) {
            logger.warn { "[Relay] Audio from unknown user: ${audioPacket.userId}" }
            return
        }

        val packetBytes = audioPacket.toBytes()
        var sentCount = 0

        room.getAllUsers().forEach { user ->
            if (user.userId != audioPacket.userId && user.udpAddress != null) {
                try {
                    val packet = DatagramPacket(
                        packetBytes,
                        packetBytes.size,
                        user.udpAddress
                    )
                    socket?.send(packet)
                    sentCount++
                } catch (e: Exception) {
                    logger.error(e) { "[Relay] Failed to relay audio to user ${user.nickname}" }
                }
            }
        }

        logger.debug { "[Relay] Relayed packet from \"${sender.nickname}\" to $sentCount users" }
    }

    fun stop() {
        relayJob?.cancel()
        socket?.close()
        logger.info { "[Relay] UDP Audio Relay stopped" }
    }
}
