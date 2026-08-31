package com.codex.aio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        val title = TextView(this).apply {
            text = "AIO"
            textSize = 42f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Local Server"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 28)
        }

        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 18)
        }

        val server = Button(this).apply {
            text = "SERVER"
            textSize = 20f
            setOnClickListener { openServer() }
        }

        val stop = Button(this).apply {
            text = "STOP SERVER"
            setOnClickListener {
                stopService(Intent(this@MainActivity, LocalStackService::class.java))
                postDelayedRefresh()
            }
        }

        val hint = TextView(this).apply {
            text = "SERVER par tap karte hi AIO configuration page browser mein khulega."
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(12, 28, 12, 0)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(42, 90, 42, 42)
            addView(title, fullWidth())
            addView(subtitle, fullWidth())
            addView(status, fullWidth())
            addView(server, fullWidth())
            addView(stop, fullWidth(12))
            addView(hint, fullWidth())
        }

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun openServer() {
        if (!LocalStackService.running) {
            ContextCompat.startForegroundService(this, Intent(this, LocalStackService::class.java))
        }
        status.text = "Starting server…"
        status.postDelayed({
            refresh()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:3001/stremio/configure")))
        }, 650)
    }

    private fun refresh() {
        status.text = when {
            LocalStackService.running -> "● Server running · 127.0.0.1:3001"
            !LocalStackService.lastError.isNullOrBlank() -> "○ Server stopped · ${LocalStackService.lastError}"
            else -> "○ Server stopped"
        }
    }

    private fun postDelayedRefresh() {
        status.postDelayed({ refresh() }, 500)
        status.postDelayed({ refresh() }, 1200)
    }

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }
}
