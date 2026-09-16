package com.ascendant.bellcontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
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

    private data class Panel(
        val level: Level,
        val periodName: TextView,
        val periodTime: TextView,
        val progressBar: ProgressBar,
        val countdownText: TextView,
        val countdownLabel: TextView,
        val table: LinearLayout,
        var schedule: List<Period> = emptyList()
    )

    private val panels = mutableListOf<Panel>()

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

        buildPanels()

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
        if (chosen != level || panels.isEmpty()) {
            level = chosen
            buildPanels()
        }
        cachedDay = -1
        RemoteConfig.syncNow(this) // pick up any sheet edits made since last open
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun buildPanels() {
        val container = findViewById<LinearLayout>(R.id.panelsContainer)
        container.removeAllViews()
        panels.clear()

        val levelsToShow =
            if (level == Level.ALL_LEVELS) listOf(Level.ELEMENTARY, Level.MIDDLE_HIGH) else listOf(level)
        levelsToShow.forEach { lvl -> panels.add(buildPanel(container, lvl)) }
    }

    private fun buildPanel(container: LinearLayout, lvl: Level): Panel {
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setPadding(0, 0, 0, 16)

        val label = TextView(this)
        label.text = if (lvl == Level.ELEMENTARY) "Elementary" else "Middle & High School"
        label.setTextColor(0xFFC4914F.toInt())
        label.textSize = 13f
        label.setTypeface(null, Typeface.BOLD)
        outer.addView(label)

        val hero = LinearLayout(this)
        hero.orientation = LinearLayout.VERTICAL
        hero.setBackgroundColor(0xFF1A2E27.toInt())
        hero.setPadding(36, 36, 36, 36)
        val heroParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        heroParams.topMargin = 8
        heroParams.bottomMargin = 16
        hero.layoutParams = heroParams

        val periodName = TextView(this)
        periodName.text = "Loading…"
        periodName.setTextColor(0xFFECE3D2.toInt())
        periodName.textSize = 24f
        hero.addView(periodName)

        val periodTime = TextView(this)
        periodTime.setTextColor(0xFF9FAE9C.toInt())
        periodTime.textSize = 13f
        val ptParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        ptParams.bottomMargin = 20
        periodTime.layoutParams = ptParams
        hero.addView(periodTime)

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 1000
        progressBar.progressTintList = ColorStateList.valueOf(0xFFC4914F.toInt())
        val pbParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12)
        pbParams.bottomMargin = 16
        progressBar.layoutParams = pbParams
        hero.addView(progressBar)

        val cdRow = LinearLayout(this)
        cdRow.orientation = LinearLayout.HORIZONTAL
        cdRow.gravity = Gravity.BOTTOM

        val countdownText = TextView(this)
        countdownText.text = "--:--"
        countdownText.setTextColor(0xFFC4914F.toInt())
        countdownText.textSize = 30f
        cdRow.addView(countdownText)

        val countdownLabel = TextView(this)
        countdownLabel.setTextColor(0xFF9FAE9C.toInt())
        countdownLabel.textSize = 12f
        val clParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        clParams.marginStart = 10
        countdownLabel.layoutParams = clParams
        cdRow.addView(countdownLabel)

        hero.addView(cdRow)
        outer.addView(hero)

        val table = LinearLayout(this)
        table.orientation = LinearLayout.VERTICAL
        table.setBackgroundColor(0xFF1A2E27.toInt())
        outer.addView(table)

        container.addView(outer)

        return Panel(lvl, periodName, periodTime, progressBar, countdownText, countdownLabel, table)
    }

    private fun renderTable(panel: Panel) {
        panel.table.removeAllViews()
        panel.schedule.forEach { p ->
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
            panel.table.addView(row)
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
        val now = Calendar.getInstance()
        val day = now.get(Calendar.DAY_OF_YEAR)
        if (day != cachedDay) {
            panels.forEach { it.schedule = RemoteConfig.getSchedule(this, it.level) }
            cachedDay = day
            panels.forEach { renderTable(it) }
        }

        findViewById<TextView>(R.id.clockText).text = String.format(
            Locale.US, "%02d:%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND)
        )
        findViewById<TextView>(R.id.dateLabel).text =
            SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now.time)

        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 +
                now.get(Calendar.MINUTE) +
                now.get(Calendar.SECOND) / 60.0

        panels.forEach { panel -> updatePanel(panel, nowMin) }
    }

    private fun updatePanel(panel: Panel, nowMin: Double) {
        val sched = panel.schedule
        val current = sched.find { nowMin >= it.startMin && nowMin < it.endMin }

        when {
            current != null -> {
                panel.periodName.text = current.name
                panel.periodTime.text = "${to12h(current.startMin)} – ${to12h(current.endMin)}"
                val total = (current.endMin - current.startMin).toDouble()
                val done = nowMin - current.startMin
                panel.progressBar.progress = ((done / total) * 1000).toInt().coerceIn(0, 1000)
                val secsLeft = ((current.endMin - nowMin) * 60).toInt()
                panel.countdownText.text = formatSecs(secsLeft)
                val idx = sched.indexOf(current)
                panel.countdownLabel.text = "until " + if (idx + 1 < sched.size) sched[idx + 1].name else "dismissal"
            }
            sched.isNotEmpty() && nowMin < sched.first().startMin -> {
                panel.periodName.text = "Before school"
                panel.periodTime.text = "First bell at ${to12h(sched.first().startMin)}"
                panel.progressBar.progress = 0
                panel.countdownText.text = formatSecs(((sched.first().startMin - nowMin) * 60).toInt())
                panel.countdownLabel.text = "until ${sched.first().name}"
            }
            sched.isEmpty() -> {
                panel.periodName.text = "No schedule synced yet"
                panel.periodTime.text = ""
                panel.progressBar.progress = 0
                panel.countdownText.text = "—:—"
                panel.countdownLabel.text = ""
            }
            else -> {
                panel.periodName.text = "School day is over"
                panel.periodTime.text = "See you tomorrow"
                panel.progressBar.progress = 1000
                panel.countdownText.text = "—:—"
                panel.countdownLabel.text = ""
            }
        }

        for (i in 0 until panel.table.childCount) {
            val row = panel.table.getChildAt(i) as LinearLayout
            val p = row.tag as? Period ?: continue
            row.setBackgroundColor(if (p == current) 0x1FC4914F else 0x00000000)
        }
    }
}
