package com.voicechat.server.sfu

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger("AudioMixer")

class AudioMixer {

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val FRAME_DURATION_MS = 10
        const val FRAME_COUNT = SAMPLE_RATE / 1000 * FRAME_DURATION_MS // 480
        const val BYTES_PER_FRAME = FRAME_COUNT * CHANNELS * (BITS_PER_SAMPLE / 8) // 960
    }

    private val userFrames = ConcurrentHashMap<String, ByteArray>()
    private val userSinks = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "audio-mixer").apply { isDaemon = true }
    }
    private var mixTask: ScheduledFuture<*>? = null

    fun start() {
        mixTask = executor.scheduleAtFixedRate(
            ::mixAndDeliver,
            0,
            FRAME_DURATION_MS.toLong(),
            TimeUnit.MILLISECONDS
        )
        logger.info { "[Mixer] Started (${SAMPLE_RATE}Hz, ${CHANNELS}ch, ${FRAME_DURATION_MS}ms frames)" }
    }

    fun stop() {
        mixTask?.cancel(false)
        executor.shutdown()
        logger.info { "[Mixer] Stopped" }
    }

    fun addUser(userId: String, mixedAudioSink: (ByteArray) -> Unit) {
        userSinks[userId] = mixedAudioSink
        logger.debug { "[Mixer] Added user $userId" }
    }

    fun removeUser(userId: String) {
        userSinks.remove(userId)
        userFrames.remove(userId)
        logger.debug { "[Mixer] Removed user $userId" }
    }

    fun pushAudioFrame(userId: String, data: ByteArray) {
        userFrames[userId] = data
    }

    private fun mixAndDeliver() {
        try {
            val currentSinks = userSinks.entries.toList()
            if (currentSinks.size < 2) {
                if (currentSinks.size == 1) {
                    currentSinks[0].value(ByteArray(BYTES_PER_FRAME))
                }
                return
            }

            val frames = userFrames.toMap()

            for ((targetUserId, sink) in currentSinks) {
                val mixed = ShortArray(FRAME_COUNT)

                for ((sourceUserId, frame) in frames) {
                    if (sourceUserId == targetUserId) continue
                    addPcm16(mixed, frame)
                }

                sink(shortsToBytes(mixed))
            }
        } catch (e: Exception) {
            logger.error(e) { "[Mixer] Error during mix cycle" }
        }
    }

    private fun addPcm16(accumulator: ShortArray, pcmBytes: ByteArray) {
        val sampleCount = minOf(accumulator.size, pcmBytes.size / 2)
        for (i in 0 until sampleCount) {
            val sample = (pcmBytes[i * 2].toInt() and 0xFF) or
                    (pcmBytes[i * 2 + 1].toInt() shl 8)
            val sum = accumulator[i].toInt() + sample.toShort().toInt()
            accumulator[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }
}
