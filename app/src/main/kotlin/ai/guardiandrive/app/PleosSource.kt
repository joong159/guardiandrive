package ai.guardiandrive.app

import ai.guardiandrive.core.RoadType
import ai.guardiandrive.core.Snapshot
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 실제 주행 모드: 차량 값(VehicleReader)을 1초마다 한 장의 스냅샷으로 묶어 엔진에 넘깁니다.
 *
 *  - 위치: Pleos FusedLocation SDK가 '준비 중'이라 안드로이드 기본 위치(LocationManager)
 *  - 도로 종류: NaviHelper 연동 전까지는 최근 2분 최고 속도로 추정 (80km/h 이상 → 고속도로)
 *    TODO NaviHelper: 도로 이름·고속도로 여부·제한속도로 교체
 *  - 음성: 사고 대응 중에는 MainActivity의 VoiceInput이 듣고 engine.onVoice로 넘김
 */
class PleosSource(context: Context, private val vehicle: VehicleReader) : DrivingSource {
    override val label = "실제 주행"
    private val ctx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val recentSpeeds = ArrayDeque<Pair<Long, Double>>()

    @Volatile private var lat: Double? = null
    @Volatile private var lon: Double? = null
    private var lm: LocationManager? = null
    private val locL = LocationListener { loc: Location -> lat = loc.latitude; lon = loc.longitude }

    @SuppressLint("MissingPermission")
    override fun start(onSnapshot: (Snapshot) -> Unit, onVoice: (String) -> Unit) {
        vehicle.start()
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                lm = ctx.getSystemService(LocationManager::class.java)?.also { m ->
                    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { m.isProviderEnabled(it) }
                    provider?.let { m.requestLocationUpdates(it, 1000L, 0f, locL, Looper.getMainLooper()) }
                }
            }.onFailure { Log.w("GuardianDrive", "위치 사용 불가", it) }
        }
        handler.post(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val speed = vehicle.speedKph ?: 0.0
                recentSpeeds.addLast(now to speed)
                while (recentSpeeds.isNotEmpty() && now - recentSpeeds.first().first > 120_000) recentSpeeds.removeFirst()
                val highway = recentSpeeds.maxOf { it.second } >= 80.0
                onSnapshot(
                    Snapshot(
                        timeMs = now,
                        speedKph = speed,
                        roadType = if (highway) RoadType.HIGHWAY else RoadType.URBAN,
                        brakePressed = vehicle.brake,
                        turnSignal = vehicle.signal,
                        hazardOn = vehicle.hazard,
                        trunkOpen = vehicle.trunk,
                        steeringDeg = vehicle.steeringDeg,
                        lat = lat, lon = lon,
                        adas = null, // ADAS SDK는 공모전 허용 목록에 없음 (향후 확장)
                    )
                )
                handler.postDelayed(this, 1000)
            }
        })
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { lm?.removeUpdates(locL) }
    }
}
