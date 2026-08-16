package com.eva.devicebridge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal UI: shows whether the Accessibility Service is currently enabled,
 * and a button that deep-links straight to the system Accessibility settings
 * screen so the user's "one-time toggle" is one tap away from app launch.
 *
 * This activity has no other role -- all real work happens in
 * EvaAccessibilityService + LocalHttpServer, which run independently of
 * whether this Activity is on screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val openSettingsButton = findViewById<Button>(R.id.openSettingsButton)

        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isAccessibilityServiceEnabled()
        statusText.text = if (enabled) {
            "✅ EVA Device Bridge is ENABLED.\nListening on 127.0.0.1:${LocalHttpServer.PORT}"
        } else {
            "⚠️ EVA Device Bridge is NOT enabled yet.\nTap below, then find \"EVA Device Bridge\" in the Accessibility list and turn it on."
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${EvaAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        if (TextUtils.isEmpty(enabledServices)) return false
        return enabledServices.split(":").any { it.equals(expectedComponent, ignoreCase = true) }
    }
}
