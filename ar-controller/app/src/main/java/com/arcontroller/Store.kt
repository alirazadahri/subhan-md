package com.arcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Calls answered by the AI and WhatsApp messages, persisted as small JSON files in app storage. */
object Store {
    private const val CALLS = "calls.json"
    private const val MESSAGES = "messages.json"
    private const val MAX_MESSAGES = 300
    private val lock = Any()

    fun callsDir(ctx: Context) = File(ctx.filesDir, "calls").apply { mkdirs() }

    // ---------------------------------------------------------------- calls

    /** id, number, name, time, status ("ai" / "declined" / "sms"), transcript, summary, audio (path or ""). */
    fun calls(ctx: Context): JSONArray = read(ctx, CALLS)

    fun addCall(ctx: Context, call: JSONObject) = synchronized(lock) {
        val arr = read(ctx, CALLS)
        val out = JSONArray().put(call)
        for (i in 0 until minOf(arr.length(), 199)) out.put(arr.get(i))
        write(ctx, CALLS, out)
    }

    fun updateCall(ctx: Context, id: String, fields: JSONObject) = synchronized(lock) {
        val arr = read(ctx, CALLS)
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (c.optString("id") == id) fields.keys().forEach { k -> c.put(k, fields.get(k)) }
        }
        write(ctx, CALLS, arr)
    }

    fun call(ctx: Context, id: String): JSONObject? {
        val arr = read(ctx, CALLS)
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == id) return arr.getJSONObject(i)
        return null
    }

    fun deleteCall(ctx: Context, id: String) = synchronized(lock) {
        val arr = read(ctx, CALLS)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (c.optString("id") == id) c.optString("audio").takeIf { it.isNotBlank() }?.let { File(it).delete() }
            else out.put(c)
        }
        write(ctx, CALLS, out)
    }

    // ---------------------------------------------------------------- messages

    /** app, sender, text, time, key (notification key, used for replies), outgoing (bool). */
    fun messages(ctx: Context): JSONArray = read(ctx, MESSAGES)

    fun addMessage(ctx: Context, msg: JSONObject) = synchronized(lock) {
        val arr = read(ctx, MESSAGES)
        // WhatsApp re-posts the same notification text; skip exact duplicates from the last minute.
        for (i in 0 until minOf(arr.length(), 15)) {
            val m = arr.getJSONObject(i)
            if (m.optString("sender") == msg.optString("sender") && m.optString("text") == msg.optString("text") &&
                msg.optLong("time") - m.optLong("time") < 60_000
            ) return@synchronized false
        }
        val out = JSONArray().put(msg)
        for (i in 0 until minOf(arr.length(), MAX_MESSAGES - 1)) out.put(arr.get(i))
        write(ctx, MESSAGES, out)
        true
    }

    fun clearMessages(ctx: Context) = synchronized(lock) { write(ctx, MESSAGES, JSONArray()) }

    // ---------------------------------------------------------------- io

    private fun read(ctx: Context, name: String): JSONArray = synchronized(lock) {
        runCatching { JSONArray(File(ctx.filesDir, name).readText()) }.getOrDefault(JSONArray())
    }

    private fun write(ctx: Context, name: String, arr: JSONArray) {
        val f = File(ctx.filesDir, name)
        val tmp = File(ctx.filesDir, "$name.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }
}
