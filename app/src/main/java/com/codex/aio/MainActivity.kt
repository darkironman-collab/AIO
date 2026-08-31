package com.codex.aio

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var openPending = false
    private var waitCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "AIOStreams"
            textSize = 38f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Original GitHub AIO · Local Android Server"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 32)
        }

        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        val server = Button(this).apply {
            text = "SERVER"
            textSize = 21f
            setOnClickListener { startAndOpenServer() }
        }

        val stop = Button(this).apply {
            text = "STOP SERVER"
            setOnClickListener {
                openPending = false
                stopService(Intent(this@MainActivity, LocalStackService::class.java))
                handler.postDelayed({ refresh() }, 700)
            }
        }

        val hint = TextView(this).apply {
            text = "SERVER par tap karo. Pehli baar bundled AIOStreams runtime extract hoga; ready hote hi original /stremio/configure page browser mein khul jayega."
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(12, 30, 12, 0)
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startAndOpenServer() {
        if (LocalStackService.ready) {
            openBrowser()
            return
        }
        if (!LocalStackService.running) {
            ContextCompat.startForegroundService(this, Intent(this, LocalStackService::class.java))
        }
        openPending = true
        waitCount = 0
        status.text = "Starting original AIOStreams…"
        waitUntilReady()
    }

    private fun waitUntilReady() {
        if (!openPending) return
        refresh()
        when {
            LocalStackService.ready -> {
                openPending = false
                openBrowser()
            }
            !LocalStackService.running && !LocalStackService.lastError.isNullOrBlank() -> {
                openPending = false
                refresh()
            }
            waitCount >= 180 -> {
                openPending = false
                status.text = "Server start timeout. STOP SERVER karke retry karo."
            }
            else -> {
                waitCount++
                handler.postDelayed({ waitUntilReady() }, 1000)
            }
        }
    }

    private fun openBrowser() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LocalStackService.CONFIG_URL)))
    }

    private fun refresh() {
        status.text = when {
            LocalStackService.ready -> "● AIOStreams ready · 127.0.0.1:3001"
            LocalStackService.running -> "● ${LocalStackService.statusMessage}"
            !LocalStackService.lastError.isNullOrBlank() -> "○ ${LocalStackService.lastError}"
            else -> "○ Server stopped"
        }
    }

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }
}
