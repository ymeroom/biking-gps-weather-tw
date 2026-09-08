package com.braintaiwan.rainpanel

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 氣象署「未來 1 小時雷達定量降雨預報」網格（幕後幫手縮小後的 qpf.json）。
 *
 * 官方排列：左下角為第一點（東經 117.975、北緯 19.975），先經度遞增（西→東），
 * 再緯度遞增（南→北）。qpf.json 只留「有雨」的格子。
 */
class QpfGrid private constructor(
    val issued: String?,
    val fetchedEpochMs: Long,
    private val originLat: Double,
    private val originLon: Double,
    private val res: Double,
    private val nx: Int,
    private val ny: Int,
    private val cells: HashMap<Int, Double>, // key = iy * nx + ix
    val error: String?,
) {
    val hasData: Boolean get() = error == null && issued != null

    /** 資料是否夠新（fetched 在 maxAgeMs 內）。 */
    fun isFresh(nowMs: Long, maxAgeMs: Long = 40 * 60_000L) =
        hasData && nowMs - fetchedEpochMs <= maxAgeMs

    /** 指定 WGS84 座標未來 1 小時預報雨量（mm）。不在範圍/沒雨回 0。 */
    fun mmAt(lat: Double, lon: Double): Double {
        val gLat = Geo.wgs84ToGridLat(lat)
        val gLon = Geo.wgs84ToGridLon(lon)
        val ix = ((gLon - originLon) / res).roundToInt()
        val iy = ((gLat - originLat) / res).roundToInt()
        if (ix < 0 || ix >= nx || iy < 0 || iy >= ny) return 0.0
        return cells[iy * nx + ix] ?: 0.0
    }

    companion object {
        fun parse(json: String, fetchedEpochMs: Long): QpfGrid {
            val o = JSONObject(json)
            val err = o.optString("error", "").ifEmpty { null }
            val issued = if (o.has("issued") && !o.isNull("issued")) o.getString("issued") else null
            val nx = o.optInt("nx", 441)
            val ny = o.optInt("ny", 561)
            val cells = HashMap<Int, Double>()
            val ix = o.optJSONArray("ix")
            val iy = o.optJSONArray("iy")
            val mm = o.optJSONArray("mm")
            if (ix != null && iy != null && mm != null) {
                val n = minOf(ix.length(), iy.length(), mm.length())
                for (k in 0 until n) {
                    cells[iy.getInt(k) * nx + ix.getInt(k)] = mm.getDouble(k)
                }
            }
            return QpfGrid(
                issued = issued,
                fetchedEpochMs = fetchedEpochMs,
                originLat = o.optDouble("originLat", 19.975),
                originLon = o.optDouble("originLon", 117.975),
                res = o.optDouble("res", 0.0125),
                nx = nx, ny = ny,
                cells = cells,
                error = err,
            )
        }
    }
}
