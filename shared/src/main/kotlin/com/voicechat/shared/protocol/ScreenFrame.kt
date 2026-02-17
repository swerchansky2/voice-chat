package com.voicechat.shared.protocol

import java.nio.ByteBuffer

data class ScreenFrame(
    val userId: String,
    val encodedData: ByteArray
) {
    fun toBytes(): ByteArray {
        val userIdBytes = userId.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + userIdBytes.size + encodedData.size)
        buffer.putInt(userIdBytes.size)
        buffer.put(userIdBytes)
        buffer.put(encodedData)
        return buffer.array()
    }

    companion object {
        private const val MAX_USER_ID_LENGTH = 256

        fun fromBytes(bytes: ByteArray): ScreenFrame? {
            if (bytes.size < 4) return null

            val buffer = ByteBuffer.wrap(bytes)
            val userIdLength = buffer.getInt()

            if (userIdLength <= 0 || userIdLength > MAX_USER_ID_LENGTH) return null
            if (bytes.size < 4 + userIdLength) return null

            val userIdBytes = ByteArray(userIdLength)
            buffer.get(userIdBytes)
            val userId = String(userIdBytes, Charsets.UTF_8)

            val dataLength = bytes.size - 4 - userIdLength
            val data = ByteArray(dataLength)
            buffer.get(data)

            return ScreenFrame(userId, data)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ScreenFrame
        return userId == other.userId && encodedData.contentEquals(other.encodedData)
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + encodedData.contentHashCode()
        return result
    }
}
