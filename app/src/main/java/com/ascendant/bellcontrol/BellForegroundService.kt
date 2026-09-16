package com.ascendant.bellcontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.Locale

class BellForegroundService : Service(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    // Keyed per underlying level (ELEMENTARY / MIDDLE_HIGH), since a combined board tracks both.
    private val rung = mutableMapOf<Level, MutableSet<Int>>()
    private val cachedSchedules = mutableMapOf<Level, List<Period>>()
    private var lastDay = -1

    private val tick = object : Runnable {
        override fun run() {
            doTick()
            handler.postDelayed(this, 1000)
        }
    }

    // Picks up edits to the central sheet without waiting for the next day or a reopen.
    // Always syncs both real levels regardless of which one this board displays — one CSV,
    // shared by every board.
    private val periodicSync = object : Runnable {
        override fun run() {
            RemoteConfig.syncNow(this@BellForegroundService) { success, _ ->
                if (success) refreshCachedSchedules()
            }
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannels()
        startForeground(NOTIF_ID_SERVICE, buildServiceNotification("Bell Control running", "Waiting for next bell…"))
        handler.post(tick)
        handler.postDelayed(periodicSync, SYNC_INTERVAL_MS)

        RemoteConfig.syncNow(this) { success, _ ->
            if (success) refreshCachedSchedules()
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale.US
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(periodicSync)
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    /** Which real level(s) this board needs to track — both, if it's a combined display. */
    private fun trackedLevels(boardLevel: Level): List<Level> =
        if (boardLevel == Level.ALL_LEVELS) listOf(Level.ELEMENTARY, Level.MIDDLE_HIGH) else listOf(boardLevel)

    private fun refreshCachedSchedules() {
        val boardLevel = Prefs.getLevel(this) ?: return
        trackedLevels(boardLevel).forEach { lvl ->
            cachedSchedules[lvl] = RemoteConfig.getSchedule(this, lvl)
        }
    }

    private fun doTick() {
        val boardLevel = Prefs.getLevel(this)
        if (boardLevel == null) {
            updateOngoingNotification(boardLevel, -1)
            return
        }

        val levels = trackedLevels(boardLevel)
        val now = Calendar.getInstance()
        val dayOfYear = now.get(Calendar.DAY_OF_YEAR)
        if (lastDay != dayOfYear) {
            levels.forEach { rung[it] = mutableSetOf() }
            refreshCachedSchedules()
            lastDay = dayOfYear
        }

        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val combined = boardLevel == Level.ALL_LEVELS

        levels.forEach { lvl ->
            val schedule = cachedSchedules[lvl] ?: return@forEach
            val rungSet = rung.getOrPut(lvl) { mutableSetOf() }
            val bounds = Schedule.boundaries(schedule)

            bounds.forEachIndexed { idx, t ->
                if (t == nowMin && !rungSet.contains(t)) {
                    rungSet.add(t)
                    val isDismissal = idx == bounds.size - 1
                    val upcoming = if (isDismissal) null else schedule[idx]
                    val text = Schedule.announcementFor(upcoming?.name ?: "", isDismissal)
                    announce(if (combined) "${levelLabel(lvl)}. $text" else text)
                }
            }
        }

        updateOngoingNotification(boardLevel, nowMin)
    }

    private fun levelLabel(lvl: Level) = if (lvl == Level.ELEMENTARY) "Elementary" else "Middle and High School"

    private fun announce(text: String) {
        requestAudioFocus()
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "bell_${System.currentTimeMillis()}")
        showAlertNotification(text)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build()
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun buildServiceNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateOngoingNotification(boardLevel: Level?, nowMin: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        val text = when {
            boardLevel == null -> "Open the app to set the classroom level"
            boardLevel == Level.ALL_LEVELS -> {
                val parts = trackedLevels(boardLevel).mapNotNull { lvl ->
                    val schedule = cachedSchedules[lvl] ?: return@mapNotNull null
                    "${levelLabel(lvl)}: ${nextBellLabel(schedule, nowMin)}"
                }
                if (parts.isEmpty()) "Open the app to sync the schedule" else parts.joinToString("  •  ")
            }
            else -> {
                val schedule = cachedSchedules[boardLevel]
                if (schedule == null) "Open the app to sync the schedule" else nextBellLabel(schedule, nowMin)
            }
        }
        nm.notify(NOTIF_ID_SERVICE, buildServiceNotification("Bell Control running", text))
    }

    private fun nextBellLabel(schedule: List<Period>, nowMin: Int): String {
        val upcoming = Schedule.boundaries(schedule).filter { it > nowMin }
        if (upcoming.isEmpty()) return "No more bells today"
        return "Next bell in ${upcoming.first() - nowMin} min"
    }

    private fun showAlertNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle("Bell")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Bell Control service", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Bell alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "bell_service"
        const val CHANNEL_ALERTS = "bell_alerts"
        const val NOTIF_ID_SERVICE = 1001
        const val SYNC_INTERVAL_MS = 30 * 60 * 1000L
    }
}
