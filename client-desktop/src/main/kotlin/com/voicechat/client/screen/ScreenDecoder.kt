package com.voicechat.client.screen

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val logger = KotlinLogging.logger {}

/**
 * InputStream backed by a concurrent queue.
 * Blocks on read() until data is available, supports graceful close.
 */
class QueueInputStream : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray? = null
    private var position = 0
    @Volatile
    var closed = false
        private set

    fun enqueue(data: ByteArray) {
        if (!closed) {
            queue.offer(data)
        }
    }

    override fun read(): Int {
        if (!ensureData()) return -1
        return current!![position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!ensureData()) return -1

        val available = current!!.size - position
        val toRead = minOf(len, available)
        System.arraycopy(current!!, position, b, off, toRead)
        position += toRead

        if (position >= current!!.size) {
            current = null
            position = 0
        }
        return toRead
    }

    private fun ensureData(): Boolean {
        if (current != null && position < current!!.size) return true

        while (!closed) {
            val data = queue.poll(100, TimeUnit.MILLISECONDS)
            if (data != null) {
                current = data
                position = 0
                return true
            }
        }
        return false
    }

    override fun available(): Int {
        return if (current != null) current!!.size - position else 0
    }

    override fun close() {
        closed = true
        queue.clear()
    }
}

class ScreenDecoder {
    private var inputStream: QueueInputStream? = null
    private var grabber: FFmpegFrameGrabber? = null
    private val converter = Java2DFrameConverter()
    private var decodeJob: Job? = null
    private var started = false

    private val _decodedFrame = MutableStateFlow<BufferedImage?>(null)
    val decodedFrame: StateFlow<BufferedImage?> = _decodedFrame.asStateFlow()

    fun start(scope: CoroutineScope) {
        if (started) return

        inputStream = QueueInputStream()

        decodeJob = scope.launch(Dispatchers.IO) {
            try {
                logger.info { "[Decoder] Creating FFmpegFrameGrabber..." }

                grabber = FFmpegFrameGrabber(inputStream).apply {
                    format = "h264"
                    setOption("probesize", "32768")
                    setOption("analyzeduration", "0")
                    setOption("flags", "low_delay")
                    setOption("fflags", "nobuffer")
                }

                logger.info { "[Decoder] Calling start() — waiting for H.264 stream header..." }
                grabber!!.start()
                logger.info { "[Decoder] Grabber started successfully!" }

                var frameCount = 0
                while (isActive) {
                    try {
                        val frame = grabber?.grabImage()
                        if (frame == null) {
                            if (inputStream?.closed == true) {
                                logger.info { "[Decoder] Input stream closed, exiting" }
                                break
                            }
                            continue
                        }

                        val image = converter.convert(frame)
                        if (image != null) {
                            val copy = BufferedImage(image.width, image.height, BufferedImage.TYPE_3BYTE_BGR)
                            val g = copy.createGraphics()
                            g.drawImage(image, 0, 0, null)
                            g.dispose()
                            _decodedFrame.value = copy
                            frameCount++
                            if (frameCount % 60 == 1) {
                                logger.info { "[Decoder] Decoded frame #$frameCount (${copy.width}x${copy.height})" }
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            logger.warn { "[Decoder] Frame decode error: ${e.message}" }
                        }
                    }
                }
                logger.info { "[Decoder] Decode loop ended, total frames: $frameCount" }
            } catch (e: Exception) {
                if (isActive) {
                    logger.error(e) { "[Decoder] Fatal error" }
                }
            }
        }

        started = true
        logger.info { "[Decoder] Initialized" }
    }

    fun feedData(data: ByteArray) {
        if (!started) {
            logger.warn { "[Decoder] feedData called but decoder not started, dropping ${data.size} bytes" }
            return
        }
        inputStream?.enqueue(data)
    }

    fun stop() {
        if (!started) return
        started = false

        inputStream?.close()
        decodeJob?.cancel()
        decodeJob = null

        try {
            grabber?.stop()
            grabber?.release()
        } catch (_: Exception) {}

        grabber = null
        inputStream = null
        _decodedFrame.value = null

        logger.info { "[Decoder] Stopped" }
    }

    fun clearFrame() {
        _decodedFrame.value = null
    }
}
