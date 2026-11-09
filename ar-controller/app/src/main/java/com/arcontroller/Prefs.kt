package com.arcontroller

import android.content.Context
import android.content.SharedPreferences

/** Settings shared between the React Native screen and the native overlay service. */
object Prefs {
    private const val NAME = "ar_controller"

    const val KEY_API_KEY = "apiKey"
    const val KEY_BASE_URL = "baseUrl"
    const val KEY_MODEL = "model"
    const val KEY_LANGUAGE = "language"
    const val KEY_WAKE_WORD = "wakeWordEnabled"
    const val KEY_FREE_AI = "freeAiEnabled"

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    // en-IN recognises English, Hindi/Urdu words written in Roman script ("youtube kholo") reasonably well.
    const val DEFAULT_LANGUAGE = "en-IN"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context) = prefs(ctx).getString(KEY_API_KEY, "") ?: ""
    fun baseUrl(ctx: Context) = (prefs(ctx).getString(KEY_BASE_URL, null) ?: "").ifBlank { DEFAULT_BASE_URL }
    fun model(ctx: Context) = (prefs(ctx).getString(KEY_MODEL, null) ?: "").ifBlank { DEFAULT_MODEL }
    fun language(ctx: Context) = (prefs(ctx).getString(KEY_LANGUAGE, null) ?: "").ifBlank { DEFAULT_LANGUAGE }
    fun wakeWordEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_WAKE_WORD, true)
    fun freeAiEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_FREE_AI, true)

    fun setFreeAi(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_FREE_AI, enabled).apply()

    fun bottomOrb(ctx: Context) = prefs(ctx).getBoolean("bottomOrb", true)
    fun setBottomOrb(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("bottomOrb", enabled).apply()

    /** Master AI switch: off = controller only (no orb, no microphone, no AI call answering). */
    fun aiEnabled(ctx: Context) = prefs(ctx).getBoolean("aiEnabled", true)
    fun setAiEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("aiEnabled", enabled).apply()

    const val DEFAULT_GREETING =
        "Assalam o Alaikum. Sir abhi busy hain. Aap apna naam aur paigham bata dein, main unhein bata dunga."
    const val DEFAULT_FAREWELL = "Shukriya. Main sir ko bata dunga. Allah Hafiz."

    fun callGreeting(ctx: Context) = (prefs(ctx).getString("callGreeting", null) ?: "").ifBlank { DEFAULT_GREETING }
    fun callFarewell(ctx: Context) = (prefs(ctx).getString("callFarewell", null) ?: "").ifBlank { DEFAULT_FAREWELL }
    fun setCallTexts(ctx: Context, greeting: String?, farewell: String?) = prefs(ctx).edit().apply {
        greeting?.let { putString("callGreeting", it.trim()) }
        farewell?.let { putString("callFarewell", it.trim()) }
    }.apply()

    /** Show the incoming-call card (answer / decline / AI answer) over other apps. */
    fun callCard(ctx: Context) = prefs(ctx).getBoolean("callCard", true)
    fun setCallCard(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("callCard", enabled).apply()

    /** Speak new WhatsApp messages aloud when the AI is on. */
    fun announceMessages(ctx: Context) = prefs(ctx).getBoolean("announceMessages", false)
    fun setAnnounceMessages(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("announceMessages", enabled).apply()

    fun save(
        ctx: Context,
        apiKey: String?,
        baseUrl: String?,
        model: String?,
        language: String?,
        wakeWord: Boolean?,
    ) {
        prefs(ctx).edit().apply {
            apiKey?.let { putString(KEY_API_KEY, it.trim()) }
            baseUrl?.let { putString(KEY_BASE_URL, it.trim().trimEnd('/')) }
            model?.let { putString(KEY_MODEL, it.trim()) }
            language?.let { putString(KEY_LANGUAGE, it.trim()) }
            wakeWord?.let { putBoolean(KEY_WAKE_WORD, it) }
        }.apply()
    }
}
