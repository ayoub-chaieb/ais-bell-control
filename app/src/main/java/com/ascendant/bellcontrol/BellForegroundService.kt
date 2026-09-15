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

    // Mirrors the JS `rung` sets — one boundary announced once per day.
    private val rungElem = mutableSetOf<Int>()
    private val rungMshs = mutableSetOf<Int>()
    private var lastDay = -1

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
        val now = Calendar.getInstance()
        val dayOfYear = now.get(Calendar.DAY_OF_YEAR)
        if (lastDay != -1 && lastDay != dayOfYear) {
            rungElem.clear(); rungMshs.clear()
        }
        lastDay = dayOfYear

        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val hitElem = checkHit(Schedule.ELEM, rungElem, nowMin)
        val hitMshs = checkHit(Schedule.MSHS, rungMshs, nowMin)

        if (hitElem != null && hitMshs != null) {
            if (hitElem == hitMshs) {
                announce(hitElem)
            } else {
                announce("${Schedule.LABEL_ELEM}. $hitElem")
                announce("${Schedule.LABEL_MSHS}. $hitMshs")
            }
        } else if (hitElem != null) {
            announce("${Schedule.LABEL_ELEM}. $hitElem")
        } else if (hitMshs != null) {
            announce("${Schedule.LABEL_MSHS}. $hitMshs")
        }

        updateOngoingNotification(nowMin)
    }

    private fun checkHit(sched: List<Period>, rung: MutableSet<Int>, nowMin: Int): String? {
        val bounds = Schedule.boundaries(sched)
        bounds.forEachIndexed { idx, t ->
            if (t == nowMin && !rung.contains(t)) {
                rung.add(t)
                val isDismissal = idx == bounds.size - 1
                val upcoming = if (isDismissal) null else sched[idx]
                return Schedule.announcementFor(upcoming?.name ?: "", isDismissal)
            }
        }
        return null
    }

    private fun announce(text: String) {
        requestAudioFocus()
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "bell_${System.currentTimeMillis()}")
        showAlertNotification(text)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ANNOUNCEMENT)
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

    private fun updateOngoingNotification(nowMin: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_SERVICE, buildServiceNotification("Bell Control running", nextBellLabel(nowMin)))
    }

    private fun nextBellLabel(nowMin: Int): String {
        val allBounds = (Schedule.boundaries(Schedule.ELEM) + Schedule.boundaries(Schedule.MSHS))
            .filter { it > nowMin }.distinct().sorted()
        if (allBounds.isEmpty()) return "No more bells today"
        val mins = allBounds.first() - nowMin
        return "Next bell in $mins min"
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
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "Bell Control service", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "Bell alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "bell_service"
        const val CHANNEL_ALERTS = "bell_alerts"
        const val NOTIF_ID_SERVICE = 1001
    }
}
