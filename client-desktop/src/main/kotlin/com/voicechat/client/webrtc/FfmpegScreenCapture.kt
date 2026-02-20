package com.voicechat.client.webrtc

import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Rectangle
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger("FFmpegCapture")

class FfmpegScreenCapture(
    private val videoSource: CustomVideoSource,
    private val captureRegion: Rectangle,
    private val frameRate: Int = 30
) {
    private var process: Process? = null
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)

    val width = captureRegion.width and 0x7FFFFFFE
    val height = captureRegion.height and 0x7FFFFFFE

    fun start() {
        if (running.getAndSet(true)) return

        val display = System.getenv("DISPLAY") ?: ":0"

        val cmd = listOf(
            "ffmpeg",
            "-f", "x11grab",
            "-video_size", "${width}x${height}",
            "-framerate", frameRate.toString(),
            "-thread_queue_size", "64",
            "-i", "$display+${captureRegion.x},${captureRegion.y}",
            "-pix_fmt", "yuv420p",
            "-f", "rawvideo",
            "-an",
            "-loglevel", "error",
            "pipe:1"
        )

        logger.info { "[FFmpegCapture] Starting: ${cmd.joinToString(" ")}" }

        val pb = ProcessBuilder(cmd)
        pb.redirectError(ProcessBuilder.Redirect.PIPE)
        process = pb.start()

        captureThread = Thread(::readFrames, "ffmpeg-capture").apply { isDaemon = true }
        captureThread!!.start()

        Thread({
            process?.errorStream?.bufferedReader()?.forEachLine { line ->
                if (running.get()) logger.warn { "[FFmpegCapture] stderr: $line" }
            }
        }, "ffmpeg-stderr").apply { isDaemon = true }.start()

        logger.info { "[FFmpegCapture] Started ${width}x${height} @ ${frameRate}fps" }
    }

    private fun readFrames() {
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)
        val frameSize = ySize + uvSize * 2
        val frameData = ByteArray(frameSize)

        val input = process?.inputStream ?: return

        try {
            while (running.get()) {
                if (!readFully(input, frameData, frameSize)) {
                    if (running.get()) logger.warn { "[FFmpegCapture] Unexpected end of stream" }
                    break
                }

                val buffer = NativeI420Buffer.allocate(width, height)
                val yBuf = buffer.dataY
                val uBuf = buffer.dataU
                val vBuf = buffer.dataV
                val yStride = buffer.strideY
                val uStride = buffer.strideU
                val vStride = buffer.strideV

                for (row in 0 until height) {
                    for (col in 0 until width) {
                        yBuf.put(row * yStride + col, frameData[row * width + col])
                    }
                }

                val halfW = width / 2
                val halfH = height / 2
                for (row in 0 until halfH) {
                    for (col in 0 until halfW) {
                        uBuf.put(row * uStride + col, frameData[ySize + row * halfW + col])
                        vBuf.put(row * vStride + col, frameData[ySize + uvSize + row * halfW + col])
                    }
                }

                val frame = VideoFrame(buffer, System.nanoTime())
                videoSource.pushFrame(frame)
                frame.dispose()
            }
        } catch (e: Exception) {
            if (running.get()) logger.warn(e) { "[FFmpegCapture] Error reading frames" }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var offset = 0
        while (offset < len) {
            val read = input.read(buf, offset, len - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        process?.destroy()
        try {
            process?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}
        if (process?.isAlive == true) process?.destroyForcibly()
        captureThread?.join(2000)
        process = null
        captureThread = null
        logger.info { "[FFmpegCapture] Stopped" }
    }

    companion object {
        fun isAvailable(): Boolean {
            return try {
                val p = ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start()
                p.inputStream.bufferedReader().readText()
                p.waitFor()
                p.exitValue() == 0
            } catch (_: Exception) {
                false
            }
        }
    }
}
