package com.braintaiwan.rainpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 騎乘中的前景服務：持續看 GPS、每隔固定時間重新評估前方 1 小時有沒有雨，
 * 更新浮窗；號誌轉紅或雨勢明顯變大時 震動＋響一聲。
 */
class RideService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "ride"
        private const val NOTIF_ID = 1
        private const val ASSESS_INTERVAL_MS = 120_000L   // 每 2 分鐘重新評估
        @Volatile var running = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayController
    private lateinit var lm: LocationManager
    private var lastLocation: Location? = null
    private var lastLight: Light = Light.WAITING
    private var lastSeverity = 0.0

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { lastLocation = loc }
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
        @Deprecated("required on old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    private val assessTick = object : Runnable {
        override fun run() {
            assessNow()
            main.postDelayed(this, ASSESS_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        running = true
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(getString(R.string.status_waiting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        overlay = OverlayController(this)
        overlay.show()
        overlay.render(Assessment(Light.WAITING, getString(R.string.status_waiting)))

        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startLocationUpdates()
        main.post(assessTick)
        return START_STICKY
    }

    private fun startLocationUpdates() {
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 5_000L, 20f, locListener, Looper.getMainLooper())
                }
            }
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastLocation = it }
        } catch (_: SecurityException) {
            // 權限被撤 → 交給 MainActivity 重新引導；這裡不崩潰
        }
    }

    private fun assessNow() {
        val loc = lastLocation
        if (loc == null) {
            update(Assessment(Light.WAITING, getString(R.string.status_waiting)))
            return
        }
        val dest = Prefs.destination(this)
        // 只有真的在移動時 heading 才可信
        val bearing: Double? =
            if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.8f) loc.bearing.toDouble() else null
        scope.launch {
            val a = Assessor.assess(this@RideService, loc.latitude, loc.longitude, bearing, dest)
            main.post { update(a) }
        }
    }

    private fun update(a: Assessment) {
        overlay.render(a)
        notify(a.message)
        val turnedRed = a.light == Light.RED && lastLight != Light.RED
        val worseRain = a.light == Light.RED && a.severity >= lastSeverity + 4.0
        if ((turnedRed || worseRain) && Prefs.soundOn(this)) alert()
        lastLight = a.light
        lastSeverity = a.severity
    }

    private fun alert() {
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        else
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        vib?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        runCatching {
            RingtoneManager.getRingtone(
                this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ).apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
                play()
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { lm.removeUpdates(locListener) }
        overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
