package com.capd.analytics.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Python round()/format() 호환 숫자 유틸.
 *
 * Python의 round(x, n)은 x의 "정확한" 이진 실수값을 십진수로 correctly-rounded
 * 방식(round-half-to-even)으로 반올림한다(David Gay's dtoa 알고리즘 기반).
 * `BigDecimal(double)`(valueOf 아님, 생성자로 직접)로 만들면 그 double의 정확한
 * 이진값을 그대로 담으므로, 여기에 setScale(n, HALF_EVEN)을 적용하면 Python의
 * round()와 동일한 결과가 나온다. ai/tools·backend 원본과 반드시 동일한 값을 내야
 * 하므로(정합성 테스트 대상) 이 방식을 사용.
 */
object NumFormat {

    fun pyRound(value: Double, digits: Int): Double {
        if (value.isNaN() || value.isInfinite()) return value
        return BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    }

    /** Python의 f"{value:+.{digits}f}" 포맷과 동일한 문자열(부호 항상 표시). */
    fun pySignedFixed(value: Double, digits: Int): String {
        val bd = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN)
        val isNegativeZero = value == 0.0 && 1.0 / value < 0
        return if (bd.signum() < 0 || isNegativeZero) {
            if (bd.signum() < 0) bd.toPlainString() else "-" + bd.toPlainString()
        } else {
            "+" + bd.toPlainString()
        }
    }

    /** Any? -> Double? (Int/Long/Float/Double/BigDecimal 등 어떤 Number 타입이든). */
    fun numOrNull(v: Any?): Double? = when (v) {
        null -> null
        is Double -> v
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    fun intOrNull(v: Any?): Int? = when (v) {
        null -> null
        is Int -> v
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}
