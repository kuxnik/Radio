package ru.kuxnik.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Открытый каталог станций https://www.radio-browser.info */
object RadioBrowser {
    private val hosts = listOf(
        "de1.api.radio-browser.info",
        "de2.api.radio-browser.info",
        "fi1.api.radio-browser.info",
        "nl1.api.radio-browser.info",
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun search(name: String, countryCode: String?, tag: String?): List<Station> =
        withContext(Dispatchers.IO) {
            val q = buildString {
                append("hidebroken=true&order=clickcount&reverse=true&limit=120")
                if (name.isNotBlank()) append("&name=").append(enc(name.trim()))
                if (countryCode != null) append("&countrycode=").append(countryCode)
                if (tag != null) append("&tag=").append(enc(tag))
            }
            var last: Exception? = null
            // На магнитолах часто сбиты часы или старые сертификаты — после https пробуем http.
            for (host in hosts.shuffled()) for (scheme in listOf("https", "http")) {
                try {
                    val c = URL("$scheme://$host/json/stations/search?$q").openConnection() as HttpURLConnection
                    c.connectTimeout = 6000
                    c.readTimeout = 12000
                    c.setRequestProperty("User-Agent", "RadioMagnitola/${BuildConfig.VERSION_NAME}")
                    try {
                        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                        val text = c.inputStream.bufferedReader().use { it.readText() }
                        val arr = JSONArray(text)
                        val seen = HashSet<String>()
                        return@withContext (0 until arr.length())
                            .mapNotNull { Station.fromRadioBrowser(arr.getJSONObject(it)) }
                            .filter { seen.add(it.name.lowercase() + "|" + it.bitrate) }
                    } finally {
                        c.disconnect()
                    }
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last ?: IOException("Каталог недоступен")
        }
}
