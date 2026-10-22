package com.shantanu.nmaps

import com.shantanu.nmaps.data.Sky
import com.shantanu.nmaps.data.Weather
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherTest {
    private val forecast = JSONObject("""{"current":{"time":"2026-10-22T09:45","temperature_2m":29.9,"weather_code":0,"is_day":1}}""")
    private val air = JSONObject("""{"current":{"time":"2026-10-22T09:00","us_aqi":174}}""")

    @Test
    fun parsesBothServices() {
        val c = Weather.parse(forecast, air)
        assertEquals(29.9, c.tempC, 0.001)
        assertEquals(174, c.aqi)
        assertEquals(Sky.Sun, c.sky)
    }

    @Test
    fun missingAirQualityIsNotAnError() {
        assertNull(Weather.parse(forecast, null).aqi)
        assertNull(Weather.parse(forecast, JSONObject("""{"current":{"us_aqi":null}}""")).aqi)
    }

    @Test
    fun skyFollowsWmoCodes() {
        assertEquals(Sky.Moon, Weather.sky(1, isDay = false))
        assertEquals(Sky.Cloud, Weather.sky(3, isDay = true))
        assertEquals(Sky.Cloud, Weather.sky(45, isDay = true))
        assertEquals(Sky.Rain, Weather.sky(61, isDay = true))
        assertEquals(Sky.Rain, Weather.sky(80, isDay = false))
        assertEquals(Sky.Snow, Weather.sky(73, isDay = true))
        assertEquals(Sky.Storm, Weather.sky(95, isDay = true))
    }

    @Test
    fun aqiBands() {
        assertEquals("Good", Weather.aqiLabel(50))
        assertEquals("Moderate", Weather.aqiLabel(51))
        assertEquals("Unhealthy", Weather.aqiLabel(174))
        assertEquals("Hazardous", Weather.aqiLabel(301))
    }
}
