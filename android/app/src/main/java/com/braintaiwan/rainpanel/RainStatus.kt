package com.braintaiwan.rainpanel

/** 號誌燈狀態。 */
enum class Light { GREEN, AMBER, RED, WAITING, NODATA }

/**
 * 一次評估的結果。
 * @param light 號誌
 * @param message 面板上那句話（例：「約 25 分後前方有中雨」）
 * @param severity 用來判斷「雨勢是否明顯變大」→ 要不要再響一次（0=無雨）
 */
data class Assessment(
    val light: Light,
    val message: String,
    val severity: Double = 0.0,
)
