package ai.guardiandrive.core

/**
 * 가디언 드라이브 전체를 묶는 엔진. 앱은 이것만 쓰면 됩니다.
 *
 *   engine.onSnapshot(s)   ← 1초마다 SDK 값
 *   engine.onVoice(text)   ← 음성 인식(SpeechToText) 결과
 *   engine.finishTrip()    → 주행 후 리포트
 *
 * 모드: 주행 중(운전 점검 + 방어 운전 레이더) ↔ 사고 대응(단계별 안내)
 */
class GuardianEngine(private val out: Output) {

    interface Output {
        fun speak(text: String)
        fun notifyEmergencyContacts(lat: Double?, lon: Double?, roadName: String?)
        fun showAccidentReport(report: AccidentReport)
        fun onState(state: UiState)
        /** 비상 연락 후 '사고 아님' → 같은 연락처에 정정 안내 */
        fun notifyFalseAlarm() {}
    }

    data class UiState(
        val mode: Mode,
        val score: Int,
        val lane: Int?,
        val adasAvailable: Boolean,
        val lastSpeech: String?,
        val accidentStep: AccidentGuide.Step?,
        val accidentDone: List<AccidentGuide.Step>,
        val alertDirection: Direction?,
    )
    enum class Mode { DRIVING, ACCIDENT }

    private val check = DrivingCheck()
    private val radar = DefensiveRadar()
    private val hazard = LaneHazardWatch()
    private var hazardDodgeUntil = 0L
    private val crash = AccidentDetector()
    private val recorder = Recorder()
    val trip = TripReport()
    private var lastSpeech: String? = null
    private var alertDir: Direction? = null
    private var alertUntil = 0L
    private var last: Snapshot? = null

    private val coach = VoiceCoach({ say(it) })

    private val guide = AccidentGuide(object : AccidentGuide.Actions {
        override fun speak(text: String) = say(text)
        override fun notifyEmergencyContacts(lat: Double?, lon: Double?, roadName: String?) =
            out.notifyEmergencyContacts(lat, lon, roadName)
        override fun showReport(report: AccidentReport) { lastReport = report; out.showAccidentReport(report) }
        override fun notifyFalseAlarm() = out.notifyFalseAlarm()
    })

    var lastReport: AccidentReport? = null
        private set

    private fun say(t: String) { lastSpeech = t; out.speak(t) }

    fun onSnapshot(s: Snapshot) {
        last = s
        recorder.add(s)

        if (guide.active) {
            guide.onSnapshot(s)
            publish(s)
            return
        }

        if (crash.update(s)) { startAccident(s, "충돌 감지 (급감속 후 정지)"); return }

        val events = check.process(s) + radar.process(s) + hazard.process(s)
        var laneChanged = false
        for (e in events) {
            recorder.add(e)
            if (e.type == EventType.LANE_HAZARD_AHEAD) hazardDodgeUntil = s.timeMs + 120_000
            // 앞쪽 사고를 피하려고 차로를 여러 번 옮긴 건 '잦은 차선 변경'으로 감점·안내하지 않음
            if (e.type == EventType.LANE_WEAVING && s.timeMs <= hazardDodgeUntil) continue
            trip.add(e)
            if (e.type == EventType.LANE_CHANGE) laneChanged = true
            if (e.priority == Priority.URGENT) { alertDir = e.direction; alertUntil = s.timeMs + 5_000 }
            coach.offer(e)
        }
        coach.tick(s, laneChanged)
        if (s.timeMs > alertUntil) alertDir = null
        publish(s)
    }

    fun onVoice(text: String) {
        val now = last?.timeMs ?: 0L
        if (guide.active) {
            guide.onVoice(text, now)
            if (guide.step == AccidentGuide.Step.CANCELLED) resumeDriving()
            last?.let(::publish)
            return
        }
        if (VoiceIntent.classify(text) == VoiceIntent.Intent.ACCIDENT) {
            last?.let { startAccident(it, "운전자 음성 신고 \"$text\"") }
        }
    }

    private fun startAccident(s: Snapshot, trigger: String) {
        coach.muted = true
        val ev = Event(EventType.ACCIDENT, s.timeMs)
        recorder.add(ev)
        trip.add(ev)
        trip.forgiveBrakesBefore(s.timeMs, 5_000) // 사고를 피하려고 밟은 브레이크는 감점하지 않음
        val report = recorder.buildReport(s.timeMs, trigger)
        lastReport = report
        guide.start(s, report)
        publish(s)
    }

    /** 사고 처리를 마치고 다시 주행 모드로 */
    fun resumeDriving() { coach.muted = false; crash.reset() }

    fun finishTrip(): String = trip.summary(check.passingLaneSeconds, adasAvailable = last?.adas != null)

    private fun publish(s: Snapshot) {
        out.onState(
            UiState(
                mode = if (guide.active) Mode.ACCIDENT else Mode.DRIVING,
                score = trip.score,
                lane = check.currentLane,
                adasAvailable = s.adas != null,
                lastSpeech = lastSpeech,
                accidentStep = guide.step,
                accidentDone = guide.completed.toList(),
                alertDirection = alertDir,
            )
        )
    }
}

/** 주행 후 '도로 매너 점수' 리포트. 100점에서 시작 */
class TripReport {
    private val events = mutableListOf<Event>()
    fun add(e: Event) { events += e }

    fun forgiveBrakesBefore(atMs: Long, windowMs: Long) {
        events.removeAll {
            (it.type == EventType.HARD_BRAKE || it.type == EventType.PHANTOM_BRAKE) && atMs - it.timeMs in 0..windowMs
        }
    }

    val score get() = (100 - events.sumOf { it.type.penalty }).coerceIn(0, 100)
    fun count(t: EventType) = events.count { it.type == t }

    val grade get() = when {
        score >= 95 -> "흐름 메이커"
        score >= 85 -> "매끄러운 드라이버"
        score >= 70 -> "조금만 더!"
        else -> "흐름 연습생"
    }

    fun summary(passingLaneSec: Int, adasAvailable: Boolean): String = buildString {
        appendLine("오늘의 도로 매너 점수: $score 점 ($grade)")
        appendLine("- 급제동: ${count(EventType.HARD_BRAKE) + count(EventType.PHANTOM_BRAKE)}회")
        appendLine("- 깜빡이 없는 차선 변경: ${count(EventType.UNSIGNALED_LANE_CHANGE)}회")
        appendLine("- 잦은 차선 변경: ${count(EventType.LANE_WEAVING)}회")
        if (adasAvailable) {
            appendLine("- 1차선 저속 주행: ${count(EventType.PASSING_LANE_HOGGING)}회 (추월차로 ${passingLaneSec / 60}분 ${passingLaneSec % 60}초)")
            appendLine("- 위험 차량 경고: ${count(EventType.CUT_IN_AHEAD) + count(EventType.ERRATIC_NEIGHBOR)}회")
        }
        appendLine("- 양보: ${count(EventType.YIELDED)}회 / 매끄러운 10분: ${count(EventType.SMOOTH_STREAK)}회")
        append("한 줄 팁: ").append(tip())
    }

    private fun tip(): String {
        val worst = events.filter { it.type.penalty > 0 }.groupingBy { it.type }.eachCount().maxByOrNull { it.value }?.key
        return when (worst) {
            EventType.HARD_BRAKE, EventType.PHANTOM_BRAKE -> "앞차와 거리를 넉넉히 두면 급제동이 줄어요."
            EventType.UNSIGNALED_LANE_CHANGE -> "깜빡이는 뒤차와의 약속이에요. 차선 바꾸기 3초 전에 켜 보세요."
            EventType.LANE_WEAVING -> "차선을 자주 바꿔도 도착 시간은 거의 같아요."
            EventType.PASSING_LANE_HOGGING -> "추월이 끝나면 바로 오른쪽 차선으로 돌아가 보세요."
            else -> "지금처럼만 달리면 완벽해요!"
        }
    }
}
