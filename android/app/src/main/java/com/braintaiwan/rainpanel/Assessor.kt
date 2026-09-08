package com.braintaiwan.rainpanel

import android.content.Context
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 判斷邏輯：從你的位置沿「直線」往目的地（或前進方向）取樣氣象署「未來 1 小時雷達
 * 定量降雨預報」，決定號誌燈與那句話。
 *
 * 這份資料是「未來 1 小時的累積雨量預報」，不是即時觀測、也沒有更細的時間切分，
 * 所以文字一律講「未來 1 小時…」，不講「正在下」「幾分鐘後開始」。
 *
 * 門檻校正紀錄：
 *  - 2026-09-08 文山區：F-B0046 該帶預報 2–4mm/1h，實際地面無雨、晴。
 *    雷達 QPF 在台北盆地常把高空回波/虛雨報成地面雨。使用者選擇維持靈敏門檻
 *    （寧可偶爾誤報也不要漏掉真的雨），故 RED 維持 2mm。待更多實騎再議。
 */
object Assessor {

    private const val ASSUMED_SPEED_KMH = 18.0      // 換算「騎到約幾分」用的假設時速
    private const val REACH_KM = 18.0               // 1 小時騎得到的距離 → 只看這段
    private const val SAMPLE_STEP_M = 500.0
    private const val AMBER_MM = 0.5                 // 未來 1h 累積雨量門檻（見上方校正紀錄）
    private const val RED_MM = 2.0
    private const val NEAR_M = 2000.0               // 這麼近就算「這一帶」，不報距離

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
            return hereOnly(grid.mmAt(lat, lon))
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
            firstRedM >= 0 -> Assessment(Light.RED, rainLine(firstRedM, maxMm), maxMm)
            firstAmberM >= 0 -> Assessment(Light.AMBER, drizzleLine(firstAmberM), maxMm)
            else -> Assessment(Light.GREEN, ctx.getString(R.string.status_green))
        }
    }

    private fun rainLine(distM: Double, maxMm: Double): String {
        val word = intensityWord(maxMm)
        return if (distM <= NEAR_M) {
            "未來 1 小時 這一帶有雨（$word）"
        } else {
            val km = (distM / 1000.0).roundToInt()
            val mins = (distM / 1000.0 / ASSUMED_SPEED_KMH * 60).roundToInt()
            "前方約 $km km 有雨（$word），騎到約 $mins 分"
        }
    }

    private fun drizzleLine(distM: Double): String =
        if (distM <= NEAR_M) "未來 1 小時 這一帶可能有零星雨"
        else "前方約 ${(distM / 1000.0).roundToInt()} km 有零星雨區"

    private fun hereOnly(mm: Double): Assessment = when {
        mm >= RED_MM -> Assessment(Light.RED, "未來 1 小時 此處有雨（${intensityWord(mm)}）", mm)
        mm >= AMBER_MM -> Assessment(Light.AMBER, "未來 1 小時 此處可能有零星雨", mm)
        else -> Assessment(Light.GREEN, "此處 未來 1 小時 大致無雨")
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

    /** 未來 1 小時累積雨量（mm）→ 白話。門檻對齊騎乘體感，非氣象定義。 */
    private fun intensityWord(mm: Double): String = when {
        mm < 4 -> "小雨"
        mm < 10 -> "中雨"
        mm < 20 -> "大雨"
        else -> "豪雨"
    }
}
