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
import androidx.core.content.ContextCompat
import com.braintaiwan.rainpanel.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val locationPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { proceedAfterLocation() }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { proceedAfterNotif() }

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
            if (RideService.running) stopRide() else startRideFlow()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        b.startStopBtn.text = getString(if (RideService.running) R.string.stop else R.string.start)
        val sb = StringBuilder()
        sb.append("定位權限：").append(if (hasLocation()) "已允許" else "未允許").append('\n')
        sb.append("顯示在其他 App 上層：").append(if (canOverlay()) "已允許" else "未允許").append('\n')
        sb.append("電池最佳化豁免：").append(if (batteryUnrestricted()) "已設定" else "未設定（背景可能被凍結）").append('\n')
        Prefs.destination(this)?.let { (lat, lon, label) ->
            sb.append("目的地：").append(label.ifEmpty { "%.4f, %.4f".format(lat, lon) })
        } ?: sb.append("目的地：未設定（會改用前進方向）")
        b.statusText.text = sb.toString()
    }

    // ---- 權限狀態 ----
    private fun hasLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun canOverlay() = Settings.canDrawOverlays(this)

    private fun batteryUnrestricted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // ---- 開始騎乘：逐項補齊權限 ----
    private fun startRideFlow() {
        if (!saveDestinationFromInput()) {
            // 沒填目的地也允許（會用前進方向），但提示一下
        }
        if (!hasLocation()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_location_title)
                .setMessage(R.string.perm_location_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    locationPerm.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
                .setNegativeButton(R.string.later, null)
                .show()
            return
        }
        proceedAfterLocation()
    }

    private fun proceedAfterLocation() {
        if (!hasLocation()) {
            toast("沒有定位權限，無法判斷前方雨況")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedAfterNotif()
    }

    private fun proceedAfterNotif() {
        if (!canOverlay()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_overlay_title)
                .setMessage(R.string.perm_overlay_msg)
                .setPositiveButton(R.string.go_settings) { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton(R.string.later, null)
                .show()
            return
        }
        if (!batteryUnrestricted()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_battery_title)
                .setMessage(R.string.perm_battery_msg)
                .setPositiveButton(R.string.go_settings) { _, _ ->
                    runCatching {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")))
                    }.onFailure {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                .setNegativeButton(R.string.later) { _, _ -> startRide() }
                .show()
            return
        }
        startRide()
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
        refreshUi()
    }

    // ---- 目的地 ----
    /** 從輸入框存目的地。成功回 true。 */
    private fun saveDestinationFromInput(): Boolean {
        val raw = b.destInput.text.toString().trim()
        if (raw.isEmpty()) return false

        // "緯度,經度"
        val parts = raw.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lon = parts[1].trim().toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                Prefs.setDestination(this, lat, lon, "")
                return true
            }
        }

        // 地名 → Geocoder（可能失敗）
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
