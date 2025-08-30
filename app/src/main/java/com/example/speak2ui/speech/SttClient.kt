package com.example.speak2ui.speech

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

import com.example.speak2ui.api.OPENAI_API_KEY
import com.example.speak2ui.api.STT_MODEL_NAME

/**
 * A client for interacting with the OpenAI Speech-to-Text (STT) API.
 *
 * This class handles the network request to transcribe an audio file into text.
 * It uses a [Mutex] to ensure that only one transcription request is active at a time.
 */
class SttClient {
    private val openaiApiKey = OPENAI_API_KEY
    private val sttModelName = STT_MODEL_NAME
    private val sttLanguage = "ko"
    private val client = OkHttpClient()
    private val txMutex = Mutex()

    /**
     * Transcribes the given WAV audio file using the OpenAI API.
     *
     * This function sends the audio file as part of a multipart form data request.
     * The file is deleted after the transcription attempt, regardless of success or failure.
     *
     * @param wavFile The WAV file to transcribe.
     * @return The transcribed text as a [String], or `null` if the transcription fails,
     *         the response is empty, or an error occurs.
     */
    suspend fun transcribeWavFile(wavFile: File): String? {
        var sttResponse: String? = null
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", wavFile.name, wavFile.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", sttModelName)
                .addFormDataPart("response_format", "text")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("language", sttLanguage)
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $openaiApiKey")
                .post(body)
                .build()

            // Use a mutex to prevent concurrent transcription requests.
            txMutex.withLock {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty().trim()
                    if (!response.isSuccessful) {
                        Log.e("SttClient", "HTTP ${response.code}: $responseBody")
                        return@use
                    }

                    if (responseBody.isNotBlank()) {
                        sttResponse = responseBody
                    } else {
                        Log.d("SttClient", "Got empty transcript; no command sent.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SttClient", "Transcription failed", e)
        } finally {
            // Ensure the temporary audio file is always deleted.
            wavFile.delete()
        }
        return sttResponse
    }
}