package ai.guardiandrive.app

import ai.guardiandrive.core.AccidentReport
import ai.guardiandrive.core.GuardianEngine
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 백그라운드에서도 계속 지켜보는 서비스.
 *
 *  - 판단 엔진, 차량 연결(VehicleReader), 주행 데이터 공급원, 음성 안내(TTS)를 모두 여기서 돌려요.
 *    → 내비게이션 등 다른 앱을 쓰는 중에도 운전 점검과 사고 감지가 멈추지 않아요.
 *  - 주행 중에는 상단에 "가디언 드라이브 지켜보는 중" 알림이 떠요 (안드로이드 규칙상 필수).
 *  - 사고가 감지되면 앱 화면을 앞으로 불러오고, 안 되는 경우를 대비해 긴급 알림도 띄워요.
 *  - 화면(MainActivity)은 이 서비스에 연결해서 상태를 보여주기만 해요.
 *    → 차의 낮/밤 모드가 바뀌어 화면이 다시 그려져도 진행 중인 안내가 끊기지 않아요.
 */
class GuardianService : Service() {

    /** 화면에 보여줄 내용 */
    sealed class Screen {
        object Idle : Screen()
        data class Live(val ui: GuardianEngine.UiState) : Screen()
        data class Report(val report: AccidentReport) : Screen()
        data class Trip(val summary: String) : Screen()
    }

    interface Listener {
        fun onScreen(screen: Screen)
        fun onMessage(text: String)
        fun onSourceChanged()
        fun onDriverText(text: String)
        fun onSpeaking(speaking: Boolean)
    }

    inner class LocalBinder : Binder() { val service: GuardianService get() = this@GuardianService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val handler = Handler(Looper.getMainLooper())
    lateinit var vehicle: VehicleReader; private set
    var source: DrivingSource? = null; private set
    private var engine: GuardianEngine = newEngine()
    var screen: Screen = Screen.Idle; private set
    var driverText: String = ""; private set
    /** 레이더에 그릴 주변 차 (ADAS 시나리오용) */
    var lastVehicles: List<ai.guardiandrive.core.NearbyVehicle> = emptyList(); private set
    var listener: Listener? = null
        set(v) { field = v; v?.onScreen(screen); v?.onDriverText(driverText); v?.onSourceChanged() }

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var pendingUtterances = 0
    private var inAccident = false
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
        vehicle = VehicleReader(this).also { v -> v.onChange = { handler.post { listener?.onSourceChanged() } } }
        vehicle.start()
        // 주의: TTS 엔진이 없는 기기는 콜백이 생성자 안에서 바로 불려 tts가 비어 있을 수 있어 post로 미룸
        tts = TextToSpeech(this) { status -> handler.post { if (status == TextToSpeech.SUCCESS) onTtsReady() else ttsReady = false } }
    }

    private fun onTtsReady() {
        val lang = tts.setLanguage(Locale.KOREAN)
        ttsReady = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { handler.post { listener?.onSpeaking(true) } }
            override fun onDone(id: String?) = finished()
            @Deprecated("") override fun onError(id: String?) = finished()
            override fun onStop(id: String?, interrupted: Boolean) = finished()
            private fun finished() { handler.post { pendingUtterances = (pendingUtterances - 1).coerceAtLeast(0); if (pendingUtterances == 0) listener?.onSpeaking(false) } }
        })
    }

    /** 음성 엔진이 없으면 화면 글자로만 안내 (Pleos Connect 에뮬레이터) */
    val hasVoice get() = ttsReady

    // ---------------- 주행 시작·종료 ----------------
    fun startSource(src: DrivingSource) {
        source?.stop()
        if (::tts.isInitialized) tts.stop()
        pendingUtterances = 0; listener?.onSpeaking(false)
        engine = newEngine()
        inAccident = false
        setDriverText("")
        source = src
        goForeground()
        src.start(
            onSnapshot = { s -> lastVehicles = s.adas?.vehicles ?: emptyList(); engine.onSnapshot(s) },
            onVoice = { text -> onVoice(text, "운전자: \"$text\"") },
        )
        listener?.onSourceChanged()
    }

    fun finishTrip() {
        source?.stop(); source = null
        inAccident = false
        setScreen(Screen.Trip(engine.finishTrip()))
        stopForegroundCompat()
        listener?.onSourceChanged()
    }

    /** 운전자 음성 또는 화면 버튼 */
    fun onVoice(text: String, shownAs: String) {
        setDriverText(shownAs)
        engine.onVoice(text)
    }

    private fun setDriverText(t: String) { driverText = t; listener?.onDriverText(t) }
    private fun setScreen(s: Screen) { screen = s; listener?.onScreen(s) }

    // ---------------- 엔진 출력 ----------------
    private fun newEngine() = GuardianEngine(object : GuardianEngine.Output {
        override fun speak(text: String) {
            if (ttsReady && tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString()) == TextToSpeech.SUCCESS) pendingUtterances++
        }

        override fun notifyEmergencyContacts(lat: Double?, lon: Double?, roadName: String?) {
            // TODO: 외부 통신 허용 여부 확인 후 구현 (문자/푸시). 시연에서는 화면 표시만.
            Log.i(TAG, "비상 연락: $roadName ($lat, $lon)")
            message("비상 연락처에 위치 전송: ${roadName ?: ""}")
        }

        override fun notifyFalseAlarm() {
            // TODO: 비상 연락과 같은 경로로 정정 안내 전송. 시연에서는 화면 표시만.
            Log.i(TAG, "비상 연락 정정: 사고 아님")
            message("비상 연락처에 오인 안내를 보냈어요 (사고 아님)")
        }

        override fun showAccidentReport(report: AccidentReport) { setScreen(Screen.Report(report)) }

        override fun onState(state: GuardianEngine.UiState) {
            val accident = state.mode == GuardianEngine.Mode.ACCIDENT
            if (accident && !inAccident) onAccidentDetected()
            if (!accident && inAccident) cancelAccidentAlert()
            inAccident = accident
            // 리포트 화면은 다음 주행 시작 전까지 유지
            if (screen is Screen.Report && !accident) return
            setScreen(Screen.Live(state))
        }
    })

    private fun message(text: String) {
        val l = listener
        if (l != null) l.onMessage(text) else notifyAlert(text)
    }

    // ---------------- 사고 감지 시 앱 화면 불러오기 ----------------
    private fun onAccidentDetected() {
        Log.i(TAG, "사고 감지 → 앱 화면 불러오기")
        // ① 바로 화면 띄우기 (차량 시스템이 허용하면 즉시 앞으로)
        runCatching { startActivity(openAppIntent()) }.onFailure { Log.w(TAG, "화면 직접 띄우기 실패", it) }
        // ② 막혀 있어도 긴급 알림(전체 화면 알림)으로 화면을 띄우거나 누르면 바로 열리게
        notifyAlert("충격이 감지됐어요. 괜찮으세요? 눌러서 안내를 확인하세요.")
    }

    private fun openAppIntent() = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun pendingOpenApp() = PendingIntent.getActivity(
        this, 0, openAppIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notifyAlert(text: String) {
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("가디언 드라이브")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pendingOpenApp())
            .setFullScreenIntent(pendingOpenApp(), true)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ID_ALERT, n) }
    }

    private fun cancelAccidentAlert() { runCatching { getSystemService(NotificationManager::class.java).cancel(ID_ALERT) } }

    // ---------------- 상시 알림 (백그라운드 실행) ----------------
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WATCH, "주행 지켜보기", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "사고 감지", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun watchNotification(): Notification = Notification.Builder(this, CH_WATCH)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("가디언 드라이브 지켜보는 중")
        .setContentText("다른 앱을 써도 운전 점검과 사고 감지는 계속돼요")
        .setContentIntent(pendingOpenApp())
        .setOngoing(true)
        .build()

    private fun goForeground() {
        // 서비스가 앱 화면과 상관없이 살아 있도록 시작 요청 후 전경 서비스로 전환
        runCatching { startForegroundService(Intent(this, GuardianService::class.java)) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(ID_WATCH, watchNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID_WATCH, watchNotification())
            foreground = true
        }.onFailure { Log.w(TAG, "백그라운드 실행 시작 실패", it) }
    }

    private fun stopForegroundCompat() {
        if (!foreground) return
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foreground = false
        stopSelf() // 화면이 연결돼 있으면 연결이 끊길 때까지 살아 있음
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 뒤 5초 안에 startForeground가 필요 → 바로 호출
        if (!foreground) goForegroundOnly()
        return START_NOT_STICKY
    }

    private fun goForegroundOnly() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(ID_WATCH, watchNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID_WATCH, watchNotification())
            foreground = true
        }
    }

    override fun onDestroy() {
        source?.stop()
        vehicle.stop()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GuardianService"
        private const val CH_WATCH = "guardian_watch"
        private const val CH_ALERT = "guardian_alert"
        private const val ID_WATCH = 1
        private const val ID_ALERT = 2

        fun bindIntent(context: Context) = Intent(context, GuardianService::class.java)
    }
}
