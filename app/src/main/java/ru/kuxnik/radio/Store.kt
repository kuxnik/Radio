package ru.kuxnik.radio

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray

/** Избранное и недавние — хранятся на устройстве. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    val favorites = mutableStateListOf<Station>().apply { addAll(load("favorites")) }
    val recents = mutableStateListOf<Station>().apply { addAll(load("recents")) }

    fun isFavorite(id: String?) = id != null && favorites.any { it.id == id }

    fun toggleFavorite(s: Station) {
        val i = favorites.indexOfFirst { it.id == s.id }
        if (i >= 0) favorites.removeAt(i) else favorites.add(s)
        save("favorites", favorites)
    }

    fun addRecent(s: Station) {
        recents.removeAll { it.id == s.id }
        recents.add(0, s)
        while (recents.size > 30) recents.removeAt(recents.lastIndex)
        save("recents", recents)
        // Обновим данные в избранном (на случай, если у станции сменился адрес).
        val i = favorites.indexOfFirst { it.id == s.id }
        if (i >= 0 && favorites[i] != s) {
            favorites[i] = s
            save("favorites", favorites)
        }
    }

    private val bufferState = mutableIntStateOf(prefs.getInt("buffer", 0))

    /** Запас буфера в секундах (0 — авто). Читает и сервис воспроизведения. */
    var bufferSec: Int
        get() = bufferState.intValue
        set(v) {
            bufferState.intValue = v
            prefs.edit().putInt("buffer", v).apply()
        }

    private val prebufferState = androidx.compose.runtime.mutableStateOf(prefs.getBoolean("prebuffer", true))

    /** Готовить заранее следующую станцию списка. */
    var prebuffer: Boolean
        get() = prebufferState.value
        set(v) {
            prebufferState.value = v
            prefs.edit().putBoolean("prebuffer", v).apply()
        }

    private val animationState = androidx.compose.runtime.mutableStateOf(prefs.getBoolean("animation", true))

    /** Стрелки и эквалайзер на экране «Эфир». */
    var animation: Boolean
        get() = animationState.value
        set(v) {
            animationState.value = v
            prefs.edit().putBoolean("animation", v).apply()
        }

    var sleepMinutes: Int
        get() = prefs.getInt("sleep", 0)
        set(v) = prefs.edit().putInt("sleep", v).apply()

    private fun load(key: String): List<Station> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { Station.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<Station>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
