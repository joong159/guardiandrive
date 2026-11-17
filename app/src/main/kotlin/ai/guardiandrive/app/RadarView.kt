package ai.guardiandrive.app

import ai.guardiandrive.core.Direction
import ai.guardiandrive.core.LaneHazardWatch
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
    /** 앞쪽 사고가 있으면 위쪽에 도로 단면(1차로 = 중앙선 쪽)과 막힌 차로를 그림 */
    var hazard: LaneHazardWatch.Ahead? = null; set(v) { field = v; invalidate() }

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
        val lineTop = if (hazard != null) h * 0.25f else 0f  // 위쪽 도로 단면과 겹치지 않게
        c.drawLine(laneW, lineTop, laneW, h, lane)
        c.drawLine(laneW * 2, lineTop, laneW * 2, h, lane)

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
        val hz = hazard
        if (hz != null) drawHazard(c, hz, w, h)
        else if (!adasAvailable) c.drawText("주변 차 정보 없음", w / 2, h * 0.2f, text)
    }

    private val laneBox = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
    private val boxText = Paint().apply { textSize = 30f; textAlign = Paint.Align.CENTER; isAntiAlias = true; isFakeBoldText = true }

    private fun drawHazard(c: Canvas, hz: LaneHazardWatch.Ahead, w: Float, h: Float) {
        val inc = hz.incident
        val n = if (inc.fullyBlocked || inc.lanesBlocked.isEmpty()) 3 else maxOf(3, inc.lanesBlocked.max())
        val top = h * 0.06f; val boxH = h * 0.1f; val pad = w * 0.06f
        val boxW = (w - pad * 2) / n
        val dist = if (hz.distM >= 1000) "%.1fkm 앞".format(hz.distM / 1000) else "${(hz.distM / 10).toInt() * 10}m 앞"
        text.textSize = 28f
        c.drawText("$dist ${inc.eventType}", w / 2, top - 8f, text)
        for (i in 1..n) {
            val blocked = inc.fullyBlocked || i in inc.lanesBlocked
            val r = RectF(pad + (i - 1) * boxW + 4f, top + 8f, pad + i * boxW - 4f, top + 8f + boxH)
            laneBox.style = if (blocked) Paint.Style.FILL else Paint.Style.STROKE
            laneBox.color = if (blocked) danger.color else lane.color
            c.drawRoundRect(r, 8f, 8f, laneBox)
            boxText.color = if (blocked) Color.WHITE else text.color
            c.drawText(if (blocked) "✕" else "${i}", r.centerX(), r.centerY() + 10f, boxText)
        }
        text.textSize = 24f
        c.drawText("1차로 = 중앙선 쪽", w / 2, top + boxH + 44f, text)
        text.textSize = 26f
    }
}
