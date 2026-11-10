package ai.guardiandrive.app

import ai.guardiandrive.core.Snapshot
import ai.guardiandrive.core.sim.Scenarios
import android.os.Handler
import android.os.Looper

/** 1초마다 주행 스냅샷(과 시연용 음성)을 넘겨주는 공급원 */
interface DrivingSource {
    /** 화면에 보여줄 이름 */
    val label: String
    fun start(onSnapshot: (Snapshot) -> Unit, onVoice: (String) -> Unit)
    fun stop()
}

/**
 * 시연 모드: 미리 만든 시나리오를 재생합니다. 시나리오 안의 운전자 음성("괜찮아" 등)도 함께 재생돼요.
 *
 * [live]를 주면(PT 시나리오) 비상등·트렁크는 시나리오 대신 실제 차량 값을 써요.
 * → 심사위원이 버추얼 콘솔로 비상등을 누르면 앱이 차량 데이터로 알아채는 장면을 보여줄 수 있어요.
 */
class DemoSource(
    private val scenario: String,
    private val speedUp: Int = Scenarios.speedUpFor(scenario),
    private val live: VehicleReader? = null,
) : DrivingSource {
    val usesLive = live != null
    override val label = if (live != null) "PT 시연" else "시연 재생 ${speedUp}배속"
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun start(onSnapshot: (Snapshot) -> Unit, onVoice: (String) -> Unit) {
        val ticks = Scenarios.all.getValue(scenario)()
        running = true
        var i = 0
        handler.post(object : Runnable {
            override fun run() {
                if (!running || i >= ticks.size) return
                val tk = ticks[i++]
                val snap = live?.let { tk.snap.copy(hazardOn = tk.snap.hazardOn || it.hazard, trunkOpen = tk.snap.trunkOpen || it.trunk) } ?: tk.snap
                onSnapshot(snap)
                tk.voice?.let(onVoice)
                handler.postDelayed(this, 1000L / speedUp)
            }
        })
    }

    override fun stop() { running = false; handler.removeCallbacksAndMessages(null) }
}
