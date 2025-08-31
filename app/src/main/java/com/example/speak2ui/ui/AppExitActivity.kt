package com.example.speak2ui.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.speak2ui.control.TooltipService
import com.example.speak2ui.speech.SttService
import com.example.speak2ui.ui.overlay.OverlayService
import kotlin.system.exitProcess

/**
 * An activity that handles the graceful exit of the application.
 *
 * This activity is responsible for stopping all running services,
 * finishing all activities in the task stack, and finally, killing the application process.
 * It is typically triggered when the user explicitly requests to exit the app.
 */
class AppExitActivity : Activity() {

    companion object {
        private const val TAG = "Main"
    }

    /**
     * Called when the activity is first created.
     *
     * This method performs the following actions in order:
     * 1. Stops all custom services (`OverlayService`, `STT.Service`, `TooltipService`, `MyImeService`).
     * 2. Finishes all activities in the current task.
     * 3. Kills the application process to ensure a clean exit.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being shut down,
     * this Bundle contains the data it most recently supplied in [onSaveInstanceState].
     * Otherwise, it is null.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try { stopService(Intent(this, OverlayService::class.java)) } catch (_: Exception) {}
        try { stopService(Intent(this, SttService::class.java)) } catch (_: Exception) {}
        try { stopService(Intent(this, TooltipService::class.java)) } catch (_: Exception) {}
        try { stopService(Intent(this, com.example.speak2ui.control.ImeService::class.java)) } catch (_: Exception) {}
        try { finishAffinity() } catch (_: Exception) {}

        try {
            android.os.Process.killProcess(android.os.Process.myPid())
        } finally {
            Log.d(TAG, "All activities terminated")
            exitProcess(0)
        }
    }
}