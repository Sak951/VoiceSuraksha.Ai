package ai.voicesuraksha.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.*
import java.util.concurrent.Executors

/**
 * Shows live AI/Human verdict (fetched from server).
 * - ringing  : "Incoming call" (no detection yet)
 * - answered : detection starts, overlay + lock-screen notification update live
 * - idle     : stops itself (call cut from ANY side)
 */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var nm: NotificationManager
    private lateinit var tm: TelephonyManager
    private var root: FrameLayout? = null
    private lateinit var tagTv: TextView; private lateinit var title: TextView
    private lateinit var sub: TextView; private lateinit var bar: ProgressBar
    private lateinit var bg: GradientDrawable
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var running = false; private var test = false
    private var p = 50.0; private var tAns = 0L; private var answered = false
    private var lastNotif = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.getBooleanExtra("test", false) == true) test = true
        if (root != null) return START_NOT_STICKY
        nm = getSystemService(NotificationManager::class.java)
        tm = getSystemService(TelephonyManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vs2", "Live call verdict", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        val n = notif("VoiceSuraksha.Ai", "Verifying this call…", 0xFF6EA8FF.toInt())
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (Settings.canDrawOverlays(this)) build()
        running = true; tick()
        return START_NOT_STICKY
    }

    private fun notif(t: String, text: String, col: Int): Notification =
        Notification.Builder(this, "vs2").setContentTitle(t).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock).setColor(col)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS).setShowWhen(false).build()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bg = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xF00E1220.toInt()); setStroke(dp(2), 0xFF6EA8FF.toInt()) }
        tagTv = TextView(this).apply { text = "● VOICESURAKSHA.AI · LIVE"; textSize = 11f; setTextColor(0xFF7D88A8.toInt()); letterSpacing = 0.1f }
        title = TextView(this).apply { text = "Incoming call"; textSize = 24f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, dp(6), 0, 0) }
        sub = TextView(this).apply { textSize = 13f; setTextColor(0xFF9AA5C4.toInt()); setPadding(0, dp(2), 0, dp(10)) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 50 }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(18)); background = bg
            addView(tagTv); addView(title); addView(sub); addView(bar)
        }
        root = FrameLayout(this).apply { setPadding(dp(12), 0, dp(12), 0); addView(card) }
        val lp = LP(LP.MATCH_PARENT, LP.WRAP_CONTENT, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCH_MODAL or LP.FLAG_NOT_TOUCHABLE or LP.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP; y = (resources.displayMetrics.heightPixels * 0.30).toInt() }
        wm.addView(root, lp)
    }

    private fun next() { if (running) ui.postDelayed({ tick() }, 700) }

    @Suppress("DEPRECATION")
    private fun tick() {
        if (!running) return
        val st = if (test) TelephonyManager.CALL_STATE_OFFHOOK
        else try { tm.callState } catch (e: Exception) { TelephonyManager.CALL_STATE_OFFHOOK }
        when (st) {
            TelephonyManager.CALL_STATE_IDLE -> { stopSelf(); return }          // cut from any side
            TelephonyManager.CALL_STATE_RINGING -> {
                answered = false
                show("📞 Incoming call", "Verification starts when you answer", 0xFF6EA8FF.toInt(), 50.0)
                next(); return
            }
        }
        if (!answered) { answered = true; tAns = System.currentTimeMillis(); p = 50.0 }
        io.execute {
            var live: org.json.JSONObject? = null; var call: org.json.JSONObject? = null; var err = ""
            try { live = Api.fetch(this, "/api/v1/live", true) } catch (e: Exception) { err = e.javaClass.simpleName + ": " + (e.message ?: "") }
            try { call = Api.fetch(this, "/api/v1/call") } catch (_: Exception) {}
            ui.post { if (running) { try { render(live, call, err) } catch (_: Exception) {}; next() } }
        }
    }

    private fun show(t: String, s: String, col: Int, prog: Double) {
        if (root != null) {
            title.text = t; title.setTextColor(if (col == 0xFF6EA8FF.toInt()) Color.WHITE else col)
            sub.text = s; bar.progress = prog.toInt(); bar.progressTintList = ColorStateList.valueOf(col); bg.setStroke(dp(2), col)
        }
        val key = "$t|$s"
        if (key != lastNotif) { lastNotif = key; nm.notify(1, notif(t, s, col)) }
    }

    private fun render(live: org.json.JSONObject?, call: org.json.JSONObject?, err: String) {
        val who = call?.let { "${it.optString("name")} · ${it.optString("number")}" } ?: "Caller"
        if (live == null) { show("⚠ Can't reach server", err.take(70), 0xFFFACC15.toInt(), 50.0); return }
        p = p * 0.5 + live.getDouble("ai_prob") * 0.5
        if (System.currentTimeMillis() - tAns < 2500) { show("Analyzing voice…", who, 0xFF6EA8FF.toInt(), 50.0); return }
        val ai = p >= 50; val col = if (ai) 0xFFFB5A7A.toInt() else 0xFF34D399.toInt()
        show(if (ai) "⚠ AI-GENERATED VOICE" else "✓ HUMAN VOICE",
            "$who · ${"%.0f".format(if (ai) p else 100 - p)}% confidence", col, p)
    }

    override fun onDestroy() {
        running = false; ui.removeCallbacksAndMessages(null); io.shutdownNow()
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }; root = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}