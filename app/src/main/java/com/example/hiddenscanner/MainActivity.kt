package com.example.hiddenscanner

import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val btn = Button(this)
        btn.text = "Scan profiles and no-icon apps"
        btn.setOnClickListener { scan() }
        val btn2 = Button(this)
        btn2.text = "Find brand hidden-apps list"
        btn2.setOnClickListener { probe() }
        out = TextView(this)
        out.setPadding(24, 24, 24, 24)
        out.setTextIsSelectable(true)
        out.text = "Tap a button. Shizuku must be running."
        val sv = ScrollView(this)
        sv.addView(out)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(btn)
        root.addView(btn2)
        root.addView(sv)
        setContentView(root)
    }

    private fun sh(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val text = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        return text + (if (err.isNotBlank()) "[err] " + err.take(120) else "")
    }

    private fun pkgs(cmd: String): Set<String> = sh(cmd).lines()
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }.toSet()

    private fun ready(): Boolean {
        try {
            if (!Shizuku.pingBinder()) {
                out.text = "Start Shizuku first (wireless debugging)."
                return false
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1)
                out.text = "Allow Shizuku permission, then tap again."
                return false
            }
        } catch (e: Throwable) {
            out.text = "Shizuku error:\n" + Log.getStackTraceString(e).take(1500)
            return false
        }
        return true
    }

    private fun probe() {
        out.text = "Testing... (about 15 seconds)"
        thread {
            val sb = StringBuilder()
            val canShell = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
            if (canShell) {
                try {
                    sb.append("PERMISSION INFO:\n")
                    sb.append(sh("dumpsys package com.android.launcher3 | grep -i -A3 'Permission \\[com.bbk' | head -n 16").take(1200).ifBlank { "(nothing)" })
                    sb.append("\n\n")
                } catch (e: Throwable) {
                    sb.append("perm info error: ").append(e.message).append("\n\n")
                }
            }
            sb.append("APP GRANTED?\n")
            for (p in listOf("com.bbk.launcher2.permission.READ_SETTINGS", "com.android.launcher.permission.READ_SETTINGS")) {
                val g = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
                sb.append(p).append(" = ").append(g).append("\n")
            }
            sb.append("\nDIRECT READ OF LAUNCHER LIST:\n")
            try {
                val c = contentResolver.query(
                    Uri.parse("content://com.bbk.launcher2.settings/favorites"), null, null, null, null
                )
                if (c == null) {
                    sb.append("cursor is null\n")
                } else {
                    sb.append("columns: ").append(c.columnNames.joinToString(",")).append("\n")
                    sb.append("rows: ").append(c.count).append("\n\n")
                    val pat = Regex("telegram|zarchiver|shizuku|godx|god x|mods", RegexOption.IGNORE_CASE)
                    var shown = 0
                    var first = true
                    while (c.moveToNext()) {
                        val row = (0 until c.columnCount).joinToString("|") {
                            try { c.getString(it) ?: "" } catch (e: Throwable) { "?" }
                        }
                        if (first) { sb.append("first row: ").append(row.take(300)).append("\n\n"); first = false }
                        if (pat.containsMatchIn(row) && shown < 8) {
                            sb.append("MATCH: ").append(row.take(300)).append("\n\n")
                            shown++
                        }
                    }
                    sb.append("matches shown: ").append(shown).append("\n")
                    c.close()
                }
            } catch (e: Throwable) {
                sb.append("FAILED: ").append(e.javaClass.simpleName).append("\n")
                sb.append((e.message ?: "").take(500)).append("\n")
            }
            runOnUiThread { out.text = sb.toString() }
        }
    }

    private fun scan() {
        if (!ready()) return
        out.text = "Scanning..."
        thread {
            val sb = StringBuilder()
            try {
                val users = Regex("""UserInfo\{(\d+):([^:]*):""").findAll(sh("pm list users"))
                    .map { it.groupValues[1] to it.groupValues[2] }.toList()
                for ((id, name) in users) {
                    val third = pkgs("pm list packages -3 --user $id")
                    val disabled = pkgs("pm list packages -d --user $id")
                    val launcher = sh(
                        "cmd package query-activities --brief " +
                        "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER --user $id"
                    ).lines().filter { it.contains("/") }
                        .map { it.trim().substringBefore("/") }.toSet()
                    val noIcon = third - launcher
                    sb.append("=== User $id ($name) ===\n")
                    sb.append("Third-party apps: ${third.size}\n")
                    sb.append("\nNO LAUNCHER ICON (${noIcon.size}):\n")
                    noIcon.sorted().forEach { sb.append("  - $it\n") }
                    sb.append("\nDISABLED (${disabled.size}):\n")
                    disabled.sorted().forEach { sb.append("  - $it\n") }
                    sb.append("\n")
                }
            } catch (e: Throwable) {
                sb.append("Scan error:\n" + Log.getStackTraceString(e).take(1500))
            }
            runOnUiThread { out.text = sb.toString() }
        }
    }
}
