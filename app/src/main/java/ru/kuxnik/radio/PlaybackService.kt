package ru.kuxnik.radio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Фоновое воспроизведение. Через MediaSession работают уведомление,
 * кнопки на руле и на магнитоле (вперёд/назад/пауза).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var bufferSec = 0
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0

    private val sleepRunnable = Runnable { player.pause() }
    private val retryRunnable = Runnable {
        if (player.playWhenReady || player.playerError != null) {
            player.seekToDefaultPosition()
            player.prepare()
            player.play()
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // Пропала связь (тоннель, нет сети) — переподключаемся сами.
            if (retries < 30) {
                retries++
                handler.removeCallbacks(retryRunnable)
                handler.postDelayed(retryRunnable, minOf(2_000L * retries, 15_000L))
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) retries = 0
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retries = 0
            handler.removeCallbacks(retryRunnable)
        }
    }

    /**
     * Плеер с заданным запасом: воспроизведение стартует, когда в буфере
     * накопится [sec] секунд звука. Поток приходит в реальном времени,
     * поэтому этот запас дальше так и держится — им и проезжаем провалы связи.
     */
    private fun buildPlayer(sec: Int): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("RadioMagnitola/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        val mediaSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10))

        val loadControl = if (sec <= 0) {
            DefaultLoadControl()
        } else {
            val ms = sec * 1000
            val keep = maxOf(50_000, ms + 30_000)
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(keep, keep, ms, ms)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
                addListener(listener)
            }
    }

    override fun onCreate() {
        super.onCreate()
        bufferSec = savedBuffer(this)
        player = buildPlayer(bufferSec)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(Callback())
            .build()
    }

    /** Новый запас буфера: пересоздаём плеер и продолжаем ту же станцию. */
    private fun applyBuffer(sec: Int) {
        if (sec == bufferSec) return
        bufferSec = sec
        val old = player
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val wasPlaying = old.playWhenReady

        handler.removeCallbacks(retryRunnable)
        val fresh = buildPlayer(sec)
        session?.player = fresh
        old.removeListener(listener)
        old.release()
        player = fresh

        if (items.isNotEmpty()) {
            fresh.setMediaItems(items, index.coerceIn(0, items.lastIndex), C.TIME_UNSET)
            fresh.prepare()
            fresh.playWhenReady = wasPlaying
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                .add(SessionCommand(CMD_BUFFER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SLEEP -> {
                    handler.removeCallbacks(sleepRunnable)
                    val min = args.getInt(ARG_MINUTES)
                    if (min > 0) handler.postDelayed(sleepRunnable, min * 60_000L)
                }
                CMD_BUFFER -> applyBuffer(args.getInt(ARG_SECONDS))
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // Адрес потока приходит в requestMetadata — восстанавливаем его.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val out = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                if (uri == null) item else item.buildUpon().setUri(uri).build()
            }.toMutableList()
            return Futures.immediateFuture(out)
        }
    }

    companion object {
        const val CMD_SLEEP = "ru.kuxnik.radio.SLEEP"
        const val CMD_BUFFER = "ru.kuxnik.radio.BUFFER"
        const val ARG_MINUTES = "minutes"
        const val ARG_SECONDS = "seconds"

        fun savedBuffer(context: Context) =
            context.getSharedPreferences("radio", Context.MODE_PRIVATE).getInt("buffer", 0)
    }
}
