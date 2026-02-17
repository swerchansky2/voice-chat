package com.voicechat.client.audio

import io.github.oshai.kotlinlogging.KotlinLogging
import javax.sound.sampled.*

private val logger = KotlinLogging.logger("Playback")

class AudioPlayback {
    private var sourceLine: SourceDataLine? = null

    companion object {
        const val SAMPLE_RATE = 48000f
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        private const val BUFFER_SIZE_BYTES = 9600 // ~100ms at 48kHz mono 16-bit
    }

    fun start() {
        stop()

        try {
            val format = AudioFormat(
                SAMPLE_RATE,
                BITS_PER_SAMPLE,
                CHANNELS,
                true, // signed
                false // little endian
            )

            val info = DataLine.Info(SourceDataLine::class.java, format)

            if (!AudioSystem.isLineSupported(info)) {
                logger.error { "[Playback] Speaker line not supported" }
                return
            }

            sourceLine = (AudioSystem.getLine(info) as SourceDataLine).apply {
                open(format, BUFFER_SIZE_BYTES)
                start()
            }

            logger.info { "[Playback] Started — ${SAMPLE_RATE.toInt()}Hz, mono, ${BITS_PER_SAMPLE}-bit" }
        } catch (e: Exception) {
            logger.error(e) { "[Playback] Failed to start audio playback" }
        }
    }

    fun stop() {
        sourceLine?.stop()
        sourceLine?.close()
        sourceLine = null
        logger.info { "[Playback] Stopped" }
    }

    fun playFrame(pcmData: ByteArray) {
        sourceLine?.write(pcmData, 0, pcmData.size)
    }
}
