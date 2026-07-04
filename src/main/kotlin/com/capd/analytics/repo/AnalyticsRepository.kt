package com.capd.analytics.repo

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

/** DB에서 읽어온 하루치 daily_records 행 (아직 daily_data 맵으로 변환 전). */
data class DailyRecordRow(
    val id: Long,
    val recordDate: LocalDate,
    val weight: BigDecimal?,
    val bloodPressure: String?,
    val totalUltrafiltration: BigDecimal?,
    val turbidPeritoneal: Boolean,
    val fastingBloodGlucose: BigDecimal?,
    val urineCount: Int?,
    val memo: String?,
)

data class ExchangeRecordRow(
    val dailyRecordId: Long,
    val sessionNumber: Int,
    val exchangeTime: String?,
    val drainageVolume: BigDecimal?,
    val infusionConcentration: BigDecimal?,
    val infusionWeight: BigDecimal?,
    val ultrafiltration: BigDecimal?,
)

/**
 * daily_records/exchange_records 읽기 전용 조회.
 *
 * ⚠️ 이번 슬라이스(6단계 1차 이관) 스코프: analytics 순수 계산 로직(DataEngineering/
 * AnalyticsTasks) + 이 읽기 쿼리만 포팅. backend/app/api/v1/routes/analytics.py에 있는
 * 아래 두 가지는 이번에는 의도적으로 제외 -- 다음 이터레이션에서 이어서 이관:
 *   1. JWT 인증 + 담당의-환자 접근권한(patient_doctor_assignments) 검증
 *   2. Silver/Gold 캐시(patient_daily_metrics/patient_daily_analytics) 읽기·upsert
 * 즉 이 서비스는 항상 on-demand로 재계산만 하고, 누구나(인증 없이) 호출 가능한 상태 --
 * 로컬 구동·정합성 검증 목적의 1차 슬라이스이며 실서비스 노출용이 아님.
 */
@Repository
class AnalyticsRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun findPatientName(patientId: Long): String? {
        val sql = "SELECT name FROM users WHERE id = :patientId AND role = 'patient'"
        return jdbc.query(sql, mapOf("patientId" to patientId)) { rs, _ -> rs.getString("name") }
            .firstOrNull()
    }

    /** 최신순(record_date DESC) submitted/reviewed 기록 최대 limit개. */
    fun findRecentRecords(patientId: Long, limit: Int): List<DailyRecordRow> {
        val sql = """
            SELECT id, record_date, weight, blood_pressure, total_ultrafiltration,
                   turbid_peritoneal, fasting_blood_glucose, urine_count, memo
            FROM daily_records
            WHERE patient_id = :patientId AND status IN ('submitted', 'reviewed')
            ORDER BY record_date DESC
            LIMIT :limit
        """.trimIndent()
        return jdbc.query(sql, mapOf("patientId" to patientId, "limit" to limit)) { rs, _ ->
            DailyRecordRow(
                id = rs.getLong("id"),
                recordDate = rs.getDate("record_date").toLocalDate(),
                weight = rs.getBigDecimal("weight"),
                bloodPressure = rs.getString("blood_pressure"),
                totalUltrafiltration = rs.getBigDecimal("total_ultrafiltration"),
                turbidPeritoneal = rs.getBoolean("turbid_peritoneal"),
                fastingBloodGlucose = rs.getBigDecimal("fasting_blood_glucose"),
                urineCount = rs.getObject("urine_count") as? Int,
                memo = rs.getString("memo"),
            )
        }
    }

    fun findExchangesFor(dailyRecordIds: List<Long>): List<ExchangeRecordRow> {
        if (dailyRecordIds.isEmpty()) return emptyList()
        val sql = """
            SELECT daily_record_id, session_number, exchange_time, drainage_volume,
                   infusion_concentration, infusion_weight, ultrafiltration
            FROM exchange_records
            WHERE daily_record_id IN (:ids)
            ORDER BY daily_record_id, session_number
        """.trimIndent()
        return jdbc.query(sql, mapOf("ids" to dailyRecordIds)) { rs, _ ->
            ExchangeRecordRow(
                dailyRecordId = rs.getLong("daily_record_id"),
                sessionNumber = rs.getInt("session_number"),
                exchangeTime = rs.getString("exchange_time"),
                drainageVolume = rs.getBigDecimal("drainage_volume"),
                infusionConcentration = rs.getBigDecimal("infusion_concentration"),
                infusionWeight = rs.getBigDecimal("infusion_weight"),
                ultrafiltration = rs.getBigDecimal("ultrafiltration"),
            )
        }
    }
}
