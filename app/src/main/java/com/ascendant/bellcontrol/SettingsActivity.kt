package com.ascendant.bellcontrol

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val level = Prefs.getLevel(this)
        findViewById<TextView>(R.id.currentLevelText).text = "This board: " + when (level) {
            Level.ELEMENTARY -> "Elementary"
            Level.MIDDLE_HIGH -> "Middle & High School"
            Level.ALL_LEVELS -> "All Levels (combined display)"
            null -> "Not set"
        }

        val syncLabel = findViewById<TextView>(R.id.lastSyncLabel)
        refreshSyncLabel(syncLabel)

        findViewById<Button>(R.id.syncNowButton).setOnClickListener {
            syncLabel.text = "Syncing…"
            RemoteConfig.syncNow(this) { success, message ->
                runOnUiThread {
                    syncLabel.text = if (success) "Synced just now" else "Sync failed: $message"
                }
            }
        }

        findViewById<Button>(R.id.changeLevelButton).setOnClickListener {
            Prefs.clearLevel(this)
            finish()
        }
    }

    private fun refreshSyncLabel(view: TextView) {
        val millis = RemoteConfig.lastSyncMillis(this)
        view.text = if (millis < 0) "Never synced yet — using the built-in default schedule"
        else "Last synced: " + SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(millis))
    }
}
