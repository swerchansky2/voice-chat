package com.voicechat.client.audio

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.sound.sampled.*
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger {}

class AudioCapture {
    private var targetLine: TargetDataLine? = null
    private var captureJob: Job? = null
    
    private val _audioData = MutableSharedFlow<ByteArray>()
    val audioData: SharedFlow<ByteArray> = _audioData.asSharedFlow()

    companion object {
        const val SAMPLE_RATE = 48000f
        const val CHANNELS = 1 // Mono
        const val BITS_PER_SAMPLE = 16
        const val FRAME_SIZE = 960 // 20ms at 48kHz
        const val BUFFER_SIZE_BYTES = FRAME_SIZE * 2 // 2 bytes per sample (16-bit)
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
            
            val info = DataLine.Info(TargetDataLine::class.java, format)
            
            if (!AudioSystem.isLineSupported(info)) {
                logger.error { "Microphone line not supported" }
                return
            }
            
            targetLine = AudioSystem.getLine(info) as TargetDataLine
            targetLine?.open(format, BUFFER_SIZE_BYTES * 4)
            targetLine?.start()
            
            logger.info { "Audio capture started" }
            
            captureJob = CoroutineScope(Dispatchers.IO).launch {
                captureLoop()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to start audio capture" }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        targetLine?.stop()
        targetLine?.close()
        targetLine = null
        logger.info { "Audio capture stopped" }
    }

    private suspend fun captureLoop() {
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        
        while (coroutineContext.isActive && targetLine != null) {
            try {
                val bytesRead = targetLine?.read(buffer, 0, buffer.size) ?: 0
                
                if (bytesRead > 0) {
                    // Copy the buffer to avoid reuse issues
                    val data = buffer.copyOf(bytesRead)
                    _audioData.emit(data)
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    logger.error(e) { "Error capturing audio" }
                }
            }
        }
    }
}
