package com.ascendant.bellcontrol

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Central, no-server config sync.
 *
 * The whole schedule — periods, breaks, Prayer, all of it — lives in one published Google
 * Sheet (or any URL that serves CSV text). Every board polls it periodically and caches the
 * last successful result, so boards keep working normally even when offline — they just show
 * the last synced schedule until the next successful check.
 *
 * CSV columns: level,period,start,end
 *   level  — ELEMENTARY or MIDDLE_HIGH (exact spelling, case-insensitive)
 *   period — the name shown on screen and spoken in the announcement
 *   start  — 24-hour start time, e.g. 6:45 or 13:10
 *   end    — 24-hour end time, same format
 *
 * To publish: in Google Sheets, File → Share → Publish to web → select the sheet → CSV →
 * Publish. Paste that link in as CONFIG_CSV_URL below, rebuild once, and from then on every
 * edit to the sheet reaches every board on its own — no new build, no server.
 */
object RemoteConfig {

    // TODO: replace with your published Google Sheet CSV link once it exists.
    const val CONFIG_CSV_URL = "PASTE_YOUR_PUBLISHED_SHEET_CSV_URL_HERE"

    private const val FILE = "bell_control_remote_config"
    private const val KEY_PREFIX = "schedule_"
    private const val KEY_LAST_SYNC = "last_sync_millis"

    private val executor = Executors.newSingleThreadExecutor()

    /** Cached schedule for a level if one has ever been synced successfully, else null. */
    fun getCached(ctx: Context, level: Level): List<Period>? {
        val raw = prefs(ctx).getString(KEY_PREFIX + level.name, null) ?: return null
        return deserialize(raw)
    }

    /** The schedule to actually use right now: last synced copy if any, else the bundled default. */
    fun getSchedule(ctx: Context, level: Level): List<Period> =
        getCached(ctx, level) ?: Schedule.defaultFor(level)

    fun lastSyncMillis(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_SYNC, -1)

    /**
     * Fetches and caches the sheet on a background thread. [onResult], if given, fires on that
     * same background thread — hop to the main thread yourself before touching views.
     */
    fun syncNow(ctx: Context, onResult: ((success: Boolean, message: String) -> Unit)? = null) {
        val appCtx = ctx.applicationContext
        executor.execute {
            try {
                val csv = fetchText(CONFIG_CSV_URL)
                val parsed = parseCsv(csv)
                if (parsed.isEmpty()) {
                    onResult?.invoke(false, "Sheet reached but no valid rows found")
                    return@execute
                }
                val editor = prefs(appCtx).edit()
                for ((level, periods) in parsed) {
                    editor.putString(KEY_PREFIX + level.name, serialize(periods))
                }
                editor.putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                editor.apply()
                onResult?.invoke(true, "Synced ${parsed.values.sumOf { it.size }} periods")
            } catch (e: Exception) {
                onResult?.invoke(false, e.message ?: "Sync failed")
            }
        }
    }

    private fun fetchText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader ->
                return reader.readText()
            }
        }
    }

    private fun parseCsv(csv: String): Map<Level, List<Period>> {
        val result = mutableMapOf<Level, MutableList<Period>>()
        val lines = csv.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for ((idx, line) in lines.withIndex()) {
            if (idx == 0 && line.lowercase().startsWith("level")) continue // header row
            val cols = line.split(",").map { it.trim() }
            if (cols.size < 4) continue
            val level = try { Level.valueOf(cols[0].uppercase()) } catch (e: Exception) { continue }
            if (level == Level.ALL_LEVELS) continue
            val name = cols[1]
            val start = parseTime(cols[2]) ?: continue
            val end = parseTime(cols[3]) ?: continue
            result.getOrPut(level) { mutableListOf() }.add(Period(name, start, end))
        }
        return result
    }

    private fun parseTime(s: String): Int? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun serialize(periods: List<Period>): String =
        periods.joinToString(";") { "${it.name}~${it.startMin}~${it.endMin}" }

    private fun deserialize(raw: String): List<Period> =
        raw.split(";").filter { it.isNotBlank() }.mapNotNull {
            val parts = it.split("~")
            if (parts.size != 3) return@mapNotNull null
            val s = parts[1].toIntOrNull() ?: return@mapNotNull null
            val e = parts[2].toIntOrNull() ?: return@mapNotNull null
            Period(parts[0], s, e)
        }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
