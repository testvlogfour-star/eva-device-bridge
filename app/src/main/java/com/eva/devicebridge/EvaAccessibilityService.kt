package com.eva.devicebridge

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class EvaAccessibilityService : AccessibilityService() {

    private var localHttpServer: LocalHttpServer? = null

    var lastPackageName: String? = null
        private set

    var lastClassName: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()

        localHttpServer = LocalHttpServer(this)

        try {
            localHttpServer?.start()
        } catch (e: Exception) {
            localHttpServer = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        lastPackageName = event.packageName?.toString()
        lastClassName = event.className?.toString()
    }

    override fun onInterrupt() {
        // Accessibility service interrupted.
    }

    override fun onDestroy() {
        try {
            localHttpServer?.stop()
        } finally {
            localHttpServer = null
        }

        super.onDestroy()
    }
}
