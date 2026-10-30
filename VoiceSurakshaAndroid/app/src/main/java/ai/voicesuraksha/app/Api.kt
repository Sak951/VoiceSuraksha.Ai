package ai.voicesuraksha.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read-only client: only fetches from the server. */
object Api {
    const val DEFAULT = "https://choking-justify-plot.ngrok-free.dev"
    fun base(c: Context) = c.getSharedPreferences("vs", 0).getString("url", DEFAULT)!!.trimEnd('/')
    fun save(c: Context, u: String) = c.getSharedPreferences("vs", 0).edit().putString("url", u.trim()).apply()

    fun fetch(c: Context, path: String, post: Boolean = false): JSONObject {
        val con = URL(base(c) + path).openConnection() as HttpURLConnection
        con.requestMethod = if (post) "POST" else "GET"
        con.connectTimeout = 3000; con.readTimeout = 3000
        con.setRequestProperty("ngrok-skip-browser-warning", "1")
        if (post) { con.doOutput = true; con.outputStream.close() }
        return JSONObject(con.inputStream.bufferedReader().readText())
    }
}
