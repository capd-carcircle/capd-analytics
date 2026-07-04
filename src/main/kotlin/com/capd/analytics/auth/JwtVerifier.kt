package com.capd.analytics.auth

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class JwtClaims(val userId: Long, val role: String?)

/**
 * backend(Python, python-jose)가 발급한 HS256 액세스 토큰을 검증.
 *
 * ⚠️ 라이브러리(JJWT 등) 대신 HMAC-SHA256을 직접 계산하는 이유: JJWT는 HS256에
 * 256비트(32바이트) 미만 키를 거부하지만(WeakKeyException) python-jose는 그런 최소
 * 길이를 강제하지 않는다. backend의 SECRET_KEY 길이에 관계없이 Python과 동일하게
 * 검증하려면 라이브러리의 키 길이 검사를 우회해야 하므로, 표준 JWT 구조(header.payload.signature)를
 * 직접 파싱 + javax.crypto.Mac으로 서명 비교한다. 새 패키지 의존성 없음(Jackson은 기존 의존성 재사용).
 *
 * backend/app/core/auth.py의 decode_access_token/get_current_user와 동일한 검증 규칙:
 * 서명 유효 + exp 미만료 + type=="access" + sub(사용자 id) 존재.
 */
@Component
class JwtVerifier(
    @Value("\${capd.jwt.secret}") private val secret: String,
) {
    private val mapper = jacksonObjectMapper()
    private val urlDecoder = Base64.getUrlDecoder()

    private fun decodeSegment(segment: String): ByteArray {
        val rem = segment.length % 4
        val padded = if (rem == 0) segment else segment + "=".repeat(4 - rem)
        return urlDecoder.decode(padded)
    }

    fun verify(token: String, expectedType: String = "access"): JwtClaims {
        if (secret.isBlank()) {
            throw UnauthorizedException("서버에 JWT 서명키(capd.jwt.secret/SECRET_KEY)가 설정되지 않았습니다.")
        }

        val parts = token.split(".")
        if (parts.size != 3) throw UnauthorizedException("유효하지 않은 토큰입니다.")
        val (headerB64, payloadB64, sigB64) = parts

        val expectedSig = try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            mac.doFinal("$headerB64.$payloadB64".toByteArray(StandardCharsets.US_ASCII))
        } catch (e: Exception) {
            throw UnauthorizedException("토큰 서명 검증에 실패했습니다.")
        }

        val actualSig = try {
            decodeSegment(sigB64)
        } catch (e: Exception) {
            throw UnauthorizedException("유효하지 않은 토큰입니다.")
        }

        if (!MessageDigest.isEqual(expectedSig, actualSig)) {
            throw UnauthorizedException("유효하지 않은 토큰입니다.")
        }

        val claims: Map<String, Any?> = try {
            val payloadJson = String(decodeSegment(payloadB64), StandardCharsets.UTF_8)
            mapper.readValue(payloadJson, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: Exception) {
            throw UnauthorizedException("유효하지 않은 토큰입니다.")
        }

        val type = claims["type"] as? String
        if (type != expectedType) {
            throw UnauthorizedException("액세스 토큰이 아닙니다.")
        }

        val exp = (claims["exp"] as? Number)?.toLong()
        if (exp != null && Instant.now().epochSecond > exp) {
            throw UnauthorizedException("토큰이 만료되었습니다.")
        }

        val userId = when (val sub = claims["sub"]) {
            is String -> sub.toLongOrNull()
            is Number -> sub.toLong()
            else -> null
        } ?: throw UnauthorizedException("토큰에 사용자 정보가 없습니다.")

        return JwtClaims(userId = userId, role = claims["role"] as? String)
    }
}
