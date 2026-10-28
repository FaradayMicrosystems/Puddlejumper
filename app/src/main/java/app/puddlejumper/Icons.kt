package app.puddlejumper

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import app.puddlejumper.core.ChargerSpeed
import app.puddlejumper.core.ChargerStatus
import app.puddlejumper.core.ChargingStation

/** Small round map icons drawn at runtime, so no image assets are needed. */
object Icons {
    private val cache = HashMap<String, Drawable>()

    fun waypoint(res: Resources, number: Int): Drawable = dot(res, "W$number", 30f, Color.rgb(27, 127, 90), number.toString())

    fun charger(res: Resources, s: ChargingStation): Drawable {
        val color = when (s.status) {
            ChargerStatus.OUT_OF_SERVICE -> Color.rgb(200, 40, 40)
            ChargerStatus.PLANNED -> Color.GRAY
            ChargerStatus.OCCUPIED -> Color.rgb(230, 130, 0)
            else -> when (s.maxSpeed) {
                ChargerSpeed.DC_FAST -> Color.rgb(106, 27, 154)
                ChargerSpeed.LEVEL2 -> Color.rgb(43, 108, 176)
                else -> Color.rgb(100, 150, 200)
            }
        }
        val label = when (s.maxSpeed) {
            ChargerSpeed.DC_FAST -> "F"
            ChargerSpeed.LEVEL2 -> "2"
            ChargerSpeed.LEVEL1 -> "1"
            null -> "?"
        }
        return dot(res, "C$color$label", 22f, color, label)
    }

    /** Rounded white label with dark text, e.g. a weather badge "☁ 12°/5°". */
    fun pill(res: Resources, text: String): Drawable = cache.getOrPut("P$text") {
        val d = res.displayMetrics.density
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * d; isFakeBoldText = true }
        val padH = 7f * d
        val h = (p.descent() - p.ascent() + 6f * d)
        val w = p.measureText(text) + 2 * padH
        val bmp = Bitmap.createBitmap(w.toInt() + 2, h.toInt() + 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val box = RectF(1f, 1f, w + 1f, h + 1f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
        c.drawRoundRect(box, h / 2, h / 2, bg)
        bg.style = Paint.Style.STROKE
        bg.strokeWidth = d
        bg.color = Color.rgb(90, 110, 130)
        c.drawRoundRect(box, h / 2, h / 2, bg)
        p.color = Color.rgb(30, 40, 50)
        c.drawText(text, 1f + padH, 1f + h / 2 - (p.descent() + p.ascent()) / 2, p)
        BitmapDrawable(res, bmp)
    }

    private fun dot(res: Resources, key: String, sizeDp: Float, color: Int, label: String): Drawable =
        cache.getOrPut(key) {
            val px = (sizeDp * res.displayMetrics.density).toInt()
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val r = px / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.WHITE
            c.drawCircle(r, r, r, p)
            p.color = color
            c.drawCircle(r, r, r * 0.82f, p)
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.textSize = px * 0.5f
            p.isFakeBoldText = true
            c.drawText(label, r, r - (p.descent() + p.ascent()) / 2, p)
            BitmapDrawable(res, bmp)
        }
}
