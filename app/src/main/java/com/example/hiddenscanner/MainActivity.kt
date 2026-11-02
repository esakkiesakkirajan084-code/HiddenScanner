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
        return text + (if (err.isNotBlank()) "[err] " + err.take(150) else "")
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
        out.text = "Searching..."
        thread {
            val sb = StringBuilder()
            val kw = "'hide|hidden|privacy|lock_app|applock'"
            val launcher = sh(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.HOME"
            ).lines().lastOrNull { it.contains("/") }?.trim()?.substringBefore("/") ?: ""
            sb.append("Phone: ").append(sh("getprop ro.product.manufacturer").trim())
                .append(" ").append(sh("getprop ro.product.model").trim()).append("\n")
            sb.append("Launcher: ").append(launcher).append("\n\n")
            val cmds = listOf(
                "settings list secure | grep -i -E $kw | head -n 12",
                "settings list system | grep -i -E $kw | head -n 12",
                "settings list global | grep -i -E $kw | head -n 12",
                "dumpsys activity service $launcher 2>&1 | grep -i -E $kw | head -n 12",
                "dumpsys package $launcher 2>&1 | grep -i -E 'hide|hidden' | head -n 8",
                "content query --uri content://$launcher.settings/favorites 2>&1 | head -n 4",
                "content query --uri content://$launcher.settings/hide_apps 2>&1 | head -n 6",
                "content query --uri content://$launcher.settings/hidden_apps 2>&1 | head -n 6",
                "pm list packages | grep -i -E 'applock|privacy|hide|vault' | head -n 10"
            )
            for (c in cmds) {
                sb.append("> ").append(c.take(70)).append("\n")
                try {
                    sb.append(sh(c).take(800).ifBlank { "(nothing)" }).append("\n\n")
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
