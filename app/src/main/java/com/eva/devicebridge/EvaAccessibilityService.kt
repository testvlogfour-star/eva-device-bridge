package com.eva.devicebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The single entry point for everything this bridge does.
 *
 * Once the user flips "Settings > Accessibility > EVA Device Bridge" on,
 * Android instantiates this service and keeps its process alive for as long
 * as the toggle stays on -- including across screen-off, app-switching, etc.
 * We piggyback the local HTTP server on that lifecycle so there is nothing
 * else for the user to start, and nothing else for them to grant.
 *
 * Responsibilities:
 *  - Track the foreground package/activity (best-effort) via accessibility
 *    events, since AccessibilityService has no direct "getCurrentActivity()"
 *    call available to third-party (non-system) apps.
 *  - Expose rootInActiveWindow / dispatchGesture / takeScreenshot to
 *    LocalHttpServer via the static `instance` reference.
 */
class EvaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "EvaAccessibilityService"

        // Nullable static reference. LocalHttpServer reads this on every
        // request; it will be null before the service is enabled and after
        // it's disabled, which the HTTP layer turns into a clean 503.
        @Volatile
        var instance: EvaAccessibilityService? = null
            private set
    }

    @Volatile
    var lastPackageName: String = ""
        private set

    @Volatile
    var lastClassName: String = ""
        private set

    private var httpServer: LocalHttpServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Belt-and-suspenders: also set flags/event mask in code, in addition
        // to the XML config, in case a given OEM ROM ignores parts of the XML.
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 0
        }
        serviceInfo = info

        httpServer = LocalHttpServer(this)
        try {
            httpServer?.start()
            Log.i(TAG, "EVA local bridge listening on 127.0.0.1:${LocalHttpServer.PORT}")
        } catch (e: Exception) {
            // Most common cause: a previous instance's socket hasn't been
            // released yet (fast disable/enable toggling). stop() closes it
            // in onUnbind/onDestroy below; if the port is still busy the
            // health check endpoint will simply be unreachable and the
            // Python client will surface a clear connection error.
            Log.e(TAG, "Failed to start local HTTP server", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // TYPE_WINDOW_STATE_CHANGED fires when a new top-level window (an
        // Activity, typically) becomes active. event.className generally
        // reflects that Activity's class name in this case -- this is a
        // best-effort signal, not a guaranteed one; some OEM launchers and
        // some frameworks (Flutter, some game engines) don't populate it
        // reliably. get_current_package_activity() on the Python side
        // documents this same caveat.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { lastPackageName = it.toString() }
            event.className?.let { lastClassName = it.toString() }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted by the system")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        httpServer?.stop()
        httpServer = null
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        httpServer?.stop()
        httpServer = null
        instance = null
        super.onDestroy()
    }
}
