package com.ascendant.bellcontrol

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class LevelSelectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_level_select)

        findViewById<Button>(R.id.elemButton).setOnClickListener { choose(Level.ELEMENTARY) }
        findViewById<Button>(R.id.mshsButton).setOnClickListener { choose(Level.MIDDLE_HIGH) }
        findViewById<Button>(R.id.allLevelsButton).setOnClickListener { choose(Level.ALL_LEVELS) }
    }

    private fun choose(level: Level) {
        Prefs.setLevel(this, level)

        val svcIntent = Intent(this, BellForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent)
        else startService(svcIntent)

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
