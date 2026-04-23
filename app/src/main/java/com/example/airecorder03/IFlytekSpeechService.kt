package com.example.airecorder03

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import android.util.Log
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * iFlytek (讯飞) speech transcription service — 语音转写标准版 v2.
 *
 * Authentication: signa = Base64(HMAC-SHA1(secretKey, MD5(appId + ts)))
 * Flow: Upload file (params in URL, file bytes in body) → Poll getResult → parse
 *
 * Key advantage over local Vosk: Audio files are sent directly to cloud,
 * skipping the local decode→resample pipeline.
 *
 * API docs: https://www.xfyun.cn/doc/asr/lfasr/API.html
 */
class IFlytekSpeechService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribe an audio file using iFlytek's 语音转写 v2 service.
     *
     * @param filePath Local path to the audio file (m4a, mp3, wav, flac, etc.)
     * @param language "CN" for Chinese, "EN" for English
     * @param appId iFlytek APPID
     * @param secretKey iFlytek SecretKey
     * @return VoskTranscriptionResult for compatibility with existing viewer, or null on failure
     */
    suspend fun transcribe(
        filePath: String,
        language: String,
        appId: String,
        secretKey: String
    ): VoskTranscriptionResult? = withContext(Dispatchers.IO) {
        try {
            val audioFile = File(filePath)
            if (!audioFile.exists()) {
                Log.e(TAG, "Audio file does not exist: $filePath")
                return@withContext null
            }

            val fileSize = audioFile.length()
            Log.d(TAG, "Starting iFlytek v2 transcription for ${audioFile.name} ($fileSize bytes)")

            // Step 1: Upload file — all params in URL, raw file bytes in body
            val ts = (System.currentTimeMillis() / 1000).toString()
            val signa = generateSigna(appId, secretKey, ts)
            val langParam = if (language == "CN") "cn" else "en"

            val orderId = uploadFile(appId, ts, signa, audioFile, fileSize, langParam)
            if (orderId == null) {
                Log.e(TAG, "Upload failed")
                return@withContext null
            }
            Log.d(TAG, "Upload successful, orderId: $orderId")

            // Step 2: Poll getResult until order status == 4 (complete)
            return@withContext pollAndGetResult(appId, secretKey, orderId)
        } catch (e: Exception) {
            Log.e(TAG, "iFlytek transcription error", e)
            return@withContext null
        }
    }

    /**
     * Upload audio file to iFlytek v2 API.
     * All parameters are appended to the URL query string.
     * The raw file bytes are sent in the request body with Content-Type: application/octet-stream.
     *
     * @return orderId on success, null on failure
     */
    private fun uploadFile(
        appId: String,
        ts: String,
        signa: String,
        audioFile: File,
        fileSize: Long,
        language: String
    ): String? {
        val encodedSigna = URLEncoder.encode(signa, "UTF-8")
        val encodedFileName = URLEncoder.encode(audioFile.name, "UTF-8")

        val url = "$BASE_URL/upload" +
                "?appId=${URLEncoder.encode(appId, "UTF-8")}" +
                "&ts=$ts" +
                "&signa=$encodedSigna" +
                "&fileName=$encodedFileName" +
                "&fileSize=$fileSize" +
                "&duration=200" +       // docs: "当前未验证，可随机传一个数字"
                "&language=$language" +
                "&eng_smoothproc=true"  // enable smoothing for better sentence segmentation

        Log.d(TAG, "Upload URL (without signa): ${url.substringBefore("&signa=")}")

        val fileBytes = audioFile.readBytes()
        val body = fileBytes.toRequestBody("application/octet-stream".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/octet-stream")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        response.close()

        Log.d(TAG, "Upload response: $responseBody")
        if (responseBody == null) return null

        return try {
            val json = JSONObject(responseBody)
            val code = json.optString("code", "")
            if (code == "000000") {
                val content = json.optJSONObject("content")
                val orderId = content?.optString("orderId")
                val estimateTime = content?.optInt("taskEstimateTime", 0) ?: 0
                Log.d(TAG, "Upload success, orderId=$orderId, estimateTime=${estimateTime}ms")
                orderId
            } else {
                Log.e(TAG, "Upload failed: code=$code, descInfo=${json.optString("descInfo")}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload response parse error: $responseBody", e)
            null
        }
    }

    /**
     * Poll getResult endpoint until order is complete (status=4) or failed (status=-1).
     * Then parse the orderResult into VoskTranscriptionResult.
     *
     * orderInfo.status values:
     *   0 = created, 3 = processing, 4 = completed, -1 = failed
     */
    private suspend fun pollAndGetResult(
        appId: String,
        secretKey: String,
        orderId: String
    ): VoskTranscriptionResult? {
        val maxAttempts = 120 // ~10 minutes with 5s interval

        for (attempt in 0 until maxAttempts) {
            delay(5000) // Wait before polling

            val ts = (System.currentTimeMillis() / 1000).toString()
            val signa = generateSigna(appId, secretKey, ts)
            val encodedSigna = URLEncoder.encode(signa, "UTF-8")
            val encodedOrderId = URLEncoder.encode(orderId, "UTF-8")

            val url = "$BASE_URL/getResult" +
                    "?appId=${URLEncoder.encode(appId, "UTF-8")}" +
                    "&ts=$ts" +
                    "&signa=$encodedSigna" +
                    "&orderId=$encodedOrderId"

            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                response.close()

                Log.d(TAG, "getResult attempt $attempt: ${responseBody?.take(500)}")

                if (responseBody != null) {
                    val json = JSONObject(responseBody)
                    val code = json.optString("code", "")

                    if (code == "000000") {
                        val content = json.optJSONObject("content") ?: continue
                        val orderInfo = content.optJSONObject("orderInfo")
                        val status = orderInfo?.optInt("status", 0) ?: 0

                        when (status) {
                            4 -> {
                                // Order complete — parse result
                                val failType = orderInfo?.optInt("failType", 0) ?: 0
                                if (failType != 0) {
                                    Log.e(TAG, "Order completed with failType=$failType")
                                    return null
                                }
                                val orderResult = content.optString("orderResult", "")
                                if (orderResult.isNotBlank()) {
                                    Log.d(TAG, "Transcription complete, parsing result...")
                                    return parseOrderResult(orderResult)
                                }
                                Log.e(TAG, "Order complete but orderResult is empty")
                                return null
                            }
                            -1 -> {
                                val failType = orderInfo?.optInt("failType", 99) ?: 99
                                Log.e(TAG, "Order failed, failType=$failType")
                                return null
                            }
                            else -> {
                                // 0=created, 3=processing — keep polling
                                Log.d(TAG, "Order status=$status, continuing to poll...")
                            }
                        }
                    } else if (code == "26605") {
                        // "任务正在处理中，请稍后重试"
                        Log.d(TAG, "Task still processing (26605)...")
                    } else {
                        Log.w(TAG, "getResult returned code=$code, desc=${json.optString("descInfo")}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getResult poll error at attempt $attempt", e)
            }
        }

        Log.e(TAG, "Polling timeout for order $orderId after $maxAttempts attempts")
        return null
    }

    /**
     * Parse the orderResult JSON string into VoskTranscriptionResult.
     *
     * Per official docs:
     * - lattice[].json_1best is a JSON string containing st object
     * - st.bg / st.ed: sentence begin/end time in **milliseconds**
     * - ws[].wb / ws[].we: word begin/end in **frames** (1 frame = 10ms), relative to bg
     * - cw[].w: the recognized word text
     * - cw[].wp: word property (n=normal, s=smoothed, p=punctuation, g=segment marker)
     */
    private fun parseOrderResult(orderResult: String): VoskTranscriptionResult? {
        return try {
            val resultJson = JSONObject(orderResult)
            val latticeArray = resultJson.optJSONArray("lattice") ?: return null

            val allWords = mutableListOf<Word>()
            val fullTextBuilder = StringBuilder()

            for (i in 0 until latticeArray.length()) {
                val latticeItem = latticeArray.getJSONObject(i)
                val json1bestStr = latticeItem.optString("json_1best", "")
                if (json1bestStr.isBlank()) continue

                val json1best = JSONObject(json1bestStr)
                val st = json1best.optJSONObject("st") ?: continue

                // bg/ed are sentence begin/end time in milliseconds
                val bgMs = st.optString("bg", "0").toLongOrNull() ?: 0L

                val rtArray = st.optJSONArray("rt") ?: continue
                val sentenceText = StringBuilder()

                for (r in 0 until rtArray.length()) {
                    val rt = rtArray.getJSONObject(r)
                    val wsArray = rt.optJSONArray("ws") ?: continue

                    for (w in 0 until wsArray.length()) {
                        val ws = wsArray.getJSONObject(w)
                        // wb/we are in frames (1 frame = 10ms), relative to bg
                        val wb = ws.optLong("wb", 0L)
                        val we = ws.optLong("we", 0L)

                        val cwArray = ws.optJSONArray("cw") ?: continue
                        val wordText = StringBuilder()
                        for (c in 0 until cwArray.length()) {
                            val cw = cwArray.getJSONObject(c)
                            val wp = cw.optString("wp", "n")
                            // Skip segment markers (g = segment boundary)
                            if (wp == "g") continue
                            wordText.append(cw.optString("w", ""))
                        }

                        val text = wordText.toString()
                        if (text.isBlank()) continue

                        sentenceText.append(text)

                        // Convert to absolute seconds:
                        // bg is in ms, wb/we are in frames (10ms each)
                        // absolute_ms = bg + frame * 10
                        allWords.add(
                            Word(
                                text = text,
                                start = (bgMs + wb * 10) / 1000.0,
                                end = (bgMs + we * 10) / 1000.0
                            )
                        )
                    }
                }

                if (sentenceText.isNotEmpty()) {
                    if (fullTextBuilder.isNotEmpty()) fullTextBuilder.append("\n")
                    fullTextBuilder.append(sentenceText)
                }
            }

            Log.d(TAG, "Parsed ${allWords.size} words, fullText length=${fullTextBuilder.length}")
            VoskTranscriptionResult(words = allWords, fullText = fullTextBuilder.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Parse orderResult error", e)
            null
        }
    }

    /**
     * Generate authentication signature for iFlytek API.
     * Formula: signa = Base64(HMAC-SHA1(secretKey, MD5(appId + ts)))
     *
     * Per official docs:
     * 1. baseString = appId + ts
     * 2. MD5(baseString) → hex string
     * 3. HMAC-SHA1(key=secretKey, data=md5hex) → Base64 encode
     */
    private fun generateSigna(appId: String, secretKey: String, ts: String): String {
        // Step 1: MD5(appId + ts)
        val md5Digest = MessageDigest.getInstance("MD5")
        val baseString = md5Digest.digest((appId + ts).toByteArray())
        val md5Hex = baseString.joinToString("") { "%02x".format(it) }

        // Step 2: HMAC-SHA1(secretKey, md5Hex)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA1"))
        val hmacResult = mac.doFinal(md5Hex.toByteArray())

        // Step 3: Base64 encode
        return Base64.encodeToString(hmacResult, Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "IFlytekSpeech"
        private const val BASE_URL = "https://raasr.xfyun.cn/v2/api"
    }
}
