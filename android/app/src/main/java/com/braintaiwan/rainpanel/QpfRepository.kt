package com.braintaiwan.rainpanel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 抓幕後幫手產生的 qpf.json（放在 data 孤兒分支，raw.githubusercontent 有 CORS/直連）。
 * 抓成功就寫一份到本機，之後抓失敗時退回用本機這份（App 自己會依時間拒收太舊的）。
 */
object QpfRepository {

    private const val URL_BASE =
        "https://raw.githubusercontent.com/ymeroom/biking-gps-weather-tw/data/qpf.json"
    private const val REFRESH_MS = 8 * 60_000L      // 8 分鐘內不重抓（氣象署 10 分更新一次）
    private const val CACHE_FILE = "qpf.json"

    @Volatile private var cached: QpfGrid? = null

    /** 拿目前最好的網格；必要時重抓。永遠不丟例外。 */
    suspend fun get(ctx: Context): QpfGrid? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val c = cached
        if (c != null && now - c.downloadedEpochMs < REFRESH_MS) return@withContext c

        val fetched = runCatching { download() }.getOrNull()
        if (fetched != null) {
            runCatching { File(ctx.filesDir, CACHE_FILE).writeText(fetched) }
            cached = QpfGrid.parse(fetched, now)
            return@withContext cached
        }

        // 抓失敗 → 用本機上次那份
        if (cached == null) {
            val f = File(ctx.filesDir, CACHE_FILE)
            if (f.exists()) {
                cached = runCatching { QpfGrid.parse(f.readText(), f.lastModified()) }.getOrNull()
            }
        }
        cached
    }

    private fun download(): String {
        val url = URL("$URL_BASE?v=${System.currentTimeMillis() / 60000}") // 分鐘級穿透 CDN 快取
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
