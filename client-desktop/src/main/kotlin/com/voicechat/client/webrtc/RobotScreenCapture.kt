package com.voicechat.client.webrtc

import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger("RobotCapture")

class RobotScreenCapture(
    private val videoSource: CustomVideoSource,
    private val captureRegion: Rectangle,
    private val frameRate: Int = 10
) {
    private val robot = Robot()
    private var executor: ScheduledExecutorService? = null
    private val running = AtomicBoolean(false)

    private var cachedPixels: IntArray? = null

    fun start() {
        if (running.getAndSet(true)) return
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "robot-capture").apply { isDaemon = true }
        }
        val periodMs = 1000L / frameRate
        executor!!.scheduleAtFixedRate(::captureFrame, 0, periodMs, TimeUnit.MILLISECONDS)
        logger.info { "[RobotCapture] Started capturing ${captureRegion.width}x${captureRegion.height} @ ${frameRate}fps" }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        executor?.shutdown()
        try {
            executor?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}
        executor = null
        logger.info { "[RobotCapture] Stopped" }
    }

    private fun captureFrame() {
        if (!running.get()) return
        try {
            val screenshot = robot.createScreenCapture(captureRegion)
            val width = screenshot.width
            val height = screenshot.height

            val buffer = NativeI420Buffer.allocate(width, height)
            rgbToI420(screenshot, buffer)

            val frame = VideoFrame(buffer, System.nanoTime())
            videoSource.pushFrame(frame)
            frame.dispose()
        } catch (e: Exception) {
            logger.warn(e) { "[RobotCapture] Frame capture failed" }
        }
    }

    private fun rgbToI420(image: BufferedImage, buffer: NativeI420Buffer) {
        val width = image.width
        val height = image.height

        val totalPixels = width * height
        val pixels = if (cachedPixels?.size == totalPixels) cachedPixels!! else IntArray(totalPixels)
        cachedPixels = pixels
        image.getRGB(0, 0, width, height, pixels, 0, width)

        val yBuf = buffer.dataY
        val uBuf = buffer.dataU
        val vBuf = buffer.dataV
        val yStride = buffer.strideY
        val uStride = buffer.strideU
        val vStride = buffer.strideV

        for (y in 0 until height) {
            val rowOffset = y * width
            val yBufOffset = y * yStride
            val isEvenRow = (y and 1) == 0
            val uvRowOffset = if (isEvenRow) (y shr 1) * uStride else -1

            for (x in 0 until width) {
                val pixel = pixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(yBufOffset + x, yVal.coerceIn(0, 255).toByte())

                if (isEvenRow && (x and 1) == 0) {
                    val uvX = x shr 1
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uBuf.put(uvRowOffset + uvX, uVal.coerceIn(0, 255).toByte())
                    vBuf.put((y shr 1) * vStride + uvX, vVal.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
