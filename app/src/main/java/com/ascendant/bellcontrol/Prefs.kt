package com.ascendant.bellcontrol

import android.content.Context

enum class Level { ELEMENTARY, MIDDLE_HIGH }

object Prefs {
    private const val FILE = "bell_control_prefs"
    private const val KEY_LEVEL = "level"
    private const val KEY_DHUHR_AUTO_PREFIX = "dhuhr_auto_"
    private const val KEY_DHUHR_MANUAL_PREFIX = "dhuhr_manual_min_"

    fun getLevel(ctx: Context): Level? {
        val v = prefs(ctx).getString(KEY_LEVEL, null) ?: return null
        return try { Level.valueOf(v) } catch (e: IllegalArgumentException) { null }
    }

    fun setLevel(ctx: Context, level: Level) {
        prefs(ctx).edit().putString(KEY_LEVEL, level.name).apply()
    }

    fun clearLevel(ctx: Context) {
        prefs(ctx).edit().remove(KEY_LEVEL).apply()
    }

    fun isDhuhrAuto(ctx: Context, level: Level): Boolean =
        prefs(ctx).getBoolean(KEY_DHUHR_AUTO_PREFIX + level.name, true)

    fun setDhuhrAuto(ctx: Context, level: Level, auto: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DHUHR_AUTO_PREFIX + level.name, auto).apply()
    }

    /** Manual Dhuhr override in minutes-since-midnight, or null if never set for this level. */
    fun getManualDhuhrMinutes(ctx: Context, level: Level): Int? {
        val v = prefs(ctx).getInt(KEY_DHUHR_MANUAL_PREFIX + level.name, -1)
        return if (v < 0) null else v
    }

    fun setManualDhuhrMinutes(ctx: Context, level: Level, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_DHUHR_MANUAL_PREFIX + level.name, minutes).apply()
    }

    /** The Dhuhr minute to actually use today: manual override if set, else the daily calculation. */
    fun resolveDhuhrMinutes(ctx: Context, level: Level): Int {
        return if (!isDhuhrAuto(ctx, level)) {
            getManualDhuhrMinutes(ctx, level) ?: PrayerTimes.dhuhrMinutesToday()
        } else {
            PrayerTimes.dhuhrMinutesToday()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
