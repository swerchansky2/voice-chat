package com.voicechat.client.screen

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage

private val logger = KotlinLogging.logger {}

class ScreenCapture {
    private var captureJob: Job? = null
    private val robot = Robot()

    private val _frames = MutableSharedFlow<BufferedImage>(extraBufferCapacity = 2)
    val frames: SharedFlow<BufferedImage> = _frames.asSharedFlow()

    fun start(scope: CoroutineScope, maxWidth: Int, maxHeight: Int, fps: Int) {
        stop()

        val frameIntervalMs = 1000L / fps

        captureJob = scope.launch(Dispatchers.IO) {
            logger.info { "Screen capture started: max ${maxWidth}x${maxHeight} @ ${fps}fps" }

            val screenSize = Toolkit.getDefaultToolkit().screenSize
            val captureRect = Rectangle(screenSize)

            while (isActive) {
                val startTime = System.currentTimeMillis()

                try {
                    val screenshot = robot.createScreenCapture(captureRect)
                    val scaled = scaleImage(screenshot, maxWidth, maxHeight)
                    _frames.tryEmit(scaled)
                } catch (e: Exception) {
                    if (isActive) {
                        logger.error(e) { "Screen capture error" }
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                val sleepTime = frameIntervalMs - elapsed
                if (sleepTime > 0) {
                    delay(sleepTime)
                }
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        logger.info { "Screen capture stopped" }
    }

    private fun scaleImage(image: BufferedImage, maxWidth: Int, maxHeight: Int): BufferedImage {
        val origWidth = image.width
        val origHeight = image.height

        if (origWidth <= maxWidth && origHeight <= maxHeight) {
            return image
        }

        val scaleX = maxWidth.toDouble() / origWidth
        val scaleY = maxHeight.toDouble() / origHeight
        val scale = minOf(scaleX, scaleY)

        val newWidth = (origWidth * scale).toInt().let { if (it % 2 != 0) it + 1 else it }
        val newHeight = (origHeight * scale).toInt().let { if (it % 2 != 0) it + 1 else it }

        val scaled = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_3BYTE_BGR)
        val g = scaled.createGraphics()
        g.drawImage(image, 0, 0, newWidth, newHeight, null)
        g.dispose()

        return scaled
    }
}
