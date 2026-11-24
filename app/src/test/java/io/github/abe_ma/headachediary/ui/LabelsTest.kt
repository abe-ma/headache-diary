package io.github.abe_ma.headachediary.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {
    @Test
    fun FR404_前日差は符号付き小数1桁で表す() {
        assertEquals("+1.2", formatPressureDelta(1.2))
        assertEquals("-3.4", formatPressureDelta(-3.4))
        assertEquals("0.0", formatPressureDelta(0.0))
        assertEquals("+10.0", formatPressureDelta(10.0))
        assertEquals("-0.5", formatPressureDelta(-0.5))
    }

    @Test
    fun FR404_丸めて0になる差は符号なしの0_0にする() {
        assertEquals("0.0", formatPressureDelta(-0.04))
        assertEquals("0.0", formatPressureDelta(0.04))
        assertEquals("0.0", formatPressureDelta(-0.0))
    }

    @Test
    fun FR404_読み上げの向きは低い_高い_同じ() {
        assertEquals(DeltaDirection.LOWER, pressureDeltaDirection(-3.4))
        assertEquals(DeltaDirection.HIGHER, pressureDeltaDirection(1.2))
        assertEquals(DeltaDirection.SAME, pressureDeltaDirection(0.0))
        assertEquals(DeltaDirection.SAME, pressureDeltaDirection(-0.04)) // 丸めて 0.0
        assertEquals("3.4", formatPressureDeltaMagnitude(-3.4))
    }

    @Test
    fun FR404_気圧は小数1桁() {
        assertEquals("1013.2", formatPressure(1013.2))
        assertEquals("1000.0", formatPressure(1000.0))
    }
}
