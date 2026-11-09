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
 * Turns natural language ("Hey AR, Ali ko call karo") into an [AICommand], and plans the steps of
 * multi-step screen tasks for [ScreenAgent].
 *
 * Provider: the user's OpenAI-compatible key if set; otherwise the free keyless Pollinations endpoint
 * (if enabled); otherwise the offline [LocalParser]. Blocking — call from a background thread.
 */
object AIBrain {

    private const val FREE_URL = "https://text.pollinations.ai/openai"
    private const val FREE_MODEL = "openai"

    val ACTIONS = listOf(
        "open_app", "float_app", "float_website", "close_floating", "call", "send_sms", "whatsapp_message",
        "flashlight_on", "flashlight_off", "volume_up", "volume_down", "mute",
        "home", "back", "recents", "notifications", "quick_settings", "lock_screen",
        "screenshot", "open_settings", "web_search", "youtube_search",
        "set_alarm", "type_text", "click", "scroll_down", "scroll_up", "read_screen",
        "media_play_pause", "media_next", "media_previous", "brightness_up", "brightness_down", "brightness_max",
        "rotate_toggle", "dnd_toggle", "silent", "vibrate", "ring", "power_menu", "battery", "time",
        "open_camera", "open_dialer", "open_messages", "open_contacts", "open_calendar", "open_calculator",
        "open_gallery", "open_music", "open_maps", "open_email", "open_browser", "open_clock",
        "reply_message", "read_messages", "read_calls",
        "agent_task", "chat",
    )

    private val history = ArrayDeque<JSONObject>()
    private const val MAX_HISTORY = 8

    private fun systemPrompt(apps: List<String>) = """
You are "AR", a voice assistant that fully controls the user's Android phone and does whatever the user asks.
The user may speak English, Urdu or Hindi (often Roman Urdu like "youtube kholo", "Ali ko call karo").
Convert each request into ONE JSON object and nothing else:
{"action": <one of ${ACTIONS.joinToString()}>, "target": <string|null>, "text": <string|null>, "reply": <short spoken reply>}

Rules:
- open_app: target = exact app name from INSTALLED APPS (full screen).
- float_app: target = exact app name. Use when the user says float/floating/popup/window/chhota/box, e.g. "YouTube float karo".
- float_website: text = website or search, shown in a floating window ("google.com floating mein kholo").
- close_floating: close floating windows.
- call: target = contact name or number. send_sms / whatsapp_message: target = contact, text = message.
- open_settings: target = wifi, bluetooth, location, display, sound, battery, apps, data or settings.
- web_search / youtube_search: text = query. set_alarm: target = "HH:MM" (24h).
- type_text: text to type in the focused field. click: target = visible button text.
- reply_message: reply to a received WhatsApp message. target = sender name (null = last sender), text = the reply
  (null if the user didn't say what to reply). Use for "Ali ko reply karo ke main aa raha hoon", "reply karo theek hai".
- read_messages: read out new WhatsApp messages. read_calls: tell what callers said to the AI answering machine.
- media_*, brightness_*, rotate_toggle, dnd_toggle, silent/vibrate/ring, power_menu, battery, time, open_camera … open_clock:
  direct device actions, no target needed.
- agent_task: ANY task that needs several taps inside apps or that no other action covers — e.g. "WhatsApp pe Ali ko hello bhejo",
  "Instagram pe reels kholo", "YouTube pe Atif Aslam ka gaana chalao aur play karo", "wifi off karo", "dark mode on karo",
  "Facebook pe post like karo". text = the full goal in clear English, mentioning the app. Never refuse a phone task; use agent_task.
- chat: only for questions/conversation; put the full answer in reply.
- reply: short, friendly, same language/style as the user.

INSTALLED APPS: ${apps.joinToString(", ").take(3000)}
""".trimIndent()

    private fun agentPrompt() = """
You are AR, an agent operating an Android phone through its accessibility tree to complete the user's GOAL.
Each turn you get the current SCREEN (numbered elements) and the HISTORY of your previous steps.
Reply with ONE JSON object and nothing else:
{"action": "open_app|tap|type|enter|scroll_down|scroll_up|back|home|wait|done",
 "id": <element number for tap/type>, "text": <text for type, or app name for open_app>,
 "say": <3-6 word status in the user's language>, "reply": <final short answer when action=done>}
Rules:
- Start with open_app if the needed app is not open.
- Use element numbers exactly as listed. To type: "type" with the input's id (no tap needed first).
- After typing a message, tap the Send button (or use "enter" for search boxes).
- If something didn't change, try a different element, scroll, or go back. Don't repeat the same failing step.
- When the goal is achieved (or impossible), use "done" with a short reply.
""".trimIndent()

    /** The free endpoint allows ~1 reply / 45 s per IP. */
    class RateLimited(val waitSeconds: Int) : Exception("Free AI limit — $waitSeconds sec baad")

    fun interpret(ctx: Context, utterance: String): AICommand {
        val local = LocalParser.parse(utterance)
        if (!hasProvider(ctx)) return local
        // Simple commands run instantly offline; the AI is only asked when the phrase isn't understood.
        if (isConfident(ctx, local)) return local
        return try {
            askLlm(ctx, utterance)
        } catch (r: RateLimited) {
            if (local.action != "chat") local
            else AICommand("chat", reply = "Free AI ki limit: ${r.waitSeconds} second baad dobara bolein. Tez AI ke liye app mein free Groq key daalein.")
        } catch (t: Throwable) {
            if (local.action != "chat") local
            else local.copy(reply = "AI se connect nahi ho saka: ${t.message ?: "network error"}")
        }
    }

    private fun isConfident(ctx: Context, c: AICommand): Boolean = when (c.action) {
        "chat" -> false
        "open_app", "float_app" -> c.target != null && AppRepository.findByName(ctx, c.target) != null
        else -> true
    }

    fun hasProvider(ctx: Context) = Prefs.apiKey(ctx).isNotBlank() || Prefs.freeAiEnabled(ctx)

    fun clearHistory() = synchronized(history) { history.clear() }

    /** One planning step for [ScreenAgent]. */
    fun nextAgentStep(ctx: Context, goal: String, screen: String, steps: List<String>): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", agentPrompt()))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    "GOAL: $goal\n\nINSTALLED APPS: ${AppRepository.labels(ctx).joinToString(", ").take(1500)}\n\n" +
                        "HISTORY:\n${steps.joinToString("\n").ifBlank { "(none)" }}\n\nSCREEN:\n$screen",
                ),
            )
        return JSONObject(extractJson(chat(ctx, messages)))
    }

    private fun askLlm(ctx: Context, utterance: String): AICommand {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt(AppRepository.labels(ctx))))
        synchronized(history) { history.forEach { messages.put(it) } }
        val userMsg = JSONObject().put("role", "user").put("content", utterance)
        messages.put(userMsg)

        val json = JSONObject(extractJson(chat(ctx, messages)))
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
    }

    /** One-shot plain-text completion (summaries etc.). */
    fun complete(ctx: Context, system: String, user: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return chat(ctx, messages, jsonMode = false).trim()
    }

    fun summarizeCall(ctx: Context, caller: String, transcript: String): String = complete(
        ctx,
        "You summarise phone messages for a busy person. Reply in one or two short sentences in Roman Urdu " +
            "(the user's language), saying who called and what they want. No preamble.",
        "Caller: $caller\nWhat the caller said (speech-to-text, may contain errors): $transcript",
    )

    /**
     * Speech-to-text for a WAV file via the provider's Whisper endpoint (Groq or OpenAI).
     * Returns null when no compatible key is configured.
     */
    fun transcribe(ctx: Context, file: java.io.File): String? {
        val key = Prefs.apiKey(ctx)
        val base = Prefs.baseUrl(ctx)
        if (key.isBlank()) return null
        val model = when {
            base.contains("groq.com") -> "whisper-large-v3-turbo"
            base.contains("openai.com") -> "whisper-1"
            else -> return null
        }
        val boundary = "----ar${System.currentTimeMillis()}"
        val conn = (URL("$base/audio/transcriptions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("model", model)
                field("response_format", "json")
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"call.wav\"\r\n" +
                        "Content-Type: audio/wav\r\n\r\n").toByteArray(),
                )
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${raw.take(160)}")
            return JSONObject(raw).optString("text").trim()
        } finally {
            conn.disconnect()
        }
    }

    /** POSTs a chat completion and returns the assistant message content. */
    private fun chat(ctx: Context, messages: JSONArray, retried: Boolean = false, jsonMode: Boolean = true): String {
        val key = Prefs.apiKey(ctx)
        val useKey = key.isNotBlank()
        val url = if (useKey) "${Prefs.baseUrl(ctx)}/chat/completions" else FREE_URL
        val model = if (useKey) Prefs.model(ctx) else FREE_MODEL
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
        if (useKey) {
            body.put("temperature", 0.2)
            body.put("max_tokens", 600)
            if (jsonMode) body.put("response_format", JSONObject().put("type", "json_object"))
            // Reasoning models think before answering; "low" keeps replies fast.
            if (jsonMode && model.contains("gpt-oss")) body.put("reasoning_effort", "low")
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (useKey) setRequestProperty("Authorization", "Bearer $key")
        }
        val code: Int
        val raw: String
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }
        if (!useKey && (code == 402 || code == 429)) {
            val secs = Regex("(?i)retry after (\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 45
            throw RateLimited(secs)
        }
        if (useKey && !retried) {
            // Providers retire models; switch to one the key can actually use and remember it.
            if (code == 404 || (code == 400 && raw.contains("model", ignoreCase = true) && raw.contains("decommission", ignoreCase = true))) {
                pickAvailableModel(ctx, key)?.let { picked ->
                    Prefs.save(ctx, null, null, picked, null, null)
                    return chat(ctx, messages, retried = true, jsonMode = jsonMode)
                }
            }
            // Some models reject JSON mode / reasoning_effort; retry as plain text (we extract the JSON ourselves).
            if (code == 400 && jsonMode) {
                return chat(ctx, messages, retried = true, jsonMode = false)
            }
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${raw.take(160)}")
        return JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    /** Lists the provider's models and picks the best general chat model available to this key. */
    private fun pickAvailableModel(ctx: Context, key: String): String? = try {
        val conn = (URL("${Prefs.baseUrl(ctx)}/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $key")
        }
        val ids = try {
            val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("data")
            (0 until data.length()).map { data.getJSONObject(it).getString("id") }
        } finally {
            conn.disconnect()
        }
        val chatIds = ids.filterNot { id ->
            listOf("whisper", "tts", "guard", "embed", "audio", "image", "vision", "moderation", "orpheus").any { id.contains(it, true) }
        }
        val preferred = listOf("gpt-oss-120b", "llama-3.3-70b", "llama-4", "qwen", "gpt-oss-20b", "llama-3.1-8b", "gpt-4o-mini")
        preferred.firstNotNullOfOrNull { p -> chatIds.firstOrNull { it.contains(p, true) } } ?: chatIds.firstOrNull()
    } catch (_: Throwable) {
        null
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

        // "Ali ko reply karo ke main aa raha hoon" / "reply karo theek hai" / "reply" (asks what to say)
        m("^(.+?)\\s+ko\\s+reply\\s*(?:karo|kro|kar do|do|bhejo)?\\s*(?:ke|k|that|ki)?\\s*(.*)$")?.let {
            return AICommand("reply_message", it.groupValues[1], it.groupValues[2].ifBlank { null })
        }
        m("^reply\\s+(?:to\\s+)?(\\S+)\\s+(?:that|saying)\\s+(.+)$")?.let {
            return AICommand("reply_message", it.groupValues[1], it.groupValues[2])
        }
        m("^reply\\s*(?:karo|kro|kar do|do|bhejo)?\\s*(?:ke|k|that|ki)?\\s*(.*)$")?.let {
            return AICommand("reply_message", null, it.groupValues[1].ifBlank { null })
        }
        if (Regex("(messages?|msgs?|paigham).*(parho|padho|sunao|read|batao|check)|(read|check).*(messages?|msgs?)|kis ka (message|msg)").containsMatchIn(s)) {
            return AICommand("read_messages")
        }
        if (Regex("(calls?|kaal).*(sunao|batao|kya kaha|read)|kis ne call|kaun call|missed call").containsMatchIn(s)) {
            return AICommand("read_calls")
        }

        m("^(?:send\\s+)?(?:whatsapp)\\s+(?:to\\s+)?(\\S+)\\s+(?:that|saying|ke|k)\\s+(.+)$")?.let {
            return AICommand("whatsapp_message", it.groupValues[1], it.groupValues[2])
        }
        m("^(?:send\\s+)?(?:message|sms|msg)\\s+(?:to\\s+)?(\\S+)\\s+(?:that|saying|ke|k)\\s+(.+)$")?.let {
            return AICommand("send_sms", it.groupValues[1], it.groupValues[2])
        }

        when {
            Regex("(close|band) .*(float|window)|(float|window).*(close|band)").containsMatchIn(s) ->
                return AICommand("close_floating")
            Regex("\\b(pause|play|resume|ruko gaana|gaana chalao)\\b").containsMatchIn(s) && !s.contains("youtube") && s.split(' ').size <= 3 ->
                return AICommand("media_play_pause")
            Regex("next (song|track|gaana)|agla gaana|skip").containsMatchIn(s) -> return AICommand("media_next")
            Regex("previous (song|track)|pichla gaana").containsMatchIn(s) -> return AICommand("media_previous")
            Regex("brightness.*(full|max)").containsMatchIn(s) -> return AICommand("brightness_max")
            Regex("brightness.*(up|barhao|zyada|increase|tez)|roshni barhao").containsMatchIn(s) -> return AICommand("brightness_up")
            Regex("brightness.*(down|kam|ghatao|decrease)|roshni kam").containsMatchIn(s) -> return AICommand("brightness_down")
            Regex("battery|charging").containsMatchIn(s) -> return AICommand("battery")
            Regex("^(time|what time|kitne baje|time kya)").containsMatchIn(s) -> return AICommand("time")
            Regex("do not disturb|\\bdnd\\b").containsMatchIn(s) -> return AICommand("dnd_toggle")
            Regex("\\b(silent|silence)\\b").containsMatchIn(s) -> return AICommand("silent")
            Regex("\\bvibrat").containsMatchIn(s) -> return AICommand("vibrate")
            Regex("auto ?rotat|rotation").containsMatchIn(s) -> return AICommand("rotate_toggle")
            Regex("power (menu|off|button)|restart phone|phone band").containsMatchIn(s) -> return AICommand("power_menu")
            Regex("^(open |kholo )?camera( kholo| open)?$|selfie").containsMatchIn(s) -> return AICommand("open_camera")
            Regex("^(open )?calculator( kholo)?$").containsMatchIn(s) -> return AICommand("open_calculator")
            Regex("^(open )?(gallery|photos)( kholo)?$").containsMatchIn(s) -> return AICommand("open_gallery")
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
