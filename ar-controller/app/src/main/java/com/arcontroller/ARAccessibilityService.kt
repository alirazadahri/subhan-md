package com.arcontroller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
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

    /** Numbered list of what the user can see and touch; the agent refers to elements by number. */
    class Screen(val text: String, val nodes: List<AccessibilityNodeInfo>)

    fun describeScreen(maxNodes: Int = 70): Screen {
        val root = rootInActiveWindow ?: return Screen("(screen not readable)", emptyList())
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder("App: ${root.packageName}\n")
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val r = Rect()
        while (queue.isNotEmpty() && nodes.size < maxNodes) {
            val n = queue.removeFirst()
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            if (!n.isVisibleToUser) continue
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText else null
            val label = (n.text ?: n.contentDescription ?: hint)?.toString()?.trim()?.replace('\n', ' ')
            val interactive = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
            if (label.isNullOrEmpty() && !n.isEditable) continue
            if (!interactive && label.isNullOrEmpty()) continue
            n.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) continue
            val kind = when {
                n.isEditable -> "input"
                n.isCheckable -> if (n.isChecked) "switch(on)" else "switch(off)"
                n.isClickable -> "button"
                n.isScrollable -> "list"
                else -> "text"
            }
            nodes += n
            sb.append('[').append(nodes.size).append("] ").append(kind)
            if (!label.isNullOrEmpty()) sb.append(" \"").append(label.take(60)).append('"')
            if (n.isFocused) sb.append(" (focused)")
            sb.append('\n')
        }
        return Screen(sb.toString(), nodes)
    }

    /** Taps a node: click action on it or a clickable parent, else a real touch gesture at its centre. */
    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        node.getBoundsInScreen(r)
        return tapAt(r.exactCenterX(), r.exactCenterY())
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Presses the keyboard's Enter/Search/Send key on the focused field (Android 11+). */
    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
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
