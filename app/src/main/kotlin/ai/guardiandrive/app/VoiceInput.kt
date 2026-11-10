package ai.guardiandrive.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 운전자 음성 듣기 ("괜찮아", "도와줘", "사고 났어" …).
 * Pleos SpeechToText SDK가 '준비 중'이라 안드로이드 기본 음성 인식을 씁니다.
 *
 *  - 사고 대응 중에는 [continuous] = true 로 계속 들어요 (끝나면 바로 다시 듣기).
 *  - 안내 음성(TTS)이 나오는 동안은 [paused] 로 잠깐 멈춰서 자기 목소리를 듣지 않게 해요.
 *  - 기기에 음성 인식이 없으면 [available] = false → 화면 버튼으로 진행.
 */
class VoiceInput(private val context: Context, private val onText: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    val available: Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var continuous = false
    private var oneShot = false
    private var errorStreak = 0
    /** 음성 인식을 계속 쓸 수 없을 때(언어 미지원 등) 알려줌 → 화면 버튼으로 진행 */
    var onGaveUp: (() -> Unit)? = null
    var paused = false
        set(v) { field = v; if (v) cancelNow() else scheduleListen(300) }
    var onListeningChanged: ((Boolean) -> Unit)? = null

    private val hasMic get() = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 사고 대응 중 계속 듣기 */
    fun setContinuous(on: Boolean) {
        if (continuous == on) return
        continuous = on
        if (on) scheduleListen(300) else if (!oneShot) cancelNow()
    }

    /** 주행 중 마이크 버튼: 한 번만 듣기 */
    fun listenOnce() { oneShot = true; scheduleListen(0) }

    private fun scheduleListen(delayMs: Long) {
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, delayMs)
    }

    private val listenRunnable = Runnable { listen() }
    private val watchdog = Runnable { if (listening) { runCatching { recognizer?.cancel() }; afterSession(300) } }

    private fun listen() {
        if (!available || !hasMic || paused || listening || !(continuous || oneShot)) return
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()?.also {
            it.setRecognitionListener(listener); recognizer = it
        } ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(intent); setListening(true) }.onFailure { Log.w("GuardianVoice", "듣기 시작 실패", it) }
        // 인식 서비스가 응답 없이 멈추는 경우 대비: 15초 뒤 강제로 끊고 다시 시작
        handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, 15_000)
    }

    private fun setListening(v: Boolean) { listening = v; onListeningChanged?.invoke(v) }

    private fun cancelNow() {
        handler.removeCallbacks(listenRunnable)
        if (listening) runCatching { recognizer?.cancel() }
        setListening(false)
    }

    private fun afterSession(retryMs: Long) {
        handler.removeCallbacks(watchdog)
        setListening(false)
        oneShot = false
        if (continuous) scheduleListen(retryMs)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            errorStreak = 0
            afterSession(300)
            if (!text.isNullOrBlank()) onText(text)
        }
        override fun onError(error: Int) {
            // 다시 해도 안 되는 오류(권한·언어 미지원·서비스 끊김)는 바로 포기
            val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == 11 || error == 12 || error == 13
            // 인식기가 꼬인 경우 새로 만들기
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                runCatching { recognizer?.destroy() }; recognizer = null
            }
            // 조용해서 못 알아들은 경우(대답 없음)는 정상 → 계속 듣기. 그 외 오류는 점점 늦게, 5번 연속이면 포기
            val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!silent) errorStreak++
            if (fatal || errorStreak >= 5) {
                Log.w("GuardianVoice", "음성 인식 중단 (오류 $error)")
                continuous = false; afterSession(0); errorStreak = 0; onGaveUp?.invoke(); return
            }
            afterSession(if (silent) 300L else 600L shl errorStreak)
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun release() { cancelNow(); runCatching { recognizer?.destroy() }; recognizer = null }
}
