package com.capd.analytics.core

import com.capd.analytics.core.NumFormat.intOrNull
import com.capd.analytics.core.NumFormat.numOrNull
import com.capd.analytics.core.NumFormat.pyRound

/**
 * data_engineering.py 포팅분 (원본: ai/tools/data_engineering.py, backend/app/services/analytics_engine.py)
 *
 * Exchange Event Table -> Exchange Aggregate Table -> Daily Table -> Daily Model Row
 *
 * ⚠️ Python 원본과 로직이 반드시 동일해야 함 -- 두 언어 버전이 같은 입력에 다른 결과를
 * 내면 안 됨(정합성 테스트: src/test/kotlin/.../ParityTest.kt, fixture: backend/tests/synth.py
 * 로 생성한 데이터를 공유). 원본 수정 시 이 파일도 함께 수정.
 */
object DataEngineering {

    /** HH:MM -> 자정 이후 분 변환. null/파싱실패면 null. */
    fun timeToMinutes(timeStr: String?): Int? {
        if (timeStr.isNullOrBlank()) return null
        return try {
            val parts = timeStr.trim().split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } catch (e: Exception) {
            null
        }
    }

    /** 혈압 문자열 -> {systolic_bp, diastolic_bp, pulse_pressure, mean_arterial_pressure} */
    fun parseBp(bpStr: String?): Map<String, Any?> {
        if (bpStr.isNullOrBlank()) return emptyMap()
        return try {
            val parts = bpStr.trim().split("/")
            val sysBp = parts[0].toInt()
            val diaBp = parts[1].toInt()
            mapOf(
                "systolic_bp" to sysBp,
                "diastolic_bp" to diaBp,
                "pulse_pressure" to (sysBp - diaBp),
                "mean_arterial_pressure" to pyRound(diaBp + (sysBp - diaBp) / 3.0, 1),
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * raw exchange_records -> Exchange Event Table (슬롯별 파생 속성 계산)
     * 파생: exchange_time_minutes, observed_flag, dwell_minutes, calculated_uf_g, uf_error_g
     */
    fun buildExchangeEvents(exchangeRecords: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val sortedRecs = exchangeRecords.sortedBy { intOrNull(it["session_number"]) ?: 0 }

        val events = mutableListOf<Map<String, Any?>>()
        var prevMinutes: Int? = null

        for (ex in sortedRecs) {
            val drainage = numOrNull(ex["drainage_volume"])
            val infused = numOrNull(ex["infusion_weight"])
            val reportedUf = numOrNull(ex["ultrafiltration"])
            val timeStr = ex["exchange_time"] as? String
            val timeMin = timeToMinutes(timeStr)
            val conc = numOrNull(ex["infusion_concentration"])

            val observed = if (drainage != null && infused != null) 1 else 0

            var dwell: Int? = null
            if (timeMin != null && prevMinutes != null) {
                var diff = timeMin - prevMinutes
                if (diff < 0) diff += 24 * 60
                dwell = diff
            }

            var calcUf: Double? = null
            if (drainage != null && infused != null) {
                calcUf = drainage - infused
            }

            var ufError: Double? = null
            if (reportedUf != null && calcUf != null) {
                ufError = reportedUf - calcUf
            }

            events.add(
                mapOf(
                    "session_number" to intOrNull(ex["session_number"]),
                    "exchange_time" to timeStr,
                    "exchange_time_minutes" to timeMin,
                    "drainage_volume" to drainage,
                    "infusion_concentration" to conc,
                    "infusion_weight" to infused,
                    "ultrafiltration" to reportedUf,
                    "observed_flag" to observed,
                    "dwell_minutes" to dwell,
                    "calculated_uf_g" to calcUf?.let { pyRound(it, 1) },
                    "uf_error_g" to ufError?.let { pyRound(it, 1) },
                )
            )

            if (timeMin != null) prevMinutes = timeMin
        }

        return events
    }

    /** 표본표준편차 (n-1), Python statistics.stdev와 동일한 정의. */
    private fun sampleStdev(vals: List<Double>): Double? {
        if (vals.size < 2) return null
        val m = vals.sum() / vals.size
        val ss = vals.sumOf { (it - m) * (it - m) }
        return Math.sqrt(ss / (vals.size - 1))
    }

    /**
     * Exchange Event Table -> Exchange Aggregate (일 단위 집계)
     */
    fun aggregateExchanges(events: List<Map<String, Any?>>): Map<String, Any?> {
        val observed = events.filter { it["observed_flag"] == 1 }
        val exchangeCount = observed.size
        val missingSlots = 5 - exchangeCount

        val drainageVals = observed.mapNotNull { numOrNull(it["drainage_volume"]) }
        val infusedVals = observed.mapNotNull { numOrNull(it["infusion_weight"]) }
        val drainSum = drainageVals.sum()
        val infusedSum = infusedVals.sum()

        val recordedUfs = observed.mapNotNull { numOrNull(it["ultrafiltration"]) }
        val recordedUfSum = if (recordedUfs.isNotEmpty()) pyRound(recordedUfs.sum(), 1) else null

        val calcUfs = observed.mapNotNull { numOrNull(it["calculated_uf_g"]) }
        val calcUfSum = if (calcUfs.isNotEmpty()) pyRound(calcUfs.sum(), 1) else null
        val ufMin = if (calcUfs.isNotEmpty()) pyRound(calcUfs.min(), 1) else null
        val ufStd = sampleStdev(calcUfs)?.let { pyRound(it, 1) }

        val dwells = events.mapNotNull { (it["dwell_minutes"] as? Int)?.toDouble() }
        val dwellMean = if (dwells.isNotEmpty()) pyRound(dwells.sum() / dwells.size, 1) else null
        val dwellStd = sampleStdev(dwells)?.let { pyRound(it, 1) }

        val concs = observed.mapNotNull { numOrNull(it["infusion_concentration"]) }
        val concMax = if (concs.isNotEmpty()) concs.max() else null

        return mapOf(
            "exchange_count" to exchangeCount,
            "missing_exchange_slots" to missingSlots,
            // Python: round(drain_sum, 1) if drain_sum else None -- drain_sum==0.0 이면 falsy라 None.
            "drain_sum_g" to (if (drainSum != 0.0) pyRound(drainSum, 1) else null),
            "infused_sum_g" to (if (infusedSum != 0.0) pyRound(infusedSum, 1) else null),
            "recorded_uf_sum_g" to recordedUfSum,
            "calculated_uf_sum_g" to calcUfSum,
            "uf_min_g" to ufMin,
            "uf_std_g" to ufStd,
            "dwell_mean_minutes" to dwellMean,
            "dwell_std_minutes" to dwellStd,
            "concentration_max" to concMax,
        )
    }

    /**
     * 하루치 기록 -> Daily Model Row (25개 컬럼). run_all_tasks()에 바로 입력 가능.
     *
     * dailyData: date/record_date, weight, blood_pressure, total_ultrafiltration,
     *            turbid_peritoneal, fasting_blood_glucose, urine_count, note/memo
     * exchangeRecords: session_number, exchange_time, drainage_volume,
     *                  infusion_concentration, infusion_weight, ultrafiltration
     */
    fun buildDailyModelRow(dailyData: Map<String, Any?>, exchangeRecords: List<Map<String, Any?>>?): Map<String, Any?> {
        val events = buildExchangeEvents(exchangeRecords ?: emptyList())
        val agg = aggregateExchanges(events)
        val bp = parseBp(dailyData["blood_pressure"] as? String)

        val reportedUf = numOrNull(dailyData["total_ultrafiltration"])
        val calcUfSum = numOrNull(agg["calculated_uf_sum_g"])
        val ufDiscrepancy = if (reportedUf != null && calcUfSum != null) {
            pyRound(reportedUf - calcUfSum, 1)
        } else null

        val row = LinkedHashMap<String, Any?>()
        row["date"] = dailyData["date"] ?: dailyData["record_date"]
        row.putAll(agg)
        row["reported_total_uf_g"] = reportedUf
        row["uf_discrepancy_g"] = ufDiscrepancy
        row["body_weight_kg"] = numOrNull(dailyData["weight"])
        row["fasting_blood_sugar"] = numOrNull(dailyData["fasting_blood_glucose"])
        row["urination_count"] = dailyData["urine_count"]
        row["cloudy_dialysate"] = if (dailyData["turbid_peritoneal"] == true) 1 else 0
        row.putAll(bp)
        row["note"] = dailyData["note"] ?: dailyData["memo"]

        return row
    }
}
