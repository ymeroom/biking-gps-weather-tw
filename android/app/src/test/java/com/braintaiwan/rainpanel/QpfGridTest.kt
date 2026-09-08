package com.braintaiwan.rainpanel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用一份真實的 qpf_sample.json（2026-09-07 23:50 發布）驗證網格索引與座標換算。 */
class QpfGridTest {

    private fun sample(): QpfGrid {
        val json = javaClass.classLoader!!.getResourceAsStream("qpf_sample.json")!!
            .bufferedReader().readText()
        return QpfGrid.parse(json, fetchedEpochMs = 1_000_000L)
    }

    @Test
    fun parsesMetadata() {
        val g = sample()
        assertTrue(g.hasData)
        assertEquals("2026-09-07T23:50:00+08:00", g.issued)
    }

    @Test
    fun strongestCellReadsBack() {
        val g = sample()
        // 最強格在 TWD67 網格 (lon 119.8125, lat 23.8625) = 33.6mm。
        // mmAt 收 WGS84，內部會加上 TWD67 偏移，所以反推回 WGS84：
        val wgsLat = 23.8625 - 0.0019
        val wgsLon = 119.8125 + 0.0081
        assertEquals(33.6, g.mmAt(wgsLat, wgsLon), 0.05)
    }

    @Test
    fun dryPlaceReadsZero() {
        val g = sample()
        // 台北市區這份預報沒有雨
        assertEquals(0.0, g.mmAt(25.033, 121.565), 0.001)
    }

    @Test
    fun outOfRangeReadsZero() {
        val g = sample()
        assertEquals(0.0, g.mmAt(35.0, 140.0), 0.001) // 日本
    }

    @Test
    fun freshnessRespectsAge() {
        val g = QpfGrid.parse(
            javaClass.classLoader!!.getResourceAsStream("qpf_sample.json")!!.bufferedReader().readText(),
            fetchedEpochMs = 1_000_000L,
        )
        assertTrue(g.isFresh(nowMs = 1_000_000L + 10 * 60_000L))
        assertTrue(!g.isFresh(nowMs = 1_000_000L + 50 * 60_000L))
    }

    @Test
    fun errorStubHasNoData() {
        val g = QpfGrid.parse(
            """{"product":"F-B0046-001","issued":null,"error":"TimeoutError: x","ix":[],"iy":[],"mm":[]}""",
            fetchedEpochMs = 1_000_000L,
        )
        assertTrue(!g.hasData)
        assertEquals(0.0, g.mmAt(24.0, 121.0), 0.0)
    }
}
