package com.braintaiwan.rainpanel

import android.content.Context

/** App 設定：目的地與提示音開關。 */
object Prefs {
    private const val FILE = "rainpanel"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setDestination(ctx: Context, lat: Double, lon: Double, label: String) {
        sp(ctx).edit()
            .putString("destLat", lat.toString())
            .putString("destLon", lon.toString())
            .putString("destLabel", label)
            .apply()
    }

    /** 回傳 (lat, lon, label)；沒設過回 null。 */
    fun destination(ctx: Context): Triple<Double, Double, String>? {
        val p = sp(ctx)
        val lat = p.getString("destLat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("destLon", null)?.toDoubleOrNull() ?: return null
        return Triple(lat, lon, p.getString("destLabel", "") ?: "")
    }

    fun setSound(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("sound", on).apply()
    fun soundOn(ctx: Context): Boolean = sp(ctx).getBoolean("sound", true)
}
