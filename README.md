# capd-analytics

CAPD 분석 서빙 도메인을 Python(backend/ai)에서 Kotlin+Spring Boot로 이관하는 서비스.
`backend/app/services/analytics_engine.py`(원본: `ai/tools/data_engineering.py` +
`ai/tools/analytics.py`)와 완전히 동일한 로직을 Kotlin으로 포팅했다.

## 이번 슬라이스(1차) 스코프

포함:
- 순수 계산 로직 포팅: `DataEngineering.kt`(Daily Model Row 생성) + `AnalyticsTasks.kt`(Task1~4:
  추세분석/이상탐지/상관관계/EDA)
- `GET /api/v1/analytics/patients/{patientId}?window=7~90` — Python backend의 동일 엔드포인트
  포팅. `daily_records`/`exchange_records`를 직접 조회해 온디맨드로 계산.
- 정합성 테스트(`src/test/kotlin/.../ParityTest.kt`) — Python이 미리 계산해 둔 fixture
  (`src/test/resources/parity/*.json`)와 Kotlin 포팅본 출력을 비교해 로직이 완전히
  동일한지 자동 검증.

의도적으로 제외(다음 이터레이션 예정):
- JWT 인증 + 담당의-환자 접근권한 검증 (`patient_doctor_assignments` 로직)
- Silver/Gold 캐시(`patient_daily_metrics`/`patient_daily_analytics`) 읽기·upsert —
  이 서비스는 항상 재계산만 함
- 즉 지금은 로컬 구동·정합성 검증 목적의 슬라이스이며 실서비스 노출용이 아님

## 실행 방법

Java 17, DB는 backend와 같은 Supabase Postgres(Session pooler)를 공유.

```bash
export DB_URL="jdbc:postgresql://<pooler-host>:5432/postgres"
export DB_USERNAME="postgres.<project-ref>"
export DB_PASSWORD="<url-encode 불필요, JDBC URL이 아니라 별도 프로퍼티라 그대로>"
./gradlew bootRun
```

기본값은 `localhost:5432`(로컬 Postgres용) — 환경변수 없이 실행하면 연결 실패.

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
