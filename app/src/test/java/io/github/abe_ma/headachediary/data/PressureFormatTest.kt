package io.github.abe_ma.headachediary.data

import io.github.abe_ma.headachediary.domain.PressureFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class PressureFormatTest {
    @Test
    fun FR_404_delta_is_signed_one_decimal_with_zero_unsigned() {
        assertEquals("+1.2", PressureFormat.delta(1.2)); assertEquals("-3.4", PressureFormat.delta(-3.4))
        assertEquals("0.0", PressureFormat.delta(0.0)); assertEquals("0.0", PressureFormat.delta(-0.0))
        assertEquals("0.0", PressureFormat.delta(0.04)); assertEquals("0.0", PressureFormat.delta(-0.04))
        assertEquals("+10.0", PressureFormat.delta(10.0)); assertEquals("-0.5", PressureFormat.delta(-0.5))
    }

    @Test
    fun FR_404_rounding_is_half_up_on_the_absolute_value_so_signs_are_symmetric() {
        assertEquals("+0.3", PressureFormat.delta(0.25)); assertEquals("-0.3", PressureFormat.delta(-0.25))
        assertEquals("+0.1", PressureFormat.delta(0.05)); assertEquals("-0.1", PressureFormat.delta(-0.05))
        assertEquals("+2.0", PressureFormat.delta(1.95)); assertEquals("-2.0", PressureFormat.delta(-1.95))
        assertEquals("0.3", PressureFormat.magnitude(-0.25)); assertEquals("3.4", PressureFormat.magnitude(-3.44))
    }

    @Test
    fun FR_404_format_is_locale_independent() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // 小数点がカンマのロケールでも '.' を使う
            assertEquals("+1.2", PressureFormat.delta(1.2)); assertEquals("1.2", PressureFormat.magnitude(-1.2))
        } finally { java.util.Locale.setDefault(old) }
    }
}
