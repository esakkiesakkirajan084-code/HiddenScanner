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

    @Suppress("DEPRECATION")
    private fun probe() {
        out.text = "Searching system apps... (about 20 seconds)"
        thread {
            val sb = StringBuilder()
            val pat = Regex("hide|hidden|privacy|applock|app_lock|vault|secure", RegexOption.IGNORE_CASE)
            val pkgPat = Regex("vivo|bbk|iqoo|launcher|settings|safecenter|permission|privacy", RegexOption.IGNORE_CASE)
            val names = try {
                packageManager.getInstalledApplications(0).map { it.packageName }.filter { pkgPat.containsMatchIn(it) }
            } catch (e: Throwable) {
                sb.append("list failed: ").append(e.message).append("\n")
                emptyList()
            }
            sb.append("Packages checked: ").append(names.size).append("\n\n")
            val tried = HashSet<String>()
            sb.append("SCREENS (activities with hide/privacy names):\n")
            var screens = 0
            for (n in names) {
                try {
                    val pi = packageManager.getPackageInfo(n, PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS)
                    pi.activities?.forEach { a ->
                        if (pat.containsMatchIn(a.name) && screens < 14) {
                            sb.append(" ").append(n).append("/").append(a.name.substringAfterLast('.')).append(" exp=").append(a.exported).append("\n")
                            screens++
                        }
                    }
                } catch (e: Throwable) { }
            }
            if (screens == 0) sb.append(" (none)\n")
            sb.append("\nPROVIDERS (hide/privacy names, or launcher):\n")
            var provs = 0
            for (n in names) {
                try {
                    val pi = packageManager.getPackageInfo(n, PackageManager.GET_PROVIDERS)
                    pi.providers?.forEach { p ->
                        val auth = (p.authority ?: "").split(";").first()
                        val interesting = pat.containsMatchIn(auth) || pat.containsMatchIn(p.name) ||
                            n == "com.android.launcher3"
                        if (interesting && auth.isNotEmpty() && tried.add(auth) && provs < 14) {
                            provs++
                            var result: String
                            try {
                                val c = contentResolver.query(Uri.parse("content://$auth"), null, null, null, null)
                                result = if (c == null) "null" else "OK rows=" + c.count + " cols=" + c.columnNames.joinToString(",").take(80)
                                c?.close()
                            } catch (e: Throwable) {
                                result = e.javaClass.simpleName + ": " + (e.message ?: "").take(70)
                            }
                            sb.append(" ").append(auth).append("\n   exp=").append(p.exported)
                                .append(" read=").append(p.readPermission).append("\n   ").append(result).append("\n")
                        }
                    }
                } catch (e: Throwable) { }
            }
            if (provs == 0) sb.append(" (none)\n")
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
