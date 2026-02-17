package com.voicechat.shared.protocol

import java.nio.ByteBuffer

data class AudioPacket(
    val userId: String,
    val audioData: ByteArray
) {
    fun toBytes(): ByteArray {
        val userIdBytes = userId.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + userIdBytes.size + audioData.size)
        buffer.putInt(userIdBytes.size)
        buffer.put(userIdBytes)
        buffer.put(audioData)
        return buffer.array()
    }

    companion object {
        private const val MAX_USER_ID_LENGTH = 256
        
        fun fromBytes(bytes: ByteArray): AudioPacket? {
            if (bytes.size < 4) return null
            
            val buffer = ByteBuffer.wrap(bytes)
            val userIdLength = buffer.getInt()
            
            // Validate userId length to prevent buffer overflow
            if (userIdLength <= 0 || userIdLength > MAX_USER_ID_LENGTH) return null
            if (bytes.size < 4 + userIdLength) return null
            
            val userIdBytes = ByteArray(userIdLength)
            buffer.get(userIdBytes)
            val userId = String(userIdBytes, Charsets.UTF_8)
            
            val audioDataLength = bytes.size - 4 - userIdLength
            val audioData = ByteArray(audioDataLength)
            buffer.get(audioData)
            
            return AudioPacket(userId, audioData)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioPacket

        if (userId != other.userId) return false
        if (!audioData.contentEquals(other.audioData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + audioData.contentHashCode()
        return result
    }
}
