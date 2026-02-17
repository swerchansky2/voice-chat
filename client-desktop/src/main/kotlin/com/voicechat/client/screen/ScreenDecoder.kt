package com.voicechat.client.screen

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val logger = KotlinLogging.logger {}

class ScreenDecoder {
    private var pipedOutput: PipedOutputStream? = null
    private var pipedInput: PipedInputStream? = null
    private var grabber: FFmpegFrameGrabber? = null
    private val converter = Java2DFrameConverter()
    private var decodeJob: Job? = null
    private var started = false

    private val _decodedFrame = MutableStateFlow<BufferedImage?>(null)
    val decodedFrame: StateFlow<BufferedImage?> = _decodedFrame.asStateFlow()

    fun start(scope: CoroutineScope) {
        if (started) return

        pipedOutput = PipedOutputStream()
        pipedInput = PipedInputStream(pipedOutput!!, 4 * 1024 * 1024)

        decodeJob = scope.launch(Dispatchers.IO) {
            try {
                grabber = FFmpegFrameGrabber(pipedInput).apply {
                    format = "h264"
                }
                grabber!!.start()
                logger.info { "H.264 decoder started" }

                while (isActive) {
                    try {
                        val frame = grabber?.grabImage() ?: break
                        val image = converter.convert(frame)
                        if (image != null) {
                            val copy = BufferedImage(image.width, image.height, BufferedImage.TYPE_3BYTE_BGR)
                            copy.graphics.drawImage(image, 0, 0, null)
                            _decodedFrame.value = copy
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            logger.debug { "Decoder frame error: ${e.message}" }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    logger.error(e) { "Decoder startup error" }
                }
            }
        }

        started = true
    }

    fun feedData(data: ByteArray) {
        try {
            pipedOutput?.write(data)
            pipedOutput?.flush()
        } catch (e: Exception) {
            logger.debug { "Feed data error: ${e.message}" }
        }
    }

    fun stop() {
        if (!started) return
        started = false

        decodeJob?.cancel()
        decodeJob = null

        try {
            pipedOutput?.close()
        } catch (_: Exception) {}

        try {
            grabber?.stop()
            grabber?.release()
        } catch (_: Exception) {}

        grabber = null
        pipedOutput = null
        pipedInput = null
        _decodedFrame.value = null

        logger.info { "H.264 decoder stopped" }
    }

    fun clearFrame() {
        _decodedFrame.value = null
    }
}
