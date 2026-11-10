package ai.guardiandrive.app

import ai.guardiandrive.core.Direction
import ai.guardiandrive.core.NearbyVehicle
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 위에서 내려다본 도로. 내 차는 파란색, 주변 차는 회색, 위험한 차는 빨간색.
 * ADAS가 없으면 내 차만 보여주고 "주변 차 정보 없음"이라고 표시.
 */
class RadarView(context: Context) : View(context) {
    var vehicles: List<NearbyVehicle> = emptyList(); set(v) { field = v; invalidate() }
    var alert: Direction? = null; set(v) { field = v; invalidate() }
    var adasAvailable = false; set(v) { field = v; invalidate() }

    private val lane = Paint().apply { color = Color.parseColor("#3A4048"); strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(30f, 24f), 0f) }
    private val me = Paint().apply { color = Color.parseColor("#3D8BFF") }
    private val other = Paint().apply { color = Color.parseColor("#8A929C") }
    private val danger = Paint().apply { color = Color.parseColor("#FF5A5A") }
    private val text = Paint().apply { color = Color.parseColor("#8A929C"); textSize = 26f; textAlign = Paint.Align.CENTER; isAntiAlias = true }

    /** 낮/밤 화면 색에 맞추기 */
    fun setColors(lane: Int, me: Int, other: Int, danger: Int, text: Int) {
        this.lane.color = lane; this.me.color = me; this.other.color = other; this.danger.color = danger; this.text.color = text
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val laneW = w / 3
        c.drawLine(laneW, 0f, laneW, h, lane)
        c.drawLine(laneW * 2, 0f, laneW * 2, h, lane)

        val pxPerM = h / 70f          // 앞 45m, 뒤 25m 정도
        val myY = h * 0.65f
        fun car(cx: Float, cy: Float, p: Paint) {
            val cw = laneW * 0.38f; val ch = cw * 1.8f
            c.drawRoundRect(RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2), 10f, 10f, p)
        }
        for (v in vehicles) {
            val cx = w / 2 - (v.lateralM / 3.5f * laneW).toFloat()
            val cy = myY - (v.longitudinalM * pxPerM).toFloat()
            val side = when { v.lateralM > NearbyVehicle.LANE_HALF_M -> Direction.LEFT; v.lateralM < -NearbyVehicle.LANE_HALF_M -> Direction.RIGHT; else -> null }
            val isDanger = alert != null && (side == alert || (side == null && v.longitudinalM in 0.0..25.0))
            car(cx, cy, if (isDanger) danger else other)
        }
        car(w / 2, myY, me)
        if (!adasAvailable) c.drawText("주변 차 정보 없음", w / 2, h * 0.2f, text)
    }
}
