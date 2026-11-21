package ru.kuxnik.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var store: Store
    private lateinit var weather: WeatherState
    private var future: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var ipJob: Job? = null

    // Все методы переопределены явно: на Android до 11 у интерфейса нет реализаций по умолчанию.
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        weather = WeatherState(this)

        val need = mutableListOf<String>()
        if (!hasLocationPermission()) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty() && Build.VERSION.SDK_INT >= 23) requestPermissions(need.toTypedArray(), 1)

        setContent { RadioApp(store, controller, weather, refreshWeather = { force -> refreshWeather(force) }) }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLocation()
    }

    private fun hasLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        // Если за 15 секунд координат нет — берём примерное место по IP.
        ipJob?.cancel()
        ipJob = lifecycleScope.launch {
            delay(if (hasLocationPermission()) 15_000L else 0L)
            if (!weather.hasLocation && weather.locateByIp()) refreshWeather(force = false)
        }
        weather.noPermission = !hasLocationPermission()
        if (weather.noPermission) return
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        lm.removeUpdates(locListener)
        var best: Location? = null
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        for (p in providers) {
            runCatching {
                lm.getLastKnownLocation(p)?.let { if (best == null || it.time > best!!.time) best = it }
                if (p != LocationManager.PASSIVE_PROVIDER) {
                    lm.requestLocationUpdates(p, 60_000L, 0f, locListener, Looper.getMainLooper())
                }
            }
        }
        best?.let { onLocation(it) }
    }

    private fun onLocation(l: Location) {
        val src = when (l.provider) {
            LocationManager.GPS_PROVIDER -> "GPS"
            LocationManager.NETWORK_PROVIDER -> "сеть"
            else -> l.provider ?: "?"
        }
        weather.setLocation(l.latitude, l.longitude, src)
        refreshWeather(force = false)
    }

    private fun refreshWeather(force: Boolean) {
        if (force && !weather.hasLocation) {
            lifecycleScope.launch { if (weather.locateByIp()) weather.refresh() }
            return
        }
        if (force || weather.stale()) lifecycleScope.launch { weather.refresh() }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            controller = runCatching { f.get() }.getOrNull()
        }, ContextCompat.getMainExecutor(this))
        startLocation()
    }

    override fun onStop() {
        ipJob?.cancel()
        (getSystemService(LOCATION_SERVICE) as? LocationManager)?.removeUpdates(locListener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }
}
