package com.example.speak2ui.speech

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * An intermediary activity responsible for ensuring the microphone permission is granted
 * before starting the main [SttService].
 *
 * This activity is launched when the user decides to start the voice control feature.
 * It requests the `RECORD_AUDIO` permission. If the permission is granted, it starts the
 * [SttService] as a foreground service and then immediately finishes itself.
 * If the permission is denied, it shows a toast message and finishes.
 */
class Starter : Activity() {

    companion object {
        private const val REQ_MIC = 1001
    }

    /**
     * Called when the activity is first created.
     *
     * Checks for the `RECORD_AUDIO` permission. If granted, it proceeds to start the STT service.
     * Otherwise, it requests the permission from the user.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being shut down,
     * this Bundle contains the data it most recently supplied in [onSaveInstanceState].
     * Otherwise, it is null.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 마이크 권한 확인/요청
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_MIC
            )
        } else {
            startSttAndFinish()
        }
    }

    /**
     * Callback for the result from requesting permissions.
     *
     * This method is invoked for every call on [requestPermissions].
     * If the microphone permission is granted, it calls [startSttAndFinish].
     * Otherwise, it displays a toast and finishes the activity.
     *
     * @param requestCode The request code passed in [requestPermissions].
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions. Never null.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startSttAndFinish()
        } else {
            Toast.makeText(this, "마이크 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * Starts the [SttService] in the foreground and finishes this activity.
     *
     * This method is called after ensuring that all necessary permissions are granted.
     * It sends an [SttService.ACTION_START] intent to the service.
     */
    private fun startSttAndFinish() {
        // 사용자 제스처 직후 & 전경 상태에서 안전하게 FGS 시작
        Log.d("STTStarter", "sttservice_gpt will start soon...")
        ContextCompat.startForegroundService(
            this,
            Intent(this, SttService::class.java).setAction(SttService.ACTION_START)
        )
        finish()
    }
}