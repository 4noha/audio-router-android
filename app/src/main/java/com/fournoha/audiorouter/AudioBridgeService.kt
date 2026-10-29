package com.fournoha.audiorouter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.InetAddress

/**
 * AudioBridge を保持して **バックグラウンドでも継続再生/送信**するための
 * Foreground Service。Android はマイク等のセンサアクセスをバックグラウンド
 * 状態の通常 Activity から行うと止めるので、必ずこの Service 越しに動かす。
 *
 * - foregroundServiceType = "microphone" (manifest 側)
 * - 起動時に通知を 1 枚出し、ongoing として表示し続ける
 * - 通知をタップすると MainActivity に戻る
 * - 通知の「停止」アクションで Service を止める
 */
class AudioBridgeService : Service() {
    private val tag = "AudioBridgeService"
    private var bridge: AudioBridge? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                val peerName = intent.getStringExtra(EXTRA_NAME) ?: host
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Android"
                startBridge(host, port, peerName, deviceName)
            }
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
            }
        }
        // バックグラウンド継続前提なので START_STICKY。OS にメモリ圧迫で
        // 殺されたら自動復活させる。ユーザによる**明示停止は通知の「停止」
        // ボタンのみ**。タスク履歴スワイプは Activity が消えるだけで Service
        // は生きる（送信継続）。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopBridge()
        super.onDestroy()
    }

    private fun startForegroundWithNotice(text: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = Intent(this, AudioBridgeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AudioRouter 送信中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .addAction(0, "停止", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startBridge(host: String, port: Int,
                             peerName: String, deviceName: String) {
        stopBridge()
        startForegroundWithNotice("$peerName ← $deviceName")
        val addr = try { InetAddress.getByName(host) } catch (t: Throwable) {
            Log.e(tag, "bad host $host", t); stopSelf(); return
        }
        val b = AudioBridge(
            host = addr, initialPort = port, deviceName = deviceName,
            onPaired = { assignedSlot ->
                Log.i(tag, "paired, slot=$assignedSlot")
            },
            onError = { t -> Log.e(tag, "bridge error", t) }
        )
        b.start()
        bridge = b
    }

    private fun stopBridge() {
        bridge?.stop(); bridge = null
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "AudioRouter 送信",
                NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        const val ACTION_START = "com.fournoha.audiorouter.START"
        const val ACTION_STOP = "com.fournoha.audiorouter.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val CHANNEL_ID = "audiorouter.bridge"
        const val NOTIF_ID = 4711

        fun start(ctx: Context, host: InetAddress, port: Int,
                  peerName: String, deviceName: String) {
            val i = Intent(ctx, AudioBridgeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host.hostAddress)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_NAME, peerName)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, AudioBridgeService::class.java).apply {
                action = ACTION_STOP
            }
            ctx.startService(i)
        }
    }
}
