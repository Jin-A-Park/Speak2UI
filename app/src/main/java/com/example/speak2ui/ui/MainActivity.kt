package com.example.speak2ui.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.speak2ui.R
import com.example.speak2ui.control.TooltipService
import com.example.speak2ui.ui.overlay.OverlayService
import androidx.core.net.toUri

/**
 * The main entry point of the application.
 *
 * This activity is responsible for checking and requesting necessary permissions
 * such as overlay and microphone access. Once permissions are granted, it starts
 * the [OverlayService] to display a floating action button and guides the user
 * to enable the accessibility service if it's not already active.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var micPermissionLauncher: ActivityResultLauncher<String>

    /**
     * Called when the activity is first created.
     *
     * Initializes the activity, sets up permission launchers, and starts the permission check flow.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being shut down,
     * this Bundle contains the data it most recently supplied in [onSaveInstanceState].
     * Otherwise, it is null.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)  // 레이아웃 설정

        overlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Settings.canDrawOverlays(this)) {
                ensureMicPermissionAndStartOverlay()
            } else {
                Toast.makeText(this, "오버레이 권한을 허용해야 버튼과 툴팁을 모두 사용할 수 있습니다", Toast.LENGTH_LONG).show()
            }
        }

        micPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startOverlayService()
                if (!isAccessibilityServiceEnabled()) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            } else {
                Toast.makeText(this, "마이크 권한을 허용해야 음성인식이 실행됩니다", Toast.LENGTH_LONG).show()
            }
        }

        checkOverlayPermission()
    }

    /**
     * Checks if the overlay permission is granted.
     *
     * If the permission is granted, it proceeds to check for microphone permission.
     * If not, it launches the system settings screen to request the overlay permission.
     */
    private fun checkOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            ensureMicPermissionAndStartOverlay()
            if (!isAccessibilityServiceEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    /**
     * Starts the [OverlayService] to display the floating button.
     */
    private fun startOverlayService() {
        startService(Intent(this, OverlayService::class.java))
    }

    /**
     * Checks if the application's accessibility service is enabled.
     *
     * @return `true` if the accessibility service is enabled, `false` otherwise.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(
            ComponentName(this, TooltipService::class.java)
                .flattenToString(), ignoreCase = true
        )
    }

    /**
     * Ensures microphone permission is granted before starting the overlay service.
     *
     * If the permission is already granted, it starts the [OverlayService].
     * Otherwise, it launches the permission request flow.
     */
    private fun ensureMicPermissionAndStartOverlay() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (granted) {
            startOverlayService()
            if (!isAccessibilityServiceEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

}