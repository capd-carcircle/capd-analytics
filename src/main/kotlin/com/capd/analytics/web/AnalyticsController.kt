package com.capd.analytics.web

import com.capd.analytics.auth.ForbiddenException
import com.capd.analytics.auth.JwtVerifier
import com.capd.analytics.auth.UnauthorizedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/v1/analytics/patients/{patientId}?window=30
 * Authorization: Bearer <backend가 발급한 access token>
 *
 * backend(Python) app/api/v1/routes/analytics.py의 Kotlin 이관본.
 * 6단계 1차 슬라이스에서 의도적으로 뺐던 JWT 인증 + 담당의-환자 접근권한 +
 * Silver/Gold 캐시를 이번(6단계 2차)에 마저 이관 — AnalyticsRepository.kt 상단 주석 참고.
 */
@RestController
class AnalyticsController(
    private val service: AnalyticsService,
    private val jwtVerifier: JwtVerifier,
) {

    @GetMapping("/api/v1/analytics/patients/{patientId}")
    fun getPatientAnalytics(
        @PathVariable patientId: Long,
        @RequestParam(defaultValue = "30") window: Int,
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
    ): Map<String, Any?> {
        val token = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw UnauthorizedException("인증이 필요합니다.")

        val claims = jwtVerifier.verify(token)
        val clampedWindow = window.coerceIn(7, 90)
        return service.getPatientAnalytics(claims.userId, patientId, clampedWindow)
    }
}

@RestControllerAdvice
class AnalyticsExceptionHandler {

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(e: UnauthorizedException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("detail" to (e.message ?: "인증이 필요합니다.")))

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(e: ForbiddenException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("detail" to (e.message ?: "접근 권한이 없습니다.")))

    @ExceptionHandler(PatientNotFoundException::class)
    fun handleNotFound(e: PatientNotFoundException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("detail" to (e.message ?: "찾을 수 없습니다.")))

    @ExceptionHandler(NoRecordsException::class)
    fun handleNoRecords(e: NoRecordsException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("detail" to (e.message ?: "기록이 없습니다.")))
}
