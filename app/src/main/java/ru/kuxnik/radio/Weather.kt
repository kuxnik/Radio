package ru.kuxnik.radio

import android.content.Context
import android.location.Geocoder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Hour(val time: String, val temp: Double, val code: Int, val precip: Int, val isDay: Boolean)
data class Day(val label: String, val max: Double, val min: Double, val code: Int, val precip: Int)

data class Weather(
    val temp: Double,
    val feels: Double,
    val code: Int,
    val isDay: Boolean,
    val wind: Double,
    val windDir: Int,
    val humidity: Int,
    val pressureMm: Int,
    val hours: List<Hour>,
    val days: List<Day>,
)

enum class Sky { CLEAR, CLOUD, FOG, RAIN, SNOW, STORM }

fun skyOf(code: Int): Sky = when (code) {
    0, 1 -> Sky.CLEAR
    2, 3 -> Sky.CLOUD
    45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUD
}

fun describe(code: Int): String = when (code) {
    0 -> "Ясно"
    1 -> "Преимущественно ясно"
    2 -> "Переменная облачность"
    3 -> "Пасмурно"
    45, 48 -> "Туман"
    51, 53, 55 -> "Морось"
    56, 57 -> "Ледяная морось"
    61 -> "Небольшой дождь"
    63 -> "Дождь"
    65 -> "Сильный дождь"
    66, 67 -> "Ледяной дождь"
    71 -> "Небольшой снег"
    73 -> "Снег"
    75 -> "Сильный снег"
    77 -> "Снежная крупа"
    80, 81 -> "Ливень"
    82 -> "Сильный ливень"
    85, 86 -> "Снегопад"
    95 -> "Гроза"
    96, 99 -> "Гроза с градом"
    else -> "—"
}

fun tempText(t: Double): String {
    val r = t.roundToInt()
    return when {
        r > 0 -> "+$r°"
        r < 0 -> "−${abs(r)}°"
        else -> "0°"
    }
}

fun windDirText(deg: Int): String =
    listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")[((deg + 22.5) / 45).toInt().mod(8)]

/** Погода по координатам GPS, с кешем на случай, если нет сети. */
class WeatherState(private val context: Context) {
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    var weather by mutableStateOf<Weather?>(null)
    var place by mutableStateOf("")
    var updated by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var hasLocation by mutableStateOf(false)
    var noPermission by mutableStateOf(false)
    /** Откуда координаты: GPS, сеть или IP-адрес. */
    var source by mutableStateOf("")

    private var lat = Double.NaN
    private var lon = Double.NaN
    private var fetchedAt = 0L
    private var fetchedLat = Double.NaN
    private var fetchedLon = Double.NaN

    init {
        val cached = prefs.getString("weather_json", null)
        if (cached != null) runCatching {
            weather = parse(JSONObject(cached))
            place = prefs.getString("weather_place", "") ?: ""
            fetchedAt = prefs.getLong("weather_at", 0L)
            updated = timeText(fetchedAt)
        }
    }

    fun setLocation(la: Double, lo: Double, src: String) {
        // Приблизительное место по IP не должно перебивать настоящий GPS.
        if (src == "IP" && hasLocation && source != "IP") return
        lat = la
        lon = lo
        source = src
        hasLocation = true
    }

    /** Запасной путь, если GPS молчит: примерное место по IP-адресу. */
    suspend fun locateByIp(): Boolean {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val o = JSONObject(get("http://ip-api.com/json/?fields=status,lat,lon,city"))
                if (o.optString("status") != "success") throw java.io.IOException("ip-api")
                Triple(o.getDouble("lat"), o.getDouble("lon"), o.optString("city"))
            }.recoverCatching {
                val o = JSONObject(get("https://ipwho.is/?fields=success,latitude,longitude,city"))
                if (!o.optBoolean("success")) throw java.io.IOException("ipwho")
                Triple(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city"))
            }.getOrNull()
        } ?: return false
        setLocation(r.first, r.second, "IP")
        if (r.third.isNotBlank() && place.isEmpty()) place = r.third
        return true
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "RadioMagnitola/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Нужно ли обновить: прошло 30 минут или уехали дальше 10 км. */
    fun stale(): Boolean {
        if (!hasLocation || loading) return false
        if (weather == null || fetchedAt == 0L) return true
        if (System.currentTimeMillis() - fetchedAt > 30 * 60_000L) return true
        if (fetchedLat.isNaN()) return true
        val dy = (lat - fetchedLat) * 111.0
        val dx = (lon - fetchedLon) * 111.0 * cos(Math.toRadians(lat))
        return sqrt(dx * dx + dy * dy) > 10.0
    }

    suspend fun refresh() {
        if (!hasLocation || loading) return
        loading = true
        val la = lat
        val lo = lon
        try {
            val json = try {
                withContext(Dispatchers.IO) { download(la, lo) }
            } catch (e: Exception) {
                throw IllegalStateException("Нет связи с сервером погоды (${e.javaClass.simpleName}: ${e.message})")
            }
            weather = try {
                parse(json)
            } catch (e: Exception) {
                throw IllegalStateException("Не разобрал ответ сервера (${e.javaClass.simpleName}: ${e.message})")
            }
            fetchedAt = System.currentTimeMillis()
            fetchedLat = la
            fetchedLon = lo
            updated = timeText(fetchedAt)
            error = null
            prefs.edit().putString("weather_json", json.toString()).putLong("weather_at", fetchedAt).apply()
            val name = withContext(Dispatchers.IO) { cityName(la, lo) }
            place = name ?: "%.2f, %.2f".format(Locale.US, la, lo)
            prefs.edit().putString("weather_place", place).apply()
        } catch (e: Exception) {
            error = (e.message ?: e.javaClass.simpleName).take(160)
        } finally {
            loading = false
        }
    }

    private fun download(la: Double, lo: Double): JSONObject {
        val q = "latitude=%.4f&longitude=%.4f".format(Locale.US, la, lo) +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m," +
            "wind_direction_10m,relative_humidity_2m,pressure_msl" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7&wind_speed_unit=ms"
        var last: Exception? = null
        for (scheme in listOf("https", "http")) {
            try {
                return JSONObject(get("$scheme://api.open-meteo.com/v1/forecast?$q"))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException()
    }

    @Suppress("DEPRECATION")
    private fun cityName(la: Double, lo: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(context, Locale("ru")).getFromLocation(la, lo, 1)?.firstOrNull() ?: return null
        a.locality ?: a.subAdminArea ?: a.adminArea
    }.getOrNull()

    private fun timeText(t: Long) = if (t == 0L) "" else SimpleDateFormat("HH:mm", Locale.US).format(t)

    private fun parse(o: JSONObject): Weather {
        val cur = o.getJSONObject("current")
        val nowTime = cur.getString("time") // 2026-11-19T19:30
        val fromHour = nowTime.take(13) + ":00"

        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hours = ArrayList<Hour>()
        for (i in 0 until ht.length()) {
            val t = ht.getString(i)
            if (t < fromHour) continue
            hours += Hour(
                time = t.substring(11, 16),
                temp = h.getJSONArray("temperature_2m").optDouble(i),
                code = h.getJSONArray("weather_code").optInt(i),
                precip = h.getJSONArray("precipitation_probability").optInt(i),
                isDay = h.getJSONArray("is_day").optInt(i, 1) == 1,
            )
            if (hours.size == 24) break
        }

        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("EE, d", Locale("ru"))
        val days = (0 until dt.length()).map { i ->
            val label = when (i) {
                0 -> "Сегодня"
                1 -> "Завтра"
                else -> runCatching { outFmt.format(inFmt.parse(dt.getString(i))!!) }
                    .getOrDefault(dt.getString(i)).replaceFirstChar { it.uppercase() }
            }
            Day(
                label = label,
                max = d.getJSONArray("temperature_2m_max").optDouble(i),
                min = d.getJSONArray("temperature_2m_min").optDouble(i),
                code = d.getJSONArray("weather_code").optInt(i),
                precip = d.getJSONArray("precipitation_probability_max").optInt(i),
            )
        }

        return Weather(
            temp = cur.optDouble("temperature_2m"),
            feels = cur.optDouble("apparent_temperature"),
            code = cur.optInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            wind = cur.optDouble("wind_speed_10m"),
            windDir = cur.optInt("wind_direction_10m"),
            humidity = cur.optInt("relative_humidity_2m"),
            pressureMm = (cur.optDouble("pressure_msl") * 0.750062).roundToInt(),
            hours = hours,
            days = days,
        )
    }
}
