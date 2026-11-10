package ai.guardiandrive.core

/**
 * 블랙박스처럼 최근 30초의 주행 데이터와 이벤트를 계속 덮어쓰며 보관.
 * 사고가 감지되면 그 순간을 기준으로 '사고 정리 리포트'를 만듭니다.
 *
 * ※ 공식 증거가 아니라, 사고 처리와 보험 접수 때 기억을 돕는 참고 기록입니다.
 */
class Recorder(private val windowMs: Long = 30_000) {
    private val snaps = ArrayDeque<Snapshot>()
    private val events = ArrayDeque<Event>()

    fun add(s: Snapshot) {
        snaps.addLast(s)
        while (snaps.isNotEmpty() && s.timeMs - snaps.first().timeMs > windowMs) snaps.removeFirst()
        while (events.isNotEmpty() && s.timeMs - events.first().timeMs > windowMs) events.removeFirst()
    }

    fun add(e: Event) { events.addLast(e) }

    fun buildReport(atMs: Long, trigger: String): AccidentReport {
        val at = snaps.lastOrNull { it.timeMs <= atMs } ?: snaps.last()
        val lines = mutableListOf<AccidentReport.Line>()

        // 충돌 직전 '마지막으로 정상 주행하던' 속도를 찾기 위해 사고 시점 직전까지만 사용
        val before = snaps.filter { it.timeMs < atMs }
        fun speedAt(offsetSec: Int) = before.lastOrNull { it.timeMs <= atMs - offsetSec * 1000L }

        // 직전 30초 기록 중 대표 시점 (30·10·5초 전)
        for (off in listOf(30, 10, 5)) speedAt(off)?.let {
            val lane = it.adas?.laneIndex?.let { l -> ", ${l}차선" } ?: ""
            lines += AccidentReport.Line(-off, "내 속도 ${it.speedKph.toInt()}km/h$lane")
        }
        // 브레이크를 처음 밟은 시점
        val firstBrake = before.dropWhile { !it.brakePressed }.firstOrNull()
            ?.takeIf { atMs - it.timeMs <= 10_000 }
        firstBrake?.let { lines += AccidentReport.Line(secOffset(it.timeMs, atMs), "브레이크 작동 (${it.speedKph.toInt()}km/h)") }
        // 깜빡이·비상등 상태
        val signalUsed = before.any { it.turnSignal != null && atMs - it.timeMs <= 10_000 }

        for (e in events.filter { it.timeMs <= atMs }) {
            val text = when (e.type) {
                EventType.CUT_IN_AHEAD -> "${e.direction?.korean ?: ""} 차가 앞으로 급하게 끼어듦"
                EventType.ERRATIC_NEIGHBOR -> "${e.direction?.korean ?: ""} 차 불안 주행 감지"
                EventType.LANE_CHANGE -> "내 차 ${e.direction?.korean ?: ""} 차선 변경"
                EventType.UNSIGNALED_LANE_CHANGE -> "(깜빡이 없이 변경)"
                EventType.HARD_BRAKE, EventType.PHANTOM_BRAKE -> null // 브레이크 줄과 중복
                else -> null
            } ?: continue
            lines += AccidentReport.Line(secOffset(e.timeMs, atMs), text)
        }
        lines += AccidentReport.Line(0, trigger)

        return AccidentReport(
            timeMs = atMs,
            lat = at.lat, lon = at.lon, roadName = at.roadName,
            roadType = at.roadType,
            timeline = lines.sortedBy { it.offsetSec },
            turnSignalUsedLast10s = signalUsed,
            hazardOnAtStop = at.hazardOn,
        )
    }

    private fun secOffset(t: Long, at: Long) = ((t - at) / 1000).toInt()
}

data class AccidentReport(
    val timeMs: Long,
    val lat: Double?,
    val lon: Double?,
    val roadName: String?,
    val roadType: RoadType,
    val timeline: List<Line>,
    val turnSignalUsedLast10s: Boolean,
    val hazardOnAtStop: Boolean,
    val guideCompleted: List<String> = emptyList(),
) {
    data class Line(val offsetSec: Int, val text: String)

    fun render(): String = buildString {
        appendLine("사고 정리 리포트")
        appendLine("위치: ${roadName ?: "알 수 없음"}${if (lat != null) " (%.5f, %.5f)".format(lat, lon) else ""}")
        appendLine("직전 기록:")
        timeline.forEach { appendLine("  ${if (it.offsetSec == 0) " 0초" else "${it.offsetSec}초"}  ${it.text}") }
        if (guideCompleted.isNotEmpty()) appendLine("완료한 조치: ${guideCompleted.joinToString(" → ")}")
        append("※ 사고 처리와 보험 접수 때 참고용 기록이에요. 공식 증거는 블랙박스 영상을 함께 쓰세요.")
    }
}
