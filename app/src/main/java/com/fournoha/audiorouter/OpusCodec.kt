package com.fournoha.audiorouter

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android の MediaCodec を使った Opus encoder/decoder ラッパ。mono 48k。
 *
 * - Opus encoder: 48k mono, frame_size = 960 (20ms)、bitrate 32k（VOIP相当）
 * - Opus decoder: raw Opus packet を受け取り、PCM Short を返す
 *
 * MediaCodec の MIME は `audio/opus`。Android 10+ で encoder/decoder 両対応。
 *
 * 注: MediaCodec の Opus packet 入出力は **TOC byte 付きの生 Opus packet**
 * (RFC 6716)。Mac 側 libopus とそのまま byte 一致する。OggOpus などの
 * コンテナは噛まない。
 */
class OpusEncoderWrap(
    bitrate: Int = 32_000,
    private val frameSamples: Int = 960
) {
    val mime = "audio/opus"
    private val codec: MediaCodec
    private var inputBufferReady = false

    init {
        codec = MediaCodec.createEncoderByType(mime)
        val fmt = MediaFormat.createAudioFormat(mime, 48_000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_PCM_ENCODING, 2)   // ENCODING_PCM_16BIT
            // Opus は AUDIO/VOIP 区別を Android MediaCodec では指定できない
            // ことが多いので、bitrate でグレード調整。
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /**
     * `pcm16` は frame_size 個の Short (mono 48k 16bit LE)。
     * 戻り値は encoded byte 列（複数フレームまとまる可能性あり、
     * 通常は 1 フレーム = 1 packet）。
     */
    fun encode(pcm16: ShortArray): List<ByteArray> {
        val packets = ArrayList<ByteArray>(2)
        val inIdx = codec.dequeueInputBuffer(20_000)
        if (inIdx >= 0) {
            val ib = codec.getInputBuffer(inIdx)!!
            ib.clear()
            val sb = ib.asShortBuffer()
            sb.put(pcm16)
            codec.queueInputBuffer(inIdx, 0, pcm16.size * 2,
                NetAudio.nowMicros(), 0)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, 0)
            if (outIdx < 0) break
            val ob = codec.getOutputBuffer(outIdx)!!
            // CSD-0/1 (init data) は無視。
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                && info.size > 0) {
                val out = ByteArray(info.size)
                ob.position(info.offset)
                ob.get(out, 0, info.size)
                packets.add(out)
            }
            codec.releaseOutputBuffer(outIdx, false)
        }
        return packets
    }

    fun stop() {
        try { codec.stop() } catch (_: Throwable) {}
        codec.release()
    }
}

class OpusDecoderWrap(
    private val maxFrameSamples: Int = 5760
) {
    val mime = "audio/opus"
    private val codec: MediaCodec

    init {
        codec = MediaCodec.createDecoderByType(mime)
        val fmt = MediaFormat.createAudioFormat(mime, 48_000, 1).apply {
            // CSD-0: Opus identification header (19 byte) — Xiph spec で
            // multibyte 数値はリトルエンディアン。
            val csd0 = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            csd0.put("OpusHead".toByteArray())
            csd0.put(1.toByte())             // version
            csd0.put(1.toByte())             // channels
            csd0.putShort(3840)              // preskip = 3840 samples (80ms@48k)
            csd0.putInt(48_000)              // original samplerate
            csd0.putShort(0)                 // output gain
            csd0.put(0)                      // channel mapping family
            csd0.flip()
            setByteBuffer("csd-0", csd0)
            // CSD-1: codec delay (ns)。Android MediaCodec は **ネイティブ
            // バイト順 (LE)** の int64 を要求。ByteBuffer 既定は BE なので
            // 明示的に LITTLE_ENDIAN を指定しないと壊れて無音になる。
            val csd1 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            csd1.putLong(80_000_000L)        // 80ms in ns
            csd1.flip()
            setByteBuffer("csd-1", csd1)
            // CSD-2: seek preroll (ns)。同上 LE。
            val csd2 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            csd2.putLong(80_000_000L)
            csd2.flip()
            setByteBuffer("csd-2", csd2)
        }
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    /** opus packet を decode して PCM Short 列を返す。失敗時は空配列。*/
    fun decode(opus: ByteArray): ShortArray {
        val inIdx = codec.dequeueInputBuffer(20_000)
        if (inIdx >= 0) {
            val ib = codec.getInputBuffer(inIdx)!!
            ib.clear()
            ib.put(opus)
            codec.queueInputBuffer(inIdx, 0, opus.size,
                NetAudio.nowMicros(), 0)
        }
        val info = MediaCodec.BufferInfo()
        val collected = ArrayList<Short>(maxFrameSamples)
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, 0)
            if (outIdx < 0) break
            val ob = codec.getOutputBuffer(outIdx)!!
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                && info.size > 0) {
                ob.position(info.offset)
                val sb = ob.asShortBuffer()
                while (sb.hasRemaining()) collected.add(sb.get())
            }
            codec.releaseOutputBuffer(outIdx, false)
        }
        return collected.toShortArray()
    }

    fun stop() {
        try { codec.stop() } catch (_: Throwable) {}
        codec.release()
    }
}
