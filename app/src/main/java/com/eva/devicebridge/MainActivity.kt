package com.eva.devicebridge

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var eyStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        eyStatusText = findViewById(R.id.eyStatusText)
        val openSettingsButton = findViewById<Button>(R.id.openSettingsButton)
        val startEyButton = findViewById<Button>(R.id.startEyButton)
        val stopEyButton = findViewById<Button>(R.id.stopEyButton)

        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        startEyButton.setOnClickListener {
            runTermuxScript("eva_start.sh")
            eyStatusText.text = "Status: starting..."
        }

        stopEyButton.setOnClickListener {
            runTermuxScript("eva_stop.sh")
            eyStatusText.text = "Status: stopped"
        }
    }

    private fun runTermuxScript(scriptName: String) {
        try {
            val intent = Intent()
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.action = "com.termux.RUN_COMMAND"
            intent.putExtra(
                "com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/home/$scriptName"
            )
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            startForegroundService(intent)
        } catch (e: Exception) {
            eyStatusText.text = "Error: ${e.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isAccessibilityServiceEnabled()
        statusText.text = if (enabled) {
            "EVA Device Bridge is ENABLED.\nListening on 127.0.0.1:${LocalHttpServer.PORT}"
        } else {
            "EVA Device Bridge is NOT enabled yet.\nTap below, then find \"EVA Device Bridge\" in the Accessibility list and turn it on."
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
