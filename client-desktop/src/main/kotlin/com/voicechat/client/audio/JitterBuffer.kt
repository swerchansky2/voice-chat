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
        private const val MAX_PLC_RUN = 3 // max consecutive PLC frames before waiting for data
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
            }

            lock.notifyAll()
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

        var plcCount = 0

        while (running) {
            try {
                val opusData: ByteArray?

                synchronized(lock) {
                    // If buffer is empty, wait for new data instead of spinning PLC
                    while (running && buffer.isEmpty()) {
                        lock.wait(100)
                    }
                    if (!running) return

                    // If we've fallen too far behind, resync to current buffer position
                    if (buffer.isNotEmpty() && nextExpectedSeq < buffer.firstKey() - BUFFER_DEPTH) {
                        logger.debug { "[Jitter] Resync: $nextExpectedSeq -> ${buffer.firstKey()}" }
                        nextExpectedSeq = buffer.firstKey()
                    }

                    opusData = buffer.remove(nextExpectedSeq)
                    nextExpectedSeq++
                }

                if (opusData != null) {
                    plcCount = 0
                    playback.playFrame(codec.decode(opusData))
                } else {
                    plcCount++
                    if (plcCount <= MAX_PLC_RUN) {
                        // Fill short gaps with PLC
                        playback.playFrame(codec.decodePLC())
                    } else {
                        // Too many consecutive missing frames — pause and wait for data
                        synchronized(lock) {
                            lock.wait(20)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running) {
                    logger.error(e) { "[Jitter] Error in playback" }
                }
            }
        }
    }

    fun stop() {
        running = false
        synchronized(lock) {
            lock.notifyAll()
        }
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
