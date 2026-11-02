package com.example.hiddenscanner

import android.app.Activity
import android.content.pm.PackageManager
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
        if (!ready()) return
        out.text = "Searching... (about 20 seconds)"
        thread {
            val sb = StringBuilder()
            val pat = "'telegram|zarchiver|shizuku|godx|god x'"
            val cmds = listOf(
                "dumpsys package providers | grep -i -E 'launcher|bbk|hideapp|privacy' | head -n 12",
                "content query --uri content://com.bbk.launcher2.settings/favorites 2>&1 | grep -i -E $pat | head -n 6",
                "content query --uri content://com.bbk.launcher2.settings/favorites 2>&1 | head -n 3 | cut -c1-200",
                "content query --uri content://com.bbk.launcher2.hideapps/apps 2>&1 | head -n 3 | cut -c1-200",
                "content query --uri content://com.vivo.launcher.hideapps 2>&1 | head -n 3 | cut -c1-200",
                "content query --uri content://com.android.launcher3.hideapps 2>&1 | head -n 3 | cut -c1-200",
                "ls /data/user/0/com.android.launcher3/databases 2>&1 | head -n 8",
                "ls /data/user_de/0/com.android.launcher3/databases 2>&1 | head -n 8",
                "pm list packages | grep -i -E 'launcher|hideapp|bbk' | head -n 12"
            )
            for (c in cmds) {
                sb.append("> ").append(c.take(75)).append("\n")
                try {
                    sb.append(sh(c).take(700).ifBlank { "(nothing)" }).append("\n\n")
                } catch (e: Throwable) {
                    sb.append("error: ").append(e.message).append("\n\n")
                }
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
