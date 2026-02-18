package com.voicechat.shared.protocol

import java.nio.ByteBuffer

/**
 * Wire format (big-endian):
 * [type:1]        = 0x56 ('V')
 * [senderIdLen:1] = byte length of senderId
 * [senderId:N]    = UTF-8 userId (UUID = 36 bytes)
 * [frameId:4]     = monotonic frame counter
 * [fragIdx:2]     = fragment index (0-based)
 * [totalFrags:2]  = total fragments for this frame
 * [payload:rest]  = raw H.264 elementary-stream bytes
 *
 * Min header: 1+1+36+4+2+2 = 46 bytes
 * Max payload per packet: 1300 bytes (safely under 1400-byte UDP MTU budget)
 */
data class VideoPacket(
    val senderId: String,
    val frameId: Int,
    val fragIdx: Int,
    val totalFrags: Int,
    val payload: ByteArray
) {
    companion object {
        private const val TYPE = 0x56.toByte()  // 'V'
        const val MAX_PAYLOAD = 1300

        fun fromBytes(data: ByteArray): VideoPacket? {
            if (data.size < 10) return null
            val buf = ByteBuffer.wrap(data)
            if (buf.get() != TYPE) return null
            val senderIdLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < senderIdLen + 8) return null
            val senderIdBytes = ByteArray(senderIdLen)
            buf.get(senderIdBytes)
            val senderId = String(senderIdBytes, Charsets.UTF_8)
            val frameId = buf.int
            val fragIdx = buf.short.toInt() and 0xFFFF
            val totalFrags = buf.short.toInt() and 0xFFFF
            if (totalFrags == 0) return null
            val payload = ByteArray(buf.remaining())
            buf.get(payload)
            return VideoPacket(senderId, frameId, fragIdx, totalFrags, payload)
        }
    }

    fun toBytes(): ByteArray {
        val senderIdBytes = senderId.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 1 + senderIdBytes.size + 4 + 2 + 2 + payload.size)
        buf.put(TYPE)
        buf.put(senderIdBytes.size.toByte())
        buf.put(senderIdBytes)
        buf.putInt(frameId)
        buf.putShort(fragIdx.toShort())
        buf.putShort(totalFrags.toShort())
        buf.put(payload)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoPacket) return false
        return senderId == other.senderId && frameId == other.frameId &&
               fragIdx == other.fragIdx && totalFrags == other.totalFrags &&
               payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = senderId.hashCode()
        result = 31 * result + frameId
        result = 31 * result + fragIdx
        result = 31 * result + totalFrags
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
