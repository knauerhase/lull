package com.knauerhase.lull.bedjet

import kotlin.test.Test
import kotlin.test.assertEquals

class HeatTest {
    @Test
    fun coolTargetsAllowTheFullTwelveHours() {
        assertEquals(11 * 60 + 59, heatLongestMinutes(temperature = 50, fanStep = 0))
    }

    @Test
    fun gentlestFanAtSixtyDegreesIsCappedJustUnderTwelveHours() {
        // Row 0, column 0 is 0xcc: twelve hours, but the unit refuses steps of twelve hours or more.
        assertEquals(11 * 60 + 59, heatLongestMinutes(temperature = 60, fanStep = 0))
    }

    @Test
    fun gentlestFanAtEightyHalfDegreesAllowsFourHours() {
        // Row 0, column 10 is 0x41: an even target reads the high nibble, four hours.
        assertEquals(4 * 60, heatLongestMinutes(temperature = 80, fanStep = 0))
    }

    @Test
    fun strongestFanAtTheHottestTargetAllowsOneHour() {
        // Row 19, column 10 is 0x11: one hour.
        assertEquals(60, heatLongestMinutes(temperature = 80, fanStep = 19))
    }
}
