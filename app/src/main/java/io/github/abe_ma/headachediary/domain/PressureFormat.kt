package io.github.abe_ma.headachediary.domain

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * 気圧差の表記規則 (FR-404)。UI・CSV・PDF で共用する唯一の実装 (画面と書き出しで表記がずれないように)。
 * 丸めは絶対値に対する HALF_UP (小数 1 桁)。符号は丸め前の値の符号で、丸めて 0.0 になるものは符号なしの `0.0`。
 */
object PressureFormat {
    private fun rounded(v: Double): BigDecimal = BigDecimal.valueOf(abs(v)).setScale(1, RoundingMode.HALF_UP)

    /** 差の大きさ (符号なし、小数 1 桁)。ロケールに依存しない。 */
    fun magnitude(deltaHpa: Double): String = rounded(deltaHpa).toPlainString()

    /** 符号付き小数 1 桁: `+1.2` / `-3.4` / `0.0`。 */
    fun delta(deltaHpa: Double): String {
        val r = rounded(deltaHpa)
        if (r.signum() == 0) return "0.0"
        return (if (deltaHpa < 0) "-" else "+") + r.toPlainString()
    }
}
