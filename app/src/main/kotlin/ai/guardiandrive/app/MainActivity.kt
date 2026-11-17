package ai.guardiandrive.app

import ai.guardiandrive.core.*
import ai.guardiandrive.core.AccidentGuide.Step
import ai.guardiandrive.core.sim.Scenarios
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * 가디언 드라이브 메인 화면 (보여주기 담당).
 *  - 판단·차량 연결·음성 안내는 GuardianService(백그라운드)가 하고, 이 화면은 상태를 보여주고 버튼·음성 입력을 넘겨요.
 *  - 차량 가로 화면: 왼쪽 도로 레이더 / 오른쪽 상태·안내. 세로 화면(휴대폰 테스트용): 위아래.
 *  - 낮에는 밝은 배경, 밤에는 어두운 배경 (차의 주간·야간 모드를 따라 자동 전환)
 */
class MainActivity : AppCompatActivity(), GuardianService.Listener {

    private var service: GuardianService? = null
    private lateinit var voice: VoiceInput

    private lateinit var radar: RadarView
    private lateinit var badge: TextView
    private lateinit var sourceChip: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var steps: TextView
    private lateinit var driverVoice: TextView
    private lateinit var listeningView: TextView
    private lateinit var accidentButtons: LinearLayout
    private lateinit var picker: Spinner

    /** 낮/밤 색상. 어느 쪽이든 본문 대비 4.5:1 이상 */
    private class Palette(
        val bg: Int, val card: Int, val fg: Int, val sub: Int,
        val accent: Int, val danger: Int, val ok: Int, val lane: Int, val other: Int,
    )
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val c: Palette by lazy {
        if (night) Palette(
            bg = Color.parseColor("#0F1216"), card = Color.parseColor("#1A1F26"), fg = Color.parseColor("#F2F4F7"),
            sub = Color.parseColor("#A6AEB8"), accent = Color.parseColor("#3D8BFF"), danger = Color.parseColor("#FF5A5A"),
            ok = Color.parseColor("#3DD68C"), lane = Color.parseColor("#3A4048"), other = Color.parseColor("#8A929C"),
        ) else Palette(
            bg = Color.parseColor("#F4F6F9"), card = Color.parseColor("#FFFFFF"), fg = Color.parseColor("#111418"),
            sub = Color.parseColor("#4A5361"), accent = Color.parseColor("#1F6FEB"), danger = Color.parseColor("#D92D20"),
            ok = Color.parseColor("#13854E"), lane = Color.parseColor("#C3CAD3"), other = Color.parseColor("#8A929C"),
        )
    }

    private val guideOrder = listOf(Step.HAZARD, Step.TRUNK, Step.EVACUATE, Step.CALL, Step.PHOTOS)

    // ---------------- 서비스 연결 ----------------
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as GuardianService.LocalBinder).service
            service = s            // 먼저 연결해 두고
            s.listener = this@MainActivity   // 그다음 현재 상태를 받아 그림
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 차량 화면은 매니페스트대로 가로 고정(Pleos 메인 화면에 뜨려면 필수). 휴대폰(테스트용)에서만 세로 회전 허용
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        voice = VoiceInput(this) { text -> service?.onVoice(text, "🎤 \"$text\"") }
        voice.onListeningChanged = { on -> runOnUiThread { listeningView.visibility = if (on) View.VISIBLE else View.GONE } }
        voice.onGaveUp = { runOnUiThread { toast("음성 인식을 쓸 수 없어요. 화면 버튼으로 진행해 주세요.") } }
        buildUi()
        savedInstanceState?.getInt("picker")?.let { picker.setSelection(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("picker", picker.selectedItemPosition)
    }

    override fun onStart() {
        super.onStart()
        bindService(GuardianService.bindIntent(this), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        // 화면이 가려져도 서비스는 계속 지켜봄. 음성 인식만 멈춤(화면이 보일 때만 마이크 사용)
        voice.setContinuous(false)
        service?.listener = null
        runCatching { unbindService(connection) }
        service = null
        super.onStop()
    }

    override fun onDestroy() { voice.release(); super.onDestroy() }

    // ---------------- 권한 ----------------
    private val wantedPermissions = buildList {
        add(android.Manifest.permission.RECORD_AUDIO)
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        addAll(listOf("pleos.car.permission.CAR_DRIVE", "pleos.car.permission.CAR_LIGHTS", "pleos.car.permission.CAR_DOORS",
            "pleos.car.permission.CAR_SAFETY", "android.car.permission.CAR_SPEED", "android.car.permission.CAR_EXTERIOR_LIGHTS"))
    }.toTypedArray()

    private var afterPermission: (() -> Unit)? = null
    private var askedPermissions = false

    /** 필요한 권한이 없으면 한 번 묻고, 답이 오면(허용·거부 상관없이) [then] 실행 */
    private fun withPermissions(then: () -> Unit) {
        val missing = wantedPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty() || askedPermissions) { then(); return }
        askedPermissions = true
        afterPermission = then
        requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 권한이 새로 생겼으면 차량 연결을 다시 시작
        service?.vehicle?.let { it.stop(); it.start() }
        afterPermission?.invoke(); afterPermission = null
        onSourceChanged()
    }

    // ---------------- 화면 만들기 ----------------
    /** 화면 폭에 맞춘 배율: 휴대폰 1배, Pleos 차량 화면(2560px 폭)은 약 2배 */
    private val k: Float get() = (resources.configuration.screenWidthDp / 1100f).coerceIn(1f, 2f)
    private fun px(v: Int) = (v * k).toInt()

    private fun buildUi() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        window.statusBarColor = c.bg
        val root = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(c.bg); setPadding(px(32), px(32), px(32), px(32))
        }
        radar = RadarView(this).apply { setColors(lane = c.lane, me = c.accent, other = c.other, danger = c.danger, text = c.sub) }
        root.addView(radar, if (portrait) LinearLayout.LayoutParams(-1, 0, 0.30f) else LinearLayout.LayoutParams(0, -1, 0.34f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(if (portrait) px(8) else px(40), px(8), px(8), px(8)) }
        fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size * k; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        badge = tv(16f, c.ok, true).also { it.text = "지켜보는 중" }
        sourceChip = tv(13f, c.sub).apply {
            background = GradientDrawable().apply { cornerRadius = 40f * k; setColor(c.card); if (!night) setStroke(2, c.lane) }
            setPadding(px(24), px(8), px(24), px(8))
        }
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusRow.addView(badge, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(sourceChip)

        title = tv(30f, c.fg, true).also { it.text = "주변이 안정적이에요" }
        body = tv(18f, c.sub).also { it.text = "시나리오를 고르고 시연 시작, 또는 실제 주행을 눌러 주세요" }
        steps = tv(18f, c.sub).apply {
            visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 24f * k; setColor(c.card); if (!night) setStroke(2, c.lane) }
            setPadding(px(28), px(20), px(28), px(20)); setLineSpacing(8f * k, 1f)
        }
        driverVoice = tv(16f, c.accent)
        listeningView = tv(15f, c.ok, true).apply { text = "🎤 듣고 있어요 · 말씀해 주세요"; visibility = View.GONE }

        picker = Spinner(this).apply {
            // 선택된 항목 글자는 화면 색에 맞게. 펼친 목록은 기본 스타일 그대로.
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Scenarios.all.keys.toList()) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also { (it as? TextView)?.apply { setTextColor(c.fg); textSize = 16f * k } }
            }
        }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; maxLines = 1; textSize = 15f * k; minHeight = px(48); setOnClickListener { onClick() }
        }
        val demo = btn("시연 시작") {
            if (picker.selectedItem as String in Scenarios.liveVehicle) withPermissions { startDemo() } else withNotificationPermission { startDemo() }
        }
        val real = btn("실제 주행") { withPermissions { service?.let { it.startSource(PleosSource(this, it.vehicle)) } } }
        val mic = btn("🎤 말하기") { withPermissions { listenOnce() } }
        val end = btn("주행 종료 · 리포트") { service?.finishTrip() }
        val controls = LinearLayout(this).apply { orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (portrait) {
            controls.addView(picker, LinearLayout.LayoutParams(-1, -2))
            for (pair in listOf(demo to real, mic to end)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(pair.first, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(pair.second, LinearLayout.LayoutParams(0, -2, 1f))
                controls.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        } else {
            controls.addView(picker, LinearLayout.LayoutParams(0, -2, 1f))
            listOf(demo, real, mic, end).forEach { controls.addView(it) }
        }

        // 사고 대응용 큰 버튼: 음성 인식이 안 될 때도 진행 가능 (사고 대응 중에만 보임). 세로 화면은 2×2
        accidentButtons = LinearLayout(this).apply { orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; visibility = View.GONE }
        var row = accidentButtons
        listOf("괜찮아" to "괜찮아", "다음" to "했어", "도와줘" to "도와줘", "사고 아님" to "사고 아니야").forEachIndexed { i, (label, said) ->
            if (portrait && i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                accidentButtons.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = px(12) })
            }
            row.addView(Button(this).apply {
                text = label; textSize = 20f * k; isAllCaps = false; maxLines = 1
                if (label == "도와줘") setTextColor(c.danger)
                setOnClickListener { service?.onVoice(said, "버튼: $label") }
            }, LinearLayout.LayoutParams(0, px(140), 1f).apply { marginEnd = px(12) })
        }

        listOf(statusRow, title, body, steps, accidentButtons, driverVoice, listeningView).forEach {
            right.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
        }
        right.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(controls)
        root.addView(right, if (portrait) LinearLayout.LayoutParams(-1, 0, 0.70f) else LinearLayout.LayoutParams(0, -1, 0.66f))
        setContentView(root)
    }

    private fun withNotificationPermission(then: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) withPermissions(then)
        else then()
    }

    private fun startDemo() {
        val s = service ?: return
        val name = picker.selectedItem as String
        val live = name in Scenarios.liveVehicle
        // 리허설 때 켜 둔 비상등·트렁크가 남아 있으면 '켜는 장면'이 안 나오므로 먼저 알려줌
        if (live && (s.vehicle.hazard || s.vehicle.trunk))
            toast("비상등·트렁크가 켜져 있어요. 먼저 끄고 시작하면 '켜는 장면'을 보여줄 수 있어요.")
        if (live && s.vehicle.connectedItems.isEmpty())
            toast("차량 데이터가 아직 안 들어와요. Pleos Connect 에뮬레이터에서 실행해 주세요.")
        s.startSource(DemoSource(name, live = if (live) s.vehicle else null))
    }

    private fun listenOnce() {
        when {
            !voice.available -> toast("이 기기에는 음성 인식이 없어요. 화면 버튼으로 진행해 주세요.")
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
            else -> { voice.paused = false; voice.listenOnce() }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---------------- 서비스 → 화면 ----------------
    override fun onMessage(text: String) = toast(text)
    override fun onDriverText(text: String) { driverVoice.text = text }
    override fun onSpeaking(speaking: Boolean) { voice.paused = speaking }

    /** 오른쪽 위 작은 표시: 지금 데이터가 어디서 오는지, 차량에서 어떤 값이 들어오는지 */
    override fun onSourceChanged() {
        val s = service ?: return
        val src = s.source
        val items = s.vehicle.connectedItems
        val usesVehicle = src is PleosSource || (src as? DemoSource)?.usesLive == true
        sourceChip.text = when {
            src == null -> if (items.isEmpty()) "대기 중" else "● 차량 연결됨"
            usesVehicle && items.isNotEmpty() -> "● ${src.label} · ${items.joinToString("·")}"
            usesVehicle -> "${src.label} · 차량 연결 기다리는 중"
            else -> src.label
        } + if (src != null) " · 백그라운드 감지 중" else ""
        sourceChip.setTextColor(if (items.isNotEmpty() && (usesVehicle || src == null)) c.ok else c.sub)
    }

    override fun onScreen(screen: GuardianService.Screen) {
        when (screen) {
            is GuardianService.Screen.Idle -> {}
            is GuardianService.Screen.Live -> render(screen.ui)
            is GuardianService.Screen.Report -> showReport(screen.report)
            is GuardianService.Screen.Trip -> showTrip(screen.summary)
        }
    }

    private fun render(s: GuardianEngine.UiState) {
        radar.adasAvailable = s.adasAvailable
        radar.vehicles = service?.lastVehicles ?: emptyList()
        radar.alert = s.alertDirection
        radar.hazard = if (s.mode == GuardianEngine.Mode.DRIVING) s.hazard else null
        val accident = s.mode == GuardianEngine.Mode.ACCIDENT
        accidentButtons.visibility = if (accident) View.VISIBLE else View.GONE
        voice.setContinuous(accident)

        if (accident) {
            val step = s.accidentStep
            badge.text = if (step == Step.EMERGENCY) "비상 연락" else "사고 대응 중"
            badge.setTextColor(c.danger)
            title.text = when (step) {
                Step.ASK_OK -> "괜찮으세요?"
                Step.HAZARD -> "비상등을 켜 주세요"
                Step.TRUNK -> "트렁크를 열어 주세요"
                Step.EVACUATE -> "안전한 곳으로 대피하세요"
                Step.CALL -> "112·119에 신고하세요"
                Step.PHOTOS -> "안전할 때 사진을 남기세요"
                Step.EMERGENCY -> "위치를 보냈어요"
                else -> ""
            }
            body.text = s.lastSpeech ?: ""
            steps.visibility = View.VISIBLE
            steps.text = stepList(step, s.accidentDone)
            return
        }
        steps.visibility = View.GONE
        val alertDir = s.alertDirection
        if (alertDir != null) {
            badge.text = "주의"; badge.setTextColor(c.danger)
            title.text = "${alertDir.korean} 차를 조심하세요"
        } else if (s.hazard != null) {
            val hz = s.hazard!!
            val inc = hz.incident
            val km = "%.1fkm".format(hz.distM / 1000)
            badge.text = "앞쪽 ${inc.eventType} · $km"; badge.setTextColor(c.danger)
            title.text = when {
                inc.fullyBlocked -> "전면 통제 · $km 앞"
                inc.lanesBlocked.isEmpty() -> "${inc.eventType} · $km 앞"
                else -> "${inc.lanesBlocked.joinToString(",")}차로 막힘 · $km 앞"
            }
        } else {
            val gap = s.flowGapKph
            val slow = gap != null && gap >= 20
            badge.text = if (slow) "흐름보다 ${gap}km/h 느림 · 도로 매너 ${s.score}점" else "지켜보는 중 · 도로 매너 ${s.score}점"
            badge.setTextColor(if (slow) c.danger else c.ok)
            title.text = s.lane?.let { "${it}차선 주행 중" } ?: if (slow) "뒤로 정체가 생길 수 있어요" else "주변이 안정적이에요"
        }
        body.text = s.lastSpeech ?: "위험한 순간이 오면 미리 알려드릴게요"
    }

    private fun showReport(report: AccidentReport) {
        voice.setContinuous(false)
        accidentButtons.visibility = View.GONE
        badge.text = "사고 정리 리포트"; badge.setTextColor(c.accent)
        title.text = report.roadName ?: "사고 기록"
        body.text = buildString {
            report.timeline.forEach { appendLine("${if (it.offsetSec == 0) " 0" else it.offsetSec}초   ${it.text}") }
            if (report.guideCompleted.isNotEmpty()) append("완료한 조치: ${report.guideCompleted.joinToString(" → ")}")
        }
        steps.visibility = View.VISIBLE
        steps.setTextColor(c.sub)
        steps.text = "※ 보험 접수 때 참고용 기록이에요. 공식 증거는 블랙박스 영상을 함께 쓰세요."
    }

    private fun showTrip(summary: String) {
        voice.setContinuous(false)
        badge.text = "주행 리포트"; badge.setTextColor(c.accent)
        title.text = "오늘의 도로 매너"
        body.text = summary
        steps.visibility = View.GONE
        accidentButtons.visibility = View.GONE
    }

    /** 진행 단계 목록: 완료 ✓(초록) / 지금 ▶(굵게) / 남은 ○(회색) */
    private fun stepList(current: Step?, done: List<Step>): CharSequence {
        val sb = SpannableStringBuilder()
        val doneCount = guideOrder.count { it in done }
        val header = if (current == Step.ASK_OK) "상태 확인 중" else "진행 ${doneCount}/${guideOrder.size}"
        sb.append(header, ForegroundColorSpan(c.sub), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (st in guideOrder) {
            sb.append("\n")
            val (mark, color, bold) = when {
                st in done -> Triple("✓  ", c.ok, false)
                st == current -> Triple("▶  ", c.fg, true)
                else -> Triple("○  ", c.sub, false)
            }
            val startIdx = sb.length
            sb.append(mark + st.title)
            sb.setSpan(ForegroundColorSpan(color), startIdx, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), startIdx, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
