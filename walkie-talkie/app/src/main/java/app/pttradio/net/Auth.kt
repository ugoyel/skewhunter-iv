package app.pttradio.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Optional sign-in with a one-time code sent by e-mail, backed by a (free tier) Supabase project.
 * Supabase sends the e-mail and keeps the list of users (e-mail, name, mobile number, marketing consent)
 * that the project owner can export. Disabled until a project URL and key are configured at build time.
 */
class Auth(url: String, private val key: String) {
    private val base = url.trim().trimEnd('/')
    val enabled = base.startsWith("https://") && key.isNotBlank()

    private fun post(path: String, body: JSONObject, done: (String?) -> Unit) {
        thread(isDaemon = true) {
            val err: String? = try {
                val c = URL("$base/auth/v1/$path").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15_000; c.readTimeout = 15_000
                c.doOutput = true
                c.setRequestProperty("apikey", key)
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code in 200..299) null else {
                    val text = try { c.errorStream?.bufferedReader()?.readText().orEmpty() } catch (e: Exception) { "" }
                    val msg = try { JSONObject(text).let { it.optString("msg").ifEmpty { it.optString("error_description").ifEmpty { it.optString("message") } } } } catch (e: Exception) { "" }
                    msg.ifEmpty { "Server said $code" }
                }
            } catch (e: Exception) { "No connection. Sign-in needs internet." }
            done(err)
        }
    }

    /** Asks the server to e-mail a code. [done] receives null on success or a message to show. */
    fun requestCode(email: String, name: String, phone: String, marketing: Boolean, done: (String?) -> Unit) =
        post("otp", JSONObject().put("email", email).put("create_user", true)
            .put("data", JSONObject().put("name", name).put("phone", phone).put("marketing_consent", marketing)), done)

    fun verify(email: String, code: String, done: (String?) -> Unit) =
        post("verify", JSONObject().put("type", "email").put("email", email).put("token", code), done)
}
