package hu.szfvbus.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils

/** Bitképek a járműjelekhez: kerek vonaljelvény egy forgatható irányjelző fölött. */
class MapIcons(context: Context) {
    private val density = context.resources.displayMetrics.density

    /** A négyzetes irányjelző kép oldala; a jelvény a közepére kerül. */
    private val canvasDp = 50f
    private val badgeRadiusDp = 15f
    private val pointerTipDp = 24f

    fun badgeName(line: String, color: Int) = "badge-$line-${color.toUInt().toString(16)}"

    fun pointerName(color: Int) = "pointer-${color.toUInt().toString(16)}"

    fun badge(line: String, color: Int): Bitmap {
        val r = badgeRadiusDp * density
        val border = 2f * density
        val size = (2 * r + 2 * border).toInt() + 2
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        c.drawCircle(cx, cx, r + border, paint)
        paint.color = color
        c.drawCircle(cx, cx, r, paint)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = when {
                line.length <= 2 -> 14f
                line.length == 3 -> 11.5f
                else -> 9.5f
            } * density
        }
        val baseline = cx - (text.descent() + text.ascent()) / 2
        c.drawText(line, cx, baseline, text)
        return bmp
    }

    /** Felfelé mutató navigációs nyíl halvány udvarral az utas helyzetéhez; a térkép a haladási irányával forgatja. */
    fun navigationArrow(): Bitmap {
        val size = (48 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(48, 0x1E, 0x88, 0xE5)
        c.drawCircle(cx, cx, cx, paint)
        val h = 14f * density
        val w = 11f * density
        val path = Path().apply {
            moveTo(cx, cx - h)
            lineTo(cx + w, cx + h)
            lineTo(cx, cx + h * 0.45f)
            lineTo(cx - w, cx + h)
            close()
        }
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0x1E, 0x88, 0xE5)
        c.drawPath(path, paint)
        return bmp
    }

    /** Felfelé (északra) mutató csepp; a térkép a jármű haladási irányával forgatja. */
    fun pointer(color: Int): Bitmap {
        val size = (canvasDp * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val r = (badgeRadiusDp + 2f) * density
        val tip = pointerTipDp * density
        val dark = ColorUtils.blendARGB(color, Color.BLACK, 0.25f)
        val path = Path().apply {
            moveTo(cx, cx - tip)
            lineTo(cx + r * 0.62f, cx - r * 0.78f)
            lineTo(cx - r * 0.62f, cx - r * 0.78f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        paint.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.color = dark
        c.drawPath(path, paint)
        return bmp
    }
}
