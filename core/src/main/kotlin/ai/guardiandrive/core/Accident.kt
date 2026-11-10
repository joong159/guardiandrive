package ai.guardiandrive.core

/**
 * 사고 감지.
 *  - 자동: 25km/h 이상으로 달리다가 1초 사이 7 m/s² 이상(약 25km/h) 감속하고 3초 안에 거의 멈춤
 *    (일반 급제동은 보통 3~6 m/s²라서 구분됨)
 *  - 수동: 운전자가 "사고 났어"라고 말함
 */
class AccidentDetector {
    companion object {
        const val CRASH_DECEL_MPS2 = 7.0
        private const val MIN_SPEED_KPH = 25.0
        private const val STOPPED_KPH = 5.0
    }

    private var prev: Snapshot? = null
    private var candidateMs: Long? = null
    private var firedMs: Long? = null

    fun update(s: Snapshot): Boolean {
        val p = prev
        prev = s
        if (firedMs != null) return false
        if (p != null) {
            val dt = (s.timeMs - p.timeMs) / 1000.0
            val decel = if (dt > 0) (p.speedKph - s.speedKph) / 3.6 / dt else 0.0
            if (decel >= CRASH_DECEL_MPS2 && p.speedKph >= MIN_SPEED_KPH) candidateMs = s.timeMs
        }
        val c = candidateMs ?: return false
        if (s.speedKph <= STOPPED_KPH && s.timeMs - c <= 3_000) {
            firedMs = s.timeMs; candidateMs = null; return true
        }
        if (s.timeMs - c > 3_000) candidateMs = null
        return false
    }

    fun reset() { firedMs = null; candidateMs = null }
}

/** 음성 인식 결과를 키워드로 분류 (LLM 없이 동작) */
object VoiceIntent {
    enum class Intent { OK, HELP, NEXT, ACCIDENT, CANCEL, UNKNOWN }

    /** 순서가 중요: 부정 표현("안 다쳤어", "안 괜찮아")을 먼저 확인 */
    fun classify(text: String): Intent {
        val t = text.replace(" ", "")
        return when {
            listOf("사고아니", "사고안났", "아니야", "아니에요", "취소").any { it in t } -> Intent.CANCEL
            listOf("안괜찮", "괜찮지않", "괜찮지않아").any { it in t } -> Intent.HELP
            listOf("안다쳤", "안아파", "멀쩡").any { it in t } -> Intent.OK
            listOf("도와", "아파", "다쳤", "구급", "응급", "살려", "피나").any { it in t } -> Intent.HELP
            listOf("사고났", "사고야", "부딪", "박았", "충돌").any { it in t } -> Intent.ACCIDENT
            listOf("괜찮", "응", "네", "예").any { it in t } -> Intent.OK
            listOf("다음", "했어", "됐어", "완료", "켰어", "열었어", "나왔어").any { it in t } -> Intent.NEXT
            else -> Intent.UNKNOWN
        }
    }
}

/**
 * 사고 직후 단계별 음성 안내.
 * 한국도로공사 '비트박스' 행동요령 순서: 비상등 → 트렁크 → 안전지대 대피 → 신고
 *
 * 단계마다 "지금 할 일 하나"만 말하고, 운전자가 "했어/다음"이라고 하거나
 * 차량 데이터로 확인되면(비상등) 다음 단계로 넘어갑니다.
 * 응답이 없거나 "도와줘"라고 하면 비상 연락 모드로 전환합니다.
 */
class AccidentGuide(
    private val actions: Actions,
    private val askTimeoutMs: Long = 20_000,
    private val repromptMs: Long = 10_000,
    private val stepTimeoutMs: Long = 25_000,
) {
    interface Actions {
        fun speak(text: String)
        /** 가족·지정 연락처에 위치와 함께 알림 (외부 통신 허용 여부에 따라 구현) */
        fun notifyEmergencyContacts(lat: Double?, lon: Double?, roadName: String?)
        /** 리포트 화면 열기 */
        fun showReport(report: AccidentReport)
        /** 이미 비상 연락을 보낸 뒤 '사고 아님'이라고 했을 때, 같은 연락처에 정정 안내 */
        fun notifyFalseAlarm() {}
    }

    enum class Step(val title: String) {
        ASK_OK("상태 확인"),
        HAZARD("비상등 켜기"),
        TRUNK("트렁크 열기"),
        EVACUATE("안전한 곳으로 대피"),
        CALL("사고 신고"),
        PHOTOS("사진 남기기"),
        EMERGENCY("비상 연락"),
        DONE("정리 완료"),
        CANCELLED("사고 아님"),
    }

    var step: Step? = null
        private set
    var completed = mutableListOf<Step>()
        private set

    private var stepStartMs = 0L
    private var reprompted = false
    private var last: Snapshot? = null
    private var pendingReport: AccidentReport? = null
    private var emergencySent = false
    /** 직전 스냅샷의 비상등 상태 — '꺼짐→켜짐' 변화만 새로 켠 것으로 봄 */
    private var prevHazard = false

    val active get() = step != null && step != Step.DONE && step != Step.CANCELLED

    fun start(s: Snapshot, report: AccidentReport) {
        last = s
        pendingReport = report
        completed.clear()
        emergencySent = false
        prevHazard = s.hazardOn
        go(Step.ASK_OK, s.timeMs)
    }

    fun onSnapshot(s: Snapshot) {
        last = s
        val hazardTurnedOn = s.hazardOn && !prevHazard
        prevHazard = s.hazardOn
        val st = step ?: return
        val waited = s.timeMs - stepStartMs
        when (st) {
            Step.ASK_OK -> when {
                // 대답 전에 비상등을 새로 켰다면 의식이 있다는 뜻 → 응답으로 보고 바로 다음 단계
                hazardTurnedOn -> { actions.speak(Messages.Accident.hazardConfirmed); markHazardDone(); go(Step.TRUNK, s.timeMs) }
                waited >= askTimeoutMs -> go(Step.EMERGENCY, s.timeMs)
                waited >= repromptMs && !reprompted -> { reprompted = true; actions.speak(Messages.Accident.askAgain) }
            }
            // 비상등은 차량 데이터로 바로 확인
            Step.HAZARD -> if (s.hazardOn) { actions.speak(Messages.Accident.hazardConfirmed); go(Step.TRUNK, s.timeMs) }
                           else if (waited >= stepTimeoutMs && !reprompted) { reprompted = true; actions.speak(Messages.Accident.hazardRemind) }
            // 트렁크도 차량 데이터로 바로 확인 (이미 열려 있으면 바로 다음 단계)
            Step.TRUNK -> if (s.trunkOpen) { actions.speak(Messages.Accident.trunkConfirmed); go(Step.EVACUATE, s.timeMs) }
                          else if (waited >= stepTimeoutMs && !reprompted) { reprompted = true; actions.speak(Messages.Accident.remind(st)) }
            Step.EVACUATE, Step.CALL, Step.PHOTOS ->
                if (waited >= stepTimeoutMs && !reprompted) { reprompted = true; actions.speak(Messages.Accident.remind(st)) }
            Step.EMERGENCY, Step.DONE, Step.CANCELLED -> {}
        }
    }

    fun onVoice(text: String, nowMs: Long) {
        val st = step ?: return
        if (!active) return
        val intent = VoiceIntent.classify(text)
        if (intent == VoiceIntent.Intent.HELP && st != Step.EMERGENCY) { go(Step.EMERGENCY, nowMs); return }
        when (st) {
            Step.ASK_OK -> when (intent) {
                VoiceIntent.Intent.OK, VoiceIntent.Intent.NEXT ->
                    if (last?.hazardOn == true) { markHazardDone(); go(Step.TRUNK, nowMs) } else go(Step.HAZARD, nowMs)
                VoiceIntent.Intent.CANCEL -> go(Step.CANCELLED, nowMs) // 사고가 아닌 급정거
                else -> {}
            }
            // 행동 단계: "했어/다음"은 물론 "네/응/괜찮아"도 진행으로 봄. "사고 아님"이면 언제든 취소
            Step.HAZARD, Step.TRUNK, Step.EVACUATE, Step.CALL, Step.PHOTOS -> when (intent) {
                VoiceIntent.Intent.NEXT, VoiceIntent.Intent.OK -> go(nextOf(st), nowMs)
                VoiceIntent.Intent.CANCEL -> go(Step.CANCELLED, nowMs)
                else -> {}
            }
            Step.EMERGENCY -> when (intent) {
                VoiceIntent.Intent.OK -> go(Step.HAZARD, nowMs)
                // 위치를 이미 보냈어도 사고가 아니면 취소하고, 연락처에 정정 안내를 보냄
                VoiceIntent.Intent.CANCEL -> { emergencySent = true; actions.notifyFalseAlarm(); go(Step.CANCELLED, nowMs) }
                else -> {}
            }
            Step.DONE, Step.CANCELLED -> {}
        }
    }

    private fun nextOf(st: Step) = when (st) {
        Step.HAZARD -> Step.TRUNK; Step.TRUNK -> Step.EVACUATE; Step.EVACUATE -> Step.CALL
        Step.CALL -> Step.PHOTOS; else -> Step.DONE
    }

    /** 비상등 단계를 건너뛰고 바로 트렁크로 갈 때도 '완료'로 기록 (진행 표시·리포트용) */
    private fun markHazardDone() { if (Step.HAZARD !in completed) completed += Step.HAZARD }

    private fun go(next: Step, nowMs: Long) {
        step?.let { if (it != Step.EMERGENCY && it != Step.ASK_OK && it !in completed) completed += it }
        step = next
        stepStartMs = nowMs
        reprompted = false
        val s = last
        val highway = s?.roadType == RoadType.HIGHWAY
        when (next) {
            Step.ASK_OK -> actions.speak(Messages.Accident.askOk)
            Step.HAZARD -> actions.speak(Messages.Accident.hazard)
            Step.TRUNK -> actions.speak(if (highway) Messages.Accident.trunk else Messages.Accident.trunkUrban)
            Step.EVACUATE -> actions.speak(if (highway) Messages.Accident.evacuateHighway else Messages.Accident.evacuateUrban)
            Step.CALL -> actions.speak(if (highway) Messages.Accident.callHighway else Messages.Accident.callUrban)
            Step.PHOTOS -> actions.speak(Messages.Accident.photos)
            Step.EMERGENCY -> {
                actions.notifyEmergencyContacts(s?.lat, s?.lon, s?.roadName)
                actions.speak(Messages.Accident.emergency)
            }
            Step.DONE -> {
                actions.speak(Messages.Accident.done)
                pendingReport?.let { actions.showReport(it.copy(guideCompleted = completed.map { c -> c.title })) }
            }
            Step.CANCELLED -> actions.speak(if (emergencySent) Messages.Accident.cancelledAfterEmergency else Messages.Accident.cancelled)
        }
    }
}
