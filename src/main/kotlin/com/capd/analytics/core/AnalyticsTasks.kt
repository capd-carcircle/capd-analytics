package com.capd.analytics.core

import com.capd.analytics.core.NumFormat.numOrNull
import com.capd.analytics.core.NumFormat.pyRound
import com.capd.analytics.core.NumFormat.pySignedFixed
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * analytics.py 포팅분 (원본: ai/tools/analytics.py, backend/app/services/analytics_engine.py)
 *
 * Task 1: Trend Analysis / Task 2: Anomaly Detection /
 * Task 3: Attribute Correlation / Task 4: EDA
 *
 * ⚠️ Python 원본과 로직·수치가 반드시 동일해야 함(정합성 테스트 대상).
 * "statement"(자연어 서술) 필드는 Python str(float) 포맷과 100% 동일한 문자열을
 * 보장하기 어려운 부분이 있어(둘 다 "정확한 최단 십진 표현"을 목표로 하지만 서로 다른
 * 알고리즘 구현), 정합성 테스트에서는 구조화된 수치·판정 필드를 기준으로 비교하고
 * statement는 내용이 동등한 수준인지만 확인한다 -- 상세 사유는 ParityTest.kt 참고.
 */
object AnalyticsTasks {

    private fun valid(series: List<Any?>): List<Double> = series.mapNotNull { numOrNull(it) }

    private fun meanOf(vals: List<Double>): Double? = if (vals.isEmpty()) null else pyRound(vals.sum() / vals.size, 2)

    private fun stdOf(vals: List<Double>): Double? {
        if (vals.size < 2) return null
        val m = vals.sum() / vals.size
        val ss = vals.sumOf { (it - m) * (it - m) }
        return pyRound(sqrt(ss / (vals.size - 1)), 2)
    }

    private fun medianOf(vals: List<Double>): Double? {
        if (vals.isEmpty()) return null
        val s = vals.sorted()
        val n = s.size
        val mid = n / 2
        return if (n % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
    }

    private fun madOf(vals: List<Double>): Double? {
        if (vals.size < 2) return null
        val med = medianOf(vals) ?: return null
        return medianOf(vals.map { abs(it - med) })
    }

    /** 평균 순위 (동점 처리 포함) -- Python _rank와 동일. */
    private fun rankOf(vals: List<Double>): List<Double> {
        val n = vals.size
        val indexed = vals.withIndex().sortedBy { it.value } // Kotlin sortedBy is stable, Python sorted() also stable
        val ranks = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j < n - 1 && indexed[j + 1].value == indexed[j].value) j++
            val avg = (i + j) / 2.0 + 1
            for (k in i..j) ranks[indexed[k].index] = avg
            i = j + 1
        }
        return ranks.toList()
    }

    /** scipy 없이 순수 Kotlin으로 Spearman 상관계수 계산. */
    private fun spearman(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 3) return null
        val rx = rankOf(x)
        val ry = rankOf(y)
        val n = rx.size
        val mx = rx.sum() / n
        val my = ry.sum() / n
        val num = (0 until n).sumOf { (rx[it] - mx) * (ry[it] - my) }
        val denX = sqrt((0 until n).sumOf { (rx[it] - mx) * (rx[it] - mx) })
        val denY = sqrt((0 until n).sumOf { (ry[it] - my) * (ry[it] - my) })
        if (denX == 0.0 || denY == 0.0) return null
        return pyRound(num / (denX * denY), 3)
    }

    private sealed class TrendThresh {
        data class Abs(val much: Double, val normal: Double) : TrendThresh()
        data class Pct(val pctMuch: Double, val pctNormal: Double) : TrendThresh()
    }

    private val TREND_THRESH: LinkedHashMap<String, TrendThresh> = linkedMapOf(
        "body_weight_kg" to TrendThresh.Abs(1.5, 0.5),
        "systolic_bp" to TrendThresh.Abs(15.0, 5.0),
        "diastolic_bp" to TrendThresh.Abs(10.0, 4.0),
        "mean_arterial_pressure" to TrendThresh.Abs(10.0, 4.0),
        "fasting_blood_sugar" to TrendThresh.Abs(30.0, 10.0),
        "urination_count" to TrendThresh.Abs(5.0, 2.0),
        "exchange_count" to TrendThresh.Abs(2.0, 1.0),
        "dwell_mean_minutes" to TrendThresh.Abs(60.0, 30.0),
        "concentration_max" to TrendThresh.Abs(1.0, 0.5),
        "calculated_uf_sum_g" to TrendThresh.Pct(20.0, 10.0),
        "infused_sum_g" to TrendThresh.Pct(20.0, 10.0),
    )

    private val UNITS: Map<String, String> = mapOf(
        "body_weight_kg" to "kg",
        "systolic_bp" to "mmHg",
        "diastolic_bp" to "mmHg",
        "mean_arterial_pressure" to "mmHg",
        "fasting_blood_sugar" to "mg/dL",
        "urination_count" to "회",
        "exchange_count" to "회",
        "dwell_mean_minutes" to "분",
        "concentration_max" to "%",
        "calculated_uf_sum_g" to "g",
        "infused_sum_g" to "g",
    )

    val TREND_ATTRS: List<String> = TREND_THRESH.keys.toList()

    private fun classifyTrend(diff: Double, thresh: TrendThresh, baseline: Double?): String {
        return when (thresh) {
            is TrendThresh.Pct -> {
                if (baseline != null && baseline != 0.0) {
                    val pct = abs(diff) / abs(baseline) * 100
                    if (pct >= thresh.pctMuch) return if (diff > 0) "much_higher_than_baseline" else "much_lower_than_baseline"
                    if (pct >= thresh.pctNormal) return if (diff > 0) "higher_than_baseline" else "lower_than_baseline"
                }
                "stable"
            }
            is TrendThresh.Abs -> {
                if (abs(diff) >= thresh.much) return if (diff > 0) "much_higher_than_baseline" else "much_lower_than_baseline"
                if (abs(diff) >= thresh.normal) return if (diff > 0) "higher_than_baseline" else "lower_than_baseline"
                "stable"
            }
        }
    }

    /** 오늘 값 vs 7일/30일 baseline 비교. historicalRows: 최신->과거 순 (오늘 제외). */
    fun task1TrendAnalysis(todayRow: Map<String, Any?>, historicalRows: List<Map<String, Any?>>): Map<String, Any?> {
        val results = LinkedHashMap<String, Any?>()
        for (attr in TREND_ATTRS) {
            val raw = numOrNull(todayRow[attr]) ?: continue
            val todayVal = raw

            val hist = valid(historicalRows.map { it[attr] })
            val last7d = hist.take(7)
            val last30d = hist.take(30)

            val thresh = TREND_THRESH.getValue(attr)
            val unit = UNITS[attr] ?: ""
            val entry = LinkedHashMap<String, Any?>()
            entry["today_value"] = todayVal
            entry["unit"] = unit

            if (last30d.isNotEmpty()) {
                val m30 = last30d.sum() / last30d.size
                val d30 = pyRound(todayVal - m30, 2)
                entry["previous_30d_mean"] = pyRound(m30, 2)
                entry["difference_from_30d_mean"] = d30
                entry["trend_30d"] = classifyTrend(d30, thresh, m30)
            }

            if (last7d.isNotEmpty()) {
                val m7 = last7d.sum() / last7d.size
                val d7 = pyRound(todayVal - m7, 2)
                val pct = if (m7 != 0.0) pyRound((todayVal - m7) / m7 * 100, 1) else null
                entry["previous_7d_mean"] = pyRound(m7, 2)
                entry["difference_from_7d_mean"] = d7
                entry["percentage_change_from_7d_mean"] = pct
                entry["trend_7d"] = classifyTrend(d7, thresh, m7)
            }

            entry["trend_summary"] = entry["trend_30d"] ?: entry["trend_7d"] ?: "insufficient_data"

            val parts = mutableListOf("오늘 값 $todayVal $unit.")
            if (entry.containsKey("trend_30d")) {
                parts.add(
                    "30일 평균 ${entry["previous_30d_mean"]} $unit 대비 " +
                        "${pySignedFixed(entry["difference_from_30d_mean"] as Double, 2)} $unit (${entry["trend_30d"]})."
                )
            }
            if (entry.containsKey("trend_7d")) {
                parts.add(
                    "7일 평균 ${entry["previous_7d_mean"]} $unit 대비 " +
                        "${pySignedFixed(entry["difference_from_7d_mean"] as Double, 2)} $unit (${entry["trend_7d"]})."
                )
            }
            if (parts.size == 1) parts.add("(과거 데이터 없음)")
            entry["statement"] = parts.joinToString(" ")

            results[attr] = entry
        }
        return mapOf("task" to "trend_analysis", "results" to results)
    }

    // reported_total_uf_g·recorded_uf_sum_g는 여기 포함하지 않음 -- 셋 다 "배액량-주입량"이라는
    // 같은 원본에서 나온 같은 값이라 calculated_uf_sum_g 하나만 대표로 씀 (Python 원본과 동일 사유).
    val ANOMALY_ATTRS: List<String> = listOf(
        "body_weight_kg",
        "calculated_uf_sum_g",
        "systolic_bp",
        "diastolic_bp",
        "mean_arterial_pressure",
        "fasting_blood_sugar",
        "infused_sum_g",
    )

    private val Z_LEVELS: List<Pair<Double, String>> = listOf(3.0 to "strong_anomaly", 2.0 to "mild_anomaly")

    private fun zLabel(z: Double): String {
        for ((thresh, label) in Z_LEVELS) if (abs(z) >= thresh) return label
        return "normal"
    }

    fun task2AnomalyDetection(todayRow: Map<String, Any?>, historicalRows: List<Map<String, Any?>>): Map<String, Any?> {
        val results = LinkedHashMap<String, Any?>()
        for (attr in ANOMALY_ATTRS) {
            val todayVal = numOrNull(todayRow[attr]) ?: continue
            val unit = UNITS[attr] ?: ""

            val hist = valid(historicalRows.take(30).map { it[attr] })

            if (hist.size < 3) {
                results[attr] = mapOf(
                    "today_value" to todayVal,
                    "sufficient_data" to false,
                    "statement" to "오늘 값 $todayVal $unit -- 과거 데이터 부족 (${hist.size}개, 최소 3개 필요)",
                )
                continue
            }

            val mean30 = hist.sum() / hist.size
            val std30 = stdOf(hist) ?: 0.001
            val med30 = medianOf(hist) ?: mean30
            val mad30 = madOf(hist) ?: 0.001

            val zScore = pyRound((todayVal - mean30) / std30, 3)
            val robustZ = pyRound(0.6745 * (todayVal - med30) / mad30, 3)

            val zLbl = zLabel(zScore)
            val robustLbl = zLabel(robustZ)

            val statement = "오늘 값 $todayVal $unit, 30일 평균 ${pyRound(mean30, 2)} $unit, " +
                "표준편차 ${pyRound(std30, 2)} $unit. " +
                "Rolling z-score: $zScore -> $zLbl. " +
                "Robust z-score: $robustZ -> $robustLbl."

            results[attr] = mapOf(
                "today_value" to todayVal,
                "baseline_mean" to pyRound(mean30, 2),
                "baseline_std" to pyRound(std30, 2),
                "z_score_30d" to zScore,
                "z_interpretation" to zLbl,
                "robust_z_score" to robustZ,
                "robust_interpretation" to robustLbl,
                "is_anomaly" to (zLbl != "normal" || robustLbl != "normal"),
                "sufficient_data" to true,
                "statement" to statement,
            )
        }
        return mapOf("task" to "anomaly_detection", "results" to results)
    }

    val CORR_ATTRS: List<String> = listOf(
        "body_weight_kg",
        "calculated_uf_sum_g",
        "systolic_bp",
        "diastolic_bp",
        "mean_arterial_pressure",
        "fasting_blood_sugar",
        "urination_count",
        "exchange_count",
        "infused_sum_g",
        "dwell_mean_minutes",
        "concentration_max",
    )

    private val CORR_LEVELS: List<Pair<Double, String>> = listOf(0.9 to "very strong", 0.7 to "strong", 0.5 to "moderate")

    private fun corrLabel(r: Double): String {
        for ((thresh, label) in CORR_LEVELS) if (abs(r) >= thresh) return label
        return "weak"
    }

    /** 최근 window일치 Spearman 상관관계 -- 계산 가능한 쌍은 전부 반환(상관계수 무관). */
    fun task3AttributeCorrelation(historicalRows: List<Map<String, Any?>>, window: Int = 30): Map<String, Any?> {
        val rows = historicalRows.take(window)
        if (rows.size < 7) {
            return mapOf(
                "task" to "attribute_correlation",
                "method" to "spearman_correlation",
                "window_days" to rows.size,
                "results" to emptyList<Any?>(),
                "note" to "데이터 부족 (${rows.size}일) -- 최소 7일 필요",
            )
        }

        val series = LinkedHashMap<String, List<Double>>()
        for (attr in CORR_ATTRS) {
            val vals = valid(rows.map { it[attr] })
            if (vals.size >= 7) series[attr] = vals
        }

        val attrs = series.keys.toList()
        val pairs = mutableListOf<Map<String, Any?>>()

        for (i in attrs.indices) {
            for (j in (i + 1) until attrs.size) {
                val a1 = attrs[i]
                val a2 = attrs[j]
                val xList = mutableListOf<Double>()
                val yList = mutableListOf<Double>()
                for (r in rows) {
                    val v1 = numOrNull(r[a1])
                    val v2 = numOrNull(r[a2])
                    if (v1 != null && v2 != null) {
                        xList.add(v1)
                        yList.add(v2)
                    }
                }
                if (xList.size < 7) continue

                val corr = spearman(xList, yList) ?: continue
                val direction = if (corr > 0) "positive" else "negative"
                val label = corrLabel(corr)
                pairs.add(
                    mapOf(
                        "attr1" to a1,
                        "attr2" to a2,
                        "correlation" to corr,
                        "direction" to direction,
                        "interpretation" to label,
                        "statement" to "$a1 and $a2 has a correlation of $corr showing a $label $direction correlation.",
                    )
                )
            }
        }

        pairs.sortByDescending { abs(it["correlation"] as Double) }

        return mapOf(
            "task" to "attribute_correlation",
            "method" to "spearman_correlation",
            "window_days" to rows.size,
            "results" to pairs,
        )
    }

    val EDA_ATTRS: List<String> = CORR_ATTRS

    fun task4Eda(todayRow: Map<String, Any?>, historicalRows: List<Map<String, Any?>>): Map<String, Any?> {
        val results = LinkedHashMap<String, Any?>()
        for (attr in EDA_ATTRS) {
            val todayRaw = numOrNull(todayRow[attr])
            val hist30 = valid(historicalRows.take(30).map { it[attr] })
            val hist7 = valid(historicalRows.take(7).map { it[attr] })

            val entry = LinkedHashMap<String, Any?>()
            if (todayRaw != null) entry["today_value"] = todayRaw
            if (hist30.isNotEmpty()) {
                entry["recent_30d_mean"] = meanOf(hist30)
                entry["recent_30d_std"] = stdOf(hist30)
                entry["recent_30d_min"] = pyRound(hist30.min(), 2)
                entry["recent_30d_max"] = pyRound(hist30.max(), 2)
            }
            if (hist7.isNotEmpty()) {
                entry["recent_7d_mean"] = meanOf(hist7)
                entry["recent_7d_min"] = pyRound(hist7.min(), 2)
                entry["recent_7d_max"] = pyRound(hist7.max(), 2)
            }

            if (entry.isNotEmpty()) results[attr] = entry
        }
        return mapOf("task" to "exploratory_data_analysis", "results" to results)
    }

    /**
     * 4가지 분석 Task 모두 실행.
     * window: task3(상관관계)에 쓸 최근 며칠치 기준(기본 30일). task1/2/4는 "오늘 vs 7일/30일
     * 평균"이라는 고정된 통계 정의라 window와 무관하게 항상 7일·30일 기준 그대로 계산함(의도된 동작).
     */
    fun runAllTasks(todayRow: Map<String, Any?>, historicalRows: List<Map<String, Any?>>, window: Int = 30): Map<String, Any?> {
        val trend = task1TrendAnalysis(todayRow, historicalRows)
        val anomaly = task2AnomalyDetection(todayRow, historicalRows)
        val corr = task3AttributeCorrelation(historicalRows, window)
        val eda = task4Eda(todayRow, historicalRows)

        @Suppress("UNCHECKED_CAST")
        val anomalyResults = anomaly["results"] as Map<String, Any?>
        val anomalyAttrs = anomalyResults.entries
            .filter { (it.value as? Map<*, *>)?.get("is_anomaly") == true }
            .map { it.key }

        return mapOf(
            "trend_analysis" to trend,
            "anomaly_detection" to anomaly,
            "attribute_correlation" to corr,
            "eda" to eda,
            "has_anomaly" to anomalyAttrs.isNotEmpty(),
            "anomaly_attrs" to anomalyAttrs,
        )
    }
}
