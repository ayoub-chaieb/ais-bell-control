package com.ascendant.bellcontrol

import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fully offline Dhuhr (solar noon) calculator for Riyadh, KSA.
 *
 * No network call, no paid API, nothing that can go down or run out of quota — it's the
 * standard NOAA equation-of-time approximation (public domain, accurate to about ±1 minute),
 * fed with Riyadh's fixed coordinates. Since it's driven by the date alone, it silently
 * tracks the real seasonal drift of solar noon every day on its own.
 */
object PrayerTimes {

    private const val RIYADH_LONGITUDE = 46.6753   // degrees east
    private const val RIYADH_UTC_OFFSET = 3.0      // Asia/Riyadh — fixed, no daylight saving
    private const val DHUHR_SAFETY_MARGIN_MIN = 1.0 // standard small buffer after solar noon

    /** Today's Dhuhr time as minutes-since-midnight on the KSA clock, e.g. 731 = 12:11. */
    fun dhuhrMinutesToday(): Int {
        val cal = Calendar.getInstance()
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)

        val gamma = 2.0 * PI / 365.0 * (dayOfYear - 1 + 12.0 / 24.0)
        val eqtMinutes = 229.18 * (
            0.000075 +
            0.001868 * cos(gamma) -
            0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) -
            0.040849 * sin(2 * gamma)
        )

        val utcHours = 12.0 - RIYADH_LONGITUDE / 15.0 - eqtMinutes / 60.0
        val localHours = utcHours + RIYADH_UTC_OFFSET + DHUHR_SAFETY_MARGIN_MIN / 60.0

        val minutes = (localHours * 60.0).roundToInt()
        return ((minutes % 1440) + 1440) % 1440
    }
}
