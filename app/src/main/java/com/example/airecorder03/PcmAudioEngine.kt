package com.example.airecorder03

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PcmAudioEngine {
    companion object {
        const val SAMPLE_RATE = 16000
        const val BYTES_PER_SAMPLE = 2  // 16-bit PCM
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val READ_CHUNK_BYTES = 3200  // 100 ms at 16 kHz
    }

    interface PcmListener {
        fun onPcmChunk(data: ByteArray, size: Int)
    }

    private var audioRecord: AudioRecord? = null
    private val listeners = mutableListOf<PcmListener>()
    private var captureJob: Job? = null
    @Volatile private var dispatching = false

    fun addListener(listener: PcmListener) = listeners.add(listener)
    fun removeListener(listener: PcmListener) = listeners.remove(listener)

    fun start(scope: CoroutineScope) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBuf * 4, READ_CHUNK_BYTES * 4)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufSize
        ).also { it.startRecording() }
        dispatching = true
        captureJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(READ_CHUNK_BYTES)
            while (isActive) {
                val n = audioRecord?.read(buf, 0, READ_CHUNK_BYTES) ?: break
                if (n > 0 && dispatching) {
                    val chunk = buf.copyOf(n)
                    listeners.forEach { it.onPcmChunk(chunk, n) }
                }
            }
        }
    }

    fun pause() { dispatching = false }
    fun resume() { dispatching = true }

    suspend fun stop() {
        dispatching = false
        captureJob?.cancelAndJoin()
        captureJob = null
        audioRecord?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        audioRecord = null
    }

    fun stopSync() {
        dispatching = false
        captureJob?.cancel()
        captureJob = null
        audioRecord?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        audioRecord = null
    }
}
