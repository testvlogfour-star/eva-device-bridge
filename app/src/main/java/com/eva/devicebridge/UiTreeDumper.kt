package com.eva.devicebridge

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Renders the current AccessibilityNodeInfo tree as XML that matches the
 * shape of `adb shell uiautomator dump` output as closely as possible, so
 * ey_jarvis.py's existing XML-parsing logic (smart_locate, etc.) needs no
 * changes at all -- it just receives this string instead of reading a
 * `/sdcard/window_dump.xml` file pulled over adb.
 *
 * Real uiautomator dumps look like:
 *   <hierarchy rotation="0">
 *     <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
 *           package="com.example" content-desc="" checkable="false"
 *           checked="false" clickable="false" enabled="true" focusable="false"
 *           focused="false" scrollable="false" long-clickable="false"
 *           password="false" selected="false" bounds="[0,0][1080,2400]">
 *       ...children...
 *     </node>
 *   </hierarchy>
 *
 * We reproduce every one of those attributes.
 */
object UiTreeDumper {

    fun dump(root: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<hierarchy rotation=\"0\">\n")
        if (root != null) {
            appendNode(sb, root, 0, 0)
        }
        sb.append("</hierarchy>\n")
        return sb.toString()
    }

    private fun appendNode(sb: StringBuilder, node: AccessibilityNodeInfo, depth: Int, index: Int) {
        val indent = "  ".repeat(depth + 1)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        sb.append(indent).append("<node")
        sb.append(" index=\"").append(index).append('"')
        sb.append(" text=\"").append(esc(node.text)).append('"')
        sb.append(" resource-id=\"").append(esc(node.viewIdResourceName)).append('"')
        sb.append(" class=\"").append(esc(node.className)).append('"')
        sb.append(" package=\"").append(esc(node.packageName)).append('"')
        sb.append(" content-desc=\"").append(esc(node.contentDescription)).append('"')
        sb.append(" checkable=\"").append(node.isCheckable).append('"')
        sb.append(" checked=\"").append(node.isChecked).append('"')
        sb.append(" clickable=\"").append(node.isClickable).append('"')
        sb.append(" enabled=\"").append(node.isEnabled).append('"')
        sb.append(" focusable=\"").append(node.isFocusable).append('"')
        sb.append(" focused=\"").append(node.isFocused).append('"')
        sb.append(" scrollable=\"").append(node.isScrollable).append('"')
        sb.append(" long-clickable=\"").append(node.isLongClickable).append('"')
        sb.append(" password=\"").append(node.isPassword).append('"')
        sb.append(" selected=\"").append(node.isSelected).append('"')
        sb.append(" bounds=\"[").append(bounds.left).append(',').append(bounds.top)
            .append("][").append(bounds.right).append(',').append(bounds.bottom).append("]\"")

        val childCount = node.childCount
        if (childCount == 0) {
            sb.append(" />\n")
            return
        }

        sb.append(">\n")
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                appendNode(sb, child, depth + 1, i)
            } finally {
                child.recycle()
            }
        }
        sb.append(indent).append("</node>\n")
    }

    private fun esc(value: CharSequence?): String {
        if (value == null) return ""
        val s = value.toString()
        val out = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                '\n' -> out.append(' ')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * Finds the smallest (most specific / deepest) node whose bounds contain
     * the given point. Used by /type when the caller supplies x,y instead of
     * targeting "whatever currently has focus".
     */
    fun findNodeAtPoint(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(x, y)) {
                val area = bounds.width().toLong() * bounds.height().toLong()
                if (area < bestArea) {
                    bestArea = area
                    best = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return best
    }

    /**
     * Searches the tree by text, content-desc, or resource-id -- mirrors what
     * smart_locate() typically needs from a uiautomator dump, but exposed as
     * a native fallback in case the Python side wants a server-side search
     * instead of parsing the whole XML client-side.
     */
    fun findNodeByAttribute(root: AccessibilityNodeInfo?, attribute: String, value: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val candidate = when (attribute) {
                "text" -> node.text?.toString()
                "content-desc" -> node.contentDescription?.toString()
                "resource-id" -> node.viewIdResourceName
                else -> null
            }
            if (candidate != null && candidate == value) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }
}
