package ai.guardiandrive.core

import ai.guardiandrive.core.AccidentGuide.Step
import ai.guardiandrive.core.EventType.*
import ai.guardiandrive.core.sim.Scenarios
import ai.guardiandrive.core.sim.runScenario

/**
 * 모든 시연 시나리오를 돌려 기대대로 동작하는지 확인.
 * 실행: ./gradlew :core:runScenarioCheck
 */
fun main() {
    var failed = 0
    fun expect(name: String, cond: Boolean) {
        println("   ${if (cond) "✅" else "❌"} $name"); if (!cond) failed++
    }

    // 음성 의도 분류 단위 확인
    println("■ 음성 의도 분류")
    val cases = mapOf(
        "괜찮아요 안 다쳤어" to VoiceIntent.Intent.OK, "안 괜찮아" to VoiceIntent.Intent.HELP,
        "도와줘 다쳤어" to VoiceIntent.Intent.HELP, "사고 아니야" to VoiceIntent.Intent.CANCEL,
        "사고 났어" to VoiceIntent.Intent.ACCIDENT, "트렁크 열었어" to VoiceIntent.Intent.NEXT,
        "가드레일 밖으로 나왔어" to VoiceIntent.Intent.NEXT, "사진 다 찍었어 완료" to VoiceIntent.Intent.NEXT,
    )
    for ((text, want) in cases) expect("\"$text\" → $want", VoiceIntent.classify(text) == want)

    for ((title, build) in Scenarios.all) {
        val r = runScenario(build())
        println("\n■ $title")
        r.spoken.forEach { (t, m) -> println("   [${t}초] 🔊 $m") }
        if (r.steps.isNotEmpty()) println("   단계: ${r.steps.joinToString(" → ") { it.title }}")
        println("   점수 ${r.score}")

        when (title) {
            Scenarios.PT_DEMO -> {
                expect("음성·비상등 없이 두면 상태 확인 후 20초 뒤 비상 연락", r.steps.take(2) == listOf(Step.ASK_OK, Step.EMERGENCY))
                expect("15분 동안 정차 유지 (발표 중 끝나지 않음)", build().size > 800)
            }
            "[기본] 잦은 급제동" -> {
                expect("급제동 3번 모두 기록", r.n(HARD_BRAKE) == 3)
                expect("두 번째 급제동 직후 한 번만 안내 (브레이크 뗀 뒤)", r.spoken.size == 1 && r.spoken[0].first in 91..95)
                expect("사고로 오인하지 않음", r.steps.isEmpty())
            }
            "[기본] 깜빡이 없는·잦은 차선 변경" -> {
                expect("차선 변경 3번 감지", r.n(LANE_CHANGE) == 3)
                expect("깜빡이 없는 변경은 1번만", r.n(UNSIGNALED_LANE_CHANGE) == 1)
                expect("잦은 차선 변경 기록", r.n(LANE_WEAVING) == 1)
                expect("깜빡이 안내는 차선 변경 5초 뒤", r.spoken.firstOrNull()?.let { it.first >= 17 && "깜빡이" in it.second } == true)
            }
            "[기본] 긴 커브길 (오탐 방지)" -> expect("차선 변경으로 오인하지 않음", r.n(LANE_CHANGE) == 0 && r.spoken.isEmpty())
            "[기본] 고속도로 사고 대응" -> {
                expect("충돌 직후 상태 확인부터", r.spoken.firstOrNull()?.let { it.first == 21 && "괜찮으세요" in it.second } == true)
                expect("비트박스 순서대로 진행",
                    r.steps == listOf(Step.ASK_OK, Step.HAZARD, Step.TRUNK, Step.EVACUATE, Step.CALL, Step.PHOTOS, Step.DONE))
                expect("비상등은 차량 데이터로 자동 확인 (30초)", r.spoken.any { it.first == 30 && "비상등 켜진 것" in it.second })
                expect("트렁크도 차량 데이터로 자동 확인 (38초)", r.spoken.any { it.first == 38 && "트렁크 열린 것" in it.second })
                expect("고속도로라 가드레일 대피·1588-2504 안내", r.spoken.any { "가드레일" in it.second } && r.spoken.any { "1588-2504" in it.second })
                val rep = r.report
                expect("사고 리포트 생성", rep != null)
                rep?.let { println(it.render().prependIndent("      ")) }
                expect("리포트에 직전 속도·브레이크 기록", rep?.timeline?.any { "95km/h" in it.text } == true && rep.timeline.any { "브레이크" in it.text })
                expect("비상 연락은 하지 않음 (괜찮다고 함)", !r.emergencyNotified)
                expect("사고를 피하려던 브레이크는 감점 안 함", r.score == 100)
            }
            "[기본] 사고 후 무응답" -> {
                expect("10초 뒤 다시 물어봄", r.spoken.any { it.first == 21 && "대답이 없으면" in it.second })
                expect("20초 뒤 비상 연락", r.emergencyNotified && r.steps.contains(Step.EMERGENCY))
            }
            "[기본] 급정거지만 사고 아님" -> {
                expect("\"아니야\"로 취소", r.steps.lastOrNull() == Step.CANCELLED && !r.emergencyNotified)
                expect("취소 뒤 주행 점검이 다시 동작", r.n(UNSIGNALED_LANE_CHANGE) == 1 && r.spoken.any { "깜빡이" in it.second })
            }
            "[기본] 가다 서다 정체 (오탐 방지)" -> expect("감점·사고 없음", r.score == 100 && r.steps.isEmpty() && r.spoken.isEmpty())
            "[기본] 11분 매끄러운 주행" -> expect("10분째 칭찬", r.n(SMOOTH_STREAK) == 1 && r.spoken.singleOrNull()?.first == 600)
            "[향후 확장] 끼어들기 후 충돌" -> {
                expect("끼어드는 순간 바로 경고", r.spoken.firstOrNull()?.let { it.first == 10 && "오른쪽 차가 앞으로" in it.second } == true)
                expect("\"도와줘\" → 비상 연락", r.emergencyNotified)
                val tl = r.report?.timeline ?: emptyList()
                tl.forEach { println("      ${it.offsetSec}초  ${it.text}") }
                expect("리포트에 끼어들기가 충돌 2초 전으로 남음", tl.any { it.offsetSec == -2 && "끼어듦" in it.text })
            }
            "[향후 확장] 갈지자 옆 차" -> expect("왼쪽 차 불안 주행 경고 1번", r.n(ERRATIC_NEIGHBOR) == 1 && r.spoken.any { "왼쪽 차가 불안" in it.second } && r.n(CUT_IN_AHEAD) == 0)
            "[향후 확장] 1차선 저속 → 양보" -> {
                expect("60초 뒤 1차선 안내", r.spoken.firstOrNull()?.let { it.first == 60 && "추월차로" in it.second } == true)
                expect("깜빡이 켜고 옮겨서 감점 없음", r.n(UNSIGNALED_LANE_CHANGE) == 0)
                expect("양보 칭찬", r.n(YIELDED) == 1 && r.spoken.any { "양보" in it.second })
                expect("점수 93 (−10 +3)", r.score == 93)
            }
        }
    }

    // PT 장면 재현: "괜찮아" → 버추얼 콘솔 비상등(25초) → 트렁크(32초) → "다음" 3번
    println("\n■ [PT 재현] 버추얼 콘솔로 비상등·트렁크를 켰을 때")
    run {
        val ticks = Scenarios.ptHighwayCrash().take(120).map { tk ->
            val t = (tk.snap.timeMs / 1000).toInt()
            val voice = when (t) { 18 -> "괜찮아"; 40 -> "다음"; 45 -> "다음"; 50 -> "완료"; else -> null }
            ai.guardiandrive.core.sim.Tick(tk.snap.copy(hazardOn = t >= 25, trunkOpen = t >= 32), voice)
        }
        val r = runScenario(ticks)
        r.spoken.forEach { (t, m) -> println("   [${t}초] 🔊 $m") }
        expect("비상등을 켜는 순간 '비상등 켜진 것 확인했어요'", r.spoken.any { it.first == 25 && "비상등 켜진 것" in it.second })
        expect("트렁크를 여는 순간 '트렁크 열린 것 확인했어요'", r.spoken.any { it.first == 32 && "트렁크 열린 것" in it.second })
        expect("마지막에 사고 정리 리포트", r.steps.lastOrNull() == Step.DONE && r.report?.guideCompleted?.size == 5)
    }

    // PT 변수: 심사위원이 "괜찮으세요?"에 답하기 전에 비상등부터 누름
    println("\n■ [PT 재현] 대답 전에 비상등부터 켰을 때")
    run {
        val ticks = Scenarios.ptHighwayCrash().take(60).map { tk ->
            val t = (tk.snap.timeMs / 1000).toInt()
            ai.guardiandrive.core.sim.Tick(tk.snap.copy(hazardOn = t >= 20))
        }
        val r = runScenario(ticks)
        expect("비상등을 응답으로 보고 '확인했어요' 후 트렁크 단계", r.spoken.any { it.first == 20 && "비상등 켜진 것" in it.second } && r.steps.take(2) == listOf(Step.ASK_OK, Step.TRUNK))
        expect("비상 연락으로 넘어가지 않음", !r.emergencyNotified)
        expect("진행 목록에 비상등 완료로 표시", r.states.last().accidentDone.contains(Step.HAZARD))
    }

    // 리허설 때 켜 둔 비상등이 남아 있으면: 껐다 다시 켜면 인정돼야 함
    println("\n■ [PT 재현] 비상등이 켜진 채로 시작 → 껐다가 다시 켬")
    run {
        val ticks = Scenarios.ptHighwayCrash().take(60).map { tk ->
            val t = (tk.snap.timeMs / 1000).toInt()
            ai.guardiandrive.core.sim.Tick(tk.snap.copy(hazardOn = t < 18 || t >= 21))
        }
        val r = runScenario(ticks)
        expect("다시 켜는 순간 확인 (21초)", r.spoken.any { it.first == 21 && "비상등 켜진 것" in it.second })
    }

    // 행동 단계에서 "네"라고 대답해도 진행
    run {
        val ticks = Scenarios.ptHighwayCrash().take(60).map { tk ->
            val t = (tk.snap.timeMs / 1000).toInt()
            ai.guardiandrive.core.sim.Tick(tk.snap, when (t) { 18 -> "괜찮아"; 20 -> "네 켰어요"; else -> null })
        }
        val r = runScenario(ticks)
        expect("행동 단계에서 \"네\"도 다음으로", r.steps.contains(Step.TRUNK))
    }

    // 비상 연락을 보낸 뒤에도 '사고 아님'이면 취소 + 정정 안내
    println("\n■ 비상 연락 후 '사고 아님'")
    run {
        val ticks = Scenarios.crashNoResponse().map { tk ->
            if (tk.snap.timeMs == 40_000L) tk.copy(voice = "사고 아니야") else tk
        }
        val r = runScenario(ticks)
        expect("비상 연락이 먼저 나감", r.emergencyNotified)
        expect("'사고 아님'으로 취소", r.steps.lastOrNull() == Step.CANCELLED)
        expect("연락처에 정정 안내", r.falseAlarmNotified && r.spoken.any { "다시 알렸어요" in it.second })
    }

    println("\n■ 주행 후 리포트 예시 ([기본] 깜빡이 없는·잦은 차선 변경)")
    println(runScenario(Scenarios.laneChanges()).tripSummary.prependIndent("   "))

    println(if (failed == 0) "\n전체 통과" else "\n실패 ${failed}건")
    if (failed > 0) kotlin.system.exitProcess(1)
}
