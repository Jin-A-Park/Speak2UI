package com.example.speak2ui.speech

import kotlin.math.log10
import kotlin.math.pow

/**
 * A simple Voice Activity Detector (VAD).
 *
 * This class analyzes frames of audio data to determine if they contain speech.
 * It works by maintaining a running average of the background noise level and
 * considering any audio significantly louder than that as speech.
 */
class VoiceDetector {
    /**
     * Represents the current state of the VAD.
     * - [IDLE]: Waiting for speech to be detected.
     * - [COLLECT]: Currently detecting and collecting speech frames.
     */
    enum class VadState { IDLE, COLLECT }
    var currentVadState = VadState.IDLE

    // Counters for consecutive speech/silence frames to avoid spurious detections.
    var onCount = 0
    var offCount = 0

    private var noiseFloorDb = -60.0

    companion object {
        // Number of consecutive speech frames required to trigger the COLLECT state.
        const val VOICE_ON_FRAMES = 3
        // Number of consecutive silence frames required to trigger the IDLE state.
        const val VOICE_OFF_FRAMES = 30
        // Smoothing factor for the noise floor exponential moving average.
        private const val NOISE_EMA_ALPHA = 0.3
        // The required volume increase (in dB) above the noise floor to be considered speech.
        private const val NOISE_OFFSET_DB = 10.0
    }

    /**
     * Checks if a given audio frame contains speech.
     *
     * @param frame The audio data as an array of shorts.
     * @return `true` if the frame's volume is above the speech threshold, `false` otherwise.
     */
    fun isSpeech(frame: ShortArray): Boolean {
        val db = rmsDb(frame)
        return isSpeechDb(db)
    }

    /**
     * Updates the estimated background noise level using the given audio frame.
     *
     * @param frame The audio data to be used for updating the noise floor.
     */
    fun updateNoiseFloor(frame: ShortArray) {
        val db = rmsDb(frame)
        updateNoiseFloor(db)
    }

    /**
     * Calculates the Root Mean Square (RMS) of an audio frame in decibels (dB).
     * @param frame The audio data.
     * @return The volume of the frame in dB.
     */
    private fun rmsDb(frame: ShortArray): Double {
        if (frame.isEmpty()) return -160.0
        val sumSq = frame.sumOf { (it.toDouble() / 32768.0).pow(2.0) }
        val rms = kotlin.math.sqrt(sumSq / frame.size)
        return if (rms <= 1e-9) -160.0 else 20.0 * log10(rms)
    }

    /**
     * Updates the noise floor using an exponential moving average.
     * @param db The dB level of the current frame.
     */
    private fun updateNoiseFloor(db: Double) {
        noiseFloorDb = NOISE_EMA_ALPHA * noiseFloorDb + (1 - NOISE_EMA_ALPHA) * db
    }

    /**
     * Determines if a given dB level should be considered speech.
     * @param db The dB level to check.
     * @return `true` if the dB level is above the noise floor plus the offset.
     */
    private fun isSpeechDb(db: Double): Boolean = db > (noiseFloorDb + NOISE_OFFSET_DB)

    /**
     * Resets the VAD to its initial state.
     */
    fun reset() {
        currentVadState = VadState.IDLE
        onCount = 0
        offCount = 0
    }
}