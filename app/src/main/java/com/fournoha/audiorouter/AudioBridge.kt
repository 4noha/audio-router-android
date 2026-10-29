package com.fournoha.audiorouter

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Android ↔ Mac の双方向 UDP オーディオブリッジ。**1 つの DatagramSocket
 * を送受信で共有**する点が肝。
 *
 *  - 送信: AudioRecord (mono 48k 16bit) → Opus encode → UDP send
 *  - 受信: UDP recv → Opus decode → AudioTrack 再生
 *  - 接続宣言: 開始時に HELLO（payload = 端末名）を送出し、paired まで
 *    2 秒毎、以後 10 秒毎に再送。Mac が端末名から slot を割当てて
 *    hello-ack (type=HELLO, payload 先頭 2 byte = 端末専用 port) を返すと、
 *    その port へ切替えて `onPaired(slot)` を 1 度だけ発火する。
 *
 * socket は connect しない（多 NIC 端末で不適切な NIC に bind されるのを
 * 避ける）。送信は毎回 DatagramPacket で宛先を明示し、受信は送信元
 * アドレスが接続先 Mac のものだけを通す。
 */
class AudioBridge(
    private val host: InetAddress,
    initialPort: Int,
    private val deviceName: String,
    private val onPaired: (Byte) -> Unit,   // 引数 = Mac から割当された slot
    private val onError: (Throwable) -> Unit = {}
) {
    private val tag = "AudioBridge"
    private val frameSamples = 960   // 20ms @48k

    @Volatile private var running = false
    @Volatile private var paired = false
    // Mac から hello-ack で通知された割当 slot。0 = 未割当 (audio 送信抑止)。
    @Volatile private var assignedSlot: Byte = 0
    // 現在 send/recv に使う Mac ポート。最初は discovery port、hello-ack で
    // 端末専用 port (= 47391 + slot) に切替わる。
    @Volatile private var port: Int = initialPort
    private val rxLogCount = AtomicInteger(0)
    private var socket: DatagramSocket? = null
    private var sendThread: Thread? = null
    private var recvThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true

        // socket は connect しない（Android 多 NIC 環境で connect すると
        // 不適切な NIC にバインドされる事案を回避）。送信は毎回
        // DatagramPacket(host, port) で宛先を明示する。受信側は
        // pkt.address でフィルタするだけにする。
        val sock = try {
            DatagramSocket().apply {
                soTimeout = 200    // recv ループの停止判定用
                broadcast = false
            }
        } catch (t: Throwable) {
            onError(t); running = false; return
        }
        socket = sock

        // **HELLO を最優先で送る**。payload は端末名 UTF-8。Mac 側が
        // 端末名から空き slot を割当て、hello-ack の slot で通知してくる。
        // paired=true になるまで 2秒毎、その後も 10秒毎の keep-alive。
        try {
            val hello = makeHello(seq = 0)
            sock.send(DatagramPacket(hello, hello.size, host, port))
            Log.i(tag, "initial HELLO sent to $host:$port name='$deviceName' (${hello.size}b)")
        } catch (t: Throwable) {
            Log.w(tag, "initial HELLO send failed", t)
        }
        thread(name = "AudioBridge-hello", isDaemon = true) {
            var n = 1
            while (running) {
                val interval = if (paired) 10_000L else 2_000L
                Thread.sleep(interval)
                if (!running) break
                try {
                    val hello = makeHello(seq = n)
                    sock.send(DatagramPacket(hello, hello.size, host, port))
                    n++
                } catch (t: Throwable) {
                    Log.w(tag, "HELLO retry send failed", t)
                }
            }
        }

        // 受信スレッド。最初に何か来たら paired 通知。
        recvThread = thread(name = "AudioBridge-recv", isDaemon = true) {
            var decoder: OpusDecoderWrap? = null
            var track: AudioTrack? = null
            try {
                decoder = OpusDecoderWrap()
                val minBuf = AudioTrack.getMinBufferSize(
                    48_000,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                    .setBufferSizeInBytes(maxOf(minBuf, frameSamples * 2 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                val buf = ByteArray(NetAudio.MAX_PACKET_BYTES)
                val pkt = DatagramPacket(buf, buf.size)
                while (running) {
                    try {
                        sock.receive(pkt)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    } catch (t: Throwable) {
                        if (running) Log.e(tag, "recv error", t)
                        break
                    }
                    // connect していないので、宛先 Mac 以外のパケットは無視。
                    if (pkt.address != host) continue
                    val decoded = NetAudio.decode(buf, pkt.length) ?: continue
                    val (h, payload) = decoded
                    if (rxLogCount.getAndIncrement() < 8 ||
                        rxLogCount.get() % 100 == 0) {
                        Log.i(tag,
                            "rx type=${h.type} slot=${h.slot} codec=${h.codec} " +
                            "seq=${h.seq} plen=${h.payloadLen}")
                    }
                    // hello-ack: Mac が割当てた slot + 専用 port で送信開始。
                    // payload = [port BE u16][Mac 名 UTF-8]
                    if (h.type == NetAudio.PacketType.HELLO) {
                        assignedSlot = h.slot
                        if (payload.size >= 2) {
                            val newPort = ((payload[0].toInt() and 0xff) shl 8) or
                                          (payload[1].toInt() and 0xff)
                            if (newPort in 1..65535 && newPort != port) {
                                Log.i(tag, "switching port $port -> $newPort")
                                port = newPort
                            }
                        }
                        if (!paired) {
                            paired = true
                            Log.i(tag, "paired, assigned slot=${h.slot}")
                            try { onPaired(h.slot) } catch (_: Throwable) {}
                        }
                        continue
                    }
                    if (h.type != NetAudio.PacketType.AUDIO) continue
                    val pcm = when (h.codec) {
                        NetAudio.Codec.OPUS -> {
                            try { decoder.decode(payload) }
                            catch (_: Throwable) { ShortArray(0) }
                        }
                        NetAudio.Codec.PCM16LE -> {
                            val s = ShortArray(payload.size / 2)
                            for (i in s.indices) {
                                val lo = payload[i * 2].toInt() and 0xFF
                                val hi = payload[i * 2 + 1].toInt() and 0xFF
                                s[i] = ((hi shl 8) or lo).toShort()
                            }
                            s
                        }
                    }
                    if (pcm.isNotEmpty()) {
                        try { track.write(pcm, 0, pcm.size) } catch (_: Throwable) {}
                    }
                }
            } catch (t: Throwable) {
                if (running) onError(t)
            } finally {
                try { track?.stop() } catch (_: Throwable) {}
                try { track?.release() } catch (_: Throwable) {}
                try { decoder?.stop() } catch (_: Throwable) {}
            }
        }

        // 送信スレッド。AudioRecord → Opus → send。
        sendThread = thread(name = "AudioBridge-send", isDaemon = true) {
            var record: AudioRecord? = null
            var encoder: OpusEncoderWrap? = null
            try {
                val bufSize = AudioRecord.getMinBufferSize(
                    48_000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT)
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    48_000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(bufSize, frameSamples * 2 * 4))
                encoder = OpusEncoderWrap(bitrate = 32_000, frameSamples = frameSamples)
                // HELLO は start() 冒頭で送信 + 別スレッドが paired まで再送中。
                record.startRecording()
                val pcm = ShortArray(frameSamples)
                var seq = 0
                while (running) {
                    var read = 0
                    while (read < frameSamples && running) {
                        val r = record.read(pcm, read, frameSamples - read)
                        if (r <= 0) { Thread.sleep(2); continue }
                        read += r
                    }
                    if (read < frameSamples) continue
                    // 未割当 slot=0 だと Mac が捨てるのでスキップ。Mac の
                    // hello-ack が返るまでマイク入力は破棄される（数百ms）。
                    val slot = assignedSlot
                    if (slot.toInt() == 0) continue
                    val packets = encoder.encode(pcm)
                    val now = NetAudio.nowMicros()
                    for (p in packets) {
                        val hdr = NetAudio.Header(
                            NetAudio.PacketType.AUDIO, slot,
                            NetAudio.Codec.OPUS, seq, now, p.size)
                        val dgram = NetAudio.encode(hdr, p)
                        try { sock.send(DatagramPacket(dgram, dgram.size, host, port)) }
                        catch (_: Throwable) {}
                        seq = (seq + 1)
                    }
                }

                // bye 送信（失敗は無視）
                try {
                    val bye = NetAudio.encode(NetAudio.Header(
                        NetAudio.PacketType.BYE, assignedSlot,
                        NetAudio.Codec.OPUS, seq, NetAudio.nowMicros(), 0),
                        ByteArray(0))
                    sock.send(DatagramPacket(bye, bye.size, host, port))
                } catch (_: Throwable) {}
            } catch (t: Throwable) {
                Log.e(tag, "send error", t)
                onError(t)
            } finally {
                try { record?.stop() } catch (_: Throwable) {}
                try { record?.release() } catch (_: Throwable) {}
                try { encoder?.stop() } catch (_: Throwable) {}
            }
        }
    }

    fun stop() {
        running = false
        sendThread?.join(500)
        recvThread?.join(500)
        sendThread = null
        recvThread = null
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
    }

    private fun makeHello(seq: Int): ByteArray {
        // HELLO の slot は 0 (=「割当して」)。payload format (デバッグ向け):
        //   "deviceName\0appVersion"     (null 区切り)
        // Mac は null で分割して name + version をパースする (旧 Mac は
        // 全体を name 扱いするが、それでも version は表示されないだけで動作)。
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val verBytes = BuildConfig.VERSION_NAME.toByteArray(Charsets.UTF_8)
        val payload = nameBytes + byteArrayOf(0x00) + verBytes
        return NetAudio.encode(NetAudio.Header(
            type = NetAudio.PacketType.HELLO,
            slot = 0,
            codec = NetAudio.Codec.OPUS,
            seq = seq,
            tsUs = NetAudio.nowMicros(),
            payloadLen = payload.size
        ), payload)
    }
}
