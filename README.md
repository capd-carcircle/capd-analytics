# capd-analytics

CAPD 분석 서빙 도메인을 Python(backend/ai)에서 Kotlin+Spring Boot로 이관하는 서비스.
`backend/app/services/analytics_engine.py`(원본: `ai/tools/data_engineering.py` +
`ai/tools/analytics.py`)와 완전히 동일한 로직을 Kotlin으로 포팅했다.

## 스코프

**1차 슬라이스** — 순수 계산 로직 포팅 + 정합성 검증:
- `DataEngineering.kt`(Daily Model Row 생성) + `AnalyticsTasks.kt`(Task1~4: 추세분석/이상탐지/상관관계/EDA)
- `GET /api/v1/analytics/patients/{patientId}?window=7~90` — Python backend의 동일 엔드포인트 포팅.
  `daily_records`/`exchange_records`를 직접 조회해 온디맨드로 계산.
- 정합성 테스트(`src/test/kotlin/.../ParityTest.kt`) — Python이 미리 계산해 둔 fixture
  (`src/test/resources/parity/*.json`)와 Kotlin 포팅본 출력을 비교해 로직이 완전히 동일한지 자동 검증.

**2차 슬라이스(이번) — 인증 + 담당권한 + 캐시, 실서비스 노출 가능한 완성본으로:**
- JWT 인증(`auth/JwtVerifier.kt`) — backend(python-jose)가 발급한 HS256 액세스 토큰을 라이브러리
  의존 없이 직접 검증(HMAC-SHA256 수동 계산). JJWT 등 표준 라이브러리는 HS256에 256비트 미만
  키를 거부하지만 python-jose는 그런 제약이 없어서, backend의 SECRET_KEY 길이에 관계없이 동일하게
  동작하도록 직접 구현. `Authorization: Bearer <token>` 필수, 없거나 무효/만료면 401.
- 담당의-환자 접근권한 검증 — backend `patients.py`의 `_require_doctor`/`_get_assignment` 로직을
  그대로 재현(`AnalyticsService.getPatientAnalytics`). 의사 role 아니면 403, 담당 이력
  (`patient_doctor_assignments`) 없고 레거시 `users.doctor_id`도 아니면 403. 과거 담당이면
  담당 종료일(`ended_at`) 이전 기록만 조회.
- Silver/Gold 캐시(`patient_daily_metrics`/`patient_daily_analytics`) 읽기·upsert — backend
  `analytics.py`의 `_read_cache`/`_upsert_cache`와 동일 규칙(캐시 계산 당시 historical 개수와
  이번 요청이 다르면 무효화). 응답 `source` 필드는 Python과 동일하게 `cache`/`on_demand`.

이제 인증 없이 호출하면 401, 의사가 아니거나 담당이 아니면 403 — 로컬 검증용 슬라이스가 아니라
실제 배포 가능한 완성본. (다음 단계는 7단계 `capd-pipeline`(Airflow) 또는 실제 배포 전환.)

## 실행 방법

Java 17, DB는 backend와 같은 Supabase Postgres(Session pooler)를 공유.
SECRET_KEY는 backend의 `.env`와 반드시 같은 값(이 서비스가 backend 발급 JWT를 검증하므로).

```bash
export DB_URL="jdbc:postgresql://<pooler-host>:5432/postgres"
export DB_USERNAME="postgres.<project-ref>"
export DB_PASSWORD="<url-encode 불필요, JDBC URL이 아니라 별도 프로퍼티라 그대로>"
export SECRET_KEY="<backend/.env의 SECRET_KEY와 동일한 값>"
./gradlew bootRun
```

기본값은 `localhost:5432`(로컬 Postgres용), `SECRET_KEY`는 빈 문자열 — 둘 다 환경변수 없이
실행하면 각각 DB 연결 실패/모든 요청 401.

## 테스트

```bash
./gradlew test
```

DB 없이도 전부 통과함(순수 함수 정합성 테스트만 있음, 이번 슬라이스엔 DB 통합 테스트 없음).

## fixture 재생성 방법

`src/test/resources/parity/*.json`은 `backend/tests/synth.py`(seed=42, 40일치 합성 데이터)로
생성한 입력을 `backend/app/services/analytics_engine.py`(Python)에 태워 만든 기대값이다.
Python 원본 로직이 바뀌면 fixture를 재생성해야 함 — CLAUDE.md 6단계 작업 기록에 생성 스크립트
절차가 남아있다.

## 배포 (Cloud Run)

`.github/workflows/deploy.yml` — main 브랜치 push 시 자동으로 테스트(`./gradlew test`) →
Docker 이미지 빌드(`Dockerfile`, 멀티스테이지: JDK17 빌드 → JRE17 런타임) → Artifact Registry
(`asia-northeast3-docker.pkg.dev/<project>/capd/analytics`) 푸시 → Cloud Run 서비스
`capd-analytics`(리전 asia-northeast3, `--allow-unauthenticated`, JWT 검증은 앱 레벨에서 처리)로
배포된다. `--min-instances` 플래그를 넣지 않아 기본값 0(트래픽 없을 때 완전히 내려감, 비용 최소화).

레포 GitHub Settings → Secrets and variables → Actions에 아래 시크릿 등록 필요:
`GCP_SA_KEY`(서비스 계정 키 JSON) · `GCP_PROJECT_ID` · `ANALYTICS_DB_URL`(JDBC URL,
`jdbc:postgresql://aws-1-ap-northeast-2.pooler.supabase.com:5432/postgres` 형태) ·
`ANALYTICS_DB_USERNAME`(`postgres.<project-ref>`) · `ANALYTICS_DB_PASSWORD` ·
`SECRET_KEY`(backend `.env`의 `SECRET_KEY`와 반드시 동일한 값).

이 서비스는 프론트엔드가 아직 호출하지 않음(의도적) — backend의 기존 Python analytics
엔드포인트가 계속 실서비스를 담당하고, 이 서비스는 독립적으로 배포·DB 실연동까지 검증된
상태로만 존재한다(Python→Kotlin 이관이 실제 클라우드 환경에서도 동작함을 증명하는 목적).
