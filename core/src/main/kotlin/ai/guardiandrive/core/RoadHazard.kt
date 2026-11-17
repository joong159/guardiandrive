package ai.guardiandrive.core

import kotlin.math.*

/**
 * 실시간 차선별 사고 알림.
 * ITS(국가교통정보센터) 돌발정보 한 건. 앱이 1분마다 받아 Snapshot.incidents로 넘겨요.
 * (웹 프로토타입 lane-accident의 normalizeEvent와 같은 항목)
 */
data class RoadIncident(
    val id: String,
    val lat: Double,
    val lon: Double,
    val roadName: String?,
    val eventType: String,          // 교통사고, 공사 …
    val lanesBlockType: String?,    // 부분차단, 전면차단
    val lanesBlocked: List<Int>,    // 1 = 중앙선 쪽 차로
) {
    val fullyBlocked get() = lanesBlockType == "전면차단"
}

/** "1,2" / "1차로" / "2-3차로" 같은 차단 차로 문자열 → 차로 번호 목록 */
fun parseLanes(raw: String?): List<Int> {
    if (raw.isNullOrBlank()) return emptyList()
    Regex("""(\d+)\s*[-~]\s*(\d+)""").find(raw)?.let { m ->
        return (m.groupValues[1].toInt()..m.groupValues[2].toInt()).toList()
    }
    return Regex("""\d+""").findAll(raw).map { it.value.toInt() }.toList()
}

/**
 * 내 진행 방향 앞쪽 [maxAheadM] 안, 같은 도로의 돌발상황만 골라 한 번씩 안내.
 * 진행 방향은 직전 위치와 지금 위치로 구해요 (위치가 없거나 멈춰 있으면 판단하지 않음).
 */
class LaneHazardWatch(private val maxAheadM: Double = 3_000.0) {
    private var incidents: List<RoadIncident> = emptyList()
    private val announced = mutableSetOf<String>()
    private var prevLat: Double? = null
    private var prevLon: Double? = null

    /** 화면 표시용: 앞쪽 사고와 남은 거리 */
    data class Ahead(val incident: RoadIncident, val distM: Double)

    /** 안내한 사고 중 아직 앞에 있는 가장 가까운 것 (지나가면 null) */
    var active: Ahead? = null
        private set

    fun process(s: Snapshot): List<Event> {
        s.incidents?.let { incidents = it }
        val lat = s.lat ?: return emptyList()
        val lon = s.lon ?: return emptyList()
        val pLat = prevLat; val pLon = prevLon
        prevLat = lat; prevLon = lon
        if (pLat == null || pLon == null || distanceM(pLat, pLon, lat, lon) < 1.0) return emptyList()
        val heading = bearingDeg(pLat, pLon, lat, lon)
        fun aheadOf(inc: RoadIncident, maxAngle: Double) =
            distanceM(lat, lon, inc.lat, inc.lon) <= maxAheadM && angleDiff(heading, bearingDeg(lat, lon, inc.lat, inc.lon)) <= maxAngle

        val events = incidents.filter { inc ->
            inc.id !in announced && sameRoad(s.roadName, inc.roadName) && aheadOf(inc, 45.0)
        }.map { inc ->
            announced += inc.id
            Event(EventType.LANE_HAZARD_AHEAD, s.timeMs, message(inc, distanceM(lat, lon, inc.lat, inc.lon)), Priority.URGENT)
        }
        // 사고 지점에 가까워지면 방위각이 크게 흔들리므로 표시 유지는 90°까지 허용
        active = incidents.filter { it.id in announced && aheadOf(it, 90.0) }
            .map { Ahead(it, distanceM(lat, lon, it.lat, it.lon)) }.minByOrNull { it.distM }
        return events
    }

    companion object {
        fun message(inc: RoadIncident, distM: Double): String {
            val where = if (distM >= 1_000) "${(distM / 1000).roundToInt()}km 앞" else "${(distM / 100).roundToInt() * 100}m 앞"
            val what = if (inc.eventType == "교통사고") "사고" else inc.eventType
            return when {
                inc.fullyBlocked -> "$where ${what}로 전면 통제 중이에요. 속도를 줄이고 우회를 준비하세요."
                inc.lanesBlocked.isEmpty() -> "$where ${what}가 있어요. 속도를 줄이세요."
                else -> {
                    val lanes = inc.lanesBlocked.joinToString(",") + "차로"
                    val free = (1..inc.lanesBlocked.max() + 1).firstOrNull { it !in inc.lanesBlocked }
                    "$where $lanes ${what}예요. ${free}차로 쪽으로 미리 옮기세요."
                }
            }
        }

        // 도로명이 없으면 거리·방향만으로 판단 (ITS "경부고속도로" ⊂ 내비 "경부고속도로 하행 …")
        private fun sameRoad(mine: String?, theirs: String?) =
            mine == null || theirs == null || mine.contains(theirs) || theirs.contains(mine)

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }

        private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        private fun angleDiff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)
    }
}
