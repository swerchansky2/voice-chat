package com.voicechat.client.screen

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVCodecParserContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte

private val logger = KotlinLogging.logger {}

class ScreenDecoder {
    private var codecCtx: AVCodecContext? = null
    private var parser: AVCodecParserContext? = null
    private var packet: AVPacket? = null
    private var avFrame: AVFrame? = null
    private var rgbFrame: AVFrame? = null
    private var swsCtx: SwsContext? = null
    private var lastWidth = 0
    private var lastHeight = 0

    private var decodeJob: Job? = null
    private var dataChannel: Channel<ByteArray>? = null
    private var started = false
    private var frameCount = 0L

    private val _decodedFrame = MutableStateFlow<BufferedImage?>(null)
    val decodedFrame: StateFlow<BufferedImage?> = _decodedFrame.asStateFlow()

    fun start(scope: CoroutineScope) {
        if (started) return

        val codec = avcodec_find_decoder(AV_CODEC_ID_H264)
        if (codec == null || codec.isNull) {
            logger.error { "[Decoder] H.264 codec not found!" }
            return
        }

        codecCtx = avcodec_alloc_context3(codec)
        codecCtx!!.thread_count(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        codecCtx!!.flags2(codecCtx!!.flags2() or AV_CODEC_FLAG2_FAST)

        val ret = avcodec_open2(codecCtx, codec, null as AVDictionary?)
        if (ret < 0) {
            logger.error { "[Decoder] avcodec_open2 failed: $ret" }
            avcodec_free_context(codecCtx)
            codecCtx = null
            return
        }

        parser = av_parser_init(AV_CODEC_ID_H264)
        if (parser == null || parser!!.isNull) {
            logger.error { "[Decoder] H.264 parser init failed!" }
            avcodec_free_context(codecCtx)
            codecCtx = null
            return
        }

        packet = av_packet_alloc()
        avFrame = av_frame_alloc()

        dataChannel = Channel(capacity = 300)

        decodeJob = scope.launch(Dispatchers.Default) {
            logger.info { "[Decoder] Decode coroutine started" }
            try {
                for (data in dataChannel!!) {
                    decodeChunk(data)
                }
            } catch (e: CancellationException) {
                // normal shutdown
            } catch (e: Exception) {
                logger.error(e) { "[Decoder] Decode coroutine error" }
            }
            logger.info { "[Decoder] Decode coroutine ended, total frames: $frameCount" }
        }

        started = true
        logger.info { "[Decoder] H.264 direct avcodec decoder ready" }
    }

    fun feedData(data: ByteArray) {
        if (!started || data.isEmpty()) return
        dataChannel?.trySend(data)
    }

    private fun decodeChunk(data: ByteArray) {
        val inputBuf = BytePointer(data.size.toLong())
        try {
            inputBuf.put(data, 0, data.size)
            inputBuf.position(0)

            var remaining = data.size
            var offset = 0L

            val outBuf = PointerPointer<BytePointer>(1)
            val outSize = IntPointer(1L)

            try {
                while (remaining > 0) {
                    outSize.put(0L, 0)

                    val consumed = av_parser_parse2(
                        parser, codecCtx,
                        outBuf, outSize,
                        inputBuf.position(offset), remaining,
                        AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0
                    )

                    if (consumed < 0) {
                        logger.warn { "[Decoder] Parser error: $consumed" }
                        break
                    }

                    offset += consumed
                    remaining -= consumed

                    val parsedSize = outSize.get()
                    if (parsedSize > 0) {
                        val parsedDataPtr = BytePointer(outBuf.get(0))
                        sendPacketAndDecode(parsedDataPtr, parsedSize)
                    }
                }
            } finally {
                outBuf.deallocate()
                outSize.deallocate()
            }
        } finally {
            inputBuf.deallocate()
        }
    }

    private fun sendPacketAndDecode(data: BytePointer, size: Int) {
        av_packet_unref(packet)
        packet!!.data(data)
        packet!!.size(size)

        val sendRet = avcodec_send_packet(codecCtx, packet)
        if (sendRet < 0) return

        while (true) {
            val recvRet = avcodec_receive_frame(codecCtx, avFrame)
            if (recvRet < 0) break

            val image = convertFrame(avFrame!!)
            if (image != null) {
                _decodedFrame.value = image
                frameCount++
                if (frameCount % 120 == 1L) {
                    logger.info { "[Decoder] Frame #$frameCount decoded (${image.width}x${image.height})" }
                }
            }
        }
    }

    private fun convertFrame(frame: AVFrame): BufferedImage? {
        val w = frame.width()
        val h = frame.height()
        if (w <= 0 || h <= 0) return null

        if (w != lastWidth || h != lastHeight) {
            cleanupSwsResources()
            lastWidth = w
            lastHeight = h

            rgbFrame = av_frame_alloc()
            rgbFrame!!.format(AV_PIX_FMT_BGR24)
            rgbFrame!!.width(w)
            rgbFrame!!.height(h)
            av_frame_get_buffer(rgbFrame, 32)

            swsCtx = sws_getContext(
                w, h, frame.format(),
                w, h, AV_PIX_FMT_BGR24,
                SWS_BILINEAR, null, null, null as DoublePointer?
            )
            logger.info { "[Decoder] SWS context created: ${w}x${h}, fmt=${frame.format()}" }
        }

        val srcSlice = PointerPointer<BytePointer>(4)
            .put(0, frame.data(0))
            .put(1, frame.data(1))
            .put(2, frame.data(2))
            .put(3, frame.data(3))

        val srcStride = IntPointer(4L)
            .put(0L, frame.linesize(0))
            .put(1L, frame.linesize(1))
            .put(2L, frame.linesize(2))
            .put(3L, frame.linesize(3))

        val dstSlice = PointerPointer<BytePointer>(4)
            .put(0, rgbFrame!!.data(0))

        val dstStride = IntPointer(4L)
            .put(0L, rgbFrame!!.linesize(0))

        try {
            sws_scale(swsCtx, srcSlice, srcStride, 0, h, dstSlice, dstStride)
        } finally {
            srcSlice.deallocate()
            srcStride.deallocate()
            dstSlice.deallocate()
            dstStride.deallocate()
        }

        val image = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
        val destPixels = (image.raster.dataBuffer as DataBufferByte).data
        val linesize = rgbFrame!!.linesize(0)
        val srcData = rgbFrame!!.data(0)

        for (y in 0 until h) {
            srcData.position(y.toLong() * linesize)
            srcData.get(destPixels, y * w * 3, w * 3)
        }

        return image
    }

    private fun cleanupSwsResources() {
        swsCtx?.let { sws_freeContext(it) }
        swsCtx = null
        rgbFrame?.let { av_frame_free(it) }
        rgbFrame = null
    }

    fun stop() {
        if (!started) return
        started = false

        dataChannel?.close()
        decodeJob?.cancel()
        decodeJob = null
        dataChannel = null

        cleanupSwsResources()
        avFrame?.let { av_frame_free(it) }
        avFrame = null
        packet?.let { av_packet_free(it) }
        packet = null
        parser?.let { av_parser_close(it) }
        parser = null
        codecCtx?.let { avcodec_free_context(it) }
        codecCtx = null

        _decodedFrame.value = null
        frameCount = 0
        lastWidth = 0
        lastHeight = 0
        logger.info { "[Decoder] Stopped and cleaned up" }
    }

    fun clearFrame() {
        _decodedFrame.value = null
    }
}
