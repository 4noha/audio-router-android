package com.fournoha.audiorouter

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * NsdHelper が拾った候補 (host, port) に対して **unicast PING** を定期送信し、
 * PONG が返ったものだけを「到達性確認済 Peer」として公開する。Spinner は
 * このリストの内容を見て表示する。
 *
 * 利点:
 *  - Bonjour TXT/SRV から実 port を取得 → Mac が 47391 以外に流れていても OK
 *  - 一度 NSD が乗れば、UDP が通る間は安定して PONG が返り続ける
 *  - 多 Android / 多 Mac (それぞれが Bonjour 公開) でもポート衝突せず動く
 *  - PONG の payload にはホスト名が入っているので Spinner 表示に使える
 *
 * NSD は時々端末で discovery 落ちするので、ここの再 PING で生存追跡もする。
 */
class PingVerifier {
    private val tag = "PingVerifier"
    private val pingIntervalMs = 2000L
    private val peerExpireMs = 10000L

    fun interface OnPeerChanged {
        fun onPeers(peers: List<VerifiedPeer>)
    }

    data class VerifiedPeer(
        val name: String,           // PONG payload (= Mac の localizedName)
        val host: InetAddress,
        val port: Int,
        val lastSeenMs: Long
    )

    /** 候補 (NSD から登録) と検証済 peer。key = "host:port"。*/
    private data class Candidate(val host: InetAddress, val port: Int, val nsdName: String)
    private val candidates = mutableMapOf<String, Candidate>()
    private val verified = mutableMapOf<String, VerifiedPeer>()

    @Volatile private var running = false
    private var sock: DatagramSocket? = null
    private var sendThread: Thread? = null
    private var recvThread: Thread? = null
    private var listener: OnPeerChanged? = null

    fun start(onPeers: OnPeerChanged) {
        if (running) return
        running = true
        this.listener = onPeers
        val s = DatagramSocket()
        s.soTimeout = 200
        sock = s

        sendThread = thread(name = "PingVerifier-send", isDaemon = true) {
            val ping = makePing()
            var nextEmit = System.currentTimeMillis()
            while (running) {
                if (System.currentTimeMillis() >= nextEmit) {
                    val targets: List<Candidate>
                    synchronized(candidates) { targets = candidates.values.toList() }
                    for (c in targets) {
                        try {
                            s.send(DatagramPacket(ping, ping.size, c.host, c.port))
                        } catch (t: Throwable) {
                            Log.w(tag, "ping send failed: ${c.host}:${c.port}", t)
                        }
                    }
                    nextEmit = System.currentTimeMillis() + pingIntervalMs
                    expirePeers()
                }
                Thread.sleep(100)
            }
        }

        recvThread = thread(name = "PingVerifier-recv", isDaemon = true) {
            val buf = ByteArray(NetAudio.MAX_PACKET_BYTES)
            val pkt = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    s.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) { continue }
                catch (t: Throwable) {
                    if (running) Log.w(tag, "recv error", t)
                    break
                }
                val (h, payload) = NetAudio.decode(buf, pkt.length) ?: continue
                if (h.type != NetAudio.PacketType.PING) continue
                val host = pkt.address ?: continue
                val key = "${host.hostAddress}:${pkt.port}"
                val name = String(payload, Charsets.UTF_8).ifBlank {
                    synchronized(candidates) { candidates[key]?.nsdName ?: "Mac" }
                }
                val now = System.currentTimeMillis()
                var changed = false
                synchronized(verified) {
                    val existing = verified[key]
                    if (existing == null || existing.name != name) changed = true
                    verified[key] = VerifiedPeer(name, host, pkt.port, now)
                    if (changed) listener?.onPeers(
                        verified.values.sortedBy { it.name })
                    else {
                        // 既存 peer の lastSeen 更新は通知しない（spinner ちらつき防止）
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        try { sock?.close() } catch (_: Throwable) {}
        sock = null
        sendThread = null
        recvThread = null
        synchronized(verified) { verified.clear() }
        synchronized(candidates) { candidates.clear() }
    }

    /** NSD で見つかった候補を登録。次回送信タイミングで PING される。 */
    fun addCandidate(name: String, host: InetAddress, port: Int) {
        val key = "${host.hostAddress}:$port"
        synchronized(candidates) {
            candidates[key] = Candidate(host, port, name)
        }
    }

    /** NSD で「失われた」候補を解除。*/
    fun removeCandidate(name: String) {
        synchronized(candidates) {
            candidates.entries.removeAll { it.value.nsdName == name }
        }
    }

    private fun makePing(): ByteArray {
        val hdr = NetAudio.Header(
            type = NetAudio.PacketType.PING,
            slot = 0,
            codec = NetAudio.Codec.PCM16LE,
            seq = 0,
            tsUs = NetAudio.nowMicros(),
            payloadLen = 0
        )
        return NetAudio.encode(hdr, ByteArray(0))
    }

    private fun expirePeers() {
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(verified) {
            val it = verified.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value.lastSeenMs > peerExpireMs) {
                    it.remove(); changed = true
                }
            }
            if (changed) listener?.onPeers(verified.values.sortedBy { it.name })
        }
    }
}
