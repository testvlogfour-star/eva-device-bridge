package com.eva.devicebridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var eyStatusText: TextView
    private var pendingScript: String? = null

    companion object {
        private const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        eyStatusText = findViewById(R.id.eyStatusText)
        val openSettingsButton = findViewById<Button>(R.id.openSettingsButton)
        val startEyButton = findViewById<Button>(R.id.startEyButton)
        val stopEyButton = findViewById<Button>(R.id.stopEyButton)
        val updateEyButton = findViewById<Button>(R.id.updateEyButton)

        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        startEyButton.setOnClickListener {
            requestPermissionThenRun("eva_start.sh", "starting...")
        }

        stopEyButton.setOnClickListener {
            requestPermissionThenRun("eva_stop.sh", "stopping...")
        }

        updateEyButton.setOnClickListener {
            requestPermissionThenRun("eva_update.sh", "updating...")
        }
    }

    private fun requestPermissionThenRun(scriptName: String, statusLabel: String) {
        if (ContextCompat.checkSelfPermission(this, RUN_COMMAND_PERMISSION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingScript = scriptName
            ActivityCompat.requestPermissions(
                this, arrayOf(RUN_COMMAND_PERMISSION), PERMISSION_REQUEST_CODE
            )
            eyStatusText.text = "Status: asking for Termux permission..."
            return
        }
        runTermuxScript(scriptName, statusLabel)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val script = pendingScript
            pendingScript = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (script != null) runTermuxScript(script, "starting...")
            } else {
                eyStatusText.text = "Status: permission denied. Tap Start again and allow it."
            }
        }
    }

    private fun runTermuxScript(scriptName: String, statusLabel: String) {
        try {
            val intent = Intent()
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.action = "com.termux.RUN_COMMAND"
            intent.putExtra(
                "com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/home/$scriptName"
            )
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            ContextCompat.startForegroundService(this, intent)
            eyStatusText.text = "Status: $statusLabel (command sent to Termux)"
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
            "EVA Device Bridge is NOT enabled yet.\nTap below, then find \"EVA Device Bridge\" in the Accessibility list."
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
