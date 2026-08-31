package com.codex.aio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        val title = TextView(this).apply {
            text = "AIO"
            textSize = 36f
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Local Android Stack"
            textSize = 17f
        }
        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, 32, 0, 18)
        }
        toggle = Button(this).apply {
            setOnClickListener {
                if (LocalStackService.running) stopService(Intent(this@MainActivity, LocalStackService::class.java))
                else ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, LocalStackService::class.java))
                postDelayedRefresh()
            }
        }

        val configure = button("OPEN AIO CONFIGURATION") { open("http://127.0.0.1:3001/stremio/configure") }
        val bridge = button("OPEN BRIDGE · 8080") { open("http://127.0.0.1:8080/") }
        val plugins = button("OPEN PLUGIN MANAGER · 8091") { open("http://127.0.0.1:8091/") }
        val manifest = button("OPEN LOCAL MANIFEST") { open("http://127.0.0.1:3001/manifest.json") }

        val info = TextView(this).apply {
            text = "AIO Server  : 127.0.0.1:3001\nBridge      : 127.0.0.1:8080\nPlugins     : 127.0.0.1:8091\n\nThe local server can run without Oracle VPS. Add compatible Stremio manifest URLs from the browser configuration page; AIO combines their stream results locally."
            textSize = 14f
            setPadding(0, 28, 0, 20)
            movementMethod = LinkMovementMethod.getInstance()
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 70, 42, 42)
            addView(title)
            addView(subtitle)
            addView(status)
            addView(toggle, fullWidth())
            addView(configure, fullWidth())
            addView(bridge, fullWidth())
            addView(plugins, fullWidth())
            addView(manifest, fullWidth())
            addView(info)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val err = LocalStackService.lastError
        if (LocalStackService.running) {
            status.text = "● RUNNING\n3001 · 8080 · 8091"
            toggle.text = "STOP LOCAL AIO"
        } else {
            status.text = if (err.isNullOrBlank()) "○ STOPPED" else "○ STOPPED\nError: $err"
            toggle.text = "START LOCAL AIO"
        }
    }

    private fun postDelayedRefresh() {
        status.postDelayed({ refresh() }, 700)
        status.postDelayed({ refresh() }, 1800)
    }

    private fun button(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue
        setOnClickListener { action() }
    }

    private fun open(url: String) {
        if (!LocalStackService.running) {
            ContextCompat.startForegroundService(this, Intent(this, LocalStackService::class.java))
        }
        status.postDelayed({
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }, 450)
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = 10
    }
}
