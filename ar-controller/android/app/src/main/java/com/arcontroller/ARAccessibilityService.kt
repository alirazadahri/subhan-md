package com.arcontroller

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Gives the controller "hands": Back/Home/Recents, notifications, lock, screenshot,
 * split-screen, clicking on-screen text, typing into fields and scrolling.
 */
class ARAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ARAccessibilityService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx, ARAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun global(action: Int): Boolean = performGlobalAction(action)

    /** Clicks the first visible element whose text/description contains [text]. */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (node in root.findAccessibilityNodeInfosByText(text)) {
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    /** Types into the focused input, or the first editable field on screen. */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findFirst(root) { it.isEditable }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findFirst(root) { it.isScrollable } ?: return false
        return node.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        )
    }

    /** Text currently visible on screen, so the AI can answer "what's on my screen". */
    fun screenText(limit: Int = 1500): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && sb.length < limit) {
            val n = queue.removeFirst()
            n.text?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return sb.toString().take(limit)
    }

    private fun findFirst(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (match(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }
}
