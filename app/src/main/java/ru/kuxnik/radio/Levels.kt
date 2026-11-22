package ru.kuxnik.radio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
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

/** Какой «отвод звука» сейчас звучит (у пребуферизации плееров два). */
object Levels {
    const val BANDS = 24
    @Volatile var active: LevelSink? = null
}

/** Снимок звука: громкость каналов и спектр в момент [mediaMs] от начала потока. */
class LevelFrame(val mediaMs: Long, val left: Float, val right: Float, val bands: FloatArray)

/**
 * Считает громкость (RMS) и спектр из PCM, который проходит через плеер.
 * Звук сюда попадает раньше, чем его слышно (буфер Android, Bluetooth), поэтому
 * каждый снимок помечен временем потока, а экран берёт снимок, соответствующий
 * позиции плеера, — так стрелки и столбики идут точно с голосом.
 */
@OptIn(UnstableApi::class)
class LevelSink : TeeAudioProcessor.AudioBufferSink {
    /** Плеер, которому принадлежит этот отвод (ставит сервис). Читается только с главного потока. */
    @Volatile var player: Player? = null

    /** Меняется при каждом сбросе (новая станция, переподключение). */
    @Volatile var generation = 0
        private set
    @Volatile private var framesSinceFlush = 0L

    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    @Volatile private var sampleRate = 44100

    // Кольцо моно-отсчётов для спектра.
    private val n = 1024
    private val ring = FloatArray(n)
    private var ringPos = 0
    private var filled = 0
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }

    // Накопление громкости между снимками.
    private var sumL = 0.0
    private var sumR = 0.0
    private var cnt = 0
    private var lastFrameAt = 0L

    // История снимков (~6 с при шаге 30 мс).
    private val historySize = 200
    private val history = arrayOfNulls<LevelFrame>(historySize)
    @Volatile private var historyPos = 0

    /** Сколько звука (мс) прошло через отвод с последнего сброса. */
    fun processedMs(): Long = framesSinceFlush * 1000 / sampleRate.coerceAtLeast(1)

    /** Последний снимок не позже [mediaMs]; null — если такого нет или он устарел. */
    fun at(mediaMs: Long): LevelFrame? {
        val end = historyPos
        for (k in 1..historySize) {
            val f = history[(end - k + historySize * 2) % historySize] ?: return null
            if (f.mediaMs <= mediaMs) return if (mediaMs - f.mediaMs < 400) f else null
        }
        return null
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        if (sampleRateHz > 0) sampleRate = sampleRateHz
        framesSinceFlush = 0
        filled = 0
        sumL = 0.0; sumR = 0.0; cnt = 0
        lastFrameAt = 0
        history.fill(null)
        historyPos = 0
        generation++
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frame = bytes * channels
        val busy = Levels.active === this
        var pos = b.position()
        while (pos + frame <= b.limit()) {
            if (busy) {
                val l: Double
                val r: Double
                if (bytes == 2) {
                    l = b.getShort(pos) / 32768.0
                    r = if (channels > 1) b.getShort(pos + 2) / 32768.0 else l
                } else {
                    l = b.getFloat(pos).toDouble()
                    r = if (channels > 1) b.getFloat(pos + 4).toDouble() else l
                }
                sumL += l * l
                sumR += r * r
                cnt++
                ring[ringPos] = ((l + r) * 0.5).toFloat()
                ringPos = (ringPos + 1) % n
                if (filled < n) filled++
            }
            framesSinceFlush++
            pos += frame
            // Снимок каждые 30 мс времени потока.
            if (busy) {
                val ms = framesSinceFlush * 1000 / sampleRate
                if (ms - lastFrameAt >= 30 && cnt > 0) {
                    lastFrameAt = ms
                    snapshot(ms)
                }
            }
        }
    }

    private fun snapshot(ms: Long) {
        val left = sqrt(sumL / cnt).toFloat()
        val right = sqrt(sumR / cnt).toFloat()
        sumL = 0.0; sumR = 0.0; cnt = 0
        history[historyPos] = LevelFrame(ms, left, right, spectrum())
        historyPos = (historyPos + 1) % historySize
    }

    /** БПФ на 1024 точки ~30 раз в секунду — копеечная нагрузка. */
    private fun spectrum(): FloatArray {
        val out = FloatArray(Levels.BANDS)
        if (filled < n) return out
        for (i in 0 until n) {
            re[i] = ring[(ringPos + i) % n] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        val fMin = 50.0
        val fMax = minOf(14000.0, sampleRate / 2.0)
        val binHz = sampleRate.toDouble() / n
        for (band in 0 until Levels.BANDS) {
            val f0 = fMin * exp(ln(fMax / fMin) * band / Levels.BANDS)
            val f1 = fMin * exp(ln(fMax / fMin) * (band + 1) / Levels.BANDS)
            val i0 = (f0 / binHz).toInt().coerceIn(1, n / 2 - 1)
            val i1 = (f1 / binHz).toInt().coerceIn(i0 + 1, n / 2)
            var sum = 0.0
            for (i in i0 until i1) sum += sqrt((re[i] * re[i] + im[i] * im[i]).toDouble())
            val mag = sum / (i1 - i0) / (n / 4.0)
            val db = 20 * log10(mag + 1e-9)
            // −60..0 дБ → 0..1, верхам немного добавляем (у музыки там меньше энергии).
            out[band] = (((db + 60) / 60) + band * 0.008).toFloat().coerceIn(0f, 1f)
        }
        return out
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
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until half) {
                    val ar = x[i + k]; val ai = y[i + k]
                    val br = x[i + k + half] * cr - y[i + k + half] * ci
                    val bi = x[i + k + half] * ci + y[i + k + half] * cr
                    x[i + k] = ar + br; y[i + k] = ai + bi
                    x[i + k + half] = ar - br; y[i + k + half] = ai - bi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Сопоставляет звучащий сейчас момент со снимками. Вызывается с главного потока ~30 раз/с.
 * После сброса запоминаем позицию плеера: дальше «что звучит» = позиция − эта точка отсчёта.
 */
class LevelClock {
    private var sink: LevelSink? = null
    private var gen = -1
    private var baseMs = 0L

    fun current(): LevelFrame? {
        val s = Levels.active ?: return null
        val p = s.player ?: return null
        val pos = p.currentPosition
        if (s !== sink || s.generation != gen) {
            sink = s
            gen = s.generation
            val processed = s.processedMs()
            // Обычно попадаем сюда сразу после сброса, пока звук ещё не пошёл.
            // Если позже (экран был выключен) — считаем, что в запасе ~0,3 с.
            baseMs = if (processed < 1500) pos else pos - (processed - 300)
        }
        return s.at(pos - baseMs)
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
