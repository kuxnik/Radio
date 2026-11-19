package ru.kuxnik.radio

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject

data class Station(
    val id: String,
    val name: String,
    val url: String,
    val favicon: String = "",
    val tags: String = "",
    val country: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
) {
    /** Буквы для плитки вместо логотипа: «Европа Плюс» → «ЕП». */
    val mono: String
        get() {
            val words = name.split(' ', '-', '.', '_').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
            val s = words.take(2).joinToString("") { it.take(1) }.uppercase()
            return s.ifEmpty { "?" }
        }

    val color: Long
        get() = PALETTE[(id.hashCode() and 0x7fffffff) % PALETTE.size]

    /** «Поп, хиты» из тегов, иначе страна. */
    val genre: String
        get() {
            val t = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2)
            val g = t.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
            return when {
                g.isNotEmpty() && country.isNotEmpty() -> "$g · $country"
                g.isNotEmpty() -> g
                else -> country
            }
        }

    val tech: String
        get() = listOfNotNull(
            codec.takeIf { it.isNotBlank() }?.uppercase(),
            bitrate.takeIf { it > 0 }?.let { "$it кбит/с" },
        ).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("favicon", favicon)
        .put("tags", tags).put("country", country).put("codec", codec).put("bitrate", bitrate)

    fun toMediaItem(): MediaItem {
        val extras = Bundle().apply { putString(EXTRA, toJson().toString()) }
        val meta = MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(name)
            .setGenre(genre)
            .setExtras(extras)
            .setIsPlayable(true)
            .setIsBrowsable(false)
        if (favicon.isNotBlank()) meta.setArtworkUri(Uri.parse(favicon))
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(url)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).build())
            .setMediaMetadata(meta.build())
            .build()
    }

    companion object {
        const val EXTRA = "station"

        private val PALETTE = longArrayOf(
            0xFF7FC8FF, 0xFFFFB547, 0xFFFF8A80, 0xFF9BE38A, 0xFFD7B6FF, 0xFFF5E27A, 0xFF7FE0D0,
        )

        fun fromJson(o: JSONObject) = Station(
            id = o.optString("id"),
            name = o.optString("name"),
            url = o.optString("url"),
            favicon = o.optString("favicon"),
            tags = o.optString("tags"),
            country = o.optString("country"),
            codec = o.optString("codec"),
            bitrate = o.optInt("bitrate"),
        )

        fun fromItem(item: MediaItem?): Station? {
            val s = item?.mediaMetadata?.extras?.getString(EXTRA) ?: return null
            return runCatching { fromJson(JSONObject(s)) }.getOrNull()
        }

        /** Запись из Radio Browser API. */
        fun fromRadioBrowser(o: JSONObject): Station? {
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (url.isBlank() || name.isBlank()) return null
            return Station(
                id = o.optString("stationuuid").ifBlank { url },
                name = name,
                url = url,
                favicon = o.optString("favicon"),
                tags = o.optString("tags"),
                country = o.optString("state").ifBlank { o.optString("country") },
                codec = o.optString("codec"),
                bitrate = o.optInt("bitrate"),
            )
        }
    }
}
