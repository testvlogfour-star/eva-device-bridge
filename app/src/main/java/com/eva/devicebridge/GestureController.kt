package com.eva.devicebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wraps AccessibilityService.dispatchGesture() (taps/swipes/long-presses) and
 * AccessibilityNodeInfo.ACTION_SET_TEXT (typing) behind simple blocking
 * calls, since NanoHTTPD's serve() is synchronous and the underlying
 * Android APIs are callback-based.
 */
object GestureController {

    private const val TAP_DURATION_MS = 50L
    private const val GESTURE_TIMEOUT_S = 5L

    fun tap(service: AccessibilityService, x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        return dispatch(service, stroke)
    }

    fun longPress(service: AccessibilityService, x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatch(service, stroke)
    }

    fun swipe(service: AccessibilityService, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatch(service, stroke)
    }

    private fun dispatch(service: AccessibilityService, stroke: GestureDescription.StrokeDescription): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var success = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }
        val dispatched = service.dispatchGesture(gesture, callback, null)
        if (!dispatched) return false
        latch.await(GESTURE_TIMEOUT_S, TimeUnit.SECONDS)
        return success
    }

    /**
     * Types text into a specific node. If x/y are supplied we locate the
     * smallest node under that point first; otherwise we fall back to
     * whatever node currently reports isFocused == true.
     *
     * Uses ACTION_SET_TEXT rather than simulating individual key events,
     * because ACTION_SET_TEXT works even when no soft keyboard is showing
     * and is far more reliable across OEM keyboards.
     */
    fun typeText(root: AccessibilityNodeInfo?, x: Int?, y: Int?, text: String): Boolean {
        val target = if (x != null && y != null) {
            UiTreeDumper.findNodeAtPoint(root, x, y)
        } else {
            findFocused(root)
        } ?: return false

        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocused(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isFocused) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }
}
