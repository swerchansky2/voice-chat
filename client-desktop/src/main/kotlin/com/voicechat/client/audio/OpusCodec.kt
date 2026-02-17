package com.voicechat.client.audio

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger("Codec")

/**
 * Opus codec wrapper.
 * TODO: Integrate Concentus library for real Opus encoding/decoding.
 * Currently using PCM passthrough as fallback.
 */
class OpusCodec {

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 1
        const val FRAME_SIZE = 960
        const val BITRATE = 64000
    }

    init {
        logger.warn { "[Codec] Using PCM passthrough (Opus not available)" }
    }

    /**
     * Encode PCM audio to Opus format.
     * TODO: Replace with actual Opus encoding using Concentus.
     */
    fun encode(pcmData: ByteArray): ByteArray {
        // Currently just returns the PCM data as-is
        return pcmData
    }

    /**
     * Decode Opus audio to PCM format.
     * TODO: Replace with actual Opus decoding using Concentus.
     */
    fun decode(opusData: ByteArray): ByteArray {
        // Currently just returns the data as-is (assuming it's PCM)
        return opusData
    }
}
