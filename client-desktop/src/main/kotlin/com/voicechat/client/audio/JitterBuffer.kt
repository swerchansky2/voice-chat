package com.voicechat.client.audio

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.TreeMap

private val logger = KotlinLogging.logger("Jitter")

class JitterBuffer(
    private val codec: OpusCodec,
    private val playback: AudioPlayback
) {
    companion object {
        private const val BUFFER_DEPTH = 3 // frames to buffer before starting playback
        private const val MAX_BUFFER_SIZE = 50
    }

    private val lock = Object()
    private val buffer = TreeMap<Int, ByteArray>()
    private var nextExpectedSeq = -1
    private var started = false
    @Volatile
    private var running = false
    private var playbackThread: Thread? = null

    fun put(sequenceNumber: Int, opusData: ByteArray) {
        synchronized(lock) {
            if (nextExpectedSeq >= 0 && sequenceNumber < nextExpectedSeq) {
                return
            }

            buffer[sequenceNumber] = opusData

            while (buffer.size > MAX_BUFFER_SIZE) {
                buffer.pollFirstEntry()
            }

            if (!started && buffer.size >= BUFFER_DEPTH) {
                started = true
                nextExpectedSeq = buffer.firstKey()
                lock.notifyAll()
            }
        }
    }

    fun start() {
        running = true
        playbackThread = Thread({
            playbackLoop()
        }, "jitter-playback").apply {
            isDaemon = true
            start()
        }
        logger.info { "[Jitter] Started" }
    }

    private fun playbackLoop() {
        // Wait until we have enough buffered frames
        synchronized(lock) {
            while (running && !started) {
                lock.wait(100)
            }
        }

        while (running) {
            try {
                val opusData = synchronized(lock) {
                    val data = buffer.remove(nextExpectedSeq)
                    nextExpectedSeq++
                    data
                }

                val pcm = if (opusData != null) {
                    codec.decode(opusData)
                } else {
                    codec.decodePLC()
                }

                // SourceDataLine.write() blocks when its buffer is full,
                // naturally providing 20ms frame pacing from the audio hardware clock
                playback.playFrame(pcm)
            } catch (e: Exception) {
                if (running) {
                    logger.error(e) { "[Jitter] Error in playback" }
                }
            }
        }
    }

    fun stop() {
        running = false
        playbackThread?.interrupt()
        playbackThread = null
        synchronized(lock) {
            buffer.clear()
            started = false
            nextExpectedSeq = -1
        }
        logger.info { "[Jitter] Stopped" }
    }
}
