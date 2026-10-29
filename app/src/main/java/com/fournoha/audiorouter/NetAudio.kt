package com.fournoha.audiorouter

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioRouter ↔ Android のパケット仕様。Mac 側
 * `audio-router/Sources/AudioRouterCore/NetAudioProtocol.swift` と
 * **byte 一致**で保つ。仕様変更は両側同時。
 *
 * パケット = 22 byte header + payload。すべて big-endian。
 *
 * ```
 *   0   4  magic  = "4NHA" (0x34 0x4E 0x48 0x41)
 *   4   1  version = 1
 *   5   1  type    (0=audio, 1=hello, 2=bye, 3=ping)
 *   6   1  slot    (1..4)
 *   7   1  codec   (0=pcm16le, 1=opus)
 *   8   4  seq     uint32 BE
 *  12   8  ts_us   int64 BE  (送信側 monotonic μs)
 *  20   2  payload_len uint16 BE
 *  22   N  payload
 * ```
 */
object NetAudio {
    val MAGIC = byteArrayOf(0x34, 0x4E, 0x48, 0x41)
    const val VERSION: Byte = 1
    const val HEADER_BYTES = 22
    const val MAX_PACKET_BYTES = 1400

    enum class PacketType(val raw: Byte) {
        AUDIO(0), HELLO(1), BYE(2), PING(3);
        companion object {
            fun fromRaw(b: Byte): PacketType? = entries.firstOrNull { it.raw == b }
        }
    }
    enum class Codec(val raw: Byte) {
        PCM16LE(0), OPUS(1);
        companion object {
            fun fromRaw(b: Byte): Codec? = entries.firstOrNull { it.raw == b }
        }
    }

    data class Header(
        val type: PacketType,
        val slot: Byte,
        val codec: Codec,
        val seq: Int,        // unsigned uint32 として扱う（負値は & 0xFFFFFFFF）
        val tsUs: Long,
        val payloadLen: Int
    )

    /** header + payload を 1 つの ByteArray にまとめる。 */
    fun encode(h: Header, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.put(MAGIC)
        bb.put(VERSION)
        bb.put(h.type.raw)
        bb.put(h.slot)
        bb.put(h.codec.raw)
        bb.putInt(h.seq)
        bb.putLong(h.tsUs)
        bb.putShort(h.payloadLen.toShort())
        bb.put(payload)
        return out
    }

    /** バッファから header と payload を切り出す。失敗で null。*/
    fun decode(buf: ByteArray, length: Int): Pair<Header, ByteArray>? {
        if (length < HEADER_BYTES) return null
        if (buf[0] != MAGIC[0] || buf[1] != MAGIC[1]
            || buf[2] != MAGIC[2] || buf[3] != MAGIC[3]) return null
        if (buf[4] != VERSION) return null
        val type = PacketType.fromRaw(buf[5]) ?: return null
        val codec = Codec.fromRaw(buf[7]) ?: return null
        val slot = buf[6]
        val bb = ByteBuffer.wrap(buf, 8, 14).order(ByteOrder.BIG_ENDIAN)
        val seq = bb.int
        val tsUs = bb.long
        val plen = bb.short.toInt() and 0xFFFF
        if (HEADER_BYTES + plen > length) return null
        val payload = buf.copyOfRange(HEADER_BYTES, HEADER_BYTES + plen)
        return Header(type, slot, codec, seq, tsUs, plen) to payload
    }

    fun nowMicros(): Long = System.nanoTime() / 1_000L
}
