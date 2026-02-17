package com.voicechat.client.audio

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.jaredmdobson.OpusApplication
import io.github.jaredmdobson.OpusDecoder
import io.github.jaredmdobson.OpusEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val logger = KotlinLogging.logger("Codec")

class OpusCodec {

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 1
        const val FRAME_SIZE = 960 // 20ms at 48kHz
        const val BITRATE = 32000
        private const val MAX_OPUS_PACKET_SIZE = 1275
        private const val EXPECTED_PCM_BYTES = FRAME_SIZE * CHANNELS * 2 // 1920
    }

    private val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
        setBitrate(BITRATE)
        setComplexity(5)
    }

    private val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)

    init {
        logger.info { "[Codec] Opus encoder/decoder initialized — ${SAMPLE_RATE}Hz, mono, ${BITRATE}bps" }
    }

    fun encode(pcmBytes: ByteArray): ByteArray {
        if (pcmBytes.size != EXPECTED_PCM_BYTES) {
            logger.warn { "[Codec] Unexpected PCM size: ${pcmBytes.size}, expected $EXPECTED_PCM_BYTES" }
            return ByteArray(0)
        }
        val pcmShorts = bytesToShorts(pcmBytes)
        val outputBuffer = ByteArray(MAX_OPUS_PACKET_SIZE)
        val bytesEncoded = encoder.encode(pcmShorts, 0, FRAME_SIZE, outputBuffer, 0, MAX_OPUS_PACKET_SIZE)
        return outputBuffer.copyOf(bytesEncoded)
    }

    fun decode(opusBytes: ByteArray): ByteArray {
        val pcmShorts = ShortArray(FRAME_SIZE * CHANNELS)
        decoder.decode(opusBytes, 0, opusBytes.size, pcmShorts, 0, FRAME_SIZE, false)
        return shortsToBytes(pcmShorts)
    }

    fun decodePLC(): ByteArray {
        val pcmShorts = ShortArray(FRAME_SIZE * CHANNELS)
        decoder.decode(null, 0, 0, pcmShorts, 0, FRAME_SIZE, false)
        return shortsToBytes(pcmShorts)
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
        return bytes
    }
}
