package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.telemetry.PowerAnalyzer
import io.hammerhead.pacepilot.util.ZoneCalculator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PowerAnalyzerTest {

    private lateinit var analyzer: PowerAnalyzer

    @Before
    fun setup() {
        analyzer = PowerAnalyzer()
    }

    @Test
    fun `5s avg is correct after 5 samples`() {
        repeat(5) { analyzer.onPowerSample(200, 250) }
        assertEquals(200, analyzer.power5sAvg)
    }

    @Test
    fun `5s avg is rolling window not cumulative`() {
        repeat(5) { analyzer.onPowerSample(200, 250) }
        repeat(5) { analyzer.onPowerSample(300, 250) }
        assertEquals(300, analyzer.power5sAvg)
    }

    @Test
    fun `30s avg reflects recent samples`() {
        repeat(30) { analyzer.onPowerSample(250, 250) }
        assertEquals(250, analyzer.power30sAvg)
    }

    @Test
    fun `zone detection is correct for FTP 250`() {
        // Z1: < 55% of 250 = < 137W
        assertEquals(1, ZoneCalculator.powerZone(130, 250))
        // Z2: 55-75% = 137-187W
        assertEquals(2, ZoneCalculator.powerZone(160, 250))
        // Z3: 76-90% = 190-225W
        assertEquals(3, ZoneCalculator.powerZone(210, 250))
        // Z4: 91-105% = 227-262W
        assertEquals(4, ZoneCalculator.powerZone(250, 250))
        // Z5: 106-120% = 265-300W
        assertEquals(5, ZoneCalculator.powerZone(275, 250))
        // Z6: 121-150% = 302-375W
        assertEquals(6, ZoneCalculator.powerZone(320, 250))
        // Z7: > 150% = > 375W
        assertEquals(7, ZoneCalculator.powerZone(400, 250))
    }

    @Test
    fun `isPowerFading detects declining trend across 3 sets`() {
        // Add 3 effort sets with declining averages
        analyzer.startEffortInterval()
        repeat(60) { analyzer.onPowerSample(270, 250) }
        analyzer.endEffortInterval()

        analyzer.startEffortInterval()
        repeat(60) { analyzer.onPowerSample(255, 250) }
        analyzer.endEffortInterval()

        analyzer.startEffortInterval()
        repeat(60) { analyzer.onPowerSample(238, 250) }
        analyzer.endEffortInterval()

        assertTrue(analyzer.isPowerFading())
    }

    @Test
    fun `isPowerFading returns false for stable power`() {
        analyzer.startEffortInterval()
        repeat(60) { analyzer.onPowerSample(250, 250) }
        analyzer.endEffortInterval()

        analyzer.startEffortInterval()
        repeat(60) { analyzer.onPowerSample(248, 250) }
        analyzer.endEffortInterval()

        analyzer.startEffortInterval()
        repeat(60) { analyzer.onPowerSample(252, 250) }
        analyzer.endEffortInterval()

        assertFalse(analyzer.isPowerFading())
    }

    @Test
    fun `isPowerFading returns false with fewer than 3 sets`() {
        analyzer.startEffortInterval()
        repeat(60) { analyzer.onPowerSample(270, 250) }
        analyzer.endEffortInterval()

        analyzer.startEffortInterval()
        repeat(60) { analyzer.onPowerSample(240, 250) }
        analyzer.endEffortInterval()

        assertFalse(analyzer.isPowerFading())
    }

    @Test
    fun `complianceScore is 1 when all samples in range`() {
        repeat(30) {
            analyzer.onPowerSample(240, 250)
            analyzer.onIntervalCompliance(230, 250)
        }
        assertEquals(1.0f, analyzer.complianceScore(), 0.01f)
    }

    @Test
    fun `complianceScore is 0 when all samples out of range`() {
        repeat(30) {
            analyzer.onPowerSample(200, 250)
            analyzer.onIntervalCompliance(230, 250)
        }
        assertEquals(0.0f, analyzer.complianceScore(), 0.01f)
    }

    @Test
    fun `isSustainedZ1 returns false when not enough samples`() {
        repeat(30) { analyzer.onPowerSample(100, 250) } // 30s of Z1
        assertFalse(analyzer.isSustainedZ1(60)) // need 60s
    }

    @Test
    fun `isSustainedZ1 returns true after sustained Z1`() {
        repeat(60) { analyzer.onPowerSample(100, 250) } // 60s of Z1 (< 137W)
        assertTrue(analyzer.isSustainedZ1(60))
    }

    @Test
    fun `resetForNewRide clears all state`() {
        repeat(30) { analyzer.onPowerSample(250, 250) }
        analyzer.resetForNewRide()
        assertEquals(0, analyzer.power5sAvg)
        assertEquals(0, analyzer.power30sAvg)
    }
}
