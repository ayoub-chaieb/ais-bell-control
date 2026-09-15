package com.ascendant.bellcontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var level: Level
    private var cachedDay = -1
    private var cachedSchedule: List<Period> = emptyList()

    private val tick = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val chosen = Prefs.getLevel(this)
        if (chosen == null) {
            startActivity(Intent(this, LevelSelectActivity::class.java))
            finish()
            return
        }
        level = chosen
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<TextView>(R.id.settingsLink).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val svcIntent = Intent(this, BellForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent)
        else startService(svcIntent)
    }

    override fun onResume() {
        super.onResume()
        val chosen = Prefs.getLevel(this)
        if (chosen == null) {
            startActivity(Intent(this, LevelSelectActivity::class.java))
            finish()
            return
        }
        level = chosen
        findViewById<TextView>(R.id.levelLabel).text =
            if (level == Level.ELEMENTARY) "Elementary" else "Middle & High School"
        cachedDay = -1 // force schedule rebuild in case level or Dhuhr settings changed
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun todaySchedule(): List<Period> {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (day != cachedDay) {
            val dhuhr = Prefs.resolveDhuhrMinutes(this, level)
            cachedSchedule = Schedule.buildDailySchedule(level, dhuhr)
            cachedDay = day
            renderTable(cachedSchedule)
        }
        return cachedSchedule
    }

    private fun renderTable(sched: List<Period>) {
        val table = findViewById<LinearLayout>(R.id.scheduleTable)
        table.removeAllViews()
        sched.forEach { p ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(24, 20, 24, 20)
            row.tag = p

            val name = TextView(this)
            name.text = p.name
            name.setTextColor(0xFFECE3D2.toInt())
            name.textSize = 14f
            name.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val time = TextView(this)
            time.text = "${to12h(p.startMin)} – ${to12h(p.endMin)}"
            time.setTextColor(0xFF9FAE9C.toInt())
            time.textSize = 13f
            time.gravity = Gravity.END

            row.addView(name)
            row.addView(time)
            table.addView(row)
        }
    }

    private fun to12h(mins: Int): String {
        var h = mins / 60
        val m = mins % 60
        val ampm = if (h < 12) "AM" else "PM"
        h %= 12
        if (h == 0) h = 12
        return String.format(Locale.US, "%d:%02d %s", h, m, ampm)
    }

    private fun formatSecs(totalSeconds: Int): String {
        val total = totalSeconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun updateUi() {
        val sched = todaySchedule()
        val now = Calendar.getInstance()

        findViewById<TextView>(R.id.clockText).text = String.format(
            Locale.US, "%02d:%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND)
        )
        findViewById<TextView>(R.id.dateLabel).text =
            SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now.time)

        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 +
                now.get(Calendar.MINUTE) +
                now.get(Calendar.SECOND) / 60.0

        val nameEl = findViewById<TextView>(R.id.periodName)
        val timeEl = findViewById<TextView>(R.id.periodTime)
        val bar = findViewById<ProgressBar>(R.id.progressBar)
        val cd = findViewById<TextView>(R.id.countdownText)
        val cdLabel = findViewById<TextView>(R.id.countdownLabel)

        val current = sched.find { nowMin >= it.startMin && nowMin < it.endMin }

        when {
            current != null -> {
                nameEl.text = current.name
                timeEl.text = "${to12h(current.startMin)} – ${to12h(current.endMin)}"
                val total = (current.endMin - current.startMin).toDouble()
                val done = nowMin - current.startMin
                bar.progress = ((done / total) * 1000).toInt().coerceIn(0, 1000)
                val secsLeft = ((current.endMin - nowMin) * 60).toInt()
                cd.text = formatSecs(secsLeft)
                val idx = sched.indexOf(current)
                cdLabel.text = "until " + if (idx + 1 < sched.size) sched[idx + 1].name else "dismissal"
            }
            sched.isNotEmpty() && nowMin < sched.first().startMin -> {
                nameEl.text = "Before school"
                timeEl.text = "First bell at ${to12h(sched.first().startMin)}"
                bar.progress = 0
                cd.text = formatSecs(((sched.first().startMin - nowMin) * 60).toInt())
                cdLabel.text = "until ${sched.first().name}"
            }
            else -> {
                nameEl.text = "School day is over"
                timeEl.text = "See you tomorrow"
                bar.progress = 1000
                cd.text = "—:—"
                cdLabel.text = ""
            }
        }

        val table = findViewById<LinearLayout>(R.id.scheduleTable)
        for (i in 0 until table.childCount) {
            val row = table.getChildAt(i) as LinearLayout
            val p = row.tag as? Period ?: continue
            row.setBackgroundColor(if (p == current) 0x1FC4914F else 0x00000000)
        }
    }
}
