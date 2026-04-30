package com.example.airecorder03

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedClip(val file: File, val durationMs: Long)

class InstantReplayBuffer(
    private val sampleRate: Int = PcmAudioEngine.SAMPLE_RATE,
    val bufferSeconds: Int = 15
) {
    private val maxBytes = sampleRate * PcmAudioEngine.BYTES_PER_SAMPLE * bufferSeconds
    private val ring = ByteArray(maxBytes)
    private var writePos = 0
    private var totalWritten = 0L
    private val lock = Any()

    fun write(pcm: ByteArray, size: Int) {
        synchronized(lock) {
            var rem = size
            var src = 0
            while (rem > 0) {
                val space = maxBytes - writePos
                val toCopy = minOf(space, rem)
                System.arraycopy(pcm, src, ring, writePos, toCopy)
                writePos = (writePos + toCopy) % maxBytes
                src += toCopy
                rem -= toCopy
            }
            totalWritten += size
        }
    }

    private fun snapshot(): Pair<ByteArray, Long> {
        synchronized(lock) {
            val available = minOf(totalWritten, maxBytes.toLong()).toInt()
            val snap = ByteArray(available)
            if (available == maxBytes) {
                val firstPart = maxBytes - writePos
                System.arraycopy(ring, writePos, snap, 0, firstPart)
                System.arraycopy(ring, 0, snap, firstPart, writePos)
            } else {
                System.arraycopy(ring, 0, snap, 0, available)
            }
            val durationMs = available.toLong() * 1000 / (sampleRate * PcmAudioEngine.BYTES_PER_SAMPLE)
            return snap to durationMs
        }
    }

    suspend fun saveClip(outputDir: File): SavedClip? = withContext(Dispatchers.IO) {
        val (pcm, durationMs) = snapshot()
        if (pcm.isEmpty()) return@withContext null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(outputDir, "replay_$stamp.m4a")
        return@withContext try {
            encodeToM4a(pcm, file, sampleRate)
            SavedClip(file, durationMs)
        } catch (e: Exception) {
            e.printStackTrace()
            file.delete()
            null
        }
    }

    fun reset() {
        synchronized(lock) {
            writePos = 0
            totalWritten = 0L
        }
    }
}

private fun encodeToM4a(pcm: ByteArray, outputFile: File, sampleRate: Int) {
    val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var trackIdx = -1
    var muxerStarted = false
    val info = MediaCodec.BufferInfo()
    val chunkSize = 3200
    var offset = 0
    var totalIn = 0L
    var eos = false

    try {
        while (true) {
            if (!eos) {
                val inputIdx = codec.dequeueInputBuffer(10_000L)
                if (inputIdx >= 0) {
                    val rem = pcm.size - offset
                    if (rem <= 0) {
                        codec.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        val n = minOf(chunkSize, rem)
                        val pts = totalIn * 1_000_000L / (sampleRate * PcmAudioEngine.BYTES_PER_SAMPLE)
                        codec.getInputBuffer(inputIdx)!!.run { clear(); put(pcm, offset, n) }
                        codec.queueInputBuffer(inputIdx, 0, n, pts, 0)
                        offset += n
                        totalIn += n
                    }
                }
            }

            var outIdx = codec.dequeueOutputBuffer(info, 10_000L)
            while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIdx = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else {
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (!isConfig && muxerStarted && trackIdx >= 0 && info.size > 0) {
                        muxer.writeSampleData(trackIdx, codec.getOutputBuffer(outIdx)!!, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (isEos) return@encodeToM4a
                }
                outIdx = codec.dequeueOutputBuffer(info, 0L)
            }
            if (eos && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // keep draining
                var drainIdx = codec.dequeueOutputBuffer(info, 10_000L)
                while (drainIdx >= 0 || drainIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (drainIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIdx = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else {
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isEos2 = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (!isConfig && muxerStarted && trackIdx >= 0 && info.size > 0) {
                            muxer.writeSampleData(trackIdx, codec.getOutputBuffer(drainIdx)!!, info)
                        }
                        codec.releaseOutputBuffer(drainIdx, false)
                        if (isEos2) return@encodeToM4a
                    }
                    drainIdx = codec.dequeueOutputBuffer(info, 10_000L)
                }
            }
        }
    } finally {
        try { codec.stop(); codec.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer.stop(); muxer.release() } catch (_: Exception) {}
    }
}
