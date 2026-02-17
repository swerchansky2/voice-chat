package com.voicechat.server.room

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class RoomManager {
    private val rooms = ConcurrentHashMap<String, Room>()
    
    companion object {
        const val DEFAULT_ROOM_ID = "main"
    }

    fun getOrCreateRoom(roomId: String = DEFAULT_ROOM_ID): Room {
        return rooms.getOrPut(roomId) {
            logger.info { "Creating new room: $roomId" }
            Room(roomId)
        }
    }

    fun getRoom(roomId: String): Room? = rooms[roomId]

    fun removeRoomIfEmpty(roomId: String) {
        rooms[roomId]?.let { room ->
            if (room.size() == 0) {
                rooms.remove(roomId)
                logger.info { "Removed empty room: $roomId" }
            }
        }
    }
}
