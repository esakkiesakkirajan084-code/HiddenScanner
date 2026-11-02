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
        out.text = "Reading database table list..."
        thread {
            val sb = StringBuilder()
            val base = "content://com.bbk.launcher2.settings/"
            try {
                val c = contentResolver.query(Uri.parse(base + "sqlite_master"), null, null, null, null)
                if (c == null) {
                    sb.append("sqlite_master: null\n")
                } else {
                    sb.append("TABLES FOUND (").append(c.count).append("):\n")
                    val cols = c.columnNames.toList()
                    val ni = cols.indexOf("name")
                    val ti = cols.indexOf("type")
                    while (c.moveToNext()) {
                        val name = if (ni >= 0) c.getString(ni) else "?"
                        val type = if (ti >= 0) c.getString(ti) else "?"
                        sb.append(" - ").append(name).append(" (").append(type).append(")\n")
                    }
                    c.close()
                }
            } catch (e: Throwable) {
                sb.append("sqlite_master FAILED: ").append(e.javaClass.simpleName).append("\n")
                sb.append((e.message ?: "").take(300)).append("\n")
            }
            sb.append("\nITEM TYPES IN favorites:\n")
            try {
                val c = contentResolver.query(
                    Uri.parse(base + "favorites"), arrayOf("itemType", "profileId", "title", "intent"),
                    null, null, null
                )
                if (c != null) {
                    val counts = HashMap<String, Int>()
                    while (c.moveToNext()) {
                        val k = "type=" + c.getString(0) + " profile=" + c.getString(1)
                        counts[k] = (counts[k] ?: 0) + 1
                    }
                    c.close()
                    counts.entries.sortedBy { it.key }.forEach {
                        sb.append(" ").append(it.key).append(" : ").append(it.value).append("\n")
                    }
                }
            } catch (e: Throwable) {
                sb.append("failed: ").append((e.message ?: "").take(200)).append("\n")
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
