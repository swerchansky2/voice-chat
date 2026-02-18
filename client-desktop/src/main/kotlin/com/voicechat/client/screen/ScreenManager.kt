package com.voicechat.client.screen

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.voicechat.client.network.VideoUdpClient
import com.voicechat.shared.protocol.VideoPacket
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger("ScreenManager")

class ScreenManager(
    private val scope: CoroutineScope,
    private val videoUdpClient: VideoUdpClient
) {
    private val _receivedFrame = MutableStateFlow<ImageBitmap?>(null)
    val receivedFrame: StateFlow<ImageBitmap?> = _receivedFrame.asStateFlow()

    private val sending = AtomicBoolean(false)
    private val receiving = AtomicBoolean(false)

    private var encoderThread: Thread? = null
    private var receiverJob: Job? = null

    init {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
    }

    // ── Sender ───────────────────────────────────────────────────────────────

    fun startSending(userId: String, settings: ScreenShareSettings) {
        if (sending.getAndSet(true)) return
        encoderThread = Thread({ runEncoder(userId, settings) }, "ScreenEncoder").also {
            it.isDaemon = true
            it.start()
        }
        logger.info { "[Sender] Started H.264 capture: ${settings.resolution.width}x${settings.resolution.height} @ ${settings.fps}fps" }
    }

    private fun runEncoder(userId: String, settings: ScreenShareSettings) {
        val width = settings.resolution.width
        val height = settings.resolution.height
        val fps = settings.fps

        // DrainableOutputStream collects encoded bytes after each recorder.record() call.
        val drainable = DrainableOutputStream()

        val recorder = FFmpegFrameRecorder(drainable, width, height).apply {
            videoCodec = AV_CODEC_ID_H264
            frameRate = fps.toDouble()
            format = "h264"   // raw H.264 elementary stream, no MPEG-TS container
            setVideoOption("preset", "ultrafast")
            setVideoOption("tune", "zerolatency")
            setVideoOption("crf", "28")
            // Repeat SPS+PPS before every IDR — makes each IDR frame self-contained
            setVideoOption("x264opts", "repeat-headers=1")
            // All-IDR: every frame is a keyframe so viewers never depend on previous frames
            setVideoOption("g", "1")
            setVideoOption("keyint_min", "1")
            setVideoOption("sc_threshold", "0")
            setOption("flush_packets", "1")
        }

        try {
            recorder.start()
            drainable.drain()  // discard any initialization bytes written by avformat_write_header
            logger.info { "[Sender] FFmpegFrameRecorder started (format=h264, all-IDR)" }

            val robot = Robot()
            val screenRect = Rectangle(Toolkit.getDefaultToolkit().screenSize)
            val scaledBuf = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
            val converter = Java2DFrameConverter()
            val intervalMs = 1000L / fps.coerceAtLeast(1)
            val frameId = AtomicInteger(0)

            var frameCount = 0
            while (sending.get()) {
                val t0 = System.currentTimeMillis()

                val screenshot = robot.createScreenCapture(screenRect)
                val frameImg = if (screenshot.width == width && screenshot.height == height) {
                    screenshot
                } else {
                    val g = scaledBuf.createGraphics()
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g.drawImage(screenshot, 0, 0, width, height, null)
                    g.dispose()
                    scaledBuf
                }

                recorder.record(converter.convert(frameImg))
                val frameBytes = drainable.drain()

                if (frameBytes.isNotEmpty()) {
                    val fid = frameId.getAndIncrement()
                    fragmentAndSend(frameBytes, fid, userId)
                    frameCount++
                    if (frameCount == 1 || frameCount % 60 == 0) {
                        logger.info { "[Sender] Frame $frameCount sent (${frameBytes.size} bytes, ${(frameBytes.size + VideoPacket.MAX_PAYLOAD - 1) / VideoPacket.MAX_PAYLOAD} frags)" }
                    }
                }

                val sleep = intervalMs - (System.currentTimeMillis() - t0)
                if (sleep > 0) Thread.sleep(sleep)
            }
        } catch (e: Exception) {
            if (sending.get()) logger.error(e) { "[Sender] Encoder error" }
        } finally {
            try {
                recorder.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun fragmentAndSend(frameBytes: ByteArray, frameId: Int, senderId: String) {
        val maxPayload = VideoPacket.MAX_PAYLOAD
        val totalFrags = (frameBytes.size + maxPayload - 1) / maxPayload
        for (i in 0 until totalFrags) {
            val start = i * maxPayload
            val end = minOf(start + maxPayload, frameBytes.size)
            val payload = frameBytes.copyOfRange(start, end)
            videoUdpClient.sendPacket(VideoPacket(senderId, frameId, i, totalFrags, payload))
        }
    }

    fun stopSending() {
        if (!sending.getAndSet(false)) return
        encoderThread?.interrupt()
        encoderThread = null
        logger.info { "[Sender] Stopped screen sharing" }
    }

    // ── Receiver ─────────────────────────────────────────────────────────────

    fun startReceiving(sharerUserId: String) {
        if (receiving.getAndSet(true)) return

        // Fragment reassembly: frameId → array of payload chunks
        val frameFrags = LinkedHashMap<Int, Array<ByteArray?>>(64, 0.75f, true)
        val converter = Java2DFrameConverter()

        receiverJob = scope.launch(Dispatchers.IO) {
            logger.info { "[Receiver] Collecting video from $sharerUserId" }
            videoUdpClient.receivedPackets.collect { pkt ->
                if (!receiving.get()) return@collect
                if (pkt.senderId != sharerUserId) return@collect

                // Store fragment
                val frags = frameFrags.getOrPut(pkt.frameId) { arrayOfNulls(pkt.totalFrags) }
                if (pkt.fragIdx < frags.size) frags[pkt.fragIdx] = pkt.payload

                // Evict frames too old to be useful (keep at most 30 in flight)
                if (frameFrags.size > 30) {
                    val it = frameFrags.iterator()
                    if (it.hasNext()) {
                        it.next(); it.remove()
                    }
                }

                // Check if this frame is complete
                if (frags.all { it != null }) {
                    frameFrags.remove(pkt.frameId)
                    val assembled = reassemble(frags.filterNotNull())
                    val bitmap = decodeFrame(assembled, converter)
                    if (bitmap != null) _receivedFrame.value = bitmap
                }
            }
        }
        logger.info { "[Receiver] Started H.264 decoder (per-frame)" }
    }

    private fun reassemble(frags: List<ByteArray>): ByteArray {
        val total = frags.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (frag in frags) {
            frag.copyInto(out, pos)
            pos += frag.size
        }
        return out
    }

    /**
     * Decode a single self-contained H.264 frame (SPS+PPS+IDR or P-frame NAL units).
     * Uses a fresh FFmpegFrameGrabber per frame — since ByteArrayInputStream is non-blocking,
     * grabber.start() returns immediately after reading all available bytes.
     * This avoids the MPEG-TS analyzeduration hang completely.
     */
    private fun decodeFrame(frameBytes: ByteArray, converter: Java2DFrameConverter): ImageBitmap? {
        if (frameBytes.isEmpty()) return null
        var grabber: FFmpegFrameGrabber? = null
        return try {
            grabber = FFmpegFrameGrabber(ByteArrayInputStream(frameBytes)).apply {
                format = "h264"
            }
            grabber.start()
            val frame = grabber.grabImage() ?: return null
            converter.convert(frame)?.toComposeImageBitmap()
        } catch (e: Exception) {
            logger.debug { "[Receiver] Frame decode error: ${e.message}" }
            null
        } finally {
            try {
                grabber?.close()
            } catch (_: Exception) {
            }
        }
    }

    fun stopReceiving() {
        if (!receiving.getAndSet(false)) return
        receiverJob?.cancel()
        receiverJob = null
        _receivedFrame.value = null
        logger.info { "[Receiver] Stopped" }
    }

    fun clearReceivedFrame() {
        _receivedFrame.value = null
    }

    fun dispose() {
        stopSending()
        stopReceiving()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * An OutputStream whose bytes can be drained atomically after each encode call.
     * The FFmpegFrameRecorder writes encoded NAL units here; [drain] collects and resets the buffer.
     */
    private class DrainableOutputStream : OutputStream() {
        private val buf = ByteArrayOutputStream(256 * 1024)

        override fun write(b: Int) = buf.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)

        @Synchronized
        fun drain(): ByteArray {
            val bytes = buf.toByteArray()
            buf.reset()
            return bytes
        }
    }
}