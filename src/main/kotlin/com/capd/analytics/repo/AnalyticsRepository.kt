package com.capd.analytics.repo

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime

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

/** users 테이블 조회 결과 (인증·접근권한 검증용). */
data class UserRow(
    val id: Long,
    val name: String,
    val role: String,
    val isActive: Boolean,
    val doctorId: Long?,
)

/** patient_doctor_assignments 한 행 (담당 이력). ended_at == null이면 현재 담당. */
data class AssignmentRow(
    val id: Long,
    val doctorId: Long,
    val patientId: Long,
    val startedAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
)

/**
 * daily_records/exchange_records 읽기 전용 조회 + users/patient_doctor_assignments
 * 인증·접근권한 조회 + Silver/Gold 캐시(patient_daily_metrics/patient_daily_analytics)
 * 읽기·upsert.
 *
 * backend/app/api/v1/routes/analytics.py + patients.py(_get_assignment/_require_doctor)의
 * Kotlin 이관본(6단계 2차) — 이번 이터레이션에서 인증·캐시까지 포함해 실서비스 노출
 * 가능한 완성본으로 만듦(1차 슬라이스 때는 이 두 가지를 의도적으로 제외했었음).
 */
@Repository
class AnalyticsRepository(private val jdbc: NamedParameterJdbcTemplate) {

    private val mapper = jacksonObjectMapper()

    // ── 유저/담당이력 ────────────────────────────────────────────

    fun findUserById(userId: Long): UserRow? {
        val sql = "SELECT id, name, role, is_active, doctor_id FROM users WHERE id = :userId"
        return jdbc.query(sql, mapOf("userId" to userId)) { rs, _ -> mapUserRow(rs) }.firstOrNull()
    }

    fun findPatient(patientId: Long): UserRow? {
        val sql = "SELECT id, name, role, is_active, doctor_id FROM users WHERE id = :patientId AND role = 'patient'"
        return jdbc.query(sql, mapOf("patientId" to patientId)) { rs, _ -> mapUserRow(rs) }.firstOrNull()
    }

    private fun mapUserRow(rs: ResultSet): UserRow = UserRow(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        role = rs.getString("role"),
        isActive = rs.getBoolean("is_active"),
        doctorId = rs.getLongOrNull("doctor_id"),
    )

    /** 해당 의사-환자 assignment 중 가장 최근 것 (현재·과거 모두). backend _get_assignment와 동일. */
    fun findLatestAssignment(doctorId: Long, patientId: Long): AssignmentRow? {
        val sql = """
            SELECT id, doctor_id, patient_id, started_at, ended_at
            FROM patient_doctor_assignments
            WHERE doctor_id = :doctorId AND patient_id = :patientId
            ORDER BY started_at DESC
            LIMIT 1
        """.trimIndent()
        return jdbc.query(sql, mapOf("doctorId" to doctorId, "patientId" to patientId)) { rs, _ ->
            AssignmentRow(
                id = rs.getLong("id"),
                doctorId = rs.getLong("doctor_id"),
                patientId = rs.getLong("patient_id"),
                startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
                endedAt = rs.getObject("ended_at", OffsetDateTime::class.java),
            )
        }.firstOrNull()
    }

    // ── daily_records / exchange_records ─────────────────────────

    fun findPatientName(patientId: Long): String? = findPatient(patientId)?.name

    /**
     * 최신순(record_date DESC) submitted/reviewed 기록 최대 limit개.
     * cutoffDate가 있으면(과거 담당 의사) record_date <= cutoffDate 까지만.
     */
    fun findRecentRecords(patientId: Long, limit: Int, cutoffDate: LocalDate? = null): List<DailyRecordRow> {
        val sql = buildString {
            append(
                """
                SELECT id, record_date, weight, blood_pressure, total_ultrafiltration,
                       turbid_peritoneal, fasting_blood_glucose, urine_count, memo
                FROM daily_records
                WHERE patient_id = :patientId AND status IN ('submitted', 'reviewed')
                """.trimIndent()
            )
            if (cutoffDate != null) append("\nAND record_date <= :cutoffDate")
            append("\nORDER BY record_date DESC\nLIMIT :limit")
        }
        val params = mutableMapOf<String, Any?>("patientId" to patientId, "limit" to limit)
        if (cutoffDate != null) params["cutoffDate"] = cutoffDate
        return jdbc.query(sql, params) { rs, _ ->
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

    // ── Gold 캐시 조회 ────────────────────────────────────────────

    /**
     * patient_daily_analytics(Gold)에 (patient_id, record_date, window_days) 캐시가
     * 있으면 반환. window_days가 캐시 키에 포함돼 있어 7/30/90 전환 시 서로 다른
     * 행에 저장되므로 무효화 없이 각자 캐시를 유지한다 -- backend
     * analytics.py _read_cache와 동일 규칙(신선도 체크는 불필요 -- 상단 주석 참고).
     * (2026-07-07 window_days 컬럼 도입 -- backend/scripts/migrate_analytics_cache_window.py
     *  참고. 예전엔 (patient_id, record_date) 딱 1행뿐이라 correlation_json.window_days를
     *  꺼내 요청과 비교하는 방식이었는데, window 전환 시마다 서로 캐시를 밀어내던 문제가
     *  있었음.)
     */
    fun readCache(patientId: Long, recordDate: LocalDate, windowDays: Int): Map<String, Any?>? {
        val sql = """
            SELECT trend_json, anomaly_json, correlation_json, eda_json, has_anomaly, anomaly_attrs
            FROM patient_daily_analytics
            WHERE patient_id = :patientId AND record_date = :recordDate AND window_days = :windowDays
        """.trimIndent()
        return jdbc.query(
            sql,
            mapOf("patientId" to patientId, "recordDate" to recordDate, "windowDays" to windowDays),
        ) { rs, _ ->
            mapOf(
                "trend_analysis" to jsonbToMap(rs, "trend_json"),
                "anomaly_detection" to jsonbToMap(rs, "anomaly_json"),
                "attribute_correlation" to jsonbToMap(rs, "correlation_json"),
                "eda" to jsonbToMap(rs, "eda_json"),
                "has_anomaly" to rs.getBoolean("has_anomaly"),
                "anomaly_attrs" to pgArrayToStringList(rs.getArray("anomaly_attrs")),
            )
        }.firstOrNull()
    }

    // ── Silver/Gold 캐시 upsert (best-effort) ────────────────────

    /** 캐시 실패는 무시(로그만 남기고 응답 자체엔 영향 없음) -- backend _upsert_cache와 동일 정책. */
    fun upsertCache(patientId: Long, recordDate: LocalDate, windowDays: Int, todayRow: Map<String, Any?>, result: Map<String, Any?>) {
        try {
            upsertSilver(patientId, recordDate, todayRow)
            upsertGold(patientId, recordDate, windowDays, result)
        } catch (e: Exception) {
            // best-effort — 온디맨드 응답 자체는 정상 반환되도록 여기서 예외를 삼킨다.
        }
    }

    private fun upsertSilver(patientId: Long, recordDate: LocalDate, row: Map<String, Any?>) {
        val sql = """
            INSERT INTO patient_daily_metrics (
                patient_id, record_date,
                exchange_count, missing_exchange_slots, drain_sum_g, infused_sum_g,
                recorded_uf_sum_g, calculated_uf_sum_g, uf_min_g, uf_std_g,
                dwell_mean_minutes, dwell_std_minutes, concentration_max,
                reported_total_uf_g, uf_discrepancy_g,
                body_weight_kg, fasting_blood_sugar, urination_count, cloudy_dialysate,
                systolic_bp, diastolic_bp, pulse_pressure, mean_arterial_pressure,
                note, updated_at
            ) VALUES (
                :patientId, :recordDate,
                :exchangeCount, :missingExchangeSlots, :drainSumG, :infusedSumG,
                :recordedUfSumG, :calculatedUfSumG, :ufMinG, :ufStdG,
                :dwellMeanMinutes, :dwellStdMinutes, :concentrationMax,
                :reportedTotalUfG, :ufDiscrepancyG,
                :bodyWeightKg, :fastingBloodSugar, :urinationCount, :cloudyDialysate,
                :systolicBp, :diastolicBp, :pulsePressure, :meanArterialPressure,
                :note, NOW()
            )
            ON CONFLICT (patient_id, record_date) DO UPDATE SET
                exchange_count          = EXCLUDED.exchange_count,
                missing_exchange_slots  = EXCLUDED.missing_exchange_slots,
                drain_sum_g             = EXCLUDED.drain_sum_g,
                infused_sum_g           = EXCLUDED.infused_sum_g,
                recorded_uf_sum_g       = EXCLUDED.recorded_uf_sum_g,
                calculated_uf_sum_g     = EXCLUDED.calculated_uf_sum_g,
                uf_min_g                = EXCLUDED.uf_min_g,
                uf_std_g                = EXCLUDED.uf_std_g,
                dwell_mean_minutes      = EXCLUDED.dwell_mean_minutes,
                dwell_std_minutes       = EXCLUDED.dwell_std_minutes,
                concentration_max       = EXCLUDED.concentration_max,
                reported_total_uf_g     = EXCLUDED.reported_total_uf_g,
                uf_discrepancy_g        = EXCLUDED.uf_discrepancy_g,
                body_weight_kg          = EXCLUDED.body_weight_kg,
                fasting_blood_sugar     = EXCLUDED.fasting_blood_sugar,
                urination_count         = EXCLUDED.urination_count,
                cloudy_dialysate        = EXCLUDED.cloudy_dialysate,
                systolic_bp             = EXCLUDED.systolic_bp,
                diastolic_bp            = EXCLUDED.diastolic_bp,
                pulse_pressure          = EXCLUDED.pulse_pressure,
                mean_arterial_pressure  = EXCLUDED.mean_arterial_pressure,
                note                    = EXCLUDED.note,
                updated_at              = NOW()
        """.trimIndent()
        jdbc.update(
            sql,
            mapOf(
                "patientId" to patientId,
                "recordDate" to recordDate,
                "exchangeCount" to row["exchange_count"],
                "missingExchangeSlots" to row["missing_exchange_slots"],
                "drainSumG" to row["drain_sum_g"],
                "infusedSumG" to row["infused_sum_g"],
                "recordedUfSumG" to row["recorded_uf_sum_g"],
                "calculatedUfSumG" to row["calculated_uf_sum_g"],
                "ufMinG" to row["uf_min_g"],
                "ufStdG" to row["uf_std_g"],
                "dwellMeanMinutes" to row["dwell_mean_minutes"],
                "dwellStdMinutes" to row["dwell_std_minutes"],
                "concentrationMax" to row["concentration_max"],
                "reportedTotalUfG" to row["reported_total_uf_g"],
                "ufDiscrepancyG" to row["uf_discrepancy_g"],
                "bodyWeightKg" to row["body_weight_kg"],
                "fastingBloodSugar" to row["fasting_blood_sugar"],
                "urinationCount" to row["urination_count"],
                "cloudyDialysate" to row["cloudy_dialysate"],
                "systolicBp" to row["systolic_bp"],
                "diastolicBp" to row["diastolic_bp"],
                "pulsePressure" to row["pulse_pressure"],
                "meanArterialPressure" to row["mean_arterial_pressure"],
                "note" to row["note"],
            ),
        )
    }

    private fun upsertGold(patientId: Long, recordDate: LocalDate, windowDays: Int, result: Map<String, Any?>) {
        val trendJson = mapper.writeValueAsString(result["trend_analysis"])
        val anomalyJson = mapper.writeValueAsString(result["anomaly_detection"])
        val correlationJson = mapper.writeValueAsString(result["attribute_correlation"])
        val edaJson = mapper.writeValueAsString(result["eda"])
        val hasAnomaly = result["has_anomaly"] as? Boolean ?: false
        @Suppress("UNCHECKED_CAST")
        val anomalyAttrs = (result["anomaly_attrs"] as? List<String>) ?: emptyList()
        // Postgres 배열 리터럴로 직접 구성 -- 값이 전부 영문 속성명(알파벳/밑줄)이라 이스케이프 불필요.
        val anomalyAttrsLiteral = "{" + anomalyAttrs.joinToString(",") + "}"

        val sql = """
            INSERT INTO patient_daily_analytics (
                patient_id, record_date, window_days,
                trend_json, anomaly_json, correlation_json, eda_json,
                has_anomaly, anomaly_attrs, computed_at
            ) VALUES (
                :patientId, :recordDate, :windowDays,
                CAST(:trendJson AS JSONB), CAST(:anomalyJson AS JSONB),
                CAST(:correlationJson AS JSONB), CAST(:edaJson AS JSONB),
                :hasAnomaly, CAST(:anomalyAttrs AS TEXT[]), NOW()
            )
            ON CONFLICT (patient_id, record_date, window_days) DO UPDATE SET
                trend_json       = EXCLUDED.trend_json,
                anomaly_json     = EXCLUDED.anomaly_json,
                correlation_json = EXCLUDED.correlation_json,
                eda_json         = EXCLUDED.eda_json,
                has_anomaly      = EXCLUDED.has_anomaly,
                anomaly_attrs    = EXCLUDED.anomaly_attrs,
                computed_at      = NOW()
        """.trimIndent()
        jdbc.update(
            sql,
            mapOf(
                "patientId" to patientId,
                "recordDate" to recordDate,
                "windowDays" to windowDays,
                "trendJson" to trendJson,
                "anomalyJson" to anomalyJson,
                "correlationJson" to correlationJson,
                "edaJson" to edaJson,
                "hasAnomaly" to hasAnomaly,
                "anomalyAttrs" to anomalyAttrsLiteral,
            ),
        )
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────

    private fun jsonbToMap(rs: ResultSet, column: String): Map<String, Any?>? {
        val obj = rs.getObject(column) ?: return null
        val text = if (obj is PGobject) obj.value else obj.toString()
        if (text.isNullOrBlank()) return null
        return mapper.readValue(text, object : TypeReference<Map<String, Any?>>() {})
    }

    private fun pgArrayToStringList(array: java.sql.Array?): List<String> {
        if (array == null) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val raw = array.array as? Array<Any?> ?: return emptyList()
        return raw.mapNotNull { it as? String }
    }

    private fun ResultSet.getLongOrNull(column: String): Long? {
        val v = getLong(column)
        return if (wasNull()) null else v
    }
}
