package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.util.ZoneCalculator
import org.junit.Assert.*
import org.junit.Test

class ZoneCalculatorTest {

    @Test
    fun `power zone boundaries are correct for FTP 250`() {
        val ftp = 250
        assertEquals(1, ZoneCalculator.powerZone(100, ftp))  // < 55% = < 137W
        assertEquals(1, ZoneCalculator.powerZone(136, ftp))
        assertEquals(2, ZoneCalculator.powerZone(138, ftp))  // 55-75%
        assertEquals(2, ZoneCalculator.powerZone(187, ftp))
        assertEquals(3, ZoneCalculator.powerZone(190, ftp))  // 76-90%
        assertEquals(3, ZoneCalculator.powerZone(225, ftp))
        assertEquals(4, ZoneCalculator.powerZone(228, ftp))  // 91-105% (228/250 = 91.2% → 91 int div)
        assertEquals(4, ZoneCalculator.powerZone(262, ftp))
        assertEquals(5, ZoneCalculator.powerZone(265, ftp))  // 106-120%
        assertEquals(5, ZoneCalculator.powerZone(300, ftp))
        assertEquals(6, ZoneCalculator.powerZone(303, ftp))  // 121-150% (303/250 = 121.2% → 121 int div)
        assertEquals(6, ZoneCalculator.powerZone(375, ftp))
        assertEquals(7, ZoneCalculator.powerZone(378, ftp))  // > 150% (378/250 = 151.2% → 151 int div)
    }

    @Test
    fun `hr zone boundaries are correct for maxHr 180`() {
        val maxHr = 180
        assertEquals(1, ZoneCalculator.hrZone(100, maxHr))   // < 60% = < 108bpm
        assertEquals(1, ZoneCalculator.hrZone(108, maxHr))
        assertEquals(2, ZoneCalculator.hrZone(110, maxHr))   // 61-70%
        assertEquals(2, ZoneCalculator.hrZone(126, maxHr))
        assertEquals(3, ZoneCalculator.hrZone(128, maxHr))   // 71-80%
        assertEquals(3, ZoneCalculator.hrZone(144, maxHr))
        assertEquals(4, ZoneCalculator.hrZone(146, maxHr))   // 81-90%
        assertEquals(4, ZoneCalculator.hrZone(162, maxHr))
        assertEquals(5, ZoneCalculator.hrZone(164, maxHr))   // > 90%
        assertEquals(5, ZoneCalculator.hrZone(180, maxHr))
    }

    @Test
    fun `isInTargetRange returns true when within range`() {
        assertTrue(ZoneCalculator.isInTargetRange(240, 230, 250))
        assertTrue(ZoneCalculator.isInTargetRange(230, 230, 250))
        assertTrue(ZoneCalculator.isInTargetRange(250, 230, 250))
    }

    @Test
    fun `isInTargetRange returns false when outside range`() {
        assertFalse(ZoneCalculator.isInTargetRange(229, 230, 250))
        assertFalse(ZoneCalculator.isInTargetRange(251, 230, 250))
    }

    @Test
    fun `wattsOverCeiling returns positive when over`() {
        assertEquals(15, ZoneCalculator.wattsOverCeiling(265, 250))
    }

    @Test
    fun `wattsOverCeiling returns negative when under ceiling`() {
        assertEquals(-10, ZoneCalculator.wattsOverCeiling(240, 250))
    }

    @Test
    fun `wattsBelowFloor returns positive when under floor`() {
        assertEquals(20, ZoneCalculator.wattsBelowFloor(210, 230))
    }

    @Test
    fun `normalizedPower computes 4th root correctly`() {
        // All same value: NP should equal avg
        val np = ZoneCalculator.normalizedPower(List(100) { 250 })
        assertEquals(250, np)
    }

    @Test
    fun `zone returns 0 for zero or negative inputs`() {
        assertEquals(0, ZoneCalculator.powerZone(0, 250))
        assertEquals(0, ZoneCalculator.powerZone(200, 0))
        assertEquals(0, ZoneCalculator.hrZone(0, 180))
        assertEquals(0, ZoneCalculator.hrZone(150, 0))
    }
}
