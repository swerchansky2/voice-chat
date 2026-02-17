package com.voicechat.client.screen

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int
) {
    private val outputStream = ChunkedOutputStream()
    private var recorder: FFmpegFrameRecorder? = null
    private val converter = Java2DFrameConverter()
    private var frameNumber = 0L
    private var started = false

    fun start() {
        if (started) return

        recorder = FFmpegFrameRecorder(outputStream, width, height).apply {
            videoCodec = avcodec.AV_CODEC_ID_H264
            format = "h264"
            frameRate = fps.toDouble()
            videoBitrate = bitrate
            pixelFormat = avutil.AV_PIX_FMT_YUV420P
            gopSize = fps * 2
            setVideoOption("preset", "ultrafast")
            setVideoOption("tune", "zerolatency")
            setVideoOption("crf", "23")
        }
        recorder!!.start()
        frameNumber = 0
        started = true
        logger.info { "H.264 encoder started: ${width}x${height} @ ${fps}fps, bitrate=$bitrate" }
    }

    fun encode(image: BufferedImage): ByteArray? {
        if (!started) return null

        return try {
            val frame = converter.convert(image)
            frame.timestamp = frameNumber * (1_000_000L / fps)
            recorder?.record(frame)
            frameNumber++
            outputStream.drain()
        } catch (e: Exception) {
            logger.error(e) { "Encoding error" }
            null
        }
    }

    fun stop() {
        if (!started) return
        started = false

        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            logger.error(e) { "Error stopping encoder" }
        }

        recorder = null
        frameNumber = 0
        outputStream.drain()
        logger.info { "H.264 encoder stopped" }
    }
}

class ChunkedOutputStream : ByteArrayOutputStream() {
    private val lock = ReentrantLock()

    override fun write(b: Int) {
        lock.withLock { super.write(b) }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        lock.withLock { super.write(b, off, len) }
    }

    fun drain(): ByteArray? {
        lock.withLock {
            if (count == 0) return null
            val data = toByteArray()
            reset()
            return data
        }
    }
}
