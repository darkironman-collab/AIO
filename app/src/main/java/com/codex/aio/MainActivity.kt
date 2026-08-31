package com.codex.aio

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "Local Bridge: STOPPED\n127.0.0.1:3000"
            textSize = 20f
        }
        val toggle = Button(this).apply { text = "START LOCAL BRIDGE" }
        toggle.setOnClickListener {
            running = !running
            status.text = if (running) "Local Bridge: RUNNING\n127.0.0.1:3000" else "Local Bridge: STOPPED\n127.0.0.1:3000"
            toggle.text = if (running) "STOP LOCAL BRIDGE" else "START LOCAL BRIDGE"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(TextView(this@MainActivity).apply { text = "AIO Bridge"; textSize = 32f })
            addView(status)
            addView(toggle)
        }
        setContentView(root)
    }
}
