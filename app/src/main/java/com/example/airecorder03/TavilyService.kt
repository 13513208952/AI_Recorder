package com.example.airecorder03

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Tavily Search API service.
 * Provides web search capability to augment the local LLM with real-time information.
 */
class TavilyService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()


    /**
     * Performs a web search via Tavily API and returns a formatted context string
     * that can be injected into the LLM prompt.
     *
     * @param query The search query
     * @param apiKey The user's Tavily API key
     * @param maxResults Maximum number of results to include (default 5)
     * @return TavilySearchResult containing the answer and sources, or null on failure
     */
    suspend fun search(
        query: String,
        apiKey: String,
        maxResults: Int = 5
    ): TavilySearchResult? = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("query", query)
                put("api_key", apiKey)
                put("search_depth", "basic")
                put("include_answer", true)
                put("max_results", maxResults)
            }

            val request = Request.Builder()
                .url(TAVILY_API_URL)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext TavilySearchResult(
                    success = false,
                    error = "HTTP ${response.code}: $errorBody"
                )
            }

            val responseBody = response.body?.string() ?: return@withContext null
            val jsonResponse = JSONObject(responseBody)

            val answer = jsonResponse.optString("answer", "")

            val results = mutableListOf<TavilyResultItem>()
            val resultsArray: JSONArray = jsonResponse.optJSONArray("results") ?: JSONArray()
            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                results.add(
                    TavilyResultItem(
                        title = item.optString("title", ""),
                        url = item.optString("url", ""),
                        content = item.optString("content", "")
                    )
                )
            }

            TavilySearchResult(
                success = true,
                answer = answer,
                results = results
            )
        } catch (e: Exception) {
            e.printStackTrace()
            TavilySearchResult(
                success = false,
                error = e.message ?: "Search failed"
            )
        }
    }

    /**
     * Formats search results into a context string suitable for LLM consumption.
     */
    fun formatSearchContext(result: TavilySearchResult): String {
        val sb = StringBuilder()
        sb.appendLine("【联网搜索结果】")

        if (result.answer.isNotBlank()) {
            sb.appendLine("摘要: ${result.answer}")
            sb.appendLine()
        }

        result.results.forEachIndexed { index, item ->
            sb.appendLine("[${index + 1}] ${item.title}")
            sb.appendLine("    ${item.content}")
            sb.appendLine("    来源: ${item.url}")
            sb.appendLine()
        }

        return sb.toString().trim()
    }

    companion object {
        private const val TAVILY_API_URL = "https://api.tavily.com/search"
    }
}

data class TavilyResultItem(
    val title: String,
    val url: String,
    val content: String
)

data class TavilySearchResult(
    val success: Boolean,
    val answer: String = "",
    val results: List<TavilyResultItem> = emptyList(),
    val error: String? = null
)

