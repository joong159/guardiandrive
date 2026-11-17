package ai.guardiandrive.core

/**
 * 가디언 드라이브 핵심 데이터 모델 (안드로이드 비의존 순수 Kotlin).
 *
 * 공모전 허용 SDK로 받는 값:  속도·브레이크·방향지시등·비상등·핸들 각도(Vehicle),
 *                            위치(FusedLocation), 도로 종류·제한속도(NaviHelper)
 * ADAS SDK가 허용되면 추가로: 차선 번호·선 종류·주변 차량 (adas 필드, 없으면 null)
 */

enum class RoadType { HIGHWAY, URBAN }

enum class Direction { LEFT, RIGHT;
    val korean get() = if (this == LEFT) "왼쪽" else "오른쪽"
}

enum class LineType { YELLOW_SOLID, ROAD_EDGE, WHITE_SOLID, WHITE_DASHED, UNKNOWN }

/**
 * ADAS가 감지한 주변 차량.
 * longitudinalM: + 앞 / - 뒤,  lateralM: + 왼쪽 / - 오른쪽 (내 차 중심 기준, 미터)
 */
data class NearbyVehicle(
    val id: Int,
    val longitudinalM: Double,
    val lateralM: Double,
) {
    val sameLane get() = kotlin.math.abs(lateralM) < LANE_HALF_M
    companion object { const val LANE_HALF_M = 1.75 }
}

/** ADAS SDK에서 오는 정보. 공모전에서 ADAS를 못 쓰면 항상 null */
data class AdasFrame(
    val laneIndex: Int? = null,      // 1 = 가장 왼쪽 차선
    val laneCount: Int? = null,
    val leftLine: LineType = LineType.UNKNOWN,
    val vehicles: List<NearbyVehicle> = emptyList(),
) {
    val frontGapM get() = vehicles.filter { it.sameLane && it.longitudinalM > 0 }.minOfOrNull { it.longitudinalM }
    val rearGapM get() = vehicles.filter { it.sameLane && it.longitudinalM < 0 }.minOfOrNull { -it.longitudinalM }
}

/** 1초마다 SDK 값을 모아 만드는 주행 스냅샷 */
data class Snapshot(
    val timeMs: Long,
    val speedKph: Double,
    val speedLimitKph: Double? = null,
    val roadType: RoadType = RoadType.HIGHWAY,
    val brakePressed: Boolean = false,
    /** 켜진 방향지시등. 꺼져 있으면 null */
    val turnSignal: Direction? = null,
    val hazardOn: Boolean = false,
    /** 트렁크 열림 (Vehicle SDK Door 도메인, 권한 CAR_DOORS) */
    val trunkOpen: Boolean = false,
    /** 핸들 각도(도). + 왼쪽 / - 오른쪽 */
    val steeringDeg: Double = 0.0,
    val lat: Double? = null,
    val lon: Double? = null,
    val roadName: String? = null,
    val adas: AdasFrame? = null,
    /** 지금 도로 구간의 평균 흐름 속도 (ITS 소통정보·내비). 모르면 null */
    val flowSpeedKph: Double? = null,
    /** 새로 받은 ITS 돌발정보 목록 (1분마다). null이면 이전 목록 유지 */
    val incidents: List<RoadIncident>? = null,
)

enum class EventType(val penalty: Int, val label: String) {
    HARD_BRAKE(3, "급제동"),
    PHANTOM_BRAKE(5, "앞이 빈데 급제동"),
    UNSIGNALED_LANE_CHANGE(3, "깜빡이 없는 차선 변경"),
    LANE_WEAVING(5, "잦은 차선 변경"),
    PASSING_LANE_HOGGING(10, "1차선 저속 주행"),
    SLOW_FLOW(5, "흐름보다 느린 주행"),
    YIELDED(-3, "양보"),
    SMOOTH_STREAK(-2, "매끄러운 10분"),
    // 아래는 점수와 무관한 안전 이벤트
    LANE_CHANGE(0, "차선 변경"),
    CUT_IN_AHEAD(0, "앞차 끼어들기"),
    ERRATIC_NEIGHBOR(0, "주변 차 불안 주행"),
    ACCIDENT(0, "충돌 감지"),
    LANE_HAZARD_AHEAD(0, "앞쪽 차로 사고"),
}

enum class Priority { NORMAL, URGENT }

data class Event(
    val type: EventType,
    val timeMs: Long,
    /** 음성으로 말할 문장. null이면 기록만 */
    val speech: String? = null,
    val priority: Priority = Priority.NORMAL,
    val direction: Direction? = null,
)
