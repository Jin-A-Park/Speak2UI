package com.example.speak2ui.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.text.Charsets.UTF_8

/**
 * Manages audio recording from the microphone and saving it to a WAV file.
 *
 * This class handles the setup of [AudioRecord], starting and stopping recording,
 * and writing the raw PCM audio data to a temporary file. It also includes utilities
 * for creating and updating the WAV file header to ensure it's a valid audio file.
 *
 * @param context The application context, used for file operations.
 */
class AudioRecorder(private val context: Context) {
    private var recorder: AudioRecord? = null
    var pcmFile: File? = null
    private var pcmFileStream: FileOutputStream? = null
    var pcmBytesWritten: Long = 0

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG: Int = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT: Int = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "Recoder"
    }

    /**
     * Initializes and starts the [AudioRecord] instance.
     * If a recording is already in progress, it will be stopped first.
     * @param readBuffer The buffer that will be used to read audio data.
     */
    fun startRecording(readBuffer: ShortArray) {
        if (recorder != null) {
            stopRecording()
        }
        try {
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(readBuffer.size * 2)
                .build()

            recorder?.startRecording()
        } catch (e: SecurityException) {
            Log.e("AudioRecorder", "AudioRecord permission error", e)
        }
    }

    /**
     * Reads audio data from the recorder into the provided buffer.
     * @param readBuffer The buffer to fill with audio data.
     * @return The number of shorts read, or 0 if the recorder is not available.
     */
    fun read(readBuffer: ShortArray): Int {
        return recorder?.read(readBuffer, 0, readBuffer.size) ?: 0
    }

    /**
     * Stops and releases the [AudioRecord] instance.
     */
    fun stopRecording() {
        recorder?.runCatching {
            stop()
            release()
        }
        recorder = null
    }

    /**
     * Creates a temporary WAV file in the cache directory and writes a placeholder header.
     */
    fun createPcmFile() {
        try {
            pcmFile = File.createTempFile("utt_stream_", ".wav", context.cacheDir)
            pcmFileStream = FileOutputStream(pcmFile)
            writeWavHeaderPlaceholder(pcmFileStream!!)
            pcmBytesWritten = 0
        } catch (e: IOException) {
            Log.e("AudioRecorder", "Failed to create temp file", e)
            cleanUpTempFile()
        }
    }

    /**
     * Appends a frame of raw PCM audio data to the temporary file.
     * @param frame The short array of PCM data to write.
     */
    fun appendPcm(frame: ShortArray) {
        try {
            val byteBuffer = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in frame) byteBuffer.putShort(s)
            pcmFileStream?.write(byteBuffer.array())
            pcmBytesWritten += byteBuffer.array().size
        } catch (e: IOException) {
            Log.e("AudioRecorder", "Failed to write PCM data", e)
            cleanUpTempFile()
        }
    }

    /**
     * Closes the file output stream for the temporary PCM file.
     */
    fun finalizePcmFile() {
        try {
            pcmFileStream?.close()
        } catch (e: IOException) {
            Log.e("AudioRecorder", "Failed to close temp file stream", e)
        } finally {
            pcmFileStream = null
        }
    }

    /**
     * Cleans up all temporary file resources, closing streams and deleting the file.
     */
    fun cleanUpTempFile() {
        try {
            pcmFileStream?.close()
        } catch (_: IOException) {}
        pcmFileStream = null
        pcmFile?.delete()
        pcmFile = null
        pcmBytesWritten = 0
    }

    /**
     * Writes a 44-byte placeholder WAV header to the given output stream.
     * The chunk sizes will be updated later in [updateWavHeader].
     * @param stream The [FileOutputStream] to write the header to.
     * @throws IOException if there is an error writing to the stream.
     */
    @Throws(IOException::class)
    private fun writeWavHeaderPlaceholder(stream: FileOutputStream) {
        val header = ByteArray(44)
        val channels: Short = 1
        val sampleRate = SAMPLE_RATE
        val bitsPerSample: Short = 16

        val byteBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.put("RIFF".toByteArray(UTF_8))
        byteBuffer.putInt(0) // ChunkSize (placeholder)
        byteBuffer.put("WAVE".toByteArray(UTF_8))
        byteBuffer.put("fmt ".toByteArray(UTF_8))
        byteBuffer.putInt(16) // Subchunk1Size for PCM
        byteBuffer.putShort(1) // AudioFormat, 1 for PCM
        byteBuffer.putShort(channels) // NumChannels
        byteBuffer.putInt(sampleRate) // SampleRate
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        byteBuffer.putInt(byteRate) // ByteRate
        val blockAlign: Short = (channels * (bitsPerSample / 8)).toShort()
        byteBuffer.putShort(blockAlign) // BlockAlign
        byteBuffer.putShort(bitsPerSample) // BitsPerSample
        byteBuffer.put("data".toByteArray(UTF_8))
        byteBuffer.putInt(0) // Subchunk2Size (placeholder)

        stream.write(header)
    }

    /**
     * Updates the WAV file header with the correct file and data chunk sizes.
     * @param wavFile The WAV file to update.
     * @param pcmDataSize The total size of the raw PCM data in bytes.
     * @throws IOException if there is an error accessing the file.
     */
    @Throws(IOException::class)
    fun updateWavHeader(wavFile: File, pcmDataSize: Long) {
        val riffChunkSize = pcmDataSize + 36
        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.seek(4) // Seek to ChunkSize field
            raf.writeInt(Integer.reverseBytes(riffChunkSize.toInt()))
            raf.seek(40) // Seek to Subchunk2Size field
            raf.writeInt(Integer.reverseBytes(pcmDataSize.toInt()))
        }
    }

    /**
     * Checks if the recorder is currently recording.
     */
    val isRecording: Boolean
        get() = recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING
}