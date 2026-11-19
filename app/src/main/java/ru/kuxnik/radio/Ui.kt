package ru.kuxnik.radio

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// ---------- Цвета (как в макете) ----------
private val Bg = Color(0xFF0E1116)
private val Panel = Color(0xFF151920)
private val Raised = Color(0xFF1E232C)
private val Sel = Color(0xFF2A303B)
private val TextC = Color(0xFFF2F4F7)
private val Sub = Color(0xFFDCE1E8)
private val Muted = Color(0xFF9AA4B2)
private val Amber = Color(0xFFFFB547)
private val AmberBg = Color(0xFF3A2A10)
private val Red = Color(0xFFFF8A80)

// ---------- Масштаб: макет 1024×600 подгоняется под любой экран ----------
private val LocalScale = staticCompositionLocalOf { 1f }

private val Int.u: Dp
    @Composable @ReadOnlyComposable get() = (this * LocalScale.current).dp

private val Int.t: TextUnit
    @Composable @ReadOnlyComposable get() = (this * LocalScale.current / LocalDensity.current.fontScale).sp

// ---------- Иконки ----------
private fun icon(d: String, fill: Boolean = true, w: Float = 2.2f): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            if (fill) addPath(addPathNodes(d), fill = SolidColor(Color.White))
            else addPath(
                addPathNodes(d), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = w,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

private const val STAR_D = "M12,3.5l2.6,5.3 5.9,0.9 -4.3,4.1 1,5.8L12,16.8l-5.2,2.8 1,-5.8 -4.3,-4.1 5.9,-0.9z"
private val IcPlay = icon("M8,5v14l11,-7z")
private val IcPause = icon("M6,5h4v14h-4zM14,5h4v14h-4z")
private val IcPrev = icon("M6,5h2.5v14H6zM20,5v14L9.5,12z")
private val IcNext = icon("M15.5,5H18v14h-2.5zM4,5v14l10.5,-7z")
private val IcStar = icon(STAR_D)
private val IcStarO = icon(STAR_D, fill = false, w = 1.8f)
private val IcSearch = icon("M18,11a7,7 0 1,1 -14,0a7,7 0 1,1 14,0M20,20l-4,-4", fill = false)
private val IcBack = icon("M15,5l-7,7 7,7", fill = false, w = 2.4f)
private val IcTimer = icon("M20,13a8,8 0 1,1 -16,0a8,8 0 1,1 16,0M12,9v4l2.5,2.5M9,2h6", fill = false)
private val IcBars = icon("M5,10v4M9,7v10M13,4v16M17,8v8M21,11v2", fill = false, w = 2.4f)

@Composable
private fun Ic(v: ImageVector, size: Int, color: Color) {
    Image(rememberVectorPainter(v), null, Modifier.size(size.u), colorFilter = ColorFilter.tint(color))
}

@Composable
private fun Txt(
    text: String, size: Int, color: Color = TextC, weight: FontWeight = FontWeight.SemiBold,
    modifier: Modifier = Modifier, maxLines: Int = 1, mono: Boolean = false, spacing: Float = 0f,
) {
    BasicText(
        text, modifier,
        style = TextStyle(
            color = color, fontSize = size.t, fontWeight = weight,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            letterSpacing = spacing.sp,
        ),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** Кнопка: скруглённый блок с нажатием. */
@Composable
private fun Btn(
    w: Int, h: Int = w, radius: Int, bg: Color, onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.width(w.u).height(h.u).clip(RoundedCornerShape(radius.u)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun stationsWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> "станция"
        m10 in 2..4 && m100 !in 12..14 -> "станции"
        else -> "станций"
    }
    return "$n $w"
}

// ---------- Состояние плеера ----------
class PlayerUi {
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var error by mutableStateOf(false)
    var station by mutableStateOf<Station?>(null)
    var track by mutableStateOf<String?>(null)
    var format by mutableStateOf("")
    var bufferMs by mutableLongStateOf(0L)

    fun sync(p: Player) {
        playing = p.playWhenReady
        buffering = p.playbackState == Player.STATE_BUFFERING
        error = p.playerError != null
        val st = Station.fromItem(p.currentMediaItem)
        station = st
        track = p.mediaMetadata.title?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() && it != st?.name }
        format = audioFormat(p) ?: st?.tech.orEmpty()
    }

    private fun audioFormat(p: Player): String? {
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO || g.length == 0) continue
            val f = g.getTrackFormat(0)
            val codec = when (f.sampleMimeType) {
                MimeTypes.AUDIO_MPEG -> "MP3"
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_OPUS -> "OPUS"
                MimeTypes.AUDIO_VORBIS -> "OGG"
                MimeTypes.AUDIO_FLAC -> "FLAC"
                else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
            }
            val br = if (f.bitrate > 0) f.bitrate / 1000 else station?.bitrate ?: 0
            val hz = if (f.sampleRate > 0) (f.sampleRate / 100 / 10.0).toString().removeSuffix(".0") + " кГц" else null
            return listOfNotNull(codec, br.takeIf { it > 0 }?.let { "$it кбит/с" }, hz).joinToString(" · ")
        }
        return null
    }
}

class CatalogState {
    var query by mutableStateOf("")
    var russia by mutableStateOf(true)
    var tag by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<Station>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var reload by mutableIntStateOf(0)
}

private val GENRES = listOf(
    "Поп" to "pop", "Рок" to "rock", "Ретро" to "retro", "Джаз" to "jazz",
    "Электроника" to "electronic", "Классика" to "classical", "Новости" to "news",
)
private val SLEEP_STEPS = listOf(0, 15, 30, 60, 90)

private class Actions(
    val play: (Station, List<Station>) -> Unit,
    val toggle: () -> Unit,
    val prev: () -> Unit,
    val next: () -> Unit,
)

// ---------- Приложение ----------
@Composable
fun RadioApp(store: Store, controller: MediaController?) {
    val ui = remember { PlayerUi() }
    val catalog = remember { CatalogState() }
    var tab by rememberSaveable { mutableIntStateOf(if (store.favorites.isEmpty()) 1 else 0) }
    var sleep by remember { mutableIntStateOf(0) }

    DisposableEffect(controller) {
        val c = controller
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = ui.sync(player)
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Station.fromItem(mediaItem)?.let { store.addRecent(it) }
            }
        }
        if (c != null) {
            c.addListener(listener)
            ui.sync(c)
        }
        onDispose { c?.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            ui.bufferMs = controller?.totalBufferedDuration ?: 0L
            delay(500)
        }
    }
    LaunchedEffect(catalog.query, catalog.russia, catalog.tag, catalog.reload) {
        delay(500)
        catalog.loading = true
        try {
            catalog.results = RadioBrowser.search(catalog.query, if (catalog.russia) "RU" else null, catalog.tag)
            catalog.error = null
        } catch (e: Exception) {
            catalog.error = "Каталог недоступен. Проверьте интернет."
        }
        catalog.loading = false
    }
    BackHandler(enabled = tab != 0) { tab = 0 }

    val actions = Actions(
        play = { st, list ->
            val c = controller
            if (c != null) {
                val l = if (list.any { it.id == st.id }) list else listOf(st)
                c.setMediaItems(l.map { it.toMediaItem() }, l.indexOfFirst { it.id == st.id }, C.TIME_UNSET)
                c.prepare()
                c.play()
            }
            store.addRecent(st)
            tab = 0
        },
        toggle = {
            val c = controller
            if (c != null) {
                if (c.playWhenReady) {
                    c.pause()
                } else if (c.mediaItemCount > 0) {
                    c.seekToDefaultPosition() // живой эфир, а не старый буфер
                    c.prepare()
                    c.play()
                } else if (store.favorites.isNotEmpty()) {
                    val first = store.favorites.first()
                    c.setMediaItems(store.favorites.map { it.toMediaItem() }, 0, C.TIME_UNSET)
                    c.prepare()
                    c.play()
                    store.addRecent(first)
                }
            }
        },
        prev = { controller?.run { seekToPreviousMediaItem(); play() } },
        next = { controller?.run { seekToNextMediaItem(); play() } },
    )
    val onSleep = {
        val i = SLEEP_STEPS.indexOf(sleep)
        sleep = SLEEP_STEPS[(i + 1) % SLEEP_STEPS.size]
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY),
            Bundle().apply { putInt(PlaybackService.ARG_MINUTES, sleep) },
        )
        Unit
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
        val land = maxWidth > maxHeight
        val scale = if (land) minOf(maxWidth.value / 1024f, maxHeight.value / 600f) else maxWidth.value / 430f
        CompositionLocalProvider(LocalScale provides scale.coerceIn(0.55f, 2.2f)) {
            when (tab) {
                0 -> AirScreen(store, ui, land, actions, sleep, onSleep, onTab = { tab = it })
                1 -> CatalogScreen(store, ui, catalog, land, actions, onBack = { tab = 0 })
                else -> RecentScreen(store, ui, land, actions, onTab = { tab = it })
            }
        }
    }
}

// ---------- Общие части ----------
@Composable
private fun TopBar(tab: Int, onTab: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.u),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(14.u)).background(Panel).padding(5.u),
            horizontalArrangement = Arrangement.spacedBy(6.u),
        ) {
            listOf("Эфир", "Каталог", "Недавние").forEachIndexed { i, label ->
                Box(
                    Modifier.height(42.u).clip(RoundedCornerShape(10.u))
                        .background(if (i == tab) Sel else Color.Transparent)
                        .clickable { onTab(i) }.padding(horizontal = 20.u),
                    contentAlignment = Alignment.Center,
                ) { Txt(label, 16, if (i == tab) TextC else Muted, FontWeight.Bold) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.u)) {
            Txt("сборка ${BuildConfig.VERSION_CODE}", 12, Muted, FontWeight.Medium)
            Btn(52, radius = 14, bg = Panel, onClick = { onTab(1) }) { Ic(IcSearch, 24, TextC) }
        }
    }
}

@Composable
private fun Logo(s: Station?, size: Int, radius: Int, textSize: Int) {
    Box(
        Modifier.size(size.u).clip(RoundedCornerShape(radius.u))
            .background(if (s != null) Color(s.color) else Raised),
        contentAlignment = Alignment.Center,
    ) {
        if (s != null) {
            Txt(s.mono, textSize, Bg, FontWeight.ExtraBold)
            if (s.favicon.isNotBlank()) {
                AsyncImage(
                    model = s.favicon, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            }
        } else Ic(IcBars, size / 2, Muted)
    }
}

@Composable
private fun StationRow(s: Station, selected: Boolean, onAir: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(70.u).clip(RoundedCornerShape(14.u))
            .background(if (selected) Sel else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.u),
    ) {
        Logo(s, 46, 12, 17)
        Column(Modifier.weight(1f)) {
            Txt(s.name, 18, TextC, FontWeight.Bold)
            Txt(s.genre.ifEmpty { s.tech }, 14, Muted)
        }
        if (onAir) Ic(IcBars, 22, Amber)
    }
}

// ---------- Экран «Эфир» ----------
@Composable
private fun AirScreen(
    store: Store, ui: PlayerUi, land: Boolean, a: Actions,
    sleep: Int, onSleep: () -> Unit, onTab: (Int) -> Unit,
) {
    val fav = @Composable { m: Modifier ->
        Column(m.background(Panel).padding(start = 14.u, end = 14.u, top = 20.u, bottom = 14.u)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.u),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("Избранное", 22, TextC, FontWeight.ExtraBold)
                Txt(stationsWord(store.favorites.size), 14, Muted)
            }
            Spacer(Modifier.height(12.u))
            if (store.favorites.isEmpty()) {
                Column(Modifier.padding(8.u), verticalArrangement = Arrangement.spacedBy(14.u)) {
                    Txt("Пока пусто. Найдите станцию в каталоге и нажмите ★", 16, Muted, maxLines = 3)
                    Box(
                        Modifier.height(52.u).clip(RoundedCornerShape(14.u)).background(Amber)
                            .clickable { onTab(1) }.padding(horizontal = 20.u),
                        contentAlignment = Alignment.Center,
                    ) { Txt("Открыть каталог", 16, Bg, FontWeight.ExtraBold) }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.u)) {
                    items(store.favorites, key = { it.id }) { s ->
                        val cur = ui.station?.id == s.id
                        StationRow(s, cur, cur && ui.playing) { a.play(s, store.favorites) }
                    }
                }
            }
        }
    }

    if (land) {
        Row(Modifier.fillMaxSize()) {
            fav(Modifier.width(320.u).fillMaxHeight())
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .padding(start = 28.u, end = 28.u, top = 20.u, bottom = 24.u),
            ) {
                TopBar(0, onTab)
                NowPlaying(ui, land = true, modifier = Modifier.weight(1f))
                BufferRow(ui)
                Spacer(Modifier.height(18.u))
                Controls(store, ui, a, sleep, onSleep)
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 16.u, end = 16.u, top = 16.u, bottom = 16.u)) {
                TopBar(0, onTab)
                NowPlaying(ui, land = false, modifier = Modifier.padding(vertical = 20.u))
                BufferRow(ui)
                Spacer(Modifier.height(16.u))
                Controls(store, ui, a, sleep, onSleep)
            }
            fav(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun NowPlaying(ui: PlayerUi, land: Boolean, modifier: Modifier) {
    val s = ui.station
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (land) 28.u else 18.u),
    ) {
        if (land) Logo(s, 210, 28, 72) else Logo(s, 120, 22, 44)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.u)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.u)) {
                val (label, fg, bg) = when {
                    s == null -> Triple("НЕ ВЫБРАНО", Muted, Raised)
                    ui.playing && ui.error -> Triple("НЕТ СВЯЗИ · ПОВТОР", Red, Raised)
                    ui.playing && ui.buffering -> Triple("ПОДКЛЮЧЕНИЕ…", Amber, AmberBg)
                    ui.playing -> Triple("● В ЭФИРЕ", Amber, AmberBg)
                    else -> Triple("ПАУЗА", Muted, Raised)
                }
                Box(
                    Modifier.height(30.u).clip(RoundedCornerShape(15.u)).background(bg).padding(horizontal = 12.u),
                    contentAlignment = Alignment.Center,
                ) { Txt(label, 14, fg, FontWeight.ExtraBold, spacing = 0.6f) }
                if (ui.format.isNotEmpty()) Txt(ui.format, 14, Muted, FontWeight.Medium, mono = true)
            }
            Txt(s?.name ?: "Выберите станцию", if (land) 44 else 30, TextC, FontWeight.ExtraBold)
            if (s != null) {
                Txt("СЕЙЧАС ИГРАЕТ", 15, Muted, FontWeight.Bold, spacing = 0.8f)
                Txt(ui.track ?: "Станция не передаёт название трека", if (land) 24 else 18,
                    if (ui.track != null) Sub else Muted, maxLines = 2)
            }
        }
    }
}

@Composable
private fun BufferRow(ui: PlayerUi) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.u)) {
        Txt("Буфер", 14, Muted, FontWeight.Bold, Modifier.width(64.u))
        Box(Modifier.weight(1f).height(6.u).clip(RoundedCornerShape(3.u)).background(Raised)) {
            val frac = (ui.bufferMs / 10_000f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.u)).background(Amber))
        }
        val sec = if (ui.bufferMs > 0) "%.1f с".format(ui.bufferMs / 1000f).replace('.', ',') else "—"
        Txt(sec, 14, Muted, FontWeight.Medium, Modifier.width(64.u), mono = true)
    }
}

@Composable
private fun Controls(store: Store, ui: PlayerUi, a: Actions, sleep: Int, onSleep: () -> Unit) {
    val s = ui.station
    val fav = store.isFavorite(s?.id)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Btn(72, radius = 20, bg = Panel, onClick = { s?.let { store.toggleFavorite(it) } }) {
            Ic(if (fav) IcStar else IcStarO, 30, if (fav) Amber else Muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.u)) {
            Btn(88, radius = 26, bg = Raised, onClick = a.prev) { Ic(IcPrev, 36, TextC) }
            Btn(112, radius = 56, bg = Amber, onClick = a.toggle) {
                Ic(if (ui.playing) IcPause else IcPlay, 46, Bg)
            }
            Btn(88, radius = 26, bg = Raised, onClick = a.next) { Ic(IcNext, 36, TextC) }
        }
        Btn(72, radius = 20, bg = if (sleep > 0) AmberBg else Panel, onClick = onSleep) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Ic(IcTimer, if (sleep > 0) 22 else 28, if (sleep > 0) Amber else TextC)
                if (sleep > 0) Txt("$sleep мин", 12, Amber, FontWeight.Bold)
            }
        }
    }
}

// ---------- Карточка станции (каталог, недавние) ----------
@Composable
private fun StationCard(store: Store, ui: PlayerUi, s: Station, onPlay: () -> Unit) {
    val fav = store.isFavorite(s.id)
    val cur = ui.station?.id == s.id
    Row(
        Modifier.fillMaxWidth().height(100.u).clip(RoundedCornerShape(18.u))
            .background(if (cur) Sel else Panel).clickable(onClick = onPlay)
            .padding(start = 14.u, end = 12.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.u),
    ) {
        Logo(s, 56, 14, 19)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.u)) {
            Txt(s.name, 18, TextC, FontWeight.Bold)
            Txt(s.genre, 14, Muted)
            Txt(s.tech, 12, Muted, FontWeight.Medium, mono = true)
        }
        Btn(48, radius = 14, bg = Color.Transparent, onClick = { store.toggleFavorite(s) }) {
            Ic(if (fav) IcStar else IcStarO, 24, if (fav) Amber else Muted)
        }
        Btn(48, radius = 24, bg = if (cur && ui.playing) Amber else Sel, onClick = onPlay) {
            if (cur && ui.playing) Ic(IcBars, 20, Bg) else Ic(IcPlay, 20, TextC)
        }
    }
}

// ---------- Экран «Каталог» ----------
@Composable
private fun CatalogScreen(
    store: Store, ui: PlayerUi, c: CatalogState, land: Boolean, a: Actions, onBack: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = if (land) 28.u else 16.u, vertical = 20.u),
        verticalArrangement = Arrangement.spacedBy(16.u),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.u)) {
            Btn(56, radius = 16, bg = Panel, onClick = onBack) { Ic(IcBack, 26, TextC) }
            BasicTextField(
                value = c.query,
                onValueChange = { c.query = it },
                singleLine = true,
                textStyle = TextStyle(color = TextC, fontSize = 19.t, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(Amber),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Row(
                        Modifier.fillMaxWidth().height(56.u).clip(RoundedCornerShape(16.u)).background(Panel)
                            .padding(horizontal = 18.u),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.u),
                    ) {
                        Ic(IcSearch, 24, Muted)
                        Box(Modifier.weight(1f)) {
                            if (c.query.isEmpty()) Txt("Название станции", 19, Muted)
                            inner()
                        }
                        if (c.query.isNotEmpty()) {
                            Box(Modifier.clickable { c.query = "" }.padding(6.u)) { Txt("✕", 18, Muted) }
                        }
                    }
                },
            )
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.u),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("Россия", c.russia) { c.russia = !c.russia }
            GENRES.forEach { (label, tag) ->
                Chip(label, c.tag == tag) { c.tag = if (c.tag == tag) null else tag }
            }
        }

        when {
            c.loading && c.results.isEmpty() -> Txt("Загружаю каталог…", 18, Muted)
            c.error != null && c.results.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.u)) {
                Txt(c.error!!, 18, Muted)
                Box(
                    Modifier.height(48.u).clip(RoundedCornerShape(14.u)).background(Raised)
                        .clickable { c.reload++ }.padding(horizontal = 18.u),
                    contentAlignment = Alignment.Center,
                ) { Txt("Повторить", 16, TextC, FontWeight.Bold) }
            }
            c.results.isEmpty() -> Txt("Ничего не нашлось", 18, Muted)
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(if (land) 3 else 1),
                horizontalArrangement = Arrangement.spacedBy(12.u),
                verticalArrangement = Arrangement.spacedBy(12.u),
            ) {
                items(c.results, key = { it.id }) { s ->
                    StationCard(store, ui, s) { a.play(s, c.results) }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(44.u).clip(RoundedCornerShape(22.u)).background(if (selected) Amber else Raised)
            .clickable(onClick = onClick).padding(horizontal = 18.u),
        contentAlignment = Alignment.Center,
    ) { Txt(label, 16, if (selected) Bg else Sub, if (selected) FontWeight.ExtraBold else FontWeight.Bold) }
}

// ---------- Экран «Недавние» ----------
@Composable
private fun RecentScreen(store: Store, ui: PlayerUi, land: Boolean, a: Actions, onTab: (Int) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = if (land) 28.u else 16.u, vertical = 20.u),
        verticalArrangement = Arrangement.spacedBy(16.u),
    ) {
        TopBar(2, onTab)
        if (store.recents.isEmpty()) Txt("Здесь появятся станции, которые вы слушали", 18, Muted)
        else LazyVerticalGrid(
            columns = GridCells.Fixed(if (land) 3 else 1),
            horizontalArrangement = Arrangement.spacedBy(12.u),
            verticalArrangement = Arrangement.spacedBy(12.u),
        ) {
            items(store.recents.toList(), key = { it.id }) { s ->
                StationCard(store, ui, s) { a.play(s, store.recents.toList()) }
            }
        }
    }
}
