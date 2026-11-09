package com.arcontroller

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Reads WhatsApp message notifications (stored in [Store]) and keeps their inline "Reply" actions,
 * so AR can answer by voice. Also exposes the active call notification's Answer / Decline buttons,
 * which work on phones where TelecomManager can't answer or end calls.
 */
class NotificationWatcher : NotificationListenerService() {

    companion object {
        private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")

        @Volatile
        var instance: NotificationWatcher? = null
            private set

        /** Called on a new incoming message (main thread not guaranteed). */
        @Volatile
        var onMessage: ((JSONObject) -> Unit)? = null

        private data class ReplyTarget(val action: Notification.Action, val sender: String, val app: String)

        private val replies = LinkedHashMap<String, ReplyTarget>()

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotificationWatcher::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }

        /** Most recent sender with a usable Reply action. */
        fun latestSender(): String? = synchronized(replies) { replies.values.lastOrNull()?.sender }

        /** Fuzzy sender match: "ali" matches "Ali Raza". */
        private fun find(query: String?): ReplyTarget? = synchronized(replies) {
            if (query.isNullOrBlank()) return replies.values.lastOrNull()
            val q = query.lowercase().trim()
            replies.values.lastOrNull { it.sender.lowercase() == q }
                ?: replies.values.lastOrNull { it.sender.lowercase().startsWith(q) }
                ?: replies.values.lastOrNull { it.sender.lowercase().contains(q) }
        }

        fun canReply(query: String?) = find(query) != null

        /** Sends [text] through the notification's Reply action. Returns the sender name or null. */
        fun reply(ctx: Context, query: String?, text: String): String? {
            val target = find(query) ?: return null
            val inputs = target.action.remoteInputs ?: return null
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return try {
                target.action.actionIntent.send(ctx, 0, intent)
                Store.addMessage(
                    ctx,
                    JSONObject().put("app", target.app).put("sender", target.sender).put("text", text)
                        .put("time", System.currentTimeMillis()).put("outgoing", true),
                )
                target.sender
            } catch (_: PendingIntent.CanceledException) {
                null
            }
        }

        /** Presses a button (e.g. Answer / Decline) on the ringing call's notification. */
        fun pressCallAction(keywords: List<String>): Boolean {
            val svc = instance ?: return false
            val active = runCatching { svc.activeNotifications }.getOrNull() ?: return false
            for (sbn in active) {
                val n = sbn.notification
                if (n.category != Notification.CATEGORY_CALL && n.fullScreenIntent == null) continue
                val action = n.actions?.firstOrNull { a ->
                    val t = a.title?.toString()?.lowercase() ?: ""
                    keywords.any { t.contains(it) }
                } ?: continue
                return runCatching { action.actionIntent.send(); true }.getOrDefault(false)
            }
            return false
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return
        // MessagingStyle: use the newest message, which is more precise than EXTRA_TEXT.
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull()?.let { p ->
            (p as? Bundle)?.getCharSequence("text")?.toString()?.let { text = it }
        }
        if (title.equals("WhatsApp", true) || Regex("^\\d+ new messages?").containsMatchIn(text)) return

        // "Ali Raza: hi" style titles in groups → "Group: Ali Raza".
        val sender = title.substringBefore(" (").trim()
        n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }?.let { action ->
            synchronized(replies) {
                replies.remove(sender.lowercase())
                replies[sender.lowercase()] = ReplyTarget(action, sender, sbn.packageName)
                while (replies.size > 40) replies.remove(replies.keys.first())
            }
        }
        val msg = JSONObject().put("app", sbn.packageName).put("sender", sender).put("text", text)
            .put("time", sbn.postTime).put("outgoing", false)
        if (Store.addMessage(this, msg)) onMessage?.invoke(msg)
    }
}
