package ai.guardiandrive.core

import kotlin.math.abs

/**
 * 방어 운전 레이더 (ADAS 감지 차량이 있을 때만 동작, 없으면 아무 일도 안 함).
 *
 *  - 앞 끼어들기: 옆 차선 차가 2초 안에 내 차선 25m 이내로 들어옴
 *  - 불안 주행 차: 옆 차가 20초 안에 차선 경계를 두 번 이상 넘나듦 (갈지자)
 *  같은 차에 대한 경고는 60초에 한 번
 */
class DefensiveRadar(
    private val cutInWindowMs: Long = 2_000,
    private val cutInMaxM: Double = 25.0,
    private val weaveWindowMs: Long = 20_000,
    private val weaveCrossings: Int = 2,
    private val perVehicleCooldownMs: Long = 60_000,
) {
    private data class Track(
        var lastLat: Double,
        var lastSeenMs: Long,
        var adjacentSinceMs: Long?,
        val crossings: ArrayDeque<Long> = ArrayDeque(),
        var lastSide: Int,
    )

    private val tracks = mutableMapOf<Int, Track>()
    private val lastWarnMs = mutableMapOf<Int, Long>()

    private fun side(lat: Double): Int = when {
        lat > NearbyVehicle.LANE_HALF_M -> 1      // 왼쪽 차선
        lat < -NearbyVehicle.LANE_HALF_M -> -1    // 오른쪽 차선
        else -> 0                                  // 내 차선
    }

    fun process(s: Snapshot): List<Event> {
        val adas = s.adas ?: return emptyList()
        val out = mutableListOf<Event>()
        for (v in adas.vehicles) {
            val sd = side(v.lateralM)
            val t = tracks.getOrPut(v.id) { Track(v.lateralM, s.timeMs, if (sd != 0) s.timeMs else null, lastSide = sd) }

            // 차선 경계 넘나듦 기록 (내 차선은 제외하고 옆 차선끼리, 또는 옆↔내 차선)
            if (sd != t.lastSide) t.crossings.addLast(s.timeMs)
            while (t.crossings.isNotEmpty() && s.timeMs - t.crossings.first() > weaveWindowMs) t.crossings.removeFirst()

            val canWarn = s.timeMs - (lastWarnMs[v.id] ?: Long.MIN_VALUE / 2) >= perVehicleCooldownMs

            // 앞 끼어들기
            val enteredMyLane = t.lastSide != 0 && sd == 0
            val cameFast = t.adjacentSinceMs?.let { s.timeMs - t.lastSeenMs <= cutInWindowMs } ?: false
            if (enteredMyLane && cameFast && v.longitudinalM in 0.0..cutInMaxM && canWarn) {
                val from = if (t.lastSide > 0) Direction.LEFT else Direction.RIGHT
                out += Event(EventType.CUT_IN_AHEAD, s.timeMs, Messages.cutIn(from), Priority.URGENT, from)
                lastWarnMs[v.id] = s.timeMs
            } else if (t.crossings.size >= weaveCrossings && sd != 0 && abs(v.longitudinalM) < 40 && canWarn) {
                val where = if (sd > 0) Direction.LEFT else Direction.RIGHT
                out += Event(EventType.ERRATIC_NEIGHBOR, s.timeMs, Messages.erratic(where), Priority.URGENT, where)
                lastWarnMs[v.id] = s.timeMs
                t.crossings.clear()
            }

            t.adjacentSinceMs = if (sd != 0) (t.adjacentSinceMs ?: s.timeMs) else null
            t.lastSide = sd
            t.lastLat = v.lateralM
            t.lastSeenMs = s.timeMs
        }
        // 10초 이상 안 보인 차는 정리
        tracks.entries.removeIf { s.timeMs - it.value.lastSeenMs > 10_000 }
        return out
    }
}
