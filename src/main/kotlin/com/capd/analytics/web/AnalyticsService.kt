package com.capd.analytics.web

import com.capd.analytics.core.AnalyticsTasks
import com.capd.analytics.core.DataEngineering
import com.capd.analytics.repo.AnalyticsRepository
import com.capd.analytics.repo.DailyRecordRow
import com.capd.analytics.repo.ExchangeRecordRow
import org.springframework.stereotype.Service

class PatientNotFoundException(message: String) : RuntimeException(message)
class NoRecordsException(message: String) : RuntimeException(message)

@Service
class AnalyticsService(private val repo: AnalyticsRepository) {

    private fun toDailyData(r: DailyRecordRow): Map<String, Any?> = mapOf(
        "record_date" to r.recordDate.toString(),
        "weight" to r.weight?.toDouble(),
        "blood_pressure" to r.bloodPressure,
        "total_ultrafiltration" to r.totalUltrafiltration?.toDouble(),
        "turbid_peritoneal" to r.turbidPeritoneal,
        "fasting_blood_glucose" to r.fastingBloodGlucose?.toDouble(),
        "urine_count" to r.urineCount,
        "note" to r.memo,
    )

    private fun toExchanges(rows: List<ExchangeRecordRow>): List<Map<String, Any?>> = rows.map {
        mapOf(
            "session_number" to it.sessionNumber,
            "exchange_time" to it.exchangeTime,
            "drainage_volume" to it.drainageVolume?.toDouble(),
            "infusion_concentration" to it.infusionConcentration?.toDouble(),
            "infusion_weight" to it.infusionWeight?.toDouble(),
            "ultrafiltration" to it.ultrafiltration?.toDouble(),
        )
    }

    /** 추세 카드 미니차트용 일별 시계열. backend analytics.py의 _build_daily_series와 동일 로직. */
    private fun buildDailySeries(
        todayRow: Map<String, Any?>,
        historicalRows: List<Map<String, Any?>>,
    ): Map<String, List<Map<String, Any?>>> {
        val allRows = listOf(todayRow) + historicalRows
        val series = LinkedHashMap<String, List<Map<String, Any?>>>()
        for (attr in AnalyticsTasks.TREND_ATTRS) {
            val pts = allRows
                .filter { it[attr] != null && it["date"] != null }
                .map { mapOf("date" to it["date"], "value" to it[attr]) }
                .sortedBy { it["date"] as String }
            if (pts.size >= 2) series[attr] = pts
        }
        return series
    }

    fun getPatientAnalytics(patientId: Long, window: Int): Map<String, Any?> {
        val patientName = repo.findPatientName(patientId)
            ?: throw PatientNotFoundException("환자를 찾을 수 없습니다.")

        val records = repo.findRecentRecords(patientId, window + 1)
        if (records.isEmpty()) throw NoRecordsException("분석할 제출/승인 기록이 없습니다.")

        val exchangesByRecord = repo.findExchangesFor(records.map { it.id })
            .groupBy { it.dailyRecordId }

        val todayRecord = records.first()
        val historicalRecords = records.drop(1)

        val todayRow = DataEngineering.buildDailyModelRow(
            toDailyData(todayRecord), toExchanges(exchangesByRecord[todayRecord.id] ?: emptyList())
        )
        val historicalRows = historicalRecords.map {
            DataEngineering.buildDailyModelRow(toDailyData(it), toExchanges(exchangesByRecord[it.id] ?: emptyList()))
        }

        val result = AnalyticsTasks.runAllTasks(todayRow, historicalRows, window)
        val dailySeries = buildDailySeries(todayRow, historicalRows)

        return mapOf(
            "patient_id" to patientId,
            "patient_name" to patientName,
            "record_date" to todayRecord.recordDate.toString(),
            "window_days" to historicalRows.size,
            "source" to "kotlin_on_demand",
            "daily_series" to dailySeries,
        ) + result
    }
}
