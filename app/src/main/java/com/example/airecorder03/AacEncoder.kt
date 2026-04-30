package com.example.airecorder03

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

class AacEncoder(
    private val sampleRate: Int = PcmAudioEngine.SAMPLE_RATE,
    private val bitrate: Int = 64_000
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var totalBytesIn = 0L
    @Volatile private var stopped = false

    fun start(outputFile: File) {
        stopped = false
        totalBytesIn = 0L
        muxerStarted = false
        audioTrackIndex = -1

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun encode(pcm: ByteArray, size: Int) {
        if (stopped) return
        val c = codec ?: return
        val presentationUs = totalBytesIn * 1_000_000L /
                (sampleRate * PcmAudioEngine.BYTES_PER_SAMPLE)
        val idx = c.dequeueInputBuffer(10_000L)
        if (idx >= 0) {
            c.getInputBuffer(idx)!!.run { clear(); put(pcm, 0, size) }
            c.queueInputBuffer(idx, 0, size, presentationUs, 0)
            totalBytesIn += size
        }
        drain(false)
    }

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        val mx = muxer ?: return
        if (endOfStream) {
            val idx = c.dequeueInputBuffer(10_000L)
            if (idx >= 0) {
                c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 10_000L)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioTrackIndex = mx.addTrack(c.outputFormat)
                    mx.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (!isConfig && muxerStarted && audioTrackIndex >= 0 && info.size > 0) {
                        mx.writeSampleData(audioTrackIndex, c.getOutputBuffer(outIdx)!!, info)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (isEos) break
                }
            }
        }
    }

    fun stop(): Long {
        stopped = true
        try { drain(true) } catch (_: Exception) {}
        val durationMs = totalBytesIn * 1000L / (sampleRate * PcmAudioEngine.BYTES_PER_SAMPLE)
        try { muxer?.run { stop(); release() } } catch (_: Exception) {}
        try { codec?.run { stop(); release() } } catch (_: Exception) {}
        muxer = null
        codec = null
        audioTrackIndex = -1
        muxerStarted = false
        return durationMs
    }
}
