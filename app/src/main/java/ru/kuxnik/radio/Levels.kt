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
import kotlin.math.sqrt

/**
 * Громкость того, что сейчас звучит (левый/правый канал, RMS 0..1).
 * Пишет аудиопоток плеера, читает экран — в одном процессе, без разрешений на микрофон.
 */
object Levels {
    @Volatile var left = 0f
    @Volatile var right = 0f
    @Volatile var updatedAt = 0L

    /** Свежие ли данные (звук реально идёт). */
    fun live() = SystemClock.uptimeMillis() - updatedAt < 400
}

/** Считает RMS по каналам из PCM-буферов, которые проходят через плеер. */
@OptIn(UnstableApi::class)
class LevelSink(private val isActive: () -> Boolean) : TeeAudioProcessor.AudioBufferSink {
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!isActive()) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        var sumL = 0.0
        var sumR = 0.0
        var n = 0
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frame = 2 * channels
                // Каждый 4-й кадр — точности хватает, нагрузка минимальная.
                val step = frame * 4
                var pos = b.position()
                while (pos + frame <= b.limit()) {
                    val l = b.getShort(pos) / 32768.0
                    val r = if (channels > 1) b.getShort(pos + 2) / 32768.0 else l
                    sumL += l * l
                    sumR += r * r
                    n++
                    pos += step
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val frame = 4 * channels
                val step = frame * 4
                var pos = b.position()
                while (pos + frame <= b.limit()) {
                    val l = b.getFloat(pos).toDouble()
                    val r = if (channels > 1) b.getFloat(pos + 4).toDouble() else l
                    sumL += l * l
                    sumR += r * r
                    n++
                    pos += step
                }
            }
            else -> return
        }
        if (n == 0) return
        Levels.left = sqrt(sumL / n).toFloat()
        Levels.right = sqrt(sumR / n).toFloat()
        Levels.updatedAt = SystemClock.uptimeMillis()
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
