package com.braintaiwan.rainpanel

import android.content.Context
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 判斷邏輯：從你的位置沿「直線」往目的地（或前進方向）取樣未來 1 小時雨量，
 * 決定號誌燈與那句話。
 */
object Assessor {

    private const val ASSUMED_SPEED_KMH = 18.0      // 換算「幾分後遇到」用的假設時速
    private const val REACH_KM = 18.0               // 1 小時騎得到的距離 → 只看這段
    private const val SAMPLE_STEP_M = 500.0
    private const val AMBER_MM = 0.5                 // 未來 1h 累積雨量門檻（實騎後校，見計劃書 Step 2）
    private const val RED_MM = 2.0
    private const val HERE_M = 600.0                 // 這麼近就算「此處」

    /**
     * @param bearing 行進方位角（度）；靜止/未知時為 null
     * @param dest (lat, lon, label) 或 null
     */
    suspend fun assess(
        ctx: Context,
        lat: Double,
        lon: Double,
        bearing: Double?,
        dest: Triple<Double, Double, String>?,
    ): Assessment {
        val grid = QpfRepository.get(ctx)
        val now = System.currentTimeMillis()
        if (grid == null || !grid.isFresh(now)) {
            return Assessment(Light.NODATA, ctx.getString(R.string.status_nodata))
        }

        val samples = buildLine(lat, lon, bearing, dest)
        if (samples.isEmpty()) {
            // 只知道現在的點
            val mm = grid.mmAt(lat, lon)
            return hereOnly(mm)
        }

        var firstRedM = -1.0
        var firstAmberM = -1.0
        var maxMm = 0.0
        var dist = 0.0
        for ((plat, plon) in samples) {
            val mm = grid.mmAt(plat, plon)
            if (mm > maxMm) maxMm = mm
            if (mm >= RED_MM && firstRedM < 0) firstRedM = dist
            if (mm >= AMBER_MM && firstAmberM < 0) firstAmberM = dist
            dist += SAMPLE_STEP_M
        }

        return when {
            firstRedM >= 0 -> {
                val word = intensityWord(maxMm)
                if (firstRedM <= HERE_M) {
                    Assessment(Light.RED, "此處正在下$word", maxMm)
                } else {
                    val mins = (firstRedM / 1000.0 / ASSUMED_SPEED_KMH * 60).roundToInt()
                    Assessment(Light.RED, "約 $mins 分後前方有$word", maxMm)
                }
            }
            firstAmberM >= 0 -> Assessment(Light.AMBER, "前方有零星小雨", maxMm)
            else -> Assessment(Light.GREEN, ctx.getString(R.string.status_green))
        }
    }

    private fun hereOnly(mm: Double): Assessment = when {
        mm >= RED_MM -> Assessment(Light.RED, "此處正在下${intensityWord(mm)}", mm)
        mm >= AMBER_MM -> Assessment(Light.AMBER, "此處有零星小雨", mm)
        else -> Assessment(Light.GREEN, "你所在位置：未來 1 小時無雨")
    }

    /** 取樣線上的點。有目的地→朝目的地；否則→朝行進方向；都沒有→空。 */
    private fun buildLine(
        lat: Double,
        lon: Double,
        bearing: Double?,
        dest: Triple<Double, Double, String>?,
    ): List<Pair<Double, Double>> {
        val reachM = REACH_KM * 1000.0
        val endLat: Double
        val endLon: Double
        val lineLen: Double

        if (dest != null) {
            val dTotal = Geo.distanceMeters(lat, lon, dest.first, dest.second)
            lineLen = min(dTotal, reachM)
            val br = Geo.bearingDeg(lat, lon, dest.first, dest.second)
            val (el, eo) = Geo.destPoint(lat, lon, br, lineLen)
            endLat = el; endLon = eo
        } else if (bearing != null) {
            lineLen = reachM
            val (el, eo) = Geo.destPoint(lat, lon, bearing, lineLen)
            endLat = el; endLon = eo
        } else {
            return emptyList()
        }

        val n = (lineLen / SAMPLE_STEP_M).toInt().coerceAtLeast(1)
        val out = ArrayList<Pair<Double, Double>>(n + 1)
        for (i in 0..n) {
            val f = i.toDouble() / n
            out.add(lat + (endLat - lat) * f to lon + (endLon - lon) * f)
        }
        return out
    }

    private fun intensityWord(mm: Double): String = when {
        mm < 4 -> "小雨"
        mm < 10 -> "中雨"
        mm < 25 -> "大雨"
        else -> "豪雨"
    }
}
