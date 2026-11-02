package com.example.hiddenscanner

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val t = TextView(this)
        t.text = "Hello - the app opened!"
        t.textSize = 20f
        t.setPadding(40, 120, 40, 40)
        val btn = Button(this)
        btn.text = "Test Shizuku"
        btn.setOnClickListener {
            try {
                val ok = rikka.shizuku.Shizuku.pingBinder()
                t.text = "Shizuku running: $ok"
            } catch (e: Throwable) {
                t.text = "Shizuku error:\n" + Log.getStackTraceString(e).take(1200)
            }
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(t)
        root.addView(btn)
        setContentView(root)
    }
}
