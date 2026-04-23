package com.example.airecorder03

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

class Recorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null

    fun start(outputFile: File) {
        // It is recommended to use the .m4a extension for AAC audio files.
        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(192000)
            setOutputFile(outputFile.absolutePath)

            try {
                prepare()
                start()
                audioFile = outputFile
            } catch (e: IOException) {
                e.printStackTrace()
                // Handle exceptions
            }
        }
    }

    fun stop() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
    }

    fun pause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recorder?.pause()
        }
    }

    fun resume() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recorder?.resume()
        }
    }
}
