package ru.kuxnik.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Угол стрелки (−50..+50°) из RMS: шкала −36..0 дБ. */
fun needleAngle(rms: Float): Float {
    if (rms <= 0.0001f) return -50f
    val db = 20f * log10(rms)
    return (-50f + (db + 36f) / 36f * 100f).coerceIn(-50f, 50f)
}

/** Маленький стрелочный индикатор. [angle] читается при отрисовке. */
@Composable
fun VuMeter(angle: () -> Float, width: Dp, height: Dp, bg: Color, tick: Color, red: Color, needle: Color) {
    Canvas(Modifier.size(width, height)) {
        drawRoundRect(bg, cornerRadius = CornerRadius(size.height * 0.18f))
        val cx = size.width / 2f
        val cy = size.height * 0.93f
        val r = size.height * 0.62f
        for (i in 0..10) {
            val deg = -50f + i * 10f
            val rad = deg * PI.toFloat() / 180f
            val r2 = r * (if (i % 5 == 0) 1.3f else 1.18f)
            drawLine(
                if (i >= 8) red else tick,
                Offset(cx + r * sin(rad), cy - r * cos(rad)),
                Offset(cx + r2 * sin(rad), cy - r2 * cos(rad)),
                strokeWidth = size.height * (if (i >= 8) 0.04f else 0.028f),
                cap = StrokeCap.Round,
            )
        }
        val a = angle() * PI.toFloat() / 180f
        val len = r * 1.12f
        drawLine(needle, Offset(cx, cy), Offset(cx + len * sin(a), cy - len * cos(a)), strokeWidth = size.height * 0.04f, cap = StrokeCap.Round)
    }
}

/**
 * Эквалайзер: столбики по полосам спектра (басы слева, верха справа).
 * [values] (0..1) читаются только при отрисовке; [frame] — счётчик кадров для перерисовки.
 */
@Composable
fun EqBars(values: FloatArray, peaks: FloatArray, frame: () -> Int, height: Dp, bar: Color, barAlt: Color, peak: Color, base: Color) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
        frame()
        val n = values.size
        val gap = size.width / n * 0.35f
        val w = (size.width - gap * (n - 1)) / n
        val h = size.height - 4f
        drawRect(base, Offset(0f, size.height - 2f), Size(size.width, 2f))
        for (i in 0 until n) {
            val x = i * (w + gap)
            val bh = (values[i].coerceIn(0.03f, 1f)) * (h - 6f)
            drawRoundRect(
                if (i % 3 == 0) barAlt else bar,
                Offset(x, h - bh), Size(w, bh),
                cornerRadius = CornerRadius(w * 0.25f, w * 0.25f),
            )
            val py = h - peaks[i].coerceIn(0.03f, 1f) * (h - 6f) - 5f
            drawRect(peak, Offset(x, py), Size(w, 3f))
        }
    }
}
