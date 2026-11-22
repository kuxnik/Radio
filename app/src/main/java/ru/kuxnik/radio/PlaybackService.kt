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
import androidx.media3.common.ForwardingPlayer
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
 *
 * Пребуферизация: кроме основного плеера [active] есть запасной [standby].
 * Он заранее подключает СЛЕДУЮЩУЮ станцию списка и играет её без звука,
 * держа тот же запас буфера. По «Вперёд» плееры меняются местами — звук
 * переключается мгновенно, а запасной начинает готовить следующую станцию.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var active: ExoPlayer
    private lateinit var standby: ExoPlayer
    private var bufferSec = 0
    private var prebuffer = true
    private val handler = Handler(Looper.getMainLooper())
    private val retries = HashMap<ExoPlayer, Int>()
    private val sinks = HashMap<ExoPlayer, LevelSink>()

    /** Индикаторы слушают звучащий плеер. */
    private fun markActive() {
        Levels.active = sinks[active]
    }

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val sleepRunnable = Runnable { active.pause() }

    private fun listenerFor(p: ExoPlayer) = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // Пропала связь (тоннель, нет сети) — переподключаемся сами.
            val n = (retries[p] ?: 0) + 1
            retries[p] = n
            if (n > 30) return
            handler.postDelayed({
                if (p.playerError != null && (p === active && p.playWhenReady || p === standby)) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    p.play()
                }
            }, minOf(2_000L * n, 15_000L))
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) retries[p] = 0
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (p === active && events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                )
            ) handler.post { syncStandby() }
        }
    }

    /**
     * Плеер с заданным запасом: воспроизведение стартует, когда в буфере
     * накопится [sec] секунд звука. Поток приходит в реальном времени,
     * поэтому этот запас дальше так и держится — им и проезжаем провалы связи.
     */
    private fun buildPlayer(sec: Int, main: Boolean): ExoPlayer {
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

        // Отвод звука для стрелок и эквалайзера.
        val sink = LevelSink()
        val p = ExoPlayer.Builder(this, LevelRenderersFactory(this, sink))
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(loadControl)
            // Аудиофокус держит только основной плеер, иначе они будут глушить друг друга.
            .setAudioAttributes(audioAttrs, main)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        sink.player = p
        sinks[p] = sink
        p.repeatMode = Player.REPEAT_MODE_ALL
        if (!main) p.volume = 0f
        p.addListener(listenerFor(p))
        return p
    }

    /** Обёртка для сессии: перехватывает «Вперёд», чтобы переключиться на готовую станцию. */
    private fun wrap(p: ExoPlayer): Player = object : ForwardingPlayer(p) {
        override fun seekToNext() {
            if (!swapForward()) super.seekToNext()
        }

        override fun seekToNextMediaItem() {
            if (!swapForward()) super.seekToNextMediaItem()
        }

        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            // Выбрали в списке ту самую станцию, что уже готова, — переключаемся мгновенно.
            if (standbyHolds(mediaItems, startIndex)) swapToStandby()
            else super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bufferSec = savedBuffer(this)
        prebuffer = savedPrebuffer(this)
        active = buildPlayer(bufferSec, main = true)
        standby = buildPlayer(bufferSec, main = false)
        markActive()

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, wrap(active))
            .setSessionActivity(open)
            .setCallback(Callback())
            .build()
    }

    // ---------- Пребуферизация ----------

    private fun ids(p: Player) = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }

    private fun standbyReady(index: Int) =
        prebuffer && standby.mediaItemCount > 0 &&
            standby.currentMediaItemIndex == index &&
            standby.playbackState == Player.STATE_READY &&
            ids(standby) == ids(active)

    private fun standbyHolds(items: List<MediaItem>, index: Int): Boolean {
        if (!prebuffer || standby.playbackState != Player.STATE_READY) return false
        if (index != standby.currentMediaItemIndex) return false
        return items.map { it.mediaId } == ids(standby)
    }

    private fun swapForward(): Boolean {
        val n = active.mediaItemCount
        if (n < 2) return false
        val next = (active.currentMediaItemIndex + 1) % n
        if (!standbyReady(next)) return false
        swapToStandby()
        return true
    }

    private fun swapToStandby() {
        val old = active
        val fresh = standby
        old.setAudioAttributes(audioAttrs, false)
        old.volume = 0f
        fresh.setAudioAttributes(audioAttrs, true)
        fresh.volume = 1f
        fresh.playWhenReady = true
        active = fresh
        standby = old
        markActive()
        session?.player = wrap(fresh)
        syncStandby()
    }

    /** Держим запасной плеер на станции, следующей за текущей. */
    private fun syncStandby() {
        val n = active.mediaItemCount
        if (!prebuffer || n < 2 || !active.playWhenReady) {
            if (standby.mediaItemCount > 0) {
                standby.stop()
                standby.clearMediaItems()
            }
            return
        }
        val next = (active.currentMediaItemIndex + 1) % n
        if (ids(standby) != ids(active)) {
            val items = (0 until n).map { active.getMediaItemAt(it) }
            standby.setMediaItems(items, next, C.TIME_UNSET)
            standby.prepare()
        } else if (standby.currentMediaItemIndex != next) {
            standby.seekTo(next, C.TIME_UNSET)
        }
        if (standby.playbackState == Player.STATE_IDLE) standby.prepare()
        standby.volume = 0f
        standby.playWhenReady = true
    }

    // ---------- Настройки ----------

    /** Новый запас буфера: пересоздаём плееры и продолжаем ту же станцию. */
    private fun applyBuffer(sec: Int) {
        if (sec == bufferSec) return
        bufferSec = sec
        val old = active
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val wasPlaying = old.playWhenReady

        handler.removeCallbacksAndMessages(null)
        val fresh = buildPlayer(sec, main = true)
        val freshStandby = buildPlayer(sec, main = false)
        session?.player = wrap(fresh)
        old.release()
        standby.release()
        retries.clear()
        sinks.remove(old)
        sinks.remove(standby)
        active = fresh
        standby = freshStandby
        markActive()

        if (items.isNotEmpty()) {
            fresh.setMediaItems(items, index.coerceIn(0, items.lastIndex), C.TIME_UNSET)
            fresh.prepare()
            fresh.playWhenReady = wasPlaying
        }
    }

    private fun applyPrebuffer(on: Boolean) {
        prebuffer = on
        syncStandby()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!active.playWhenReady || active.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        Levels.active = null
        active.release()
        standby.release()
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
                .add(SessionCommand(CMD_PREBUFFER, Bundle.EMPTY))
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
                CMD_PREBUFFER -> applyPrebuffer(args.getBoolean(ARG_ON, true))
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
        const val CMD_PREBUFFER = "ru.kuxnik.radio.PREBUFFER"
        const val ARG_MINUTES = "minutes"
        const val ARG_SECONDS = "seconds"
        const val ARG_ON = "on"

        private fun prefs(c: Context) = c.getSharedPreferences("radio", Context.MODE_PRIVATE)
        fun savedBuffer(c: Context) = prefs(c).getInt("buffer", 0)
        fun savedPrebuffer(c: Context) = prefs(c).getBoolean("prebuffer", true)
    }
}
