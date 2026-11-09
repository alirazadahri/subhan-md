package com.arcontroller

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Wake word ("Hey AR") + command listening + text-to-speech.
 *
 * Uses Android's built-in SpeechRecognizer in a restart loop, so it needs no extra library.
 * For battery-friendly always-on detection, swap the wake-word stage for Picovoice Porcupine
 * (see README). Must be used on the main thread.
 */
class VoiceAssistant(private val ctx: Context, private val listener: Listener) : RecognitionListener {

    enum class State { OFF, WAITING_WAKE_WORD, LISTENING_COMMAND, THINKING, SPEAKING }

    interface Listener {
        fun onStateChanged(state: State, detail: String?)
        fun onCommand(text: String)
        /** Microphone loudness 0..1 while listening (drives the orb animation). */
        fun onLevel(level: Float) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var running = false
    private var wakeLoop = false
    private var listenAfterSpeech = false
    var state = State.OFF
        private set

    // Speech engines transcribe "Hey AR" as "hey are", "hey r", "hey yaar", "hey a.r." etc.; "AR …" at the start also counts.
    private val wakeRegex = Regex(
        "(?:\\b(?:hey|hay|hi|ok|okay|hello)[\\s,]+(?:a\\.?\\s?r\\.?|ar|are|aar|arr|air|r|yaar)|^\\s*(?:a\\.?\\s?r\\.?|aar))(?=\\W|$)",
        RegexOption.IGNORE_CASE,
    )

    fun start(wakeWordLoop: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            setState(State.OFF, "Speech recognition is phone par available nahi")
            return
        }
        running = true
        wakeLoop = wakeWordLoop
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(this) }
        }
        if (tts == null) {
            tts = TextToSpeech(ctx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale.forLanguageTag(Prefs.language(ctx))
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { main.post { afterSpeaking() } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { main.post { afterSpeaking() } }
            })
        }
        if (wakeLoop) listen(State.WAITING_WAKE_WORD) else setState(State.OFF, null)
    }

    /** Skip the wake word and take a command right now (mic button in the panel). */
    fun listenForCommandNow() {
        if (!running) start(wakeLoop)
        recognizer?.cancel()
        listen(State.LISTENING_COMMAND)
    }

    fun setThinking(text: String) = setState(State.THINKING, text)

    /** Speak a question, then listen for the answer (e.g. "Kya reply karun?"). */
    fun ask(text: String) {
        listenAfterSpeech = true
        speak(text)
    }

    /** Tap on the orb while listening: stop without running a command. */
    fun cancelListening() {
        listenAfterSpeech = false
        recognizer?.cancel()
        setState(State.OFF, null)
    }

    fun speak(text: String) {
        recognizer?.cancel()
        setState(State.SPEAKING, text)
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ar-${System.currentTimeMillis()}")
        } else {
            main.postDelayed({ afterSpeaking() }, 1200)
        }
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        setState(State.OFF, null)
    }

    private fun afterSpeaking() {
        if (!running) return
        if (listenAfterSpeech) {
            listenAfterSpeech = false
            listen(State.LISTENING_COMMAND)
            return
        }
        if (wakeLoop) listen(State.WAITING_WAKE_WORD) else setState(State.OFF, null)
    }

    private fun listen(mode: State) {
        val rec = recognizer ?: return
        setState(mode, if (mode == State.WAITING_WAKE_WORD) "Bolein \"Hey AR\"…" else "Sun raha hoon…")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.language(ctx))
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        try {
            rec.startListening(intent)
        } catch (t: Throwable) {
            restartLater(1500)
        }
    }

    private fun restartLater(delayMs: Long) {
        if (!running) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            if (!running) return@postDelayed
            if (wakeLoop) listen(State.WAITING_WAKE_WORD) else setState(State.OFF, null)
        }, delayMs)
    }

    private fun setState(s: State, detail: String?) {
        state = s
        listener.onStateChanged(s, detail)
    }

    // ---- RecognitionListener ----

    override fun onResults(results: Bundle?) {
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val text = candidates.firstOrNull()?.trim().orEmpty()

        when (state) {
            State.WAITING_WAKE_WORD -> {
                val hit = candidates.firstNotNullOfOrNull { wakeRegex.find(it)?.let { m -> it to m } }
                if (hit == null) {
                    restartLater(150)
                    return
                }
                val (phrase, match) = hit
                val rest = phrase.substring(match.range.last + 1).trim(' ', ',', '.')
                if (rest.length > 2) {
                    // "Hey AR open YouTube" in one breath.
                    listener.onCommand(rest)
                } else {
                    // After the prompt, take the actual command instead of waiting for the wake word again.
                    listenAfterSpeech = true
                    speak("Ji, boliye")
                }
            }
            State.LISTENING_COMMAND -> {
                if (text.isBlank()) restartLater(150) else listener.onCommand(stripWake(text))
            }
            else -> Unit
        }
    }

    private fun stripWake(text: String): String {
        val m = wakeRegex.find(text) ?: return text
        return if (m.range.first <= 2) text.substring(m.range.last + 1).trim(' ', ',', '.').ifBlank { text } else text
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (state != State.LISTENING_COMMAND) return
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
            if (it.isNotBlank()) listener.onStateChanged(state, "“$it”")
        }
    }

    override fun onError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                setState(State.OFF, "Microphone permission chahiye")
                running = false
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                recognizer?.cancel()
                restartLater(1000)
            }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (state == State.LISTENING_COMMAND && !wakeLoop) setState(State.OFF, "Kuch sunai nahi diya")
                else restartLater(200)
            }
            else -> restartLater(1500)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
