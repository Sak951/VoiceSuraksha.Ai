package ai.voicesuraksha.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var s1: TextView; private lateinit var s2: TextView; private lateinit var url: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(t: String, primary: Boolean, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
        setBackgroundColor(if (primary) 0xFF6EA8FF.toInt() else 0xFF1A2140.toInt()); setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) }
    }
    private fun tv(t: String, sz: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(c); if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF07090F.toInt()); setPadding(dp(24), dp(56), dp(24), dp(24)) }
        l.addView(tv("🛡  VoiceSuraksha.Ai", 26f, Color.WHITE, true))
        l.addView(tv("Live AI-voice protection for every call", 14f, 0xFF7D88A8.toInt()).apply { setPadding(0, dp(4), 0, dp(24)) })
        s1 = tv("", 15f, Color.WHITE); s2 = tv("", 15f, Color.WHITE)
        l.addView(s1); l.addView(s2.apply { setPadding(0, dp(6), 0, dp(18)) })
        l.addView(tv("Server", 12f, 0xFF7D88A8.toInt()))
        url = EditText(this).apply { setText(Api.base(this@MainActivity)); setTextColor(Color.WHITE); textSize = 14f; setSingleLine() }
        l.addView(url)
        l.addView(btn("Save server", false) { Api.save(this, url.text.toString()); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() })
        l.addView(btn("Check server connection", false) {
            Thread {
                val r = try { "✅ Connected · verdict: " + Api.fetch(this, "/api/v1/live", true).optString("verdict", "?") }
                catch (e: Exception) { "❌ " + e.javaClass.simpleName + ": " + e.message }
                runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
            }.start() })
        l.addView(btn("1 · Allow display over other apps", true) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        l.addView(btn("2 · Allow phone state", true) {
            val p = mutableListOf(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(p.toTypedArray(), 1) })
        l.addView(btn("Test overlay (10 sec)", false) {
            startForegroundService(Intent(this, OverlayService::class.java).putExtra("test", true))
            Handler(Looper.getMainLooper()).postDelayed({ stopService(Intent(this, OverlayService::class.java)) }, 10000) })
        setContentView(ScrollView(this).apply { addView(l); setBackgroundColor(0xFF07090F.toInt()) })
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        val ph = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        s1.text = (if (ov) "✅" else "❌") + "  Display over other apps"
        s2.text = (if (ph) "✅" else "❌") + "  Phone state access"
    }
}