package com.voicechat.client.audio

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.TreeMap

private val logger = KotlinLogging.logger("Jitter")

class JitterBuffer(
    private val codec: OpusCodec,
    private val playback: AudioPlayback
) {
    companion object {
        private const val BUFFER_DEPTH = 5 // frames to buffer before starting playback (~100ms)
        private const val MAX_BUFFER_SIZE = 50
        private const val MAX_PLC_RUN = 5 // max consecutive PLC frames before waiting for data
        private const val DRIFT_WINDOW = 100 // frames (~2s) for clock drift averaging
    }

    private val lock = Object()
    private val buffer = TreeMap<Int, ByteArray>()
    private var nextExpectedSeq = -1
    private var started = false
    @Volatile
    private var running = false
    private var playbackThread: Thread? = null

    // Clock drift compensation state
    private var driftSampleCount = 0
    private var driftFullnessSum = 0L

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
                var skipFrame = false

                synchronized(lock) {
                    // If buffer is empty, wait for new data instead of spinning PLC
                    while (running && buffer.isEmpty()) {
                        lock.wait(100)
                    }
                    if (!running) return

                    // Clock drift compensation: track average buffer fullness
                    driftSampleCount++
                    driftFullnessSum += buffer.size
                    if (driftSampleCount >= DRIFT_WINDOW) {
                        val avgFullness = driftFullnessSum.toDouble() / driftSampleCount
                        driftSampleCount = 0
                        driftFullnessSum = 0L
                        // If buffer is consistently growing, sender is faster — skip one frame to drain
                        if (avgFullness > BUFFER_DEPTH + 2) {
                            logger.debug { "[Jitter] Drift correction: skipping frame (avg fullness=${"%.1f".format(avgFullness)})" }
                            skipFrame = true
                        }
                    }

                    // If we've fallen too far behind, resync to current buffer position
                    if (buffer.isNotEmpty() && nextExpectedSeq < buffer.firstKey() - BUFFER_DEPTH) {
                        logger.debug { "[Jitter] Resync: $nextExpectedSeq -> ${buffer.firstKey()}" }
                        nextExpectedSeq = buffer.firstKey()
                    }

                    opusData = buffer.remove(nextExpectedSeq)
                    nextExpectedSeq++

                    // If skipping for drift correction, consume one extra frame
                    if (skipFrame && buffer.containsKey(nextExpectedSeq)) {
                        val skipData = buffer.remove(nextExpectedSeq)
                        nextExpectedSeq++
                        // Decode the skipped frame to keep codec state consistent, but don't play it
                        if (skipData != null) {
                            codec.decode(skipData)
                        }
                    }
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
            driftSampleCount = 0
            driftFullnessSum = 0L
        }
        logger.info { "[Jitter] Stopped" }
    }
}
