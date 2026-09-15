package com.ascendant.bellcontrol

import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var level: Level

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        level = Prefs.getLevel(this) ?: Level.ELEMENTARY

        findViewById<TextView>(R.id.currentLevelText).text =
            "This board: " + if (level == Level.ELEMENTARY) "Elementary" else "Middle & High School"

        val autoSwitch = findViewById<Switch>(R.id.dhuhrAutoSwitch)
        val manualButton = findViewById<Button>(R.id.setManualTimeButton)
        val manualLabel = findViewById<TextView>(R.id.manualTimeLabel)

        autoSwitch.isChecked = Prefs.isDhuhrAuto(this, level)
        manualButton.isEnabled = !autoSwitch.isChecked
        refreshManualLabel(manualLabel)

        autoSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            Prefs.setDhuhrAuto(this, level, checked)
            manualButton.isEnabled = !checked
            refreshManualLabel(manualLabel)
        }

        manualButton.setOnClickListener {
            val current = Prefs.getManualDhuhrMinutes(this, level) ?: PrayerTimes.dhuhrMinutesToday()
            TimePickerDialog(this, { _, h, m ->
                Prefs.setManualDhuhrMinutes(this, level, h * 60 + m)
                refreshManualLabel(manualLabel)
            }, current / 60, current % 60, false).show()
        }

        findViewById<Button>(R.id.changeLevelButton).setOnClickListener {
            Prefs.clearLevel(this)
            finish()
        }
    }

    private fun refreshManualLabel(view: TextView) {
        val auto = Prefs.isDhuhrAuto(this, level)
        val computed = PrayerTimes.dhuhrMinutesToday()
        view.text = if (auto) {
            "Auto (calculated for today): %02d:%02d".format(computed / 60, computed % 60)
        } else {
            val m = Prefs.getManualDhuhrMinutes(this, level) ?: computed
            "Manual: %02d:%02d".format(m / 60, m % 60)
        }
    }
}
