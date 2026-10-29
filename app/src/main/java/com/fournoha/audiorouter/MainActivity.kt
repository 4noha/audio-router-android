package com.fournoha.audiorouter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fournoha.audiorouter.databinding.ActivityMainBinding
import java.net.InetAddress

/**
 * UI フロー（手動「接続」ボタンは廃止、全自動）:
 *
 *  1. 起動時にマイク権限を取り、取れたら NsdHelper + PingVerifier を起動
 *  2. NSD `_4noha-audio._udp` で Mac の (host, port, name) を解決
 *  3. その宛先に PingVerifier が unicast PING を 2 秒毎送信、PONG が返った
 *     ものだけが「到達性確認済 Peer」となり Spinner に並ぶ
 *  4. 最初に確認済となった Mac を初期選択して **即座に AudioBridge start**
 *  5. ユーザが Spinner で別の Mac を選んだら、現接続を切って新規接続
 *  6. AudioBridge が Mac から hello-ack 受信でペアリング成立
 *
 * Bonjour 経由でポートを取るので、Mac が 47391 以外に bind した場合や、
 * 複数 Mac が別ポートで起動している場合も自動対応する。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val tag = "MainActivity"

    private data class Peer(val name: String, val host: InetAddress, val port: Int) {
        override fun toString() = "$name (${host.hostAddress}:$port)"
    }

    private var nsd: NsdHelper? = null
    private var verifier: PingVerifier? = null
    @Volatile private var paired = false
    private var serviceActive = false

    private val peers = mutableListOf<Peer>()
    private lateinit var peerAdapter: ArrayAdapter<Any>

    private var currentPeer: Peer? = null
    private var deviceName: String = "Android"

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestNotifIfNeeded()
            startDiscoveryIfNeeded()
        } else {
            binding.txtStatus.text = getString(R.string.permission_mic)
        }
    }

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* 通知拒否でも動作はする（通知が出ないだけ） */ }

    private fun requestNotifIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.txtStatus.text = getString(R.string.status_idle)
        binding.txtVersion.text = "v${BuildConfig.VERSION_NAME}"

        // 端末名: ユーザ設定 (Settings.Global.DEVICE_NAME) → Build.MODEL の順。
        // Mac 側で行ラベルに使われる。
        deviceName = (Settings.Global.getString(contentResolver, "device_name")
            ?: Build.MODEL).ifBlank { Build.MODEL }
        binding.txtDevice.text = "端末名: $deviceName"

        peerAdapter = ArrayAdapter<Any>(this,
            android.R.layout.simple_spinner_item)
        peerAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item)
        peerAdapter.add(getString(R.string.peer_none))
        binding.spinnerPeer.adapter = peerAdapter
        binding.spinnerPeer.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: AdapterView<*>, v: View?, position: Int, id: Long
                ) {
                    val item = peerAdapter.getItem(position)
                    if (item is Peer) reconnect(item)
                }
                override fun onNothingSelected(p: AdapterView<*>) {}
            }

        // slot 選択 UI は廃止。Mac 側が端末名で動的割当。
        binding.btnRescan.setOnClickListener {
            stopBridge()
            stopDiscovery()
            peers.clear()
            refreshPeerSpinner(restoreSelection = null)
            paired = false
            currentPeer = null
            binding.txtStatus.text = getString(R.string.status_idle)
            startDiscoveryIfNeeded()
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            requestNotifIfNeeded()
            startDiscoveryIfNeeded()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startDiscoveryIfNeeded() {
        if (nsd != null || verifier != null) return
        val v = PingVerifier()
        verifier = v
        v.start { verifiedList ->
            runOnUiThread {
                val newPeers = verifiedList.map { Peer(it.name, it.host, it.port) }
                if (newPeers.toSet() != peers.toSet()) {
                    peers.clear()
                    peers.addAll(newPeers)
                    refreshPeerSpinner(restoreSelection = currentPeer)
                    if (currentPeer == null && peers.isNotEmpty()) {
                        reconnect(peers.first())
                    }
                    if (currentPeer != null && currentPeer !in peers && !paired) {
                        stopBridge()
                        currentPeer = null
                        binding.txtStatus.text = getString(R.string.status_idle)
                    }
                }
            }
        }
        val n = NsdHelper(this)
        nsd = n
        n.start(
            onResolved = { name, host, port ->
                verifier?.addCandidate(name, host, port)
            },
            onLost = { name ->
                verifier?.removeCandidate(name)
            }
        )
    }

    private fun stopDiscovery() {
        nsd?.stop(); nsd = null
        verifier?.stop(); verifier = null
    }

    private fun refreshPeerSpinner(restoreSelection: Peer?) {
        peerAdapter.clear()
        if (peers.isEmpty()) {
            peerAdapter.add(getString(R.string.peer_none))
        } else {
            for (p in peers) peerAdapter.add(p as Any)
        }
        if (restoreSelection != null) {
            val idx = peers.indexOf(restoreSelection)
            if (idx >= 0) binding.spinnerPeer.setSelection(idx)
        }
    }

    private fun reconnect(peer: Peer) {
        if (currentPeer == peer && serviceActive) return
        currentPeer = peer
        paired = false
        binding.txtStatus.text = getString(R.string.status_connecting)
        // Foreground Service 経由で AudioBridge を立てる。Activity が
        // バックグラウンドに行っても止まらない。slot は Mac が割当てる
        // ので Android からは送らず、deviceName を渡すだけ。
        AudioBridgeService.start(this, peer.host, peer.port,
            peer.name, deviceName)
        serviceActive = true
        binding.txtStatus.text = getString(R.string.status_running)
    }

    private fun stopBridge() {
        if (serviceActive) {
            AudioBridgeService.stop(this)
            serviceActive = false
        }
    }

    override fun onDestroy() {
        // **Service は止めない** — Activity が破棄されてもバックグラウンドで
        // 送信継続。完全停止は通知の「停止」ボタンか、本アプリのプロセスキル。
        stopDiscovery()
        super.onDestroy()
    }
}
