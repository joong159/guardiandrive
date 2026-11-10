package ai.guardiandrive.app

import ai.guardiandrive.core.Direction
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

/**
 * 실제 차량(또는 Pleos Connect 에뮬레이터의 버추얼 콘솔) 값을 실시간으로 읽어 두는 곳.
 *
 *  - Pleos Vehicle SDK : 브레이크, 방향지시등, 핸들 각도, 트렁크(뒷문 위치)
 *  - 비상등: ① Pleos Safety API 계기판 경고등(왼쪽·오른쪽 방향지시 표시등이 동시에 켜짐) 1초마다 확인
 *           ② 안드로이드 표준 HAZARD_LIGHTS_STATE (시스템 권한이 필요해 일반 앱은 못 읽을 수 있음) — 둘 중 하나라도 켜지면 켜짐
 *  - 안드로이드 표준 차량 API(CarPropertyManager) : 속도
 *    → Vehicle SDK 2.3.2 공개 API에 속도·비상등이 없어서, 같은 차량 속성(PERF_VEHICLE_SPEED,
 *      HAZARD_LIGHTS_STATE)을 안드로이드 표준 방식으로 읽습니다.
 *
 * 휴대폰 에뮬레이터처럼 차량 서비스가 없는 기기에서는 조용히 실패하고 기본값(꺼짐)으로 남아요.
 */
@SuppressLint("MissingPermission")
class VehicleReader(context: Context) {
    private val ctx = context.applicationContext
    private val tag = "GuardianVehicle"

    @Volatile var speedKph: Double? = null; private set   // null = 아직 못 읽음
    @Volatile var brake = false; private set
    @Volatile var signal: Direction? = null; private set
    @Volatile private var carHazard = false
    @Volatile private var telltaleBothOnMs = 0L
    /** 비상등: 표준 속성 또는 '양쪽 방향지시 표시등이 1.5초 안에 함께 켜짐' */
    val hazard: Boolean get() = carHazard || System.currentTimeMillis() - telltaleBothOnMs < 1_500
    @Volatile var trunk = false; private set
    @Volatile var steeringDeg = 0.0; private set

    /** 화면에 보여줄 연결 상태 (값이 한 번이라도 들어온 항목) */
    private val received = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val connectedItems: Set<String> get() = received
    var onChange: (() -> Unit)? = null

    private var vehicle: Vehicle? = null
    private var car: Any? = null          // android.car.Car (휴대폰에는 클래스가 없어서 Any로 보관)
    private var carCallback: Any? = null
    private var started = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastNotified = ""

    /** 화면 갱신은 '새 항목 연결' 또는 비상등·트렁크 변화가 있을 때만 (핸들 각도처럼 자주 오는 값으로 화면이 바쁘지 않게) */
    private fun mark(item: String) {
        val isNew = received.add(item)
        if (isNew) Log.i(tag, "연결됨: $item")
        val key = "$hazard/$trunk"
        if (isNew || key != lastNotified) { lastNotified = key; onChange?.invoke() }
    }

    fun start() {
        if (started) return
        started = true
        startPleos()
        startCarApi()
    }

    // ---------- Pleos Vehicle SDK ----------
    private val brakeL = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) { brake = status == true; mark("브레이크") }
        override fun onFailed(e: Exception) { Log.w(tag, "브레이크 실패: ${e.message}") }
    }
    private val signalL = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            signal = when (state) { TurnSignalValue.LEFT -> Direction.LEFT; TurnSignalValue.RIGHT -> Direction.RIGHT; else -> null }
            mark("방향지시등")
        }
        override fun onFailed(e: Exception) { Log.w(tag, "방향지시등 실패: ${e.message}") }
    }
    private val steerL = object : SteeringWheelAngleListener {
        // 엔진 기준: 왼쪽 = +. 실제 차에서 부호가 반대면 여기서 -를 붙이세요.
        override fun onSteeringWheelAngleUpdated(angle: Float?) { steeringDeg = angle?.toDouble() ?: 0.0; mark("핸들") }
        override fun onFailed(e: Exception) { Log.w(tag, "핸들 실패: ${e.message}") }
    }
    private val trunkL = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) { trunk = (pos ?: 0) > 0; mark("트렁크") }
        override fun onFailed(e: Exception) { Log.w(tag, "트렁크 실패: ${e.message}") }
    }
    private val trunkArea = DoorArea(DoorValue.REAR)

    private fun startPleos() {
        runCatching {
            val v = Vehicle(ctx).also { it.initialize() }
            vehicle = v
            runCatching { v.getBrake().registerBrakePedalStatus(brakeL) }.onFailure { Log.w(tag, "브레이크 등록 실패", it) }
            runCatching { v.getTurnSignal().registerTurnSignalState(signalL) }.onFailure { Log.w(tag, "방향지시등 등록 실패", it) }
            runCatching { v.getSteeringWheel().registerSteeringWheelAngle(steerL) }.onFailure { Log.w(tag, "핸들 등록 실패", it) }
            runCatching { v.getDoor().registerDoorPosition(trunkArea, trunkL) }.onFailure { Log.w(tag, "트렁크 등록 실패", it) }
            handler.post(telltalePoll)
        }.onFailure { Log.w(tag, "Pleos Vehicle SDK 사용 불가 (차량 서비스 없음)", it) }
    }

    /** 계기판 경고등 1초마다 확인 → 왼쪽·오른쪽 방향지시 표시등이 함께 켜져 있으면 비상등 */
    private val telltalePoll = object : Runnable {
        override fun run() {
            val v = vehicle ?: return
            runCatching {
                v.getSafety().getTelltalesInfo({ t ->
                    fun on(type: TelltaleType) = t.telltales.any { it.type == type && it.state != TelltaleState.OFF }
                    if (on(TelltaleType.TURN_SIGNAL_LEFT) && on(TelltaleType.TURN_SIGNAL_RIGHT)) telltaleBothOnMs = System.currentTimeMillis()
                    mark("비상등")
                }, { e -> Log.w(tag, "경고등 조회 실패: ${e.message}") })
            }
            handler.postDelayed(this, 1_000)
        }
    }

    // ---------- 안드로이드 표준 차량 API (속도·비상등) ----------
    private fun startCarApi() {
        try {
            val c = android.car.Car.createCar(ctx) ?: return
            car = c
            val pm = c.getCarManager(android.car.Car.PROPERTY_SERVICE) as android.car.hardware.property.CarPropertyManager
            val cb = object : android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: android.car.hardware.CarPropertyValue<*>) {
                    when (value.propertyId) {
                        android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                            speedKph = ((value.value as? Float) ?: 0f) * 3.6; mark("속도")
                        }
                        android.car.VehiclePropertyIds.HAZARD_LIGHTS_STATE -> {
                            carHazard = (value.value as? Int) == 1; mark("비상등")   // 0 꺼짐, 1 켜짐
                        }
                    }
                }
                override fun onErrorEvent(propId: Int, zone: Int) { Log.w(tag, "차량 속성 오류 $propId") }
            }
            carCallback = cb
            runCatching { pm.registerCallback(cb, android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED, 1f) }
                .onFailure { Log.w(tag, "속도 등록 실패", it) }
            runCatching {
                pm.registerCallback(cb, android.car.VehiclePropertyIds.HAZARD_LIGHTS_STATE,
                    android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE)
            }.onFailure { Log.w(tag, "비상등 등록 실패", it) }
        } catch (t: Throwable) {
            // 휴대폰 에뮬레이터 등 차량 기능이 없는 기기 (NoClassDefFoundError 포함)
            Log.w(tag, "차량 API 사용 불가: ${t.javaClass.simpleName}")
        }
    }

    fun stop() {
        handler.removeCallbacks(telltalePoll)
        vehicle?.let { v ->
            // 하나가 실패해도 나머지 해제와 release()는 꼭 실행
            try {
                runCatching { v.getBrake().unregisterBrakePedalStatus(brakeL) }
                runCatching { v.getTurnSignal().unregisterTurnSignalState(signalL) }
                runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(steerL) }
                runCatching { v.getDoor().unregisterDoorPosition(trunkArea, trunkL) }
            } finally { runCatching { v.release() } }
        }
        received.clear()
        try {
            (car as? android.car.Car)?.let { c ->
                val pm = c.getCarManager(android.car.Car.PROPERTY_SERVICE) as android.car.hardware.property.CarPropertyManager
                (carCallback as? android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback)?.let { pm.unregisterCallback(it) }
                c.disconnect()
            }
        } catch (_: Throwable) {}
        vehicle = null; car = null; started = false
    }
}
