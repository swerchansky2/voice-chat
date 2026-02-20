package com.voicechat.client.webrtc

import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.DataBufferInt
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger("RobotCapture")

class RobotScreenCapture(
    private val videoSource: CustomVideoSource,
    private val captureRegion: Rectangle,
    private val frameRate: Int = 15
) {
    private val robot = Robot()
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.getAndSet(true)) return
        captureThread = Thread(::captureLoop, "robot-capture").apply { isDaemon = true }
        captureThread!!.start()
        logger.info { "[RobotCapture] Started capturing ${captureRegion.width}x${captureRegion.height} @ ${frameRate}fps" }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        captureThread?.join(2000)
        captureThread = null
        logger.info { "[RobotCapture] Stopped" }
    }

    private fun captureLoop() {
        val intervalNs = 1_000_000_000L / frameRate

        while (running.get()) {
            val startNs = System.nanoTime()
            try {
                captureFrame()
            } catch (e: Exception) {
                logger.warn(e) { "[RobotCapture] Frame capture failed" }
            }
            val elapsedNs = System.nanoTime() - startNs
            val sleepNs = intervalNs - elapsedNs
            if (sleepNs > 1_000_000) {
                Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
            }
        }
    }

    private fun captureFrame() {
        val screenshot = robot.createScreenCapture(captureRegion)
        val width = screenshot.width
        val height = screenshot.height

        val pixels = (screenshot.raster.dataBuffer as DataBufferInt).data

        val buffer = NativeI420Buffer.allocate(width, height)
        val yBuf = buffer.dataY
        val uBuf = buffer.dataU
        val vBuf = buffer.dataV
        val yStride = buffer.strideY
        val uStride = buffer.strideU
        val vStride = buffer.strideV

        for (y in 0 until height) {
            val rowOff = y * width
            val yOff = y * yStride
            val isEvenRow = (y and 1) == 0
            val uvRow = (y shr 1) * uStride
            val vvRow = (y shr 1) * vStride

            var x = 0
            while (x < width) {
                val pixel = pixels[rowOff + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                yBuf.put(yOff + x, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())

                if (isEvenRow && (x and 1) == 0) {
                    val uvX = x shr 1
                    uBuf.put(uvRow + uvX, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                    vBuf.put(vvRow + uvX, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                }
                x++
            }
        }

        val frame = VideoFrame(buffer, System.nanoTime())
        videoSource.pushFrame(frame)
        frame.dispose()
    }
}
