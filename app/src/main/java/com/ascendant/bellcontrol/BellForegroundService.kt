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

    private val rung = mutableSetOf<Int>()
    private var lastDay = -1
    private var cachedSchedule: List<Period> = emptyList()

    private val tick = object : Runnable {
        override fun run() {
            doTick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannels()
        startForeground(NOTIF_ID_SERVICE, buildServiceNotification("Bell Control running", "Waiting for next bell…"))
        handler.post(tick)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale.US
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    private fun doTick() {
        val level = Prefs.getLevel(this)
        if (level == null) {
            updateOngoingNotification(emptyList(), -1)
            return
        }

        val now = Calendar.getInstance()
        val dayOfYear = now.get(Calendar.DAY_OF_YEAR)
        if (lastDay != dayOfYear) {
            rung.clear()
            val dhuhr = Prefs.resolveDhuhrMinutes(this, level)
            cachedSchedule = Schedule.buildDailySchedule(level, dhuhr)
            lastDay = dayOfYear
        }

        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val bounds = Schedule.boundaries(cachedSchedule)

        bounds.forEachIndexed { idx, t ->
            if (t == nowMin && !rung.contains(t)) {
                rung.add(t)
                val isDismissal = idx == bounds.size - 1
                val upcoming = if (isDismissal) null else cachedSchedule[idx]
                announce(Schedule.announcementFor(upcoming?.name ?: "", isDismissal))
            }
        }

        updateOngoingNotification(cachedSchedule, nowMin)
    }

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

    private fun updateOngoingNotification(schedule: List<Period>, nowMin: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_SERVICE, buildServiceNotification("Bell Control running", nextBellLabel(schedule, nowMin)))
    }

    private fun nextBellLabel(schedule: List<Period>, nowMin: Int): String {
        if (schedule.isEmpty()) return "Open the app to set the classroom level"
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
    }
}
