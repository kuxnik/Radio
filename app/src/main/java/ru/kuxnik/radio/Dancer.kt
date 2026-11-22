package ru.kuxnik.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/** Полигон: цвет и точки x,y в системе координат 160×200. */
internal class P(color: Long, val pts: FloatArray) {
    val color = Color(color)
}

internal class Animal(
    val name: String,
    val tail: List<P>, val legL: List<P>, val legR: List<P>, val body: List<P>,
    val armL: List<P>, val armR: List<P>, val head: List<P>,
)

val DANCE_NAMES = listOf("покачивание", "прыжки", "твист")
val ANIMAL_NAMES: List<String> get() = ANIMALS.map { it.name }

/** Поза в момент времени: углы (градусы) и сдвиги (единицы 160×200). */
private class Pose {
    var allDy = 0f; var allRot = 0f; var allScaleY = 1f
    var tail = 0f; var legL = 0f; var legR = 0f
    var armL = 0f; var armR = 0f; var head = 0f; var headDy = 0f
}

/** Три танца. [a] — размах от громкости (0.35..1). */
private fun pose(dance: Int, t: Float, a: Float, p: Pose) {
    val tau = (2 * PI).toFloat()
    when (dance) {
        1 -> { // прыжки, такт 0.6 с
            val ph = (t % 0.6f) / 0.6f
            val h = if (ph in 0.15f..0.75f) sin(PI.toFloat() * (ph - 0.15f) / 0.6f) else 0f
            p.allDy = -18f * a * h
            p.allScaleY = if (ph < 0.15f) 1f - 0.08f * a * sin(PI.toFloat() * ph / 0.15f) else 1f
            p.allRot = 0f
            p.armL = 20f - 70f * a * h
            p.armR = -20f + 70f * a * h
            p.headDy = 4f * a * abs(sin(tau * t / 0.6f))
            p.head = 0f
            p.legL = -8f * a * h
            p.legR = -8f * a * h
            p.tail = 14f * a * sin(tau * t / 0.6f)
        }
        2 -> { // твист, полный цикл 1.6 с
            val s = sin(tau * t / 1.6f)
            p.allDy = 0f; p.allScaleY = 1f
            p.allRot = 9f * a * s
            p.armL = 30f * a * s
            p.armR = 30f * a * s
            p.head = -10f * a * s
            p.headDy = 0f
            val st = sin(tau * t / 0.8f)
            p.legL = -6f * a * max(0f, st)
            p.legR = -6f * a * max(0f, -st)
            p.tail = 14f * a * sin(tau * t / 0.8f)
        }
        else -> { // покачивание, такт 0.5 с
            val s = sin(tau * t)
            p.allDy = -7f * a * abs(sin(PI.toFloat() * t / 0.5f))
            p.allScaleY = 1f
            p.allRot = 0f
            p.head = 9f * a * sin(PI.toFloat() * t)
            p.headDy = 0f
            p.armL = -5f - 23f * a * s
            p.armR = 5f + 23f * a * s
            p.tail = 1f + 13f * a * s
            p.legL = -6f * a * max(0f, s)
            p.legR = -6f * a * max(0f, -s)
        }
    }
}

private fun pathOf(p: P): Path = Path().apply {
    moveTo(p.pts[0], p.pts[1])
    var i = 2
    while (i < p.pts.size) {
        lineTo(p.pts[i], p.pts[i + 1])
        i += 2
    }
    close()
}

private class Built(val animal: Animal) {
    val tail = animal.tail.map { pathOf(it) to it.color }
    val legL = animal.legL.map { pathOf(it) to it.color }
    val legR = animal.legR.map { pathOf(it) to it.color }
    val body = animal.body.map { pathOf(it) to it.color }
    val armL = animal.armL.map { pathOf(it) to it.color }
    val armR = animal.armR.map { pathOf(it) to it.color }
    val head = animal.head.map { pathOf(it) to it.color }
}

private fun DrawScope.fill(list: List<Pair<Path, Color>>) {
    for ((path, c) in list) drawPath(path, c)
}

/**
 * Танцующий зверь. Рисуется ~26 плоскими полигонами, без текстур.
 * [time] и [energy] читаются только при отрисовке — пересчитывается лишь картинка.
 */
@Composable
fun Dancer(animal: Int, dance: Int, time: () -> Float, energy: () -> Float, width: Dp, height: Dp) {
    val built = remember(animal) { Built(ANIMALS[animal.mod(ANIMALS.size)]) }
    val pose = remember { Pose() }
    Canvas(Modifier.size(width, height)) {
        val k = minOf(size.width / 160f, size.height / 200f)
        pose(dance, time(), 0.35f + 0.65f * energy(), pose)
        scale(k, k, pivot = Offset.Zero) {
            translate((size.width / k - 160f) / 2f, 0f) {
                rotate(pose.allRot, Offset(80f, 196f)) {
                    scale(1f, pose.allScaleY, Offset(80f, 196f)) {
                        translate(0f, pose.allDy) {
                            rotate(pose.tail, Offset(112f, 158f)) { fill(built.tail) }
                            translate(0f, pose.legL) { fill(built.legL) }
                            translate(0f, pose.legR) { fill(built.legR) }
                            fill(built.body)
                            rotate(pose.armL, Offset(60f, 114f)) { fill(built.armL) }
                            rotate(pose.armR, Offset(100f, 114f)) { fill(built.armR) }
                            translate(0f, pose.headDy) {
                                rotate(pose.head, Offset(80f, 106f)) { fill(built.head) }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
        drawRoundRect(bg, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.18f))
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
