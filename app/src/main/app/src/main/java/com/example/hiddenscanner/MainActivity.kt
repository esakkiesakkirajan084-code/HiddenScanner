package com.example.hiddenscanner

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.*
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val old = prefs.getString("last", null)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            prefs.edit().putString("last", Log.getStackTraceString(e)).commit()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        try {
            buildUi()
            if (old != null) {
                out.text = "LAST CRASH (send me a screenshot):\n\n" + old.take(1500)
                prefs.edit().remove("last").commit()
            }
        } catch (e: Throwable) {
            val t = TextView(this)
            t.text = "ERROR:\n" + Log.getStackTraceString(e).take(1500)
            setContentView(t)
        }
    }

    private fun buildUi() {
        val btn = Button(this)
        btn.text = "Scan"
        btn.setOnClickListener { scan() }
        out = TextView(this)
        out.setPadding(24, 24, 24, 24)
        val sv = ScrollView(this)
        sv.addView(out)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(btn)
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
        p.waitFor()
        return text
    }

    private fun pkgs(cmd: String): Set<String> = sh(cmd).lines()
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }.toSet()

    private fun scan() {
        try {
            if (!Shizuku.pingBinder()) {
                out.text = "Start Shizuku first (wireless debugging)."
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1)
                out.text = "Allow Shizuku permission, then tap Scan again."
                return
            }
        } catch (e: Throwable) {
            out.text = "Shizuku error:\n" + Log.getStackTraceString(e).take(1500)
            return
        }
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
                    if (id != "0") {
                        sb.append("\nAll apps in this profile:\n")
                        third.sorted().forEach { sb.append("  - $it\n") }
                    }
                    sb.append("\n")
                }
            } catch (e: Throwable) {
                sb.append("Scan error:\n" + Log.getStackTraceString(e).take(1500))
            }
            runOnUiThread { out.text = sb.toString() }
        }
    }
}
