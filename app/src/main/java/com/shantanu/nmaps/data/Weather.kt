package com.shantanu.nmaps.data

import android.util.LruCache
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/** What the sky is doing, in the six faces the dot-matrix badge can draw. */
enum class Sky { Sun, Moon, Cloud, Rain, Snow, Storm }

/** Current weather and air quality at a spot. [aqi] is the US AQI, missing where there's no coverage. */
data class Conditions(val tempC: Double, val code: Int, val isDay: Boolean, val aqi: Int?) {
    val sky get() = Weather.sky(code, isDay)

    /** "31°" or "88°", following the units setting. */
    val temperature get() = "${(if (UnitPrefs.imperial) tempC * 9 / 5 + 32 else tempC).roundToInt()}°"
}

/** Weather and air quality from Open-Meteo: free, no key, and the only such service without one. */
object Weather {
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val AIR = "https://air-quality-api.open-meteo.com/v1/air-quality"
    private const val TTL_MS = 10 * 60_000L

    // Lazy so the pure helpers (parse, sky, aqiLabel) work on the JVM without Android's LruCache.
    private val cache by lazy { LruCache<String, Pair<Long, Conditions>>(64) }

    /** Conditions near [at], cached for ten minutes per ~2 km cell so panning doesn't hammer the service. */
    suspend fun at(at: LatLon): Conditions {
        val key = "%.2f,%.2f".format(Locale.US, at.lat, at.lon)
        cache.get(key)?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.let { return it.second }
        val q = "latitude=$key".replace(",", "&longitude=")
        val c = coroutineScope {
            val forecast = async { Http.json("$FORECAST?$q&current=temperature_2m,weather_code,is_day") }
            // Air quality covers less of the world than weather; its absence isn't an error.
            val air = async { runCatching { Http.json("$AIR?$q&current=us_aqi") }.getOrNull() }
            parse(forecast.await(), air.await())
        }
        cache.put(key, System.currentTimeMillis() to c)
        return c
    }

    fun parse(forecast: JSONObject, air: JSONObject?): Conditions {
        val cur = forecast.getJSONObject("current")
        val aqi = air?.optJSONObject("current")?.takeIf { !it.isNull("us_aqi") }?.optInt("us_aqi")
        return Conditions(cur.getDouble("temperature_2m"), cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1, aqi)
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    fun sky(code: Int, isDay: Boolean): Sky = when (code) {
        0, 1 -> if (isDay) Sky.Sun else Sky.Moon
        2, 3, 45, 48 -> Sky.Cloud
        in 51..67, in 80..82 -> Sky.Rain
        in 71..77, 85, 86 -> Sky.Snow
        in 95..99 -> Sky.Storm
        else -> Sky.Cloud
    }

    /** US EPA bands. */
    fun aqiLabel(aqi: Int): String = when {
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Moderate"
        aqi <= 150 -> "Unhealthy for some"
        aqi <= 200 -> "Unhealthy"
        aqi <= 300 -> "Very unhealthy"
        else -> "Hazardous"
    }
}
