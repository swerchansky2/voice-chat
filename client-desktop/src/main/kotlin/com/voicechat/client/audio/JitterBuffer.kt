package com.voicechat.client.audio

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger("Jitter")

class JitterBuffer(
    private val codec: OpusCodec,
    private val playback: AudioPlayback
) {
    companion object {
        private const val BUFFER_DEPTH = 3 // frames to buffer before starting playback
        private const val FRAME_INTERVAL_MS = 20L
        private const val MAX_BUFFER_SIZE = 50 // drop if buffer grows too large
    }

    private val buffer = TreeMap<Int, ByteArray>() // sequenceNumber -> opus data
    private var nextExpectedSeq = -1
    private var started = false
    private var scheduler: ScheduledExecutorService? = null

    @Synchronized
    fun put(sequenceNumber: Int, opusData: ByteArray) {
        // Drop packets that are too old
        if (nextExpectedSeq >= 0 && sequenceNumber < nextExpectedSeq) {
            return
        }

        buffer[sequenceNumber] = opusData

        // Drop oldest if buffer grows too large
        while (buffer.size > MAX_BUFFER_SIZE) {
            buffer.pollFirstEntry()
        }

        // Start playback once we've buffered enough
        if (!started && buffer.size >= BUFFER_DEPTH) {
            started = true
            nextExpectedSeq = buffer.firstKey()
            startPlaybackTimer()
        }
    }

    private fun startPlaybackTimer() {
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "jitter-playback").apply { isDaemon = true }
        }
        scheduler?.scheduleAtFixedRate(::tick, 0, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS)
        logger.info { "[Jitter] Playback timer started" }
    }

    @Synchronized
    private fun tick() {
        try {
            val opusData = buffer.remove(nextExpectedSeq)
            val pcm = if (opusData != null) {
                codec.decode(opusData)
            } else {
                // Packet missing — use PLC
                codec.decodePLC()
            }
            playback.playFrame(pcm)
            nextExpectedSeq++
        } catch (e: Exception) {
            logger.error(e) { "[Jitter] Error in playback tick" }
        }
    }

    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        synchronized(this) {
            buffer.clear()
            started = false
            nextExpectedSeq = -1
        }
        logger.info { "[Jitter] Stopped" }
    }
}
