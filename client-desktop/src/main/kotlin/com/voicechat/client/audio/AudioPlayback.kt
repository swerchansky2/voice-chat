package com.voicechat.client.audio

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.*

private val logger = KotlinLogging.logger {}

class AudioPlayback {
    private var sourceLine: SourceDataLine? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    
    @Volatile
    private var isRunning = false

    companion object {
        const val SAMPLE_RATE = 48000f
        const val CHANNELS = 1 // Mono
        const val BITS_PER_SAMPLE = 16
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
                logger.error { "Speaker line not supported" }
                return
            }
            
            sourceLine = AudioSystem.getLine(info) as SourceDataLine
            sourceLine?.open(format)
            sourceLine?.start()
            
            isRunning = true
            logger.info { "Audio playback started" }
            
            // Start playback thread
            Thread {
                playbackLoop()
            }.start()
        } catch (e: Exception) {
            logger.error(e) { "Failed to start audio playback" }
        }
    }

    fun stop() {
        isRunning = false
        audioQueue.clear()
        sourceLine?.stop()
        sourceLine?.close()
        sourceLine = null
        logger.info { "Audio playback stopped" }
    }

    fun play(audioData: ByteArray) {
        if (isRunning && audioQueue.size < 10) { // Prevent queue overflow
            audioQueue.offer(audioData)
        }
    }

    private fun playbackLoop() {
        while (isRunning) {
            try {
                val data = audioQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (data != null && sourceLine != null) {
                    sourceLine?.write(data, 0, data.size)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning) {
                    logger.error(e) { "Error during playback" }
                }
            }
        }
    }
}
