package ai.guardiandrive.core

import kotlin.math.abs
import kotlin.math.sign

/**
 * 차선 변경 감지. ADAS 없이도 동작하도록 3가지 방법을 씁니다.
 *
 *  1) ADAS 차선 번호가 바뀌면 → 그 방향으로 변경 (가장 정확)
 *  2) 깜빡이를 켰다 끄는 동안 그 방향으로 핸들을 살짝 돌렸다면 → 깜빡이 켠 차선 변경
 *  3) 깜빡이 없이 핸들을 한쪽으로 1~3초 돌렸다가 반대로 되돌리면 → 깜빡이 없는 차선 변경
 *     (커브길처럼 한쪽으로 4초 넘게 계속 돌리면 차선 변경이 아님)
 */
class LaneChangeDetector(
    private val minSpeedKph: Double = 50.0,
    private val signalSteerDeg: Double = 1.5,
    private val swerveDeg: Double = 3.0,
    private val returnDeg: Double = 2.0,
) {
    data class Result(val direction: Direction, val signaled: Boolean)

    private var prevLane: Int? = null

    // 깜빡이 추적
    private var signalDir: Direction? = null
    private var signalStartMs = 0L
    private var signalPeakDeg = 0.0
    private var signalCooldownUntil = 0L

    // 깜빡이 없는 핸들 패턴 추적
    private var swerveSign = 0.0
    private var swerveStartMs = 0L
    private var swerveEndMs: Long? = null

    private var lastSignalMs = Long.MIN_VALUE / 2

    fun update(s: Snapshot): Result? {
        if (s.turnSignal != null) lastSignalMs = s.timeMs

        // 1) ADAS 차선 번호
        val lane = s.adas?.laneIndex
        if (lane != null) {
            val prev = prevLane
            prevLane = lane
            if (prev != null && prev != lane) {
                resetSwerve()
                // 차선을 넘기 직전 3초 안에 깜빡이를 켰으면 '깜빡이 켠 변경'
                return Result(if (lane < prev) Direction.LEFT else Direction.RIGHT, s.timeMs - lastSignalMs <= 3_000)
            }
            return null
        }

        // 2) 깜빡이 + 핸들
        val sig = s.turnSignal
        if (sig != null) {
            if (signalDir != sig) { signalDir = sig; signalStartMs = s.timeMs; signalPeakDeg = 0.0 }
            val toward = if (sig == Direction.LEFT) s.steeringDeg else -s.steeringDeg
            signalPeakDeg = maxOf(signalPeakDeg, toward)
            resetSwerve()
            return null
        }
        val endedSignal = signalDir
        if (endedSignal != null) {
            val heldSec = (s.timeMs - signalStartMs) / 1000.0
            signalDir = null
            if (heldSec in 2.0..20.0 && signalPeakDeg >= signalSteerDeg && s.speedKph >= minSpeedKph) {
                signalCooldownUntil = s.timeMs + 4_000 // 되돌리는 핸들을 깜빡이 없는 변경으로 오인하지 않게
                return Result(endedSignal, signaled = true)
            }
        }
        if (s.timeMs < signalCooldownUntil) return null

        // 3) 깜빡이 없는 핸들 패턴
        if (s.speedKph < minSpeedKph) { resetSwerve(); return null }
        val a = s.steeringDeg
        if (swerveSign == 0.0) {
            if (abs(a) >= swerveDeg) { swerveSign = sign(a); swerveStartMs = s.timeMs; swerveEndMs = null }
            return null
        }
        val sameSide = sign(a) == swerveSign && abs(a) >= swerveDeg
        val end = swerveEndMs
        if (end == null) {
            when {
                sameSide && s.timeMs - swerveStartMs > 4_000 -> resetSwerve()          // 커브
                sameSide -> {}
                else -> swerveEndMs = s.timeMs
            }
            // 바로 반대로 되돌림
            if (swerveEndMs != null && sign(a) == -swerveSign && abs(a) >= returnDeg) return finishSwerve(s)
            return null
        }
        if (sign(a) == -swerveSign && abs(a) >= returnDeg) return finishSwerve(s)
        if (s.timeMs - end > 3_000) resetSwerve()
        return null
    }

    private fun finishSwerve(s: Snapshot): Result? {
        val heldSec = ((swerveEndMs ?: s.timeMs) - swerveStartMs) / 1000.0
        val dir = if (swerveSign > 0) Direction.LEFT else Direction.RIGHT
        resetSwerve()
        return if (heldSec in 1.0..3.5) Result(dir, signaled = false) else null
    }

    private fun resetSwerve() { swerveSign = 0.0; swerveEndMs = null }
}
