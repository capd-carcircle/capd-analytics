package com.capd.analytics.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/v1/analytics/patients/{patientId}?window=30
 *
 * backend(Python) app/api/v1/routes/analytics.py의 Kotlin 이관본(1차 슬라이스).
 * 인증·접근권한·Gold캐시는 AnalyticsRepository.kt 상단 주석 참고(다음 이터레이션 예정).
 */
@RestController
class AnalyticsController(private val service: AnalyticsService) {

    @GetMapping("/api/v1/analytics/patients/{patientId}")
    fun getPatientAnalytics(
        @PathVariable patientId: Long,
        @RequestParam(defaultValue = "30") window: Int,
    ): Map<String, Any?> {
        val clampedWindow = window.coerceIn(7, 90)
        return service.getPatientAnalytics(patientId, clampedWindow)
    }
}

@RestControllerAdvice
class AnalyticsExceptionHandler {

    @ExceptionHandler(PatientNotFoundException::class)
    fun handleNotFound(e: PatientNotFoundException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("detail" to (e.message ?: "찾을 수 없습니다.")))

    @ExceptionHandler(NoRecordsException::class)
    fun handleNoRecords(e: NoRecordsException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("detail" to (e.message ?: "기록이 없습니다.")))
}
