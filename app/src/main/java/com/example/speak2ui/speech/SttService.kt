package com.example.speak2ui.speech

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.speak2ui.control.Accessibility
import com.example.speak2ui.control.TooltipService
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt

/**
 * A foreground service for continuous speech-to-text (STT) transcription.
 *
 * This service runs in the background, continuously listening for user speech.
 * It uses a [VoiceDetector] (VAD) to detect speech segments, records them using
 * [AudioRecorder], and sends them to the [SttClient] for transcription. The resulting
 * text is then broadcasted to the [Accessibility] to be executed as a command.
 */
class SttService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var vad: VoiceDetector
    private lateinit var sttClient: SttClient

    // Flags to control the listening loop state.
    @Volatile private var isScreenControlCompleted = false
    @Volatile private var didGoHome = false
    @Volatile private var listeningPaused = false

    private var utterStartMs = 0L
    private var listenStartMs = 0L

    companion object {
        const val ACTION_START = "com.example.speak2ui.speech.START"
        const val ACTION_STOP  = "com.example.speak2ui.speech.STOP"

        private const val NOTI_ID = 101
        private const val NOTI_CHANNEL_ID = "SttServiceChannel"
        private const val NOTI_CHANNEL_NAME = "STT Service"

        const val MIN_SPEECH_MS = 250
        private const val FRAME_MS = 20
        private const val MAX_UTTERANCE_MS = 10_000L
        private const val INITIAL_SILENCE_TIMEOUT_MS = 15_000L
    }

    /**
     * Receives a broadcast when the [ActionExecutor] has finished processing a command.
     */
    private val screenControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.SCREEN_CONTROL_COMPLETE") {
                isScreenControlCompleted = true
                showOverlayMessage(this@SttService, "화면 제어 완료!", position = Gravity.BOTTOM)
            }
        }
    }

    override fun onCreate() {
        super.onCreate() 
        audioRecorder = AudioRecorder(this)
        vad = VoiceDetector()
        sttClient = SttClient()

        val filter = IntentFilter("com.example.SCREEN_CONTROL_COMPLETE")
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(screenControlReceiver, filter, receiverFlags)
    }

    /**
     * Handles the start command for the service.
     *
     * It ensures all permissions are granted, starts the service in the foreground,
     * and kicks off the listening loop.
     */
    @RequiresPermission(value = "android.permission.RECORD_AUDIO")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasRecordPermission()) {
            Log.e("STTService", "❌ RECORD_AUDIO permission not granted. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTI_ID, buildNotification())

        // On first start, navigate to the home screen to provide a consistent starting point.
        if (!didGoHome && hasRequiredPermissions()) {
            goHome()
            didGoHome = true
        }

        startForegroundAndListen()

        return START_STICKY
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        listeningPaused = true
        serviceScope.cancel()
        audioRecorder.stopRecording()
        audioRecorder.cleanUpTempFile()
        try {
            unregisterReceiver(screenControlReceiver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Starts the listening process if it's not already active.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startForegroundAndListen() {
        showOverlayMessage(this, "새로운 음성인식 준비 완료", position = Gravity.BOTTOM)

        if (audioRecorder.isRecording || listeningPaused) {
            Log.d("STTService", "Already recording or paused, skipping start.")
            return
        }
        Log.d("STTProcessLog", "🚀 Starting listening loop...")
        listenStartMs = SystemClock.elapsedRealtime()
        serviceScope.launch { recordLoop() }
    }

    /**
     * Resets the VAD and audio recorder and restarts the listening loop.
     */
    @RequiresPermission(value = "android.permission.RECORD_AUDIO")
    private fun restartListening() {
        listeningPaused = false
        vad.reset()
        audioRecorder.cleanUpTempFile()
        startForegroundAndListen()
    }

    /**
     * The main recording loop.
     *
     * Calibrates the noise floor, then continuously reads audio frames and passes them
     * to the VAD for processing.
     */
    @RequiresPermission(value = "android.permission.RECORD_AUDIO")
    private suspend fun recordLoop() {
        if (!hasRecordPermission()) {
            Log.e("STTService", "❌ RECORD_AUDIO permission missing at runtime.")
            stopSelf()
            return
        }

        val frameSamples = (AudioRecorder.SAMPLE_RATE * FRAME_MS) / 1000
        val readBuffer = ShortArray(frameSamples)
        audioRecorder.startRecording(readBuffer)

        // Calibrate noise floor for the first 500ms.
        val calibrationFrames = 25
        repeat(calibrationFrames) {
            val n = audioRecorder.read(readBuffer)
            if (n > 0) {
                vad.updateNoiseFloor(readBuffer.copyOf(n))
            }
            delay(FRAME_MS.toLong())
        }
        Log.d("STTService", "✅ Noise floor calibrated")

        while (audioRecorder.isRecording) {
            val n = audioRecorder.read(readBuffer)
            if (n > 0) {
                handleFrame(readBuffer.copyOf(n))
            } else {
                delay(10)
            }
        }
        Log.d("STTProcessLog", "🛑 Recording stopped.")
    }

    /**
     * Processes a single frame of audio with the Voice Activity Detector (VAD).
     *
     * This function implements the VAD state machine (IDLE -> COLLECT -> IDLE).
     * @param frame The audio frame to process.
     */
    @RequiresPermission(value = "android.permission.RECORD_AUDIO")
    private fun handleFrame(frame: ShortArray) {
        val now = SystemClock.elapsedRealtime()
        val isSpeech = vad.isSpeech(frame)

        // Timeout for initial silence.
        if (vad.currentVadState == VoiceDetector.VadState.IDLE && now - listenStartMs > INITIAL_SILENCE_TIMEOUT_MS && !isSpeech) {
            listenStartMs = now
            return
        }

        when (vad.currentVadState) {
            VoiceDetector.VadState.IDLE -> {
                if (isSpeech) {
                    vad.onCount++
                    if (vad.onCount >= VoiceDetector.VOICE_ON_FRAMES) {
                        // Speech detected, start collecting audio frames.
                        Log.d("STTProcessLog", "🗣️ Speech detected, starting collection.")
                        showOverlayMessage(this, "외부 음성 감지됨", position = Gravity.BOTTOM)
                        vad.currentVadState = VoiceDetector.VadState.COLLECT
                        utterStartMs = now
                        vad.offCount = 0
                        audioRecorder.createPcmFile()
                        audioRecorder.appendPcm(frame)
                    }
                } else {
                    // Not speech, update noise floor and reset counter.
                    vad.updateNoiseFloor(frame)
                    vad.onCount = 0
                }
            }
            VoiceDetector.VadState.COLLECT -> {
                audioRecorder.appendPcm(frame)
                if (isSpeech) {
                    vad.offCount = 0 // Reset silence counter.
                    if (now - utterStartMs >= MAX_UTTERANCE_MS) {
                        // Max utterance time reached, finalize.
                        finalizeAndSend()
                    }
                } else {
                    vad.offCount++
                    if (vad.offCount >= VoiceDetector.VOICE_OFF_FRAMES) {
                        // End of speech detected, finalize.
                        finalizeAndSend()
                    }
                }
            }
        }
    }

    /**
     * Finalizes the audio recording and sends it for transcription.
     *
     * This function stops the recording, finalizes the WAV file header, and launches a coroutine
     * to handle the transcription and subsequent command processing.
     */
    @RequiresPermission(value = "android.permission.RECORD_AUDIO")
    private fun finalizeAndSend() {
        showOverlayMessage(this, "음성 감지 종료. 텍스트로 변환 중...", position = Gravity.BOTTOM)

        val recordingDuration = SystemClock.elapsedRealtime() - utterStartMs
        vad.reset()

        val fileToFinalize = audioRecorder.pcmFile ?: return
        val bytesWritten = audioRecorder.pcmBytesWritten

        audioRecorder.finalizePcmFile()

        // Ignore utterances that are too short.
        val durationMs = (bytesWritten.toDouble() / 2 / AudioRecorder.SAMPLE_RATE) * 1000.0
        if (durationMs < MIN_SPEECH_MS) {
            Log.d("STTService", "Speech too short ($durationMs ms), ignoring.")
            fileToFinalize.delete()
            return
        }

        try {
            audioRecorder.updateWavHeader(fileToFinalize, bytesWritten)
        } catch (e: java.io.IOException) {
            Log.e("STTService", "Failed to update WAV header", e)
            fileToFinalize.delete()
            return
        }

        Log.d("STTService", "💾 Audio file saved")
        Log.d("STTProcessLog", "🕒 Total recording duration: $recordingDuration ms.")

        // Pause listening and stop the recorder before transcription.
        listeningPaused = true
        audioRecorder.stopRecording()
        serviceScope.launch { transcribeAndProcess(fileToFinalize) }
    }

    /**
     * Transcribes the audio file and processes the resulting command.
     * After processing, it restarts the listening loop.
     * @param wavFile The completed WAV file to transcribe.
     */
    @RequiresPermission(value = "android.permission.RECORD_AUDIO")
    private suspend fun transcribeAndProcess(wavFile: File) {
        val sttResponse = sttClient.transcribeWavFile(wavFile)
        if (sttResponse != null) {
            Log.d("STTProcessLog", "📝 ASR Result: \"$sttResponse\"")
            showOverlayMessage(this, "음성인식 완료! 결과: $sttResponse", position = Gravity.BOTTOM)
            val ok = sendCommandAndAwait(sttResponse)
            if (!ok) Log.w("STTService", "⚠️ Timeout waiting for screen control completion.")
        }
        withContext(Dispatchers.Main) {
            restartListening()
        }
    }

    /**
     * Broadcasts the transcribed command to the [Accessibility] and waits for completion.
     * @param command The text command to be executed.
     * @param timeoutMs The maximum time to wait for the completion signal.
     * @return `true` if the completion signal was received within the timeout, `false` otherwise.
     */
    private suspend fun sendCommandAndAwait(command: String, timeoutMs: Long = 15_000L): Boolean {
        Intent("com.example.COMMAND_ACTION").apply {
            setPackage(packageName)
            putExtra("command", command)
        }.also {
            sendBroadcast(it)
            Log.d("STTService", "📡 Command broadcasted: \"$command\"")
        }

        isScreenControlCompleted = false
        return withTimeoutOrNull(timeoutMs) {
            while (!isScreenControlCompleted) delay(100)
            true
        } ?: false
    }

    /**
     * Builds the foreground service notification.
     * @return The [Notification] object.
     */
    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            NOTI_CHANNEL_ID,
            NOTI_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground service for voice recognition."
            setSound(null, null)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)

        val stopIntent = Intent(this, Service::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTI_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice control active")
            .setContentText("Listening for commands...")
            .addAction(0, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    /**
     * Sends an intent to navigate to the device's home screen.
     */
    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    /** Checks if the app has the RECORD_AUDIO permission. */
    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Checks if the app has the SYSTEM_ALERT_WINDOW (overlay) permission. */
    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    /** Checks if all required permissions (record, overlay, accessibility) are granted. */
    private fun hasRequiredPermissions(): Boolean = hasRecordPermission() && hasOverlayPermission() && isAnyA11yEnabled()

    /** Checks if either of the app's accessibility services are enabled. */
    private fun isAnyA11yEnabled(): Boolean =
        isA11yEnabledFor(TooltipService::class.java) || isA11yEnabledFor(Accessibility::class.java)

    /**
     * Checks if a specific accessibility service is enabled in the system settings.
     * @param cls The class of the [Accessibility] to check.
     * @return `true` if the service is enabled, `false` otherwise.
     */
    private fun isA11yEnabledFor(cls: Class<out AccessibilityService>): Boolean {
        val component = ComponentName(this, cls)
        val enabledStr = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (enabledStr.split(':').any { it.equals(component.flattenToString(), true) }) {
            return true
        }
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == component.packageName && it.resolveInfo.serviceInfo.name == component.className }
    }

    /**
     * Displays a temporary, non-interactive message as a screen overlay.
     *
     * @param context The context.
     * @param message The message to display.
     * @param durationMs The duration to show the message, in milliseconds.
     * @param position The gravity of the message on screen (e.g., [Gravity.TOP], [Gravity.BOTTOM]).
     * @param yOffsetDp The vertical offset from the gravity position, in DP.
     */
    fun showOverlayMessage(
        context: Context,
        message: String,
        durationMs: Long = 1500,
        position: Int = Gravity.TOP,
        yOffsetDp: Int = 64
    ) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            if (!Settings.canDrawOverlays(context)) return@post

            val wm = context.getSystemService(WINDOW_SERVICE) as WindowManager

            val tv = TextView(context.applicationContext).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(context,14), dp(context,10), dp(context,14), dp(context,10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context,12).toFloat()
                    setColor(0xCC222222.toInt())
                }
                elevation = dp(context,8).toFloat()
            }
            tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val lp = WindowManager.LayoutParams(
                tv.measuredWidth, tv.measuredHeight, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = when (position) {
                    Gravity.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    Gravity.CENTER -> Gravity.CENTER
                    else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
                x = 0
                y = when (position) {
                    Gravity.TOP -> dp(context, yOffsetDp)
                    Gravity.BOTTOM -> dp(context, yOffsetDp)
                    else -> 0
                }
            }

            tv.alpha = 0f
            tv.translationY = when (position) {
                Gravity.TOP -> -dp(context,24).toFloat()
                Gravity.BOTTOM ->  dp(context,24).toFloat()
                else -> 0f
            }

            wm.addView(tv, lp)

            // Animate the toast in and out.
            tv.animate().alpha(1f).translationY(0f).setDuration(160).withEndAction {
                tv.postDelayed({
                    val outDy = when (position) {
                        Gravity.TOP -> -dp(context,12).toFloat()
                        Gravity.BOTTOM ->  dp(context,12).toFloat()
                        else -> 0f
                    }
                    tv.animate().alpha(0f).translationY(outDy).setDuration(140).withEndAction {
                        try { wm.removeView(tv) } catch (_: Exception) {}
                    }.start()
                }, durationMs)
            }.start()

            tv.setOnClickListener { try { wm.removeView(tv) } catch (_: Exception) {} }
        }
    }

    /**
     * Converts a DP value to pixels.
     */
    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()
}
