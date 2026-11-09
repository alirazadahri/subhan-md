package com.arcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One structured instruction produced from a spoken/typed sentence. */
data class AICommand(
    val action: String,
    val target: String? = null,
    val text: String? = null,
    val reply: String? = null,
)

/**
 * Turns natural language ("Hey AR, Ali ko call karo") into an [AICommand].
 * Uses any OpenAI-compatible chat API when an API key is configured; otherwise falls back
 * to the offline [LocalParser]. Blocking — call from a background thread.
 */
object AIBrain {

    val ACTIONS = listOf(
        "open_app", "float_app", "call", "send_sms", "whatsapp_message",
        "flashlight_on", "flashlight_off", "volume_up", "volume_down", "mute",
        "home", "back", "recents", "notifications", "quick_settings", "lock_screen",
        "screenshot", "split_screen", "open_settings", "web_search", "youtube_search",
        "set_alarm", "type_text", "click", "scroll_down", "scroll_up", "read_screen", "chat",
    )

    private val history = ArrayDeque<JSONObject>()
    private const val MAX_HISTORY = 8

    private fun systemPrompt(apps: List<String>) = """
You are "AR", a voice assistant that controls the user's Android phone.
The user may speak English, Urdu or Hindi (often Roman Urdu like "youtube kholo", "Ali ko call karo").
Convert each request into ONE JSON object and nothing else:
{"action": <one of ${ACTIONS.joinToString()}>, "target": <string|null>, "text": <string|null>, "reply": <short spoken reply>}

Rules:
- open_app / float_app: target = the exact app name from INSTALLED APPS. Use float_app when the user says float/floating/popup/chhota window.
- call: target = contact name or phone number.
- send_sms / whatsapp_message: target = contact name or number, text = message body.
- open_settings: target = one of wifi, bluetooth, location, display, sound, battery, apps, data, settings.
- web_search / youtube_search: text = search query.
- set_alarm: target = time in 24h "HH:MM".
- type_text: text = what to type. click: target = visible button/label text.
- chat: for questions or conversation; put the full answer in reply.
- reply must be short, friendly, in the same language/style the user used.

INSTALLED APPS: ${apps.joinToString(", ").take(3000)}
""".trimIndent()

    fun interpret(ctx: Context, utterance: String): AICommand {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) return LocalParser.parse(utterance)
        return try {
            askLlm(ctx, key, utterance)
        } catch (t: Throwable) {
            LocalParser.parse(utterance).let {
                if (it.action == "chat") it.copy(reply = "AI se connect nahi ho saka: ${t.message ?: "network error"}") else it
            }
        }
    }

    fun clearHistory() = synchronized(history) { history.clear() }

    private fun askLlm(ctx: Context, key: String, utterance: String): AICommand {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt(AppRepository.labels(ctx))))
        synchronized(history) { history.forEach { messages.put(it) } }
        val userMsg = JSONObject().put("role", "user").put("content", utterance)
        messages.put(userMsg)

        val body = JSONObject()
            .put("model", Prefs.model(ctx))
            .put("temperature", 0.2)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", messages)

        val conn = (URL("${Prefs.baseUrl(ctx)}/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${raw.take(120)}")

            val content = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            val json = JSONObject(extractJson(content))

            synchronized(history) {
                history.addLast(userMsg)
                history.addLast(JSONObject().put("role", "assistant").put("content", json.toString()))
                while (history.size > MAX_HISTORY) history.removeFirst()
            }

            return AICommand(
                action = json.optString("action", "chat").ifBlank { "chat" },
                target = json.optNullableString("target"),
                text = json.optNullableString("text"),
                reply = json.optNullableString("reply"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun extractJson(s: String): String {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) s.substring(start, end + 1) else "{\"action\":\"chat\",\"reply\":${JSONObject.quote(s)}}"
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}

/** Offline rule-based parser (English + Roman Urdu/Hindi). Used without an API key or when offline. */
object LocalParser {
    private val ACT = "(?:karo|kro|kar do|kardo|kr do|krdo|please|plz)?"

    fun parse(input: String): AICommand {
        val s = input.lowercase().trim().trimEnd('.', '!', '?')

        fun m(pattern: String) = Regex(pattern).find(s)

        m("^(?:float|floating|popup)\\s+(.+)$")?.let { return AICommand("float_app", it.groupValues[1]) }
        m("^(.+?)\\s+(?:float|floating|popup)\\s*(?:me|mein|main)?\\s*(?:kholo|open)?\\s*$ACT$")?.let {
            return AICommand("float_app", it.groupValues[1])
        }

        m("^(?:call|dial|phone)\\s+(?:to\\s+)?(.+)$")?.let { return AICommand("call", it.groupValues[1]) }
        m("^(.+?)\\s+(?:ko|par|pe)\\s+(?:call|phone|fone)\\s*(?:lagao|milao)?\\s*$ACT$")?.let {
            return AICommand("call", it.groupValues[1])
        }

        m("^(?:send\\s+)?(?:whatsapp)\\s+(?:to\\s+)?(\\S+)\\s+(?:that|saying|ke|k)\\s+(.+)$")?.let {
            return AICommand("whatsapp_message", it.groupValues[1], it.groupValues[2])
        }
        m("^(?:send\\s+)?(?:message|sms|msg)\\s+(?:to\\s+)?(\\S+)\\s+(?:that|saying|ke|k)\\s+(.+)$")?.let {
            return AICommand("send_sms", it.groupValues[1], it.groupValues[2])
        }

        when {
            Regex("(torch|flash ?light).*(on|chalao|jalao)|(on|turn on).*(torch|flash ?light)").containsMatchIn(s) ->
                return AICommand("flashlight_on", reply = "Torch on")
            Regex("(torch|flash ?light).*(off|band|bujhao)|(off|turn off).*(torch|flash ?light)").containsMatchIn(s) ->
                return AICommand("flashlight_off", reply = "Torch off")
            Regex("volume.*(up|barhao|zyada|increase)").containsMatchIn(s) -> return AICommand("volume_up")
            Regex("volume.*(down|kam|ghatao|decrease)").containsMatchIn(s) -> return AICommand("volume_down")
            Regex("\\b(mute|silent|chup)\\b").containsMatchIn(s) -> return AICommand("mute")
            Regex("\\b(go )?home\\b").containsMatchIn(s) -> return AICommand("home")
            Regex("^(go )?back$|wapas|peeche").containsMatchIn(s) -> return AICommand("back")
            Regex("\\brecents?\\b").containsMatchIn(s) -> return AICommand("recents")
            Regex("notification").containsMatchIn(s) -> return AICommand("notifications")
            Regex("quick settings").containsMatchIn(s) -> return AICommand("quick_settings")
            Regex("screenshot|screen shot").containsMatchIn(s) -> return AICommand("screenshot")
            Regex("\\block\\b").containsMatchIn(s) -> return AICommand("lock_screen")
            Regex("\\bsplit\\b").containsMatchIn(s) -> return AICommand("split_screen")
            Regex("scroll (down|neeche)").containsMatchIn(s) -> return AICommand("scroll_down")
            Regex("scroll (up|upar|oopar)").containsMatchIn(s) -> return AICommand("scroll_up")
            Regex("read (the )?screen|screen (par|pe) kya").containsMatchIn(s) -> return AICommand("read_screen")
            Regex("wi-?fi").containsMatchIn(s) -> return AICommand("open_settings", "wifi")
            Regex("bluetooth").containsMatchIn(s) -> return AICommand("open_settings", "bluetooth")
            Regex("location|gps").containsMatchIn(s) -> return AICommand("open_settings", "location")
        }

        m("^(?:type|likho)\\s+(.+)$")?.let { return AICommand("type_text", text = it.groupValues[1]) }
        m("^(?:click|tap|press|dabao)\\s+(?:on\\s+)?(.+)$")?.let { return AICommand("click", it.groupValues[1]) }
        m("^(?:play|youtube (?:par|pe|pr)?)\\s+(.+?)(?:\\s+on youtube)?$")?.let {
            return AICommand("youtube_search", text = it.groupValues[1])
        }
        m("^(?:search|google)\\s+(?:for\\s+)?(.+)$")?.let { return AICommand("web_search", text = it.groupValues[1]) }
        m("^(?:set\\s+)?alarm\\s+(?:for\\s+|at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")?.let {
            var h = it.groupValues[1].toInt()
            val min = it.groupValues[2].ifBlank { "0" }.toInt()
            if (it.groupValues[3] == "pm" && h < 12) h += 12
            if (it.groupValues[3] == "am" && h == 12) h = 0
            return AICommand("set_alarm", "%02d:%02d".format(h, min))
        }

        m("^(?:open|launch|start|chalao|kholo)\\s+(.+)$")?.let { return AICommand("open_app", it.groupValues[1]) }
        m("^(.+?)\\s+(?:kholo|open karo|open kro|open|chalao|chala do|start karo)\\s*$ACT$")?.let {
            return AICommand("open_app", it.groupValues[1])
        }

        return AICommand(
            "chat",
            reply = "Maaf kijiye, ye command samajh nahi aayi. Settings mein AI API key add karein taake main har baat samajh sakoon.",
        )
    }
}
