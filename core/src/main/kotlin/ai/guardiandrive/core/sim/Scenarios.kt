package ai.guardiandrive.core.sim

import ai.guardiandrive.core.*

/**
 * 시연용 주행 시나리오. 앱의 '시연 모드'가 1초 단위로 재생합니다.
 * 12월 PT 심사에서 에뮬레이터로 만들기 어려운 사고·끼어들기 장면을 확실하게 재현하기 위한 용도예요.
 *
 * [기본] = 공모전 허용 SDK만 사용 (adas = null)
 * [ADAS] = ADAS SDK 사용이 허용될 때만 가능한 장면
 */
data class Tick(val snap: Snapshot, val voice: String? = null)

object Scenarios {
    private const val ROAD = "경부고속도로 하행 (서울→부산)"

    private fun hw(
        t: Int, speed: Double, brake: Boolean = false, signal: Direction? = null,
        steer: Double = 0.0, hazard: Boolean = false, trunk: Boolean = false, adas: AdasFrame? = null,
    ) = Snapshot(
        timeMs = t * 1000L, speedKph = speed, speedLimitKph = 110.0, roadType = RoadType.HIGHWAY,
        brakePressed = brake, turnSignal = signal, hazardOn = hazard, trunkOpen = trunk, steeringDeg = steer,
        lat = 37.2636 + t * 0.0002, lon = 127.0286, roadName = ROAD, adas = adas,
    )

    /** [기본] 4분 동안 급제동 3번 → 두 번째에서 한 번만 안내 */
    fun repeatedBraking(): List<Tick> = (0..200).map { t ->
        val brakeAt = t in listOf(30, 90, 150)
        val speed = when {
            brakeAt -> 85.0
            t - 1 in listOf(30, 90, 150) -> 90.0
            else -> 100.0
        }
        Tick(hw(t, speed, brake = brakeAt))
    }

    /** [기본] 깜빡이 없이 왼쪽으로, 깜빡이 켜고 오른쪽, 다시 왼쪽 → 1분 안에 3번 변경 */
    fun laneChanges(): List<Tick> = (0..100).map { t ->
        val steer = when (t) {
            10, 11 -> 4.0; 12 -> -3.0          // 깜빡이 없이 왼쪽
            41, 42 -> -2.0; 43 -> 1.5           // 깜빡이 켜고 오른쪽
            66, 67 -> 2.0; 68 -> -1.5           // 깜빡이 켜고 왼쪽
            else -> 0.0
        }
        val signal = when (t) { in 40..43 -> Direction.RIGHT; in 65..68 -> Direction.LEFT; else -> null }
        Tick(hw(t, 100.0, signal = signal, steer = steer))
    }

    /** [기본] 긴 커브길: 핸들을 15초간 한쪽으로 → 차선 변경으로 오인하면 안 됨 */
    fun longCurve(): List<Tick> = (0..40).map { t ->
        Tick(hw(t, 90.0, steer = if (t in 10..25) 5.0 else 0.0))
    }

    /** [기본] 고속도로 사고 → 단계별 안내를 끝까지 따라감. 비상등(30초)·트렁크(38초)는 차량 데이터로 자동 확인 */
    fun highwayCrash(): List<Tick> = (0..110).map { t ->
        val speed = when { t < 20 -> 95.0; t == 20 -> 70.0; else -> 0.0 }
        val voice = when (t) {
            24 -> "괜찮아요 안 다쳤어"
            50 -> "가드레일 밖으로 나왔어"
            70 -> "신고했어"
            100 -> "사진 다 찍었어 완료"
            else -> null
        }
        Tick(hw(t, speed, brake = t in 20..21, hazard = t >= 30, trunk = t >= 38), voice)
    }

    /**
     * [PT] 12월 PT 시연용 고속도로 사고. 실제 시간(1배속)으로 재생합니다.
     * 비상등·트렁크·음성은 일부러 넣지 않았어요 → 심사위원이 버추얼 콘솔로 비상등·트렁크를 직접 켜면
     * 앱이 차량 데이터로 알아채고 다음 단계로 넘어가요. 나머지 단계는 음성 또는 화면 버튼으로 진행.
     * 충돌 후에는 15분 동안 정차 상태를 유지해서 발표 중에 시나리오가 끝나지 않아요.
     */
    fun ptHighwayCrash(): List<Tick> = (0..900).map { t ->
        val speed = when { t < 15 -> 95.0 + (t % 3); t == 15 -> 70.0; else -> 0.0 }
        Tick(hw(t, speed, brake = t in 15..16))
    }

    /** [기본] 사고 후 대답이 없음 → 10초에 다시 묻고 20초에 비상 연락 */
    fun crashNoResponse(): List<Tick> = (0..50).map { t ->
        val speed = when { t < 10 -> 80.0; t == 10 -> 55.0; else -> 0.0 }
        Tick(hw(t, speed, brake = t in 10..11))
    }

    /** [기본] 급정거했지만 사고가 아님 → "아니야" 하면 취소하고 주행 모드로 복귀 */
    fun falseAlarm(): List<Tick> = (0..60).map { t ->
        val speed = when { t < 10 -> 50.0; t == 10 -> 20.0; t in 11..19 -> 0.0; else -> minOf(100.0, (t - 19) * 20.0) }
        val steer = when (t) { 40, 41 -> 4.0; 42 -> -3.0; else -> 0.0 }
        Tick(hw(t, speed, brake = t in 10..11, steer = steer), if (t == 14) "사고 아니야" else null)
    }

    /** [기본] 막히는 길에서 가다 서다 반복 → 급제동·사고로 오인하면 안 됨 */
    fun stopAndGo(): List<Tick> = (0..120).map { t ->
        val phase = t % 12
        val speed = if (phase < 6) 5.0 + phase * 5 else 30.0 - (phase - 6) * 5
        Tick(hw(t, speed, brake = phase >= 6))
    }

    /** [기본] 11분 매끄러운 주행 → 10분째 칭찬 */
    fun smoothDrive(): List<Tick> = (0..660).map { t -> Tick(hw(t, 100.0)) }

    // ---------- ADAS ----------

    private fun frame(lane: Int, vararg v: NearbyVehicle, leftLine: LineType = LineType.WHITE_DASHED) =
        AdasFrame(laneIndex = lane, laneCount = 3, leftLine = leftLine, vehicles = v.toList())

    /** [ADAS] 오른쪽 차가 급하게 끼어듦 → 경고 → 충돌 → "도와줘" → 비상 연락. 리포트에 끼어들기가 남음 */
    fun cutInCrash(): List<Tick> = (0..40).map { t ->
        val car = when {
            t < 10 -> NearbyVehicle(1, 15.0, -3.5)
            t == 10 -> NearbyVehicle(1, 10.0, -1.0)
            else -> NearbyVehicle(1, 3.0, 0.0)
        }
        val speed = when { t <= 10 -> 95.0; t == 11 -> 80.0; else -> 0.0 }
        Tick(hw(t, speed, brake = t in 11..12, adas = frame(2, car)), if (t == 16) "도와줘 다쳤어" else null)
    }

    /** [ADAS] 왼쪽 옆 차가 갈지자로 내 차선을 넘나듦 → "왼쪽 차가 불안하게 달려요" */
    fun erraticNeighbor(): List<Tick> = (0..30).map { t ->
        val lat = when (t) { 5, 6 -> 1.2; 9 -> 3.5; 12, 13 -> 1.3; else -> 3.5 }
        Tick(hw(t, 100.0, adas = frame(2, NearbyVehicle(2, -8.0, lat))))
    }

    /** [ADAS] 1차선에서 85km/h, 뒤차가 붙음 → 60초 뒤 안내 → 깜빡이 켜고 오른쪽 → 양보 칭찬 */
    fun passingLaneHogging(): List<Tick> = (0..90).map { t ->
        val moved = t >= 70
        val adas = if (moved) frame(2) else frame(1, NearbyVehicle(9, -15.0, 0.0), leftLine = LineType.YELLOW_SOLID)
        Tick(hw(t, 85.0, signal = if (t in 67..69) Direction.RIGHT else null, adas = adas))
    }

    const val PT_DEMO = "[PT] 고속도로 사고 (비상등·트렁크 직접)"

    /** 비상등·트렁크를 실제 차량(버추얼 콘솔) 값으로 받는 시나리오 */
    val liveVehicle = setOf(PT_DEMO)

    /** PT 시나리오는 실제 시간으로, 나머지는 2배속으로 재생 */
    fun speedUpFor(name: String) = if (name in liveVehicle) 1 else 2

    val all: Map<String, () -> List<Tick>> = linkedMapOf(
        PT_DEMO to ::ptHighwayCrash,
        "[기본] 잦은 급제동" to ::repeatedBraking,
        "[기본] 깜빡이 없는·잦은 차선 변경" to ::laneChanges,
        "[기본] 긴 커브길 (오탐 방지)" to ::longCurve,
        "[기본] 고속도로 사고 대응" to ::highwayCrash,
        "[기본] 사고 후 무응답" to ::crashNoResponse,
        "[기본] 급정거지만 사고 아님" to ::falseAlarm,
        "[기본] 가다 서다 정체 (오탐 방지)" to ::stopAndGo,
        "[기본] 11분 매끄러운 주행" to ::smoothDrive,
        // ADAS SDK가 공모전 허용 목록에 없어 '향후 확장'으로 소개 (코드는 완성)
        "[향후 확장] 끼어들기 후 충돌" to ::cutInCrash,
        "[향후 확장] 갈지자 옆 차" to ::erraticNeighbor,
        "[향후 확장] 1차선 저속 → 양보" to ::passingLaneHogging,
    )
}

/** 시나리오 실행 결과 */
class RunResult {
    val spoken = mutableListOf<Pair<Int, String>>()
    val states = mutableListOf<GuardianEngine.UiState>()
    var emergencyNotified = false
    var falseAlarmNotified = false
    var report: AccidentReport? = null
    lateinit var tripSummary: String
    var score = 100
    var eventCounts: Map<EventType, Int> = emptyMap()
    fun n(t: EventType) = eventCounts[t] ?: 0
    val steps get() = states.mapNotNull { it.accidentStep }.distinct()
}

fun runScenario(ticks: List<Tick>): RunResult {
    val r = RunResult()
    var now = 0
    val engine = GuardianEngine(object : GuardianEngine.Output {
        override fun speak(text: String) { r.spoken += now to text }
        override fun notifyEmergencyContacts(lat: Double?, lon: Double?, roadName: String?) { r.emergencyNotified = true }
        override fun showAccidentReport(report: AccidentReport) { r.report = report }
        override fun onState(state: GuardianEngine.UiState) { r.states += state }
        override fun notifyFalseAlarm() { r.falseAlarmNotified = true }
    })
    for (tk in ticks) {
        now = (tk.snap.timeMs / 1000).toInt()
        engine.onSnapshot(tk.snap)
        tk.voice?.let { engine.onVoice(it) }
    }
    r.tripSummary = engine.finishTrip()
    r.score = engine.trip.score
    r.eventCounts = EventType.values().associateWith { engine.trip.count(it) }
    if (r.report == null) r.report = engine.lastReport
    return r
}
