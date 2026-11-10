package ai.guardiandrive.core

/**
 * "내 운전이 정체를 만들고 있나?" 점검.
 *
 * 허용 SDK만으로 동작:  급제동(잦으면 안내), 깜빡이 없는 차선 변경, 잦은 차선 변경, 매끄러운 주행 칭찬
 * ADAS가 있으면 추가:   앞이 빈데 급제동, 1차선(추월차로) 저속 주행 + 양보 칭찬
 *
 * 억울한 지적을 막는 원칙
 *  - 앞이 막혀 있을 수 있는 상황(ADAS 없음)에서는 급제동 1번으로 지적하지 않고, 5분 안에 2번 이상일 때만 말함
 *  - 순간이 아니라 지속된 패턴만 판단
 *  - 사고 상황의 급정거는 여기서 다루지 않음 (AccidentDetector 담당)
 */
class DrivingCheck(private val cfg: Config = Config()) {
    data class Config(
        val hardBrakeMps2: Double = 3.0,
        val hardBrakeMinKph: Double = 60.0,
        val repeatBrakeWindowSec: Int = 300,
        val repeatBrakeCount: Int = 2,
        val weavingCount: Int = 3,
        val weavingWindowSec: Int = 120,
        val hoggingWindowSec: Int = 60,
        val slowRatio: Double = 0.85,
        val congestionKph: Double = 40.0,
        val openRoadM: Double = 80.0,
        val tailgatedM: Double = 30.0,
        val tailgatedRatio: Double = 0.5,
        val yieldWindowSec: Int = 60,
        val smoothStreakSec: Int = 600,
        val brakeDebounceSec: Int = 10,
        val alertCooldownSec: Int = 180,
    )

    private val lanes = LaneChangeDetector()
    private var prev: Snapshot? = null

    private val brakeTimes = ArrayDeque<Long>()
    private var lastBrakeMs = Long.MIN_VALUE / 2
    private var lastBrakeSpeechMs = Long.MIN_VALUE / 2

    private val laneChangeTimes = ArrayDeque<Long>()
    private var lastWeavingMs = Long.MIN_VALUE / 2

    private var hogSince: Long? = null
    private var hogSamples = 0
    private var hogTail = 0
    private var lastHogMs = Long.MIN_VALUE / 2
    private var awaitingYieldSince: Long? = null

    private var smoothSince: Long? = null

    /** 차선 번호를 알면(ADAS) 추월차로 주행 시간 */
    var passingLaneSeconds = 0
        private set
    var currentLane: Int? = null
        private set

    /** 이번 스냅샷의 감속도 (m/s²). 사고 감지에서도 씀 */
    fun decelOf(s: Snapshot, p: Snapshot?): Double {
        p ?: return 0.0
        val dt = (s.timeMs - p.timeMs) / 1000.0
        return if (dt <= 0) 0.0 else (p.speedKph - s.speedKph) / 3.6 / dt
    }

    fun process(s: Snapshot): List<Event> {
        val out = mutableListOf<Event>()
        currentLane = laneOf(s)
        val passing = s.roadType == RoadType.HIGHWAY && (s.adas?.laneCount ?: 2) > 1 && currentLane == 1
        if (passing) passingLaneSeconds++

        lanes.update(s)?.let { lc -> onLaneChange(s, lc, out) }
        checkBrake(s, out)
        checkHogging(s, passing, out)
        checkYieldTimeout(s)
        checkSmooth(s, out)

        // 감점 이벤트가 있으면 '매끄러운 주행' 카운트를 처음부터 다시
        if (out.any { it.type.penalty > 0 }) {
            smoothSince = null
            out.removeAll { it.type == EventType.SMOOTH_STREAK }
        }
        prev = s
        return out
    }

    private fun laneOf(s: Snapshot): Int? {
        val a = s.adas ?: return null
        a.laneIndex?.let { return it }
        return if (a.leftLine == LineType.YELLOW_SOLID || a.leftLine == LineType.ROAD_EDGE) 1 else null
    }

    private fun onLaneChange(s: Snapshot, lc: LaneChangeDetector.Result, out: MutableList<Event>) {
        out += Event(EventType.LANE_CHANGE, s.timeMs, direction = lc.direction)
        if (!lc.signaled) {
            out += Event(EventType.UNSIGNALED_LANE_CHANGE, s.timeMs, Messages.unsignaled(lc.direction), direction = lc.direction)
        }
        // 1차선 경고 후 오른쪽으로 옮기면 칭찬
        if (awaitingYieldSince != null && lc.direction == Direction.RIGHT) {
            awaitingYieldSince = null
            out += Event(EventType.YIELDED, s.timeMs, Messages.yielded())
        }
        laneChangeTimes.addLast(s.timeMs)
        while (laneChangeTimes.isNotEmpty() && s.timeMs - laneChangeTimes.first() > cfg.weavingWindowSec * 1000L) laneChangeTimes.removeFirst()
        if (laneChangeTimes.size >= cfg.weavingCount && s.timeMs - lastWeavingMs > cfg.weavingWindowSec * 1000L) {
            lastWeavingMs = s.timeMs
            val n = laneChangeTimes.size
            laneChangeTimes.clear()
            out += Event(EventType.LANE_WEAVING, s.timeMs, Messages.weaving(n))
        }
    }

    private fun checkBrake(s: Snapshot, out: MutableList<Event>) {
        val p = prev ?: return
        val decel = decelOf(s, p)
        val isHard = decel >= cfg.hardBrakeMps2 && decel < AccidentDetector.CRASH_DECEL_MPS2 &&
            s.brakePressed && p.speedKph >= cfg.hardBrakeMinKph &&
            s.timeMs - lastBrakeMs >= cfg.brakeDebounceSec * 1000L
        if (!isHard) return
        lastBrakeMs = s.timeMs

        val adas = s.adas
        if (adas != null) {
            val front = adas.frontGapM
            val openRoad = front == null || front > cfg.openRoadM
            val someoneBehind = adas.rearGapM != null
            if (openRoad && someoneBehind && s.turnSignal == null) {
                out += Event(EventType.PHANTOM_BRAKE, s.timeMs, Messages.phantomBrake())
            }
            return // 앞이 막혀서 밟은 급제동은 운전자 탓 아님
        }

        // ADAS 없음: 이유를 모르므로 기록만, 잦을 때만 안내
        brakeTimes.addLast(s.timeMs)
        while (brakeTimes.isNotEmpty() && s.timeMs - brakeTimes.first() > cfg.repeatBrakeWindowSec * 1000L) brakeTimes.removeFirst()
        val speak = brakeTimes.size >= cfg.repeatBrakeCount && s.timeMs - lastBrakeSpeechMs >= cfg.alertCooldownSec * 1000L
        if (speak) lastBrakeSpeechMs = s.timeMs
        out += Event(EventType.HARD_BRAKE, s.timeMs, if (speak) Messages.repeatedBrake(brakeTimes.size) else null)
    }

    private fun checkHogging(s: Snapshot, passing: Boolean, out: MutableList<Event>) {
        val adas = s.adas
        val limit = s.speedLimitKph
        val front = adas?.frontGapM
        val ok = adas != null && passing && limit != null &&
            s.speedKph >= cfg.congestionKph && s.speedKph < limit * cfg.slowRatio &&
            (front == null || front > cfg.openRoadM) && s.turnSignal == null
        if (!ok) { hogSince = null; hogSamples = 0; hogTail = 0; return }
        if (hogSince == null) hogSince = s.timeMs
        hogSamples++
        if ((adas!!.rearGapM ?: Double.MAX_VALUE) < cfg.tailgatedM) hogTail++
        val sec = (s.timeMs - hogSince!!) / 1000
        if (sec >= cfg.hoggingWindowSec && hogTail.toDouble() / hogSamples >= cfg.tailgatedRatio &&
            s.timeMs - lastHogMs >= cfg.alertCooldownSec * 1000L
        ) {
            lastHogMs = s.timeMs
            awaitingYieldSince = s.timeMs
            hogSince = null; hogSamples = 0; hogTail = 0
            out += Event(EventType.PASSING_LANE_HOGGING, s.timeMs, Messages.hogging(s.speedKph.toInt()))
        }
    }

    private fun checkYieldTimeout(s: Snapshot) {
        val since = awaitingYieldSince ?: return
        if (s.timeMs - since > cfg.yieldWindowSec * 1000L) awaitingYieldSince = null
    }

    private fun checkSmooth(s: Snapshot, out: MutableList<Event>) {
        if (s.speedKph < cfg.congestionKph) return
        if (smoothSince == null) smoothSince = s.timeMs
        if (s.timeMs - smoothSince!! >= cfg.smoothStreakSec * 1000L) {
            smoothSince = s.timeMs
            out += Event(EventType.SMOOTH_STREAK, s.timeMs, Messages.smooth())
        }
    }
}
