package com.voicechat.client.webrtc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.onvoid.webrtc.media.video.VideoFrame
import java.awt.image.BufferedImage

object VideoFrameConverter {

    @Volatile
    private var cachedImage: BufferedImage? = null

    @Volatile
    private var cachedPixels: IntArray? = null

    fun toImageBitmap(frame: VideoFrame): ImageBitmap {
        val buffer = frame.buffer
        val width = buffer.width
        val height = buffer.height

        val i420 = buffer.toI420()
        try {
            val yBuf = i420.dataY
            val uBuf = i420.dataU
            val vBuf = i420.dataV
            val yStride = i420.strideY
            val uStride = i420.strideU
            val vStride = i420.strideV

            val totalPixels = width * height
            val pixels = if (cachedPixels?.size == totalPixels) cachedPixels!! else IntArray(totalPixels)
            cachedPixels = pixels

            for (y in 0 until height) {
                val yRowOffset = y * yStride
                val uvRow = (y shr 1) * uStride
                val vuvRow = (y shr 1) * vStride
                val pixelRow = y * width

                for (x in 0 until width) {
                    val yVal = yBuf[yRowOffset + x].toInt() and 0xFF
                    val uvX = x shr 1
                    val uVal = uBuf[uvRow + uvX].toInt() and 0xFF
                    val vVal = vBuf[vuvRow + uvX].toInt() and 0xFF

                    val c = yVal - 16
                    val d = uVal - 128
                    val e = vVal - 128

                    var r = (298 * c + 409 * e + 128) shr 8
                    var g = (298 * c - 100 * d - 208 * e + 128) shr 8
                    var b = (298 * c + 516 * d + 128) shr 8

                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255

                    pixels[pixelRow + x] = (r shl 16) or (g shl 8) or b
                }
            }

            val image = if (cachedImage?.width == width && cachedImage?.height == height) {
                cachedImage!!
            } else {
                BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { cachedImage = it }
            }

            image.setRGB(0, 0, width, height, pixels, 0, width)
            return image.toComposeImageBitmap()
        } finally {
            i420.release()
        }
    }
}
