package com.example.airecorder03

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.UniversalAudioInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class Word(val text: String, val start: Double, val end: Double)

@Serializable
data class VoskTranscriptionResult(val words: List<Word>, val fullText: String)

data class PcmAudioInfo(val tempFilePath: String, val sampleRate: Int, val channels: Int)

class VoskService(private val context: Context) {

    private val models = mutableMapOf<String, Model>()
    private val voskSampleRate = 16000.0f

    private suspend fun getModel(language: String): Model? = withContext(Dispatchers.IO) {
        if (models.containsKey(language)) {
            return@withContext models[language]
        }

        val modelName = when (language) {
            "EN" -> "vosk-model-small-en-us"
            "CN" -> "vosk-model-small-cn"
            else -> null
        } ?: return@withContext null

        try {
            val model = unpackModel(modelName)
            models[language] = model
            return@withContext model
        } catch (e: IOException) {
            e.printStackTrace()
            return@withContext null
        }
    }

    private suspend fun unpackModel(name: String): Model = suspendCancellableCoroutine { continuation ->
        StorageService.unpack(context, name, "model",
            { model -> continuation.resume(model) },
            { exception -> continuation.resumeWithException(exception) })
    }

    suspend fun transcribe(filePath: String, language: String): VoskTranscriptionResult? = withContext(Dispatchers.IO) {
        val model = getModel(language) ?: return@withContext null
        val recognizer = Recognizer(model, voskSampleRate)
        recognizer.setWords(true)

        var rawPcmFile: File? = null
        var standardPcmFile: File? = null

        try {
            // 1. Decode to Raw PCM Cache
            val rawPcmInfo = decodeToRawPcmFile(filePath)
            rawPcmFile = File(rawPcmInfo.tempFilePath)

            // 2. Resample to Standard PCM Cache
            standardPcmFile = resampleToStandardPcmFile(rawPcmInfo)

            // 3. Recognize from Standard PCM
            FileInputStream(standardPcmFile).use { stream ->
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    recognizer.acceptWaveForm(buffer, bytesRead)
                }
            }
            return@withContext parseResult(recognizer.finalResult)

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            recognizer.close()
            // 4. Cleanup
            rawPcmFile?.delete()
            standardPcmFile?.delete()
        }
    }

    @Throws(IOException::class)
    private fun resampleToStandardPcmFile(rawPcmInfo: PcmAudioInfo): File {
        val standardPcmFile = File.createTempFile("standard_pcm_", ".raw", context.cacheDir)
        val rawPcmFile = File(rawPcmInfo.tempFilePath)
        var dispatcher: AudioDispatcher? = null

        FileOutputStream(standardPcmFile).use { fos ->
            val format = TarsosDSPAudioFormat(
                rawPcmInfo.sampleRate.toFloat(),
                16, // Assuming 16-bit PCM
                rawPcmInfo.channels,
                true, // Signed
                false // Little-endian
            )
            val audioStream = UniversalAudioInputStream(FileInputStream(rawPcmFile), format)
            dispatcher = AudioDispatcher(audioStream, 4096, 0)

            dispatcher.addAudioProcessor(object : AudioProcessor {
                override fun process(audioEvent: AudioEvent): Boolean {
                    var floatBuffer = audioEvent.floatBuffer

                    if (rawPcmInfo.channels > 1) {
                        // Mono conversion
                        val monoBuffer = FloatArray(floatBuffer.size / rawPcmInfo.channels)
                        for (i in monoBuffer.indices) {
                            var sum = 0f
                            for (c in 0 until rawPcmInfo.channels) {
                                sum += floatBuffer[i * rawPcmInfo.channels + c]
                            }
                            monoBuffer[i] = sum / rawPcmInfo.channels
                        }
                        floatBuffer = monoBuffer
                    }

                    val resampledBuffer = if (rawPcmInfo.sampleRate.toFloat() != voskSampleRate) {
                        // Resampling
                        val ratio = voskSampleRate.toDouble() / rawPcmInfo.sampleRate.toDouble()
                        val outputSize = (floatBuffer.size * ratio).toInt()
                        val resampled = FloatArray(outputSize)
                        for (i in resampled.indices) {
                            val position = i / ratio
                            val index = position.toInt()
                            val fraction = position - index
                            val v1 = floatBuffer.getOrElse(index) { 0f }
                            val v2 = floatBuffer.getOrElse(index + 1) { 0f }
                            resampled[i] = v1 + ((v2 - v1) * fraction).toFloat()
                        }
                        resampled
                    } else {
                        floatBuffer
                    }

                    if (resampledBuffer.isEmpty()) return true

                    val byteBuffer = ByteBuffer.allocate(resampledBuffer.size * 2)
                    byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    for (f in resampledBuffer) {
                        val s = (f * 32767.0f).toInt()
                        byteBuffer.putShort(s.coerceIn(-32768, 32767).toShort())
                    }

                    fos.write(byteBuffer.array())
                    return true
                }

                override fun processingFinished() {}
            })

            dispatcher.run()
        }

        return standardPcmFile
    }

    @Throws(IOException::class)
    private fun decodeToRawPcmFile(filePath: String): PcmAudioInfo {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val tempFile = File.createTempFile("raw_pcm_", ".raw", context.cacheDir)

        try {
            extractor.setDataSource(filePath)
            val format = extractor.selectAudioTrack() ?: throw IOException("No audio track found in $filePath")
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("No MIME type found for audio track")
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            FileOutputStream(tempFile).use { fos ->
                val bufferInfo = MediaCodec.BufferInfo()
                var isEos = false

                while (!isEos) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    while (outputBufferIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                            val pcmChunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcmChunk)
                            fos.write(pcmChunk)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isEos = true
                        }
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    }
                }
            }
            return PcmAudioInfo(tempFile.absolutePath, sampleRate, channels)
        } catch (e: Exception) {
            tempFile.delete()
            throw IOException("Failed to decode audio to PCM", e)
        } finally {
            extractor.release()
            codec?.stop()
            codec?.release()
        }
    }

    private fun MediaExtractor.selectAudioTrack(): MediaFormat? {
        for (i in 0 until trackCount) {
            val format = getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                selectTrack(i)
                return format
            }
        }
        return null
    }

    private fun parseResult(json: String?): VoskTranscriptionResult? {
        if (json == null) return null
        return try {
            val jsonObject = JSONObject(json)
            val fullText = jsonObject.optString("text", "")
            val words = mutableListOf<Word>()
            if (jsonObject.has("result")) {
                val resultArray: JSONArray = jsonObject.getJSONArray("result")
                for (i in 0 until resultArray.length()) {
                    val wordObject = resultArray.getJSONObject(i)
                    words.add(Word(wordObject.getString("word"), wordObject.getDouble("start"), wordObject.getDouble("end")))
                }
            }
            VoskTranscriptionResult(words, fullText)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun release() {
        models.values.forEach { it.close() }
        models.clear()
    }

    fun clearCache() {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("raw_pcm_") || file.name.startsWith("standard_pcm_")) {
                file.delete()
            }
        }
    }
}
