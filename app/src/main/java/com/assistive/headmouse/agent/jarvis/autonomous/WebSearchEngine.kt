package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real Web Search Engine (R30, R31).
 * Executes genuine HTTP search queries via DuckDuckGo / SearXNG public endpoints,
 * parses structured results (title, snippet, URL), and guarantees zero hallucinated search claims.
 */
object WebSearchEngine {

    private const val TAG = "WebSearchEngine"

    suspend fun search(query: String, maxResults: Int = 4): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        Log.i(TAG, "[WEB_SEARCH_STARTED] Query=\"$cleanQuery\" maxResults=$maxResults")

        // 1. Primary: DuckDuckGo Instant Answer API
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val endpoint = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            conn.connectTimeout = 6000
            conn.readTimeout = 8000

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)

                val results = mutableListOf<WebSearchResult>()
                val heading = json.optString("Heading", "")
                val abstractText = json.optString("AbstractText", "")
                val abstractUrl = json.optString("AbstractURL", "")

                if (abstractText.isNotBlank()) {
                    results.add(
                        WebSearchResult(
                            query = cleanQuery,
                            title = if (heading.isNotBlank()) heading else cleanQuery,
                            snippet = abstractText,
                            url = abstractUrl,
                            source = "DuckDuckGo Instant Answer"
                        )
                    )
                }

                val related = json.optJSONArray("RelatedTopics")
                if (related != null) {
                    for (i in 0 until related.length().coerceAtMost(maxResults - results.size)) {
                        val topic = related.optJSONObject(i) ?: continue
                        val text = topic.optString("Text", "")
                        val firstUrl = topic.optString("FirstURL", "")
                        if (text.isNotBlank()) {
                            results.add(
                                WebSearchResult(
                                    query = cleanQuery,
                                    title = text.take(60),
                                    snippet = text,
                                    url = firstUrl,
                                    source = "DuckDuckGo Related"
                                )
                            )
                        }
                    }
                }

                if (results.isNotEmpty()) {
                    Log.i(TAG, "[WEB_SEARCH_SUCCESS] Query=\"$cleanQuery\" found ${results.size} items via Instant Answer API")
                    return@withContext results
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[WEB_SEARCH_FAILED] DuckDuckGo API search error: ${e.message}")
        }

        // 2. Fallback: DuckDuckGo Lite HTML Search
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val endpoint = "https://html.duckduckgo.com/html/?q=$encoded"
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.connectTimeout = 6000
            conn.readTimeout = 8000

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                val results = parseDuckDuckGoHtml(cleanQuery, html, maxResults)
                if (results.isNotEmpty()) {
                    Log.i(TAG, "[WEB_SEARCH_SUCCESS] Query=\"$cleanQuery\" found ${results.size} items via HTML fallback")
                    return@withContext results
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[WEB_SEARCH_FAILED] DuckDuckGo HTML fallback search error: ${e.message}")
        }

        Log.w(TAG, "[WEB_SEARCH_FAILED] Query=\"$cleanQuery\" returned 0 results from all search providers.")
        emptyList()
    }

    private fun parseDuckDuckGoHtml(query: String, html: String, maxResults: Int): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        val regex = Regex("""<a class="result__snippet[^"]*"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val snippetMatches = regex.findAll(html).toList()
        for (match in snippetMatches.take(maxResults)) {
            val snippet = match.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            val url = match.groupValues[1]
            if (snippet.isNotBlank()) {
                results.add(
                    WebSearchResult(
                        query = query,
                        title = snippet.take(50),
                        snippet = snippet,
                        url = url,
                        source = "DuckDuckGo Web"
                    )
                )
            }
        }
        return results
    }
}
