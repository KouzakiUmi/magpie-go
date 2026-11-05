package com.magpie.android.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.magpie.android.R
import com.magpie.android.data.PowerSettings
import com.magpie.android.ui.MainActivity
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 让进程保持前台，并给用户一个能看到、能操作的常驻通知。
 *
 * 通知里只放「服务在跑」这一件事和两个入口，不出现 web key 或任何凭据。
 *
 * 「省电设置」这个动作为什么放进通知：magpie 是被其他客户端长期调用的网关，
 * 国产 ROM 在锁屏一段时间后会清后台，用户看到的现象是「锁屏前能用、解锁后连不上」。
 * 放通知栏比在网页里压一条横幅更合适——不占网页空间，用户想起来随时能点。
 */
class MagpieService : Service() {
    private var foregroundReady = false
    private val modelWatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var modelFingerprint: String? = null

    companion object {
        private const val CHANNEL_ID = "magpie_runtime"
        private const val NOTIFICATION_ID = 3430
        const val ACTION_OPEN_POWER_SETTINGS = "com.magpie.android.action.POWER_SETTINGS"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            startForeground(NOTIFICATION_ID, buildNotification())
            foregroundReady = true
            startModelWatch()
            AndroidMagpieRuntime.getInstance(applicationContext).onServiceReady(this)
        } catch (error: Exception) {
            android.util.Log.e("MagpieService", "foreground start failed: ${error.javaClass.simpleName}: ${error.message}")
            AndroidMagpieRuntime.getInstance(applicationContext).onServiceError()
            stopSelf()
        }
    }

    private fun buildNotification(): android.app.Notification = notificationBuilder().build()

    private fun notificationBuilder(): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome_art)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())

        // 已经被系统忽略电池优化时不再展示这一项，免得长期堆一个没用的按钮。
        if (!PowerSettings.isIgnoringBatteryOptimizations(this)) {
            builder.addAction(
                android.R.drawable.ic_menu_manage,
                getString(R.string.power_banner_action),
                powerSettingsIntent(),
            )
        }
        return builder
    }

    private fun startModelWatch() {
        modelWatchScope.launch {
            while (isActive) {
                val fingerprint = fetchModelFingerprint()
                if (fingerprint != null) {
                    val previous = modelFingerprint
                    modelFingerprint = fingerprint
                    if (previous != null && previous != fingerprint) notifyModelListChanged()
                }
                delay(60_000)
            }
        }
    }

    private fun notifyModelListChanged() {
        if (!foregroundReady) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notificationBuilder().setContentText(getString(R.string.models_changed)).build(),
            )
        }
    }

    private fun fetchModelFingerprint(): String? = runCatching {
        val connection = URL("http://127.0.0.1:3425/v1/models").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.setRequestProperty("Authorization", "Bearer magpie")
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > 4 * 1024 * 1024) return null
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            val items = JSONArray(body)
            val identities = ArrayList<String>(items.length())
            for (index in 0 until items.length()) {
                val model = items.getJSONObject(index)
                identities += listOf(
                    model.optString("id"),
                    model.optString("owned_by"),
                    model.optJSONArray("native_endpoints")?.toString() ?: "",
                    model.optJSONObject("modalities")?.toString() ?: "",
                    model.optJSONArray("supported_reasoning_levels")?.toString() ?: "",
                ).joinToString("|")
            }
            identities.sort()
            MessageDigest.getInstance("SHA-256").digest(identities.joinToString("\n").toByteArray()).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun powerSettingsIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        1,
        Intent(ACTION_OPEN_POWER_SETTINGS).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundReady) AndroidMagpieRuntime.getInstance(applicationContext).onServiceReady(this)
        return START_NOT_STICKY
    }

    /**
     * 重建通知。
     *
     * 需要它的原因：通知只在服务启动时构建一次。用户在系统设置里把本应用加进
     * 电池白名单之后，「省电设置」这一项其实已经没用了，但旧通知里还挂着，
     * 会让人以为设置没生效。界面回到前台时调一次，把过期的那项去掉。
     */
    fun refreshNotification() {
        if (!foregroundReady) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        modelWatchScope.cancel()
        AndroidMagpieRuntime.getInstance(applicationContext).onServiceDestroyed(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * 处理通知里的「省电设置」。
 *
 * 用广播而不是把 PendingIntent 直接指向某个 Activity：目标页属于系统或厂商应用，
 * 路径各 ROM 不同，由 [PowerSettings] 按候选顺序解析，失败还能退到下一个。
 */
class PowerSettingsActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MagpieService.ACTION_OPEN_POWER_SETTINGS) return
        PowerSettings.openBackgroundSettings(context)
    }
}
