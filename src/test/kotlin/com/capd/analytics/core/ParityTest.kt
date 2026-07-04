package com.capd.analytics.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * ParityTest — Python(backend/app/services/analytics_engine.py, ai/tools 원본과 동일 로직) vs
 * Kotlin 포팅본(DataEngineering/AnalyticsTasks) 출력이 완전히 동일한지 검증.
 *
 * backend/tests/test_ai_parity.py와 같은 목적(정합성 자동 검증)이지만, 언어가 달라
 * Python의 random.Random(seed) PRNG를 Kotlin에서 재현할 수 없으므로 접근 방식이 다르다:
 * Python 쪽(backend/tests/synth.py, seed=42, n_days=40)에서 미리 생성한 원본 입력(raw_series.json)과
 * 그 입력에 대한 Python의 실제 계산 결과(daily_model_rows.json, run_all_tasks_results.json)를
 * fixture로 고정해 두고, 이 테스트는 같은 입력을 Kotlin 포팅본에 먹여서 fixture와 비교한다.
 * fixture 재생성: 새 CLAUDE.md 6단계 작업 기록 참고(backend/tests/synth.py + analytics_engine.py 사용).
 *
 * "statement"(자연어 서술) 필드는 비교에서 제외한다 -- Python str(float)와 Kotlin의
 * Double.toString()이 둘 다 "정확히 왕복 가능한 최단 십진 표현"을 목표로 하지만 구현
 * 알고리즘이 달라(Python: David Gay's dtoa, JDK 17: 구버전 FloatingDecimal, JDK 19+에서만
 * Ryu 계열로 개선됨) 극히 드문 값에서 자릿수 표현이 어긋날 수 있음. 실제 판정에 쓰이는
 * 모든 수치·분류 필드(today_value, z_score, interpretation, correlation, is_anomaly 등)는
 * 전부 엄격 비교 대상이라 계산 로직 자체의 정합성은 이 테스트로 충분히 보장됨.
 */
class ParityTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    private fun loadRawSeries(): List<Pair<Map<String, Any?>, List<Map<String, Any?>>>> {
        val text = javaClass.getResourceAsStream("/parity/raw_series.json")!!.readBytes().decodeToString()
        val list: List<Map<String, Any?>> = mapper.readValue(text)
        return list.map { entry ->
            @Suppress("UNCHECKED_CAST")
            val dailyData = entry["daily_data"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val exchanges = entry["exchange_records"] as List<Map<String, Any?>>
            dailyData to exchanges
        }
    }

    private fun loadDailyModelRows(): List<Map<String, Any?>> {
        val text = javaClass.getResourceAsStream("/parity/daily_model_rows.json")!!.readBytes().decodeToString()
        return mapper.readValue(text)
    }

    private fun loadRunAllTasksResults(): Map<String, Map<String, Any?>> {
        val text = javaClass.getResourceAsStream("/parity/run_all_tasks_results.json")!!.readBytes().decodeToString()
        return mapper.readValue(text)
    }

    /** statement류 서술 필드를 뺀 깊은 비교. 불일치를 전부 모아 리포트. */
    private fun deepDiff(expected: Any?, actual: Any?, path: String, diffs: MutableList<String>) {
        when {
            expected == null && actual == null -> return
            expected == null || actual == null -> diffs.add("$path: expected=$expected actual=$actual")
            expected is Map<*, *> && actual is Map<*, *> -> {
                val keys = expected.keys + actual.keys
                for (k in keys) {
                    if (k == "statement" || k == "note") continue // 자유서술/원본통과 텍스트는 비교 제외(위 클래스 주석 참고)
                    deepDiff(expected[k], actual[k], "$path.$k", diffs)
                }
            }
            expected is List<*> && actual is List<*> -> {
                if (expected.size != actual.size) {
                    diffs.add("$path: size expected=${expected.size} actual=${actual.size}")
                } else {
                    for (i in expected.indices) deepDiff(expected[i], actual[i], "$path[$i]", diffs)
                }
            }
            expected is Number && actual is Number -> {
                val e = expected.toDouble()
                val a = actual.toDouble()
                // 부동소수점 표현오차만 허용(실질 반올림 로직은 NumFormat.pyRound로 이미 양쪽 동일 자리수 처리됨)
                if (Math.abs(e - a) > 1e-9) diffs.add("$path: expected=$e actual=$a")
            }
            else -> {
                if (expected != actual) diffs.add("$path: expected=$expected actual=$actual")
            }
        }
    }

    @Test
    fun `build daily model row matches python fixture`() {
        val series = loadRawSeries()
        val expectedRows = loadDailyModelRows()
        assertTrue(series.size == expectedRows.size, "fixture 크기 불일치")

        val diffs = mutableListOf<String>()
        for (i in series.indices) {
            val (dailyData, exchanges) = series[i]
            val actual = DataEngineering.buildDailyModelRow(dailyData, exchanges)
            deepDiff(expectedRows[i], actual, "row[$i]", diffs)
        }
        assertTrue(diffs.isEmpty(), "daily_model_row 불일치 ${diffs.size}건:\n" + diffs.joinToString("\n"))
    }

    @ParameterizedTest
    @ValueSource(ints = [7, 30, 90])
    fun `run all tasks matches python fixture`(window: Int) {
        val expectedRows = loadDailyModelRows() // oldest first, 이미 Python이 계산한 daily model row
        val rowsNewestFirst = expectedRows.reversed()
        val todayRow = rowsNewestFirst.first()
        val historicalRows = rowsNewestFirst.drop(1)

        val actual = AnalyticsTasks.runAllTasks(todayRow, historicalRows, window)
        val expected = loadRunAllTasksResults()[window.toString()]!!

        val diffs = mutableListOf<String>()
        deepDiff(expected, actual, "window=$window", diffs)
        assertTrue(diffs.isEmpty(), "run_all_tasks(window=$window) 불일치 ${diffs.size}건:\n" + diffs.joinToString("\n"))
    }
}
