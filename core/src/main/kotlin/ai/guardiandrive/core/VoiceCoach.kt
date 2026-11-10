package ai.guardiandrive.core

/**
 * 주행 중 "언제 말할지" 결정 (사고 안내는 이 규칙을 거치지 않고 바로 말함).
 *
 *  - URGENT(위험한 차 경고): 바로 말함. 단 직전 발화와 3초 간격
 *  - NORMAL 개선 안내: 3분 간격 / 칭찬: 1분 간격 / 어떤 말이든 10초 간격
 *  - 깜빡이·브레이크 중, 차선 변경 직후 5초는 NORMAL 안내를 미룸
 *  - 30초 넘게 못 한 말은 버리고 주행 후 리포트에만 남김
 */
class VoiceCoach(
    private val speak: (String) -> Unit,
    private val negativeGapMs: Long = 180_000,
    private val praiseGapMs: Long = 60_000,
    private val anyGapMs: Long = 10_000,
    private val urgentGapMs: Long = 3_000,
    private val staleMs: Long = 30_000,
) {
    private val pending = ArrayDeque<Event>()
    private var lastNeg = Long.MIN_VALUE / 2
    private var lastPraise = Long.MIN_VALUE / 2
    private var lastAny = Long.MIN_VALUE / 2
    private var lastLaneChange = Long.MIN_VALUE / 2
    var muted = false

    fun offer(e: Event) { if (e.speech != null) pending.addLast(e) }

    fun tick(s: Snapshot, laneChangedNow: Boolean) {
        if (laneChangedNow) lastLaneChange = s.timeMs
        if (muted) { pending.clear(); return }
        while (pending.isNotEmpty() && s.timeMs - pending.first().timeMs > staleMs) pending.removeFirst()

        // 급한 경고 먼저
        pending.firstOrNull { it.priority == Priority.URGENT }?.let { e ->
            if (s.timeMs - lastAny >= urgentGapMs) {
                pending.remove(e); lastAny = s.timeMs; speak(e.speech!!)
            }
            return
        }
        if (pending.isEmpty()) return
        val busy = s.turnSignal != null || s.brakePressed || s.timeMs - lastLaneChange < 5_000
        if (busy || s.timeMs - lastAny < anyGapMs) return

        val e = pending.removeFirst()
        val praise = e.type.penalty < 0
        val ok = if (praise) s.timeMs - lastPraise >= praiseGapMs else s.timeMs - lastNeg >= negativeGapMs
        if (!ok) return // 너무 잦으면 말하지 않고 리포트에만
        if (praise) lastPraise = s.timeMs else lastNeg = s.timeMs
        lastAny = s.timeMs
        speak(e.speech!!)
    }
}
