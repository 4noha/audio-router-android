package com.fournoha.audiorouter

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress

/**
 * `_4noha-audio._udp` を NSD で探し、解決して (name, host, port) を返す。
 * **port は Mac が実際に bind しているもの**（typically 47391、別ポートに
 * 流れた場合も Bonjour TXT/SRV で正確に知れる）。本ヘルパは「候補」を出す
 * だけで、実際の到達性 (UDP が通るか) は `PingVerifier` が unicast PING で
 * 確認する。
 */
class NsdHelper(private val ctx: Context) {
    private val tag = "NsdHelper"
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = ctx.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun interface OnResolved {
        fun onResolved(name: String, host: InetAddress, port: Int)
    }
    fun interface OnLost { fun onLost(name: String) }

    fun start(onResolved: OnResolved, onLost: OnLost) {
        multicastLock = wifi.createMulticastLock("4noha-audio-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(tag, "discovery started: $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(tag, "discovery stopped")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "stop failed: $errorCode")
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                resolve(info, onResolved)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                Log.d(tag, "lost: ${info.serviceName}")
                onLost.onLost(info.serviceName)
            }
        }
        discoveryListener = listener
        nsd.discoverServices("_4noha-audio._udp",
            NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(info: NsdServiceInfo, onResolved: OnResolved) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "resolve failed ${serviceInfo.serviceName}: $errorCode")
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host
                val port = serviceInfo.port
                if (host != null && port > 0) {
                    Log.d(tag, "resolved ${serviceInfo.serviceName} -> $host:$port")
                    onResolved.onResolved(serviceInfo.serviceName, host, port)
                }
            }
        })
    }

    fun stop() {
        try { discoveryListener?.let { nsd.stopServiceDiscovery(it) } } catch (_: Throwable) {}
        discoveryListener = null
        try { multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
    }
}
