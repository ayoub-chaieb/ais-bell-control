package com.ascendant.bellcontrol

import android.content.Context

enum class Level { ELEMENTARY, MIDDLE_HIGH, ALL_LEVELS }

object Prefs {
    private const val FILE = "bell_control_prefs"
    private const val KEY_LEVEL = "level"

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

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
