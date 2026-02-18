package com.voicechat.server.video

import com.voicechat.server.room.RoomManager
import com.voicechat.shared.protocol.VideoPacket
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket

private val logger = KotlinLogging.logger("VideoRelay")

class UdpVideoRelay(
    private val roomManager: RoomManager,
    private val port: Int = 9002
) {
    private var socket: DatagramSocket? = null
    private var relayJob: Job? = null

    fun start(scope: CoroutineScope) {
        socket = DatagramSocket(port)
        logger.info { "[VideoRelay] UDP Video Relay listening on port $port" }

        relayJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(65535)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val data = packet.data.copyOfRange(0, packet.length)
                    val videoPacket = VideoPacket.fromBytes(data) ?: continue
                    relayVideoPacket(videoPacket)
                } catch (e: Exception) {
                    if (isActive) logger.error(e) { "[VideoRelay] Error receiving UDP packet" }
                }
            }
        }
    }

    private fun relayVideoPacket(videoPacket: VideoPacket) {
        val room = roomManager.getRoom(RoomManager.DEFAULT_ROOM_ID) ?: return
        val sender = room.getUser(videoPacket.senderId)
        if (sender == null) {
            logger.warn { "[VideoRelay] Video from unknown user: ${videoPacket.senderId}" }
            return
        }

        val packetBytes = videoPacket.toBytes()
        room.getAllUsers().forEach { user ->
            if (user.userId != videoPacket.senderId && user.videoUdpAddress != null) {
                try {
                    socket?.send(
                        DatagramPacket(packetBytes, packetBytes.size, user.videoUdpAddress)
                    )
                } catch (e: Exception) {
                    logger.error(e) { "[VideoRelay] Failed to relay to ${user.nickname}" }
                }
            }
        }
    }

    fun stop() {
        relayJob?.cancel()
        socket?.close()
        logger.info { "[VideoRelay] Stopped" }
    }
}
