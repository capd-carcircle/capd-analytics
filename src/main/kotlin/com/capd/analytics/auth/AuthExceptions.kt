package com.capd.analytics.auth

/** 인증 실패(토큰 없음/무효/만료) — HTTP 401. */
class UnauthorizedException(message: String) : RuntimeException(message)

/** 인증은 됐지만 권한 없음(의사 아님/담당 아님) — HTTP 403. */
class ForbiddenException(message: String) : RuntimeException(message)
