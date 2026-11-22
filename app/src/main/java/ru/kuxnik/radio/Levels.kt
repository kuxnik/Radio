package ru.kuxnik.radio

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Громкость того, что сейчас звучит (левый/правый канал, RMS 0..1).
 * Пишет аудиопоток плеера, читает экран — в одном процессе, без разрешений на микрофон.
 */
object Levels {
    @Volatile var left = 0f
    @Volatile var right = 0f
    @Volatile var updatedAt = 0L

    /** Полосы спектра 0..1 (басы → верха) для эквалайзера. */
    const val BANDS = 24
    @Volatile var bands = FloatArray(BANDS)

    /** Свежие ли данные (звук реально идёт). */
    fun live() = SystemClock.uptimeMillis() - updatedAt < 400
}

/** Считает RMS по каналам из PCM-буферов, которые проходят через плеер. */
@OptIn(UnstableApi::class)
class LevelSink(private val isActive: () -> Boolean) : TeeAudioProcessor.AudioBufferSink {
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var sampleRate = 44100

    // Кольцо моно-отсчётов для спектра.
    private val n = 1024
    private val ring = FloatArray(n)
    private var ringPos = 0
    private var filled = 0
    private var lastFft = 0L
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        if (sampleRateHz > 0) sampleRate = sampleRateHz
    }

    private fun push(v: Float) {
        ring[ringPos] = v
        ringPos = (ringPos + 1) % n
        if (filled < n) filled++
    }

    /** БПФ на 1024 точки не чаще ~30 раз в секунду — копеечная нагрузка. */
    private fun spectrum() {
        val now = SystemClock.uptimeMillis()
        if (filled < n || now - lastFft < 30) return
        lastFft = now
        for (i in 0 until n) {
            re[i] = ring[(ringPos + i) % n] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        val out = FloatArray(Levels.BANDS)
        val fMin = 50.0
        val fMax = minOf(14000.0, sampleRate / 2.0)
        val binHz = sampleRate.toDouble() / n
        for (b in 0 until Levels.BANDS) {
            val f0 = fMin * exp(ln(fMax / fMin) * b / Levels.BANDS)
            val f1 = fMin * exp(ln(fMax / fMin) * (b + 1) / Levels.BANDS)
            val i0 = (f0 / binHz).toInt().coerceIn(1, n / 2 - 1)
            val i1 = (f1 / binHz).toInt().coerceIn(i0 + 1, n / 2)
            var sum = 0.0
            for (i in i0 until i1) sum += sqrt((re[i] * re[i] + im[i] * im[i]).toDouble())
            val mag = sum / (i1 - i0) / (n / 4.0)
            val db = 20 * log10(mag + 1e-9)
            // −60..0 дБ → 0..1, верхам немного добавляем (у музыки там меньше энергии).
            out[b] = (((db + 60) / 60) + b * 0.008).toFloat().coerceIn(0f, 1f)
        }
        Levels.bands = out
    }

    private fun fft(x: FloatArray, y: FloatArray) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = x[i]; x[i] = x[j]; x[j] = tr
                val ti = y[i]; y[i] = y[j]; y[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = x[i + k]; val ai = y[i + k]
                    val br = x[i + k + len / 2] * cr - y[i + k + len / 2] * ci
                    val bi = x[i + k + len / 2] * ci + y[i + k + len / 2] * cr
                    x[i + k] = ar + br; y[i + k] = ai + bi
                    x[i + k + len / 2] = ar - br; y[i + k + len / 2] = ai - bi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!isActive()) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        var sumL = 0.0
        var sumR = 0.0
        var cnt = 0
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frame = 2 * channels
                var pos = b.position()
                while (pos + frame <= b.limit()) {
                    val l = b.getShort(pos) / 32768.0
                    val r = if (channels > 1) b.getShort(pos + 2) / 32768.0 else l
                    sumL += l * l
                    sumR += r * r
                    cnt++
                    push(((l + r) * 0.5).toFloat())
                    pos += frame
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val frame = 4 * channels
                var pos = b.position()
                while (pos + frame <= b.limit()) {
                    val l = b.getFloat(pos).toDouble()
                    val r = if (channels > 1) b.getFloat(pos + 4).toDouble() else l
                    sumL += l * l
                    sumR += r * r
                    cnt++
                    push(((l + r) * 0.5).toFloat())
                    pos += frame
                }
            }
            else -> return
        }
        if (cnt == 0) return
        Levels.left = sqrt(sumL / cnt).toFloat()
        Levels.right = sqrt(sumR / cnt).toFloat()
        Levels.updatedAt = SystemClock.uptimeMillis()
        spectrum()
    }
}

/** Рендереры плеера с «отводом» звука для индикаторов. */
@OptIn(UnstableApi::class)
class LevelRenderersFactory(context: Context, private val sink: LevelSink) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(TeeAudioProcessor(sink)))
        .build()
}
