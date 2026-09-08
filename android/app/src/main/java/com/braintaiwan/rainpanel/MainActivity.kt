package com.braintaiwan.rainpanel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.braintaiwan.rainpanel.databinding.ActivityMainBinding
import java.util.Locale

/**
 * 設定目的地、走完權限、開始/結束騎乘。
 *
 * 權限流程：按「開始」後設 [startRequested]，[advance] 依序檢查各項權限，
 * 遇到缺的就要求（或跳系統設定）並 return；使用者從系統設定回來時 onResume 會再呼叫
 * [advance] 接著往下——所以「按一次開始就一路帶到底」，不用重按。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var startRequested = false
    private var batteryPrompted = false
    private var dialog: AlertDialog? = null

    private val locationPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { advance() }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { advance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        Prefs.destination(this)?.let { (lat, lon, label) ->
            b.destInput.setText(if (label.isNotEmpty()) label else "$lat,$lon")
        }
        b.soundSwitch.isChecked = Prefs.soundOn(this)
        b.soundSwitch.setOnCheckedChangeListener { _, on -> Prefs.setSound(this, on) }

        b.useCurrentBtn.setOnClickListener { useCurrentAsDest() }
        b.startStopBtn.setOnClickListener {
            if (RideService.running) {
                stopRide()
            } else {
                saveDestinationFromInput()
                startRequested = true
                batteryPrompted = false
                advance()
            }
        }
        b.statusText.setOnClickListener { openAppSettings() } // 點狀態列 → App 設定，手動調權限
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        if (startRequested && !RideService.running) advance()
    }

    /** 依序檢查權限：缺的就要求並 return；全通過就啟動。可重複呼叫（onResume 會再叫）。 */
    private fun advance() {
        if (!startRequested) return
        if (dialog?.isShowing == true) return

        if (!hasLocation()) {
            showGate(R.string.perm_location_title, R.string.perm_location_msg, android.R.string.ok) {
                locationPerm.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (!canOverlay()) {
            showGate(R.string.perm_overlay_title, R.string.perm_overlay_msg, R.string.go_settings) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
            return
        }

        // 電池最佳化：建議但不強制。每次「開始」只提醒一次，之後照樣啟動。
        if (!batteryUnrestricted() && !batteryPrompted) {
            batteryPrompted = true
            dialog = AlertDialog.Builder(this)
                .setTitle(R.string.perm_battery_title)
                .setMessage(R.string.perm_battery_msg)
                .setPositiveButton(R.string.go_settings) { _, _ -> requestIgnoreBattery() }
                .setNegativeButton(R.string.later) { _, _ -> advance() }
                .setOnCancelListener { advance() }
                .show()
            return
        }

        startRequested = false
        startRide()
    }

    /** 顯示一個「缺這個權限」的提示框；按確定跑 [onConfirm]，取消/稍後則放棄啟動。 */
    private fun showGate(titleRes: Int, msgRes: Int, positiveRes: Int, onConfirm: () -> Unit) {
        dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(msgRes)
            .setPositiveButton(positiveRes) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.later) { _, _ -> startRequested = false }
            .setOnCancelListener { startRequested = false }
            .show()
    }

    // ---- 權限狀態 ----
    private fun hasLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun canOverlay() = Settings.canDrawOverlays(this)

    private fun notificationsEnabled() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun batteryUnrestricted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBattery() {
        runCatching {
            @Suppress("BatteryLife")
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun refreshUi() {
        b.startStopBtn.text = getString(if (RideService.running) R.string.stop else R.string.start)
        val sb = StringBuilder()
        sb.append("定位權限：").append(if (hasLocation()) "已允許" else "未允許").append('\n')
        sb.append("通知：").append(if (notificationsEnabled()) "開啟" else "關閉（浮窗仍會顯示，但常駐通知看不到）").append('\n')
        sb.append("顯示在其他 App 上層：").append(if (canOverlay()) "已允許" else "未允許").append('\n')
        sb.append("電池最佳化豁免：")
            .append(if (batteryUnrestricted()) "已設定" else "未設定（背景可能被凍結）").append('\n')
        Prefs.destination(this)?.let { (lat, lon, label) ->
            sb.append("目的地：").append(label.ifEmpty { "%.4f, %.4f".format(lat, lon) })
        } ?: sb.append("目的地：未設定（會改用前進方向）")
        sb.append("\n\n（點這段文字可開啟系統的 App 權限設定）")
        b.statusText.text = sb.toString()
    }

    private fun startRide() {
        ContextCompat.startForegroundService(
            this, Intent(this, RideService::class.java).setAction(RideService.ACTION_START)
        )
        toast("開始。浮窗已開，可切回 Google 地圖。")
        refreshUi()
    }

    private fun stopRide() {
        startService(Intent(this, RideService::class.java).setAction(RideService.ACTION_STOP))
        startRequested = false
        refreshUi()
    }

    // ---- 目的地 ----
    /** 從輸入框存目的地。成功回 true。 */
    private fun saveDestinationFromInput(): Boolean {
        val raw = b.destInput.text.toString().trim()
        if (raw.isEmpty()) return false

        val parts = raw.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lon = parts[1].trim().toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                Prefs.setDestination(this, lat, lon, "")
                return true
            }
        }

        return runCatching {
            @Suppress("DEPRECATION")
            val res = Geocoder(this, Locale.TAIWAN).getFromLocationName(raw, 1)
            if (!res.isNullOrEmpty()) {
                Prefs.setDestination(this, res[0].latitude, res[0].longitude, raw)
                true
            } else {
                toast("找不到「$raw」，可改輸入 緯度,經度")
                false
            }
        }.getOrElse {
            toast("地名查詢失敗，可改輸入 緯度,經度")
            false
        }
    }

    private fun useCurrentAsDest() {
        if (!hasLocation()) {
            locationPerm.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        if (loc == null) {
            toast("目前沒有位置，請到室外再試")
            return
        }
        b.destInput.setText("%.5f,%.5f".format(loc.latitude, loc.longitude))
        Prefs.setDestination(this, loc.latitude, loc.longitude, "")
        refreshUi()
        toast("已把目前位置設成終點（測試用）")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
