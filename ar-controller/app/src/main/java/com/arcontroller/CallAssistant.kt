package com.arcontroller

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Incoming calls: shows a card with Answer / Decline / AI answer, and runs the AI answering flow:
 * answer → speaker on → speak greeting → record the caller → farewell → hang up → transcribe + summarise.
 *
 * Limits: Android only lets a non-system app hear the caller because this app has an Accessibility
 * service; the caller's voice is captured from the loudspeaker, so quality depends on the phone.
 */
class CallAssistant(private val ctx: Context, private val ui: Ui) {

    interface Ui {
        fun showIncoming(number: String, name: String?)
        fun hideIncoming()
        fun aiStatus(text: String)
        fun callSaved(call: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var listener: PhoneStateListener? = null
    @Volatile private var callState = TelephonyManager.CALL_STATE_IDLE
    @Volatile private var ringingNumber = ""
    @Volatile private var aiSession = false
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun start() {
        if (listener != null || !has(Manifest.permission.READ_PHONE_STATE)) return
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                callState = state
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        ringingNumber = phoneNumber.orEmpty()
                        if (Prefs.callCard(ctx)) ui.showIncoming(ringingNumber, contactName(ringingNumber))
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> if (!aiSession) ui.hideIncoming()
                    TelephonyManager.CALL_STATE_IDLE -> if (!aiSession) ui.hideIncoming()
                }
            }
        }
        runCatching { tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE) }.onSuccess { listener = l }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        listener?.let { runCatching { tm.listen(it, PhoneStateListener.LISTEN_NONE) } }
        listener = null
        tts?.shutdown()
        tts = null
    }

    fun contactName(number: String): String? {
        if (number.isBlank() || !has(Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- call control

    @SuppressLint("MissingPermission")
    fun answer(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (runCatching { telecom.acceptRingingCall(); true }.getOrDefault(false)) return true
        }
        return NotificationWatcher.pressCallAction(listOf("answer", "accept", "jawab", "uthao", "receive"))
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun hangUp(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (runCatching { telecom.endCall() }.getOrDefault(false)) return true
        }
        if (NotificationWatcher.pressCallAction(listOf("decline", "reject", "hang up", "end", "dismiss"))) return true
        return ARAccessibilityService.instance?.let { it.clickByText("End call") || it.clickByText("Hang up") } ?: false
    }

    /** Decline and text the caller that you're busy. */
    @Suppress("DEPRECATION")
    fun declineWithSms(number: String): String {
        hangUp()
        if (number.isBlank()) return "Number nahi mila, SMS nahi gaya"
        if (!has(Manifest.permission.SEND_SMS)) return "Call band. SMS ke liye permission dein"
        return runCatching {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendTextMessage(number, null, "I'm busy right now, I'll call you back soon. (AR Controller)", null, null)
            save(number, contactName(number), "sms", "", "Busy SMS bheja gaya", "")
            "Call band, busy SMS bhej diya"
        }.getOrElse { "SMS nahi gaya: ${it.message}" }
    }

    // ---------------------------------------------------------------- AI answering

    fun aiAnswer(numberArg: String? = null) {
        if (aiSession) return
        val number = numberArg?.takeIf { it.isNotBlank() } ?: ringingNumber
        val name = contactName(number)
        aiSession = true
        ui.aiStatus("AI call utha raha hai…")
        Thread({ runSession(number, name) }, "ar-call").start()
    }

    private fun status(t: String) = main.post { ui.aiStatus(t) }

    private fun runSession(number: String, name: String?) {
        var audio: File? = null
        try {
            ensureTts()
            if (!answer()) {
                status("Call answer nahi ho saki (permission / phone support)")
                return
            }
            // Wait for the call to connect.
            val deadline = System.currentTimeMillis() + 5000
            while (callState != TelephonyManager.CALL_STATE_OFFHOOK && System.currentTimeMillis() < deadline) Thread.sleep(100)
            Thread.sleep(900)
            speakerOn()
            status("AI caller se baat kar raha hai…")
            speakBlocking(Prefs.callGreeting(ctx))
            if (callState == TelephonyManager.CALL_STATE_IDLE) return
            status("Caller ki baat record ho rahi hai…")
            audio = File(Store.callsDir(ctx), "call_${System.currentTimeMillis()}.wav")
            val heard = record(audio)
            if (callState != TelephonyManager.CALL_STATE_IDLE) {
                speakBlocking(Prefs.callFarewell(ctx))
                Thread.sleep(300)
                hangUp()
            }
            if (!heard) {
                audio.delete()
                audio = null
            }
        } catch (t: Throwable) {
            CrashReporter.save(ctx, "AI call session", t)
        } finally {
            runCatching { am.isSpeakerphoneOn = false }
            aiSession = false
            main.post { ui.hideIncoming() }
        }
        val call = save(number, name, "ai", "", if (audio == null) "Caller ki awaaz record nahi hui" else "Transcribe ho raha hai…", audio?.absolutePath ?: "")
        status("Call save ho gayi")
        // Speech-to-text + summary need the network; done after the call.
        if (audio != null) {
            val transcript = runCatching { AIBrain.transcribe(ctx, audio) }.getOrNull()
            val summary = when {
                transcript == null -> "Recording save hai. Transcript ke liye Groq ya OpenAI key chahiye."
                transcript.isBlank() -> "Caller ne kuch nahi bola"
                else -> runCatching { AIBrain.summarizeCall(ctx, name ?: number, transcript) }.getOrDefault(transcript.take(140))
            }
            val id = call.getString("id")
            Store.updateCall(ctx, id, JSONObject().put("transcript", transcript ?: "").put("summary", summary))
            Store.call(ctx, id)?.let { c -> main.post { ui.callSaved(c) } }
        }
    }

    private fun save(number: String, name: String?, status: String, transcript: String, summary: String, audio: String): JSONObject {
        val call = JSONObject()
            .put("id", "c${System.currentTimeMillis()}")
            .put("number", number)
            .put("name", name ?: "")
            .put("time", System.currentTimeMillis())
            .put("status", status)
            .put("transcript", transcript)
            .put("summary", summary)
            .put("audio", audio)
        Store.addCall(ctx, call)
        main.post { ui.callSaved(call) }
        return call
    }

    @Suppress("DEPRECATION")
    private fun speakerOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { runCatching { am.setCommunicationDevice(it) } }
        }
        runCatching { am.isSpeakerphoneOn = true }
        if (!am.isSpeakerphoneOn) ARAccessibilityService.instance?.clickByText("Speaker")
    }

    private fun ensureTts() {
        if (tts != null) return
        val ready = CountDownLatch(1)
        main.post {
            tts = TextToSpeech(ctx) { s ->
                ttsReady = s == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.forLanguageTag(Prefs.language(ctx))
                    // Voice-call routing so the speech plays through the call speaker.
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                }
                ready.countDown()
            }
        }
        ready.await(4, TimeUnit.SECONDS)
    }

    private fun speakBlocking(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        val done = CountDownLatch(1)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = done.countDown()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = done.countDown()
        })
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "call-${System.currentTimeMillis()}")
        done.await(20, TimeUnit.SECONDS)
    }

    /**
     * Records up to 25 s; stops 2.5 s after the caller stops talking, or after 9 s of silence.
     * Returns true if any speech was heard.
     */
    @SuppressLint("MissingPermission")
    private fun record(out: File): Boolean {
        if (!has(Manifest.permission.RECORD_AUDIO)) return false
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        val raf = RandomAccessFile(out, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44))
        val buf = ShortArray(rate / 10)
        val bytes = ByteArray(buf.size * 2)
        var total = 0
        var heard = false
        var lastVoice = System.currentTimeMillis()
        val start = lastVoice
        rec.startRecording()
        try {
            while (System.currentTimeMillis() - start < 25_000 && callState != TelephonyManager.CALL_STATE_IDLE) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    sum += buf[i] * buf[i].toDouble()
                    bytes[2 * i] = (buf[i].toInt() and 0xff).toByte()
                    bytes[2 * i + 1] = (buf[i].toInt() shr 8 and 0xff).toByte()
                }
                raf.write(bytes, 0, n * 2)
                total += n * 2
                val rms = sqrt(sum / n)
                val now = System.currentTimeMillis()
                if (rms > 700) {
                    heard = true
                    lastVoice = now
                }
                if (heard && now - lastVoice > 2500) break
                if (!heard && now - start > 9000) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            writeWavHeader(raf, total, rate)
            raf.close()
        }
        return heard
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Int, rate: Int) {
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        raf.seek(0)
        raf.write("RIFF".toByteArray()); raf.write(le32(36 + dataLen)); raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray()); raf.write(le32(16)); raf.write(le16(1)); raf.write(le16(1))
        raf.write(le32(rate)); raf.write(le32(rate * 2)); raf.write(le16(2)); raf.write(le16(16))
        raf.write("data".toByteArray()); raf.write(le32(dataLen))
    }
}
