package com.khwlab.hub.feature.camera

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException

/**
 * 把 MJPEG 帧(Bitmap)经 MediaCodec(H.264)+ MediaMuxer 封装成 MP4,无音频。
 *
 * 设计:字节缓冲模式 + 手动 Bitmap→NV12 转换(纯 CPU、全设备确定),
 * 不依赖 GPU surface,避免个别设备黑帧;PTS 按真实流逝时间算,播放速度贴近实际。
 *
 * 用法:
 *   val rec = MjpegVideoRecorder(file, width, height)
 *   rec.start()
 *   rec.addFrame(bitmap)   // 帧速率不确定时,内部按需丢帧
 *   rec.stop()             // 返回是否成功;失败时清掉半成品文件
 */
class MjpegVideoRecorder(
    private val outputFile: File,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val targetFps: Int = 15,
    private val bitRate: Int = 4_000_000,
) {

    private val width: Int = sourceWidth - sourceWidth % 2
    private val height: Int = sourceHeight - sourceHeight % 2

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var started = false

    private var startNs = 0L
    private var lastPtsUs = 0L
    private var lastFrameNs = 0L
    private var firstFrame = true

    /** 第一个真正写入 muxer 的样本是否已写(用于强制首样本 KEY_FRAME,见 drainEncoder)。 */
    private var wroteFirstSample = false
    /** stop 时 EOS 是否已成功送进编码器(送不进 = 收尾不完整)。 */
    private var eosQueued = false
    /** 编码器是否已吐出 EOS(全部帧已写出 = 收尾完整)。 */
    private var eosConsumed = false

    /** NV12 缓冲区。多留一行宽,避免整块 UV 写入越界。 */
    private var yuv: ByteArray? = null
    private var pixels: IntArray? = null

    fun start() {
        check(!started) { "recorder already started" }
        require(width > 0 && height > 0) { "bad frame size ${width}x$height" }
        require(targetFps in 1..60) { "bad fps $targetFps" }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            @Suppress("DEPRECATION") // COLOR_FormatYUV420SemiPlanar 仍被全系编码器支持,字节缓冲模式下是最简可靠选择
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        this.codec = codec

        // muxer 只创建,不 start;轨道注册与 muxer.start() 必须等到编码器
        // 通过 INFO_OUTPUT_FORMAT_CHANGED 交出带 csd 的输出格式后做,
        // 否则 addTrack 拿到空格式 → MP4 无 moov/损坏,writeSampleData 崩(trackIndex=-1)。
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        this.muxer = muxer

        codec.start()
        started = true
        firstFrame = true
        wroteFirstSample = false
        eosQueued = false
        eosConsumed = false
        startNs = System.nanoTime()
        lastFrameNs = startNs
        lastPtsUs = 0
        yuv = ByteArray(width * height * 3 / 2 + width)
        pixels = IntArray(width * height)
    }

    /** 喂一帧。帧率高于 targetFps 时按比例丢帧,低于则正常记录。@Synchronized 防止与 stop() 并发。 */
    @Synchronized
    fun addFrame(bitmap: Bitmap) {
        if (!started) return // stop() 后(或未 start)的帧直接丢弃,不抛异常
        val nowNs = System.nanoTime()
        // 限流:不到 1/targetFps 间隔的帧丢弃(画面接近实时即可,不必每帧都编)
        val minGapNs = 1_000_000_000L / targetFps
        if (nowNs - lastFrameNs < minGapNs) return
        lastFrameNs = nowNs

        val bmp = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            // 尺寸不是偶数或不一致时缩放补齐(保持编码器要求;width/height 已在构造时取偶)
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        try {
            val arr = pixels ?: return // 编码器未 start()(理论上 check 已挡),丢弃该帧
            bmp.getPixels(arr, 0, width, 0, 0, width, height)
            encodeFrame(arr)
        } finally {
            if (bmp !== bitmap) bmp.recycle()
        }
    }

    private fun encodeFrame(argb: IntArray) {
        val c = codec ?: return
        val yuv = yuv ?: return
        rgbToNv12(argb, yuv, width, height)
        var ptsUs = (System.nanoTime() - startNs) / 1000
        if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1_000 // 保证单调递增
        lastPtsUs = ptsUs

        val inIdx = c.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val inBuf = c.getInputBuffer(inIdx)
            if (inBuf == null) {
                c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                return
            }
            inBuf.clear()
            inBuf.put(yuv, 0, width * height * 3 / 2)
            // 首帧标 KEY_FRAME:兜底编码器首个输出样本的 sync-sample
            // (有些编码器首输出不带 KEY_FRAME,MP4 会缺关键帧,被部分播放器判损坏)。
            val flags = if (firstFrame) {
                firstFrame = false
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            c.queueInputBuffer(inIdx, 0, width * height * 3 / 2, ptsUs, flags)
        }
        drainEncoder(endOfStream = false)
    }

    /** 结束录制:送 EOS,排空编码器,收尾 muxer。 */
    @Synchronized
    fun stop(): Boolean {
        if (!started) return false
        started = false
        var ok = true
        val r = codec
        if (r == null) {
            ok = false
        } else {
            try {
                if (!eosQueued) {
                    val inIdx = r.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        r.queueInputBuffer(
                            inIdx, 0, 0, lastPtsUs + 1_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosQueued = true
                    } else {
                        ok = false // 拿不到输入缓冲就送不进 EOS,收尾不完整
                    }
                }
            } catch (e: Exception) {
                ok = false
            }
            if (eosQueued) {
                // 排空到编码器吐出 EOS 或超时(别无限等)
                stopDeadline = System.currentTimeMillis() + 5000
                var drained = false
                while (!drained && !eosConsumed && System.currentTimeMillis() < stopDeadline) {
                    try {
                        drained = drainEncoder(endOfStream = true)
                    } catch (e: Exception) {
                        ok = false
                        break
                    }
                }
                if (!eosConsumed) ok = false // EOS 没被消费 = 有帧还压在编码器里
            }
        }
        val keep = ok && muxerRunning && trackIndex >= 0
        release() // 先落 moov 再删(失败时文件已残留,这里直接删掉)
        if (!keep) {
            try {
                outputFile.delete()
            } catch (_: Exception) {
            }
            return false
        }
        return true
    }

    private var muxerRunning = false

    /**
     * 从编码器取出输出缓冲并写入 muxer。
     * @return 本次调用是否"稳定排空":EOS 模式下一直排到编码器吐出 EOS(或超时),
     *         否则尽快返回避免阻塞录像协程。
     */
    private fun drainEncoder(endOfStream: Boolean): Boolean {
        val c = codec ?: return true
        val m = muxer ?: return true
        val info = MediaCodec.BufferInfo()
        var draining = endOfStream
        var stable = true
        // 无 EOS 时每次排空几轮即可;有 EOS 时一直等到编码器吐出 EOS 或超时
        var loops = 0
        while (draining || loops < 3) {
            loops++
            val outIdx = c.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return true
                    // EOS 模式:不能提前返回,继续等;超时由 stop() 的 deadline 兜底
                    if (System.currentTimeMillis() > stopDeadline) return false
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 此刻才拿到带 csd 的最终输出格式:注册轨道并启动 muxer
                    if (!muxerRunning) {
                        trackIndex = m.addTrack(c.outputFormat)
                        m.start()
                        muxerRunning = true
                    }
                }
                outIdx >= 0 -> {
                    val outBuf = c.getOutputBuffer(outIdx)
                    if (outBuf != null) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && muxerRunning && trackIndex >= 0) {
                            // 第一个写入样本强制带 KEY_FRAME:保证 MP4 有 sync-sample。
                            if (!wroteFirstSample) {
                                wroteFirstSample = true
                                info.flags = info.flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                            }
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            m.writeSampleData(trackIndex, outBuf, info)
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        draining = false
                        eosConsumed = true
                    }
                }
                else -> if (!endOfStream) return true
            }
        }
        return stable
    }

    private var stopDeadline = 0L

    private fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            muxer?.stop()
        } catch (_: Exception) {
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        trackIndex = -1
        muxerRunning = false
        yuv = null
        pixels = null
    }

    companion object {
        /** ARGB_8888 → NV12(BT.601 全范围,与 esp32-camera RGB565 解码结果一致)。 */
        private fun rgbToNv12(argb: IntArray, out: ByteArray, w: Int, h: Int) {
            val uvBase = w * h
            var i = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val px = argb[i]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF

                    val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    out[i] = clampByte(yy)

                    // UV 每 2×2 块取一次(块左上写整块)
                    if ((x and 1) == 0 && (y and 1) == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        val uv = uvBase + (y shr 1) * w + x
                        out[uv] = clampByte(u)
                        out[uv + 1] = clampByte(v)
                    }
                    i++
                }
            }
        }

        private fun clampByte(v: Int): Byte = when {
            v < 0 -> 0
            v > 255 -> 255
            else -> v
        }.toByte()
    }
}
