package ai.voicesuraksha.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val svc = Intent(c, OverlayService::class.java)
        when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING, TelephonyManager.EXTRA_STATE_OFFHOOK -> c.startForegroundService(svc)
            TelephonyManager.EXTRA_STATE_IDLE -> c.stopService(svc)
        }
    }
}
