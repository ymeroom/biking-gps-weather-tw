package com.braintaiwan.rainpanel

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 大地幾何小工具。經緯度單位為度。 */
object Geo {

    private const val R = 6_371_000.0 // 地球半徑（公尺）

    /**
     * WGS84（手機 GPS）→ 氣象署 QPF 網格用的 TWD67 舊基準 的近似偏移。
     * 約 -800m 東、+200m 北。雨區都是好幾公里大，這個等級的誤差在容忍範圍內；
     * 實際數值等下雨實騎時對照雷達再校（見計劃書 Step 8）。把兩個常數設 0 = 完全忽略基準差。
     */
    private const val TWD67_DLAT = 0.0019
    private const val TWD67_DLON = -0.0081

    fun wgs84ToGridLat(lat: Double) = lat + TWD67_DLAT
    fun wgs84ToGridLon(lon: Double) = lon + TWD67_DLON

    /** 兩點間距離（公尺）。 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** 從 (lat,lon) 往指定方位角前進 distM 公尺後的座標。 */
    fun destPoint(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val d = distM / R
        val br = Math.toRadians(bearingDeg)
        val la = Math.toRadians(lat)
        val lo = Math.toRadians(lon)
        val la2 = asin(sin(la) * cos(d) + cos(la) * sin(d) * cos(br))
        val lo2 = lo + atan2(sin(br) * sin(d) * cos(la), cos(d) - sin(la) * sin(la2))
        return Math.toDegrees(la2) to ((Math.toDegrees(lo2) + 540) % 360 - 180)
    }

    /** 從第一點指向第二點的方位角（度，0=北）。 */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val la1 = Math.toRadians(lat1)
        val la2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun approxEqual(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) < eps
}
