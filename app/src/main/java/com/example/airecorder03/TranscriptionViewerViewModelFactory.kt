package com.example.airecorder03

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class TranscriptionViewerViewModelFactory(
    private val application: Application,
    private val recordingPath: String?,
    private val transcriptionPath: String?
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TranscriptionViewerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            // 这里我们保持和 ViewModel 构造函数一致，只传 3 个参数
            return TranscriptionViewerViewModel(application, recordingPath, transcriptionPath) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
