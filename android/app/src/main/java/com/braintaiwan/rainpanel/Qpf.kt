package com.braintaiwan.rainpanel

import org.json.JSONObject
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * 氣象署「未來 1 小時雷達定量降雨預報」網格（幕後幫手縮小後的 qpf.json）。
 *
 * 官方排列：左下角為第一點（東經 117.975、北緯 19.975），先經度遞增（西→東），
 * 再緯度遞增（南→北）。qpf.json 只留「有雨」的格子。
 */
class QpfGrid private constructor(
    val issued: String?,
    /** 這份 qpf.json 是「本機何時下載」的（給 QpfRepository 當重抓節流用，不代表預報新舊）。 */
    val downloadedEpochMs: Long,
    /** 預報本身的「發布時刻」（來自 qpf.json 的 issued 欄位），0 = 不明。新舊判斷看這個。 */
    private val issuedEpochMs: Long,
    private val originLat: Double,
    private val originLon: Double,
    private val res: Double,
    private val nx: Int,
    private val ny: Int,
    private val cells: HashMap<Int, Double>, // key = iy * nx + ix
    val error: String?,
) {
    val hasData: Boolean get() = error == null && issued != null

    /**
     * 預報是否夠新——看「發布時刻」離現在多久，不是看本機何時下載。
     * 後端每 ~12 分更新一次；發布時刻通常落後現在 15–25 分。預設 55 分內算堪用。
     */
    fun isFresh(nowMs: Long, maxAgeMs: Long = 55 * 60_000L) =
        hasData && issuedEpochMs > 0 && nowMs - issuedEpochMs <= maxAgeMs

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
        fun parse(json: String, downloadedEpochMs: Long): QpfGrid {
            val o = JSONObject(json)
            val err = o.optString("error", "").ifEmpty { null }
            val issued = if (o.has("issued") && !o.isNull("issued")) o.getString("issued") else null
            val issuedEpochMs = issued?.let {
                runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrDefault(0L)
            } ?: 0L
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
                downloadedEpochMs = downloadedEpochMs,
                issuedEpochMs = issuedEpochMs,
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
