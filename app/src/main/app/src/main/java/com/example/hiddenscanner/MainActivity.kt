package com.example.hiddenscanner

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val btn = Button(this).apply { text = "Scan"; setOnClickListener { scan() } }
        out = TextView(this).apply { setPadding(24, 24, 24, 24); setTextIsSelectable(true) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btn)
            addView(ScrollView(this@MainActivity).apply { addView(out) })
        }
        setContentView(root)
    }

    private fun sh(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return text
    }

    private fun pkgs(cmd: String) = sh(cmd).lines()
        .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.toSet()

    private fun scan() {
        try {
            if (!Shizuku.pingBinder()) { out.text = "Start Shizuku first (wireless debugging)."; return }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1); out.text = "Allow Shizuku permission, then tap Scan again."; return
            }
        } catch (e: Exception) { out.text = "Shizuku not available: ${e.message}"; return }

        out.text = "Scanning..."
        thread {
            val sb = StringBuilder()
            val users = Regex("""UserInfo\{(\d+):([^:]*):""").findAll(sh("pm list users"))
                .map { it.groupValues[1] to it.groupValues[2] }.toList()
            for ((id, name) in users) {
                val third = pkgs("pm list packages -3 --user $id")
                val disabled = pkgs("pm list packages -d --user $id")
                val launcher = sh("cmd package query-activities --brief " +
                    "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER --user $id")
                    .lines().filter { it.contains("/") }.map { it.trim().substringBefore("/") }.toSet()
                val noIcon = third - launcher
                sb.append("=== User $id ($name) ===\n")
                sb.append("Third-party apps: ${third.size}\n")
                sb.append("\nNO LAUNCHER ICON (${noIcon.size}):\n")
                noIcon.sorted().forEach { sb.append("  • $it\n") }
                sb.append("\nDISABLED (${disabled.size}):\n")
                disabled.sorted().forEach { sb.append("  • $it\n") }
                if (id != "0") {
                    sb.append("\nAll apps in this profile:\n")
                    third.sorted().forEach { sb.append("  • $it\n") }
                }
                sb.append("\n")
            }
            runOnUiThread { out.text = sb.toString() }
        }
    }
}
